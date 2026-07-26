package cn.flying.service.key;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 key-wrapping 值对象的失败关闭与固定脱敏合同。
 */
class KeyWrappingValueObjectTest {

    /**
     * 验证明文密钥拒绝空白值，wrap 请求字符串覆盖空/非空 context 且不泄漏密钥。
     */
    @Test
    void shouldValidatePlaintextAndRedactWrapRequests() {
        assertThatThrownBy(() -> PlaintextDataKey.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlaintextDataKey.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlaintextDataKey.of("   ")).isInstanceOf(IllegalArgumentException.class);

        PlaintextDataKey key = PlaintextDataKey.of("sensitive-key");
        assertThat(key.reveal()).isEqualTo("sensitive-key");
        assertThat(key.toString()).isEqualTo("PlaintextDataKey[REDACTED]");

        KeyWrapRequest withoutContext = new KeyWrapRequest(key, null, null, null);
        KeyWrapRequest withContext = new KeyWrapRequest(key, context(WrappingContext.EXTERNAL_CONTEXT_V2),
                reference(), 1);
        assertThat(withoutContext.toString()).contains("contextSchema=null").doesNotContain("sensitive-key");
        assertThat(withContext.toString()).contains(WrappingContext.EXTERNAL_CONTEXT_V2)
                .doesNotContain("sensitive-key", "provider-key-id");
    }

    /**
     * 验证显式成功/失败结果拒绝非法构造，并按 retryable 语义抛出项目异常。
     */
    @Test
    void shouldEnforceResultAndFailureBoundaries() {
        assertThatThrownBy(() -> KeyWrappingResult.success(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyWrappingResult.failure(null))
                .isInstanceOf(IllegalArgumentException.class);

        KeyWrappingResult<String> success = KeyWrappingResult.success("ok");
        assertThat(success.isSuccess()).isTrue();
        assertThat(success.requireValue()).isEqualTo("ok");
        assertThat(success.toString()).isEqualTo("KeyWrappingResult[success]");

        KeyWrappingFailure nonRetryable = new KeyWrappingFailure(
                KeyWrappingFailureCategory.INVALID_REQUEST, false, "safe-code");
        KeyWrappingResult<String> failure = KeyWrappingResult.failure(nonRetryable);
        assertThat(failure.isSuccess()).isFalse();
        assertThat(failure.value()).isNull();
        assertThat(failure.failure()).isSameAs(nonRetryable);
        assertThatThrownBy(failure::requireValue).isInstanceOf(GeneralException.class);
        assertThat(failure.toString()).contains("INVALID_REQUEST", "safe-code");

        KeyWrappingFailure retryable = KeyWrappingFailure.of(KeyWrappingFailureCategory.TIMEOUT, true);
        assertThat(retryable.toException()).isInstanceOf(RetryableException.class);
        assertThat(retryable.toString()).contains("providerCode=none");
    }

    /**
     * 验证两个 context schema、哈希比较与全部字段完整性分支。
     */
    @Test
    void shouldValidateCanonicalContextMatrix() {
        WrappingContext local = context(WrappingContext.LOCAL_AAD_V1);
        WrappingContext external = context(WrappingContext.EXTERNAL_CONTEXT_V2);
        assertThat(new String(local.canonicalBytes(), StandardCharsets.UTF_8)).contains("|");
        assertThat(new String(external.canonicalBytes(), StandardCharsets.UTF_8))
                .startsWith(WrappingContext.EXTERNAL_CONTEXT_V2 + "\n");
        assertThat(external.matchesHash(null)).isFalse();
        assertThat(external.matchesHash("not-a-hash")).isFalse();
        assertThat(external.matchesHash(external.sha256Hex().toUpperCase())).isTrue();
        assertThat(external.matchesHash("0".repeat(64))).isFalse();
        assertThat(external.toString()).doesNotContain("file-hash", "OWNER");

        List<WrappingContext> invalid = java.util.Arrays.asList(
                new WrappingContext(null, 2L, "file-hash", "OWNER", 3L, 1, "suite", external.schema()),
                new WrappingContext(1L, null, "file-hash", "OWNER", 3L, 1, "suite", external.schema()),
                new WrappingContext(1L, 2L, "file-hash", "OWNER", null, 1, "suite", external.schema()),
                new WrappingContext(1L, 2L, "file-hash", "OWNER", 3L, null, "suite", external.schema()),
                new WrappingContext(1L, 2L, "file-hash", "OWNER", 3L, 0, "suite", external.schema()),
                new WrappingContext(1L, 2L, "", "OWNER", 3L, 1, "suite", external.schema()),
                new WrappingContext(1L, 2L, "file-hash", "", 3L, 1, "suite", external.schema()),
                new WrappingContext(1L, 2L, "file-hash", "OWNER", 3L, 1, "", external.schema()),
                new WrappingContext(1L, 2L, "file-hash", "OWNER", 3L, 1, "suite", "unknown")
        );
        for (WrappingContext candidate : invalid) {
            assertThatThrownBy(candidate::canonicalBytes).isInstanceOf(GeneralException.class);
            assertThatThrownBy(candidate::sha256Hex).isInstanceOf(GeneralException.class);
        }
    }

    /**
     * 验证旧配置访问器精确映射到隔离后的 local provider 配置。
     */
    @Test
    void shouldPreserveLegacyLocalConfigurationAccessors() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setProvider("vault-transit");
        properties.setKmsKeyId("legacy-key-id");
        properties.setLocalMasterKey("legacy-master-key");
        properties.setLocalMasterKeys(null);

        assertThat(properties.getProvider()).isEqualTo("vault-transit");
        assertThat(properties.getKmsKeyId()).isEqualTo("legacy-key-id");
        assertThat(properties.getLocalMasterKey()).isEqualTo("legacy-master-key");
        assertThat(properties.getLocalMasterKeys()).isEmpty();

        properties.setLocalMasterKeys(Map.of(1, "old", 2, "new"));
        assertThat(properties.getLocalMasterKeys()).containsEntry(1, "old").containsEntry(2, "new");
    }

    /**
     * 验证 transport result 的响应判定与字符串脱敏覆盖成功和失败分支。
     */
    @Test
    void shouldClassifyAndRedactTransportResults() {
        VaultTransitHttpResult response = VaultTransitHttpResult.response(
                200, "secret-body".getBytes(StandardCharsets.UTF_8));
        VaultTransitHttpResult missingStatus = new VaultTransitHttpResult(null, new byte[0], null);
        VaultTransitHttpResult failure = VaultTransitHttpResult.failure(
                KeyWrappingFailure.of(KeyWrappingFailureCategory.TIMEOUT, true));

        assertThat(response.hasResponse()).isTrue();
        assertThat(missingStatus.hasResponse()).isFalse();
        assertThat(failure.hasResponse()).isFalse();
        assertThat(response.toString()).contains("statusCode=200", "body=REDACTED")
                .doesNotContain("secret-body");
        assertThat(failure.toString()).contains("TIMEOUT");
    }

    /**
     * 构造固定的认证上下文。
     */
    private WrappingContext context(String schema) {
        return new WrappingContext(1L, 2L, "file-hash", "OWNER", 3L, 1, "suite", schema);
    }

    /**
     * 构造用于脱敏断言的 provider reference。
     */
    private WrappingKeyReference reference() {
        return new WrappingKeyReference(
                "vault-transit", 1, "provider-key-id", "1", "wrapping", "context-v1");
    }
}
