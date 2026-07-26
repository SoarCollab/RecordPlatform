package cn.flying.service.key;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VaultTransitKeyWrappingProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FileKeyEnvelopeProperties properties;
    private StubTransport transport;
    private VaultTransitKeyWrappingProvider provider;

    @BeforeEach
    void setUp() {
        properties = new FileKeyEnvelopeProperties();
        FileKeyEnvelopeProperties.VaultTransit vault = properties.getProviders().getVaultTransit();
        vault.setAddress("http://vault.test:8200");
        vault.setAllowHttp(true);
        vault.setToken("test-vault-token");
        vault.setMount("transit");
        vault.setKeyName("file-key");
        vault.setKeyVersion(1);
        transport = new StubTransport();
        provider = new VaultTransitKeyWrappingProvider(properties, transport, objectMapper);
    }

    /**
     * 验证 wrap 只向 Vault 发送 canonical context 摘要，不发送业务标识原值。
     */
    @Test
    void shouldWrapWithHashedDerivedContext() throws Exception {
        transport.enqueue(response(200, """
                {"data":{"ciphertext":"vault:v1:wrapped"}}
                """));
        WrappingContext context = context(1);
        WrappingKeyReference target = provider.activeKeyReference(1).requireValue();

        KeyWrappingResult<WrappedDataKey> result = provider.wrap(new KeyWrapRequest(
                PlaintextDataKey.of("serialized-key"), context, target, 1));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().keyReference().providerKeyVersion()).isEqualTo("1");
        JsonNode payload = objectMapper.readTree(transport.lastBody);
        String expectedContext = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(context.canonicalBytes()));
        assertThat(payload.path("context").textValue()).isEqualTo(expectedContext);
        assertThat(new String(transport.lastBody, StandardCharsets.UTF_8))
                .doesNotContain("hash-sensitive", "OWNER", "test-vault-token");
        assertThat(transport.lastHeaders.get("X-Vault-Token")).isEqualTo("test-vault-token");
        assertThat(result.value().toString()).doesNotContain("vault:v1:wrapped");
    }

    /**
     * 验证 decrypt 成功响应恢复 plaintext，且对象字符串不泄漏密钥。
     */
    @Test
    void shouldUnwrapValidVaultCiphertext() {
        transport.enqueue(response(200, """
                {"data":{"plaintext":"c2VyaWFsaXplZC1rZXk="}}
                """));

        KeyWrappingResult<PlaintextDataKey> result = provider.unwrap(new KeyUnwrapRequest(
                persisted("vault:v1:wrapped", "1"), context(1)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().reveal()).isEqualTo("serialized-key");
        assertThat(result.value().toString()).doesNotContain("serialized-key");
    }

    /**
     * 验证同 named key 与稳定 v2 context 使用原生 rewrap。
     */
    @Test
    void shouldRewrapToExplicitVaultKeyVersion() throws Exception {
        properties.getProviders().getVaultTransit().setKeyVersion(2);
        transport.enqueue(response(200, """
                {"data":{"ciphertext":"vault:v2:rewrapped"}}
                """));
        WrappingKeyReference target = provider.activeKeyReference(2).requireValue();

        KeyWrappingResult<WrappedDataKey> result = provider.rewrap(new KeyRewrapRequest(
                persisted("vault:v1:wrapped", "1"), context(1), target, context(2), 2));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().keyReference().providerKeyVersion()).isEqualTo("2");
        assertThat(transport.lastUri.getPath()).isEqualTo("/v1/transit/rewrap/file-key");
        assertThat(objectMapper.readTree(transport.lastBody).path("key_version").intValue()).isEqualTo(2);
    }

    /**
     * 验证 HTTP status 仅按状态码映射稳定分类，不传播 Vault errors 文本。
     */
    @Test
    void shouldMapVaultStatusesWithoutRawErrors() {
        assertFailure(400, KeyWrappingFailureCategory.INVALID_REQUEST, false);
        assertFailure(403, KeyWrappingFailureCategory.PERMISSION_DENIED, false);
        assertFailure(404, KeyWrappingFailureCategory.KEY_NOT_FOUND, false);
        assertFailure(429, KeyWrappingFailureCategory.THROTTLED, true);
        assertFailure(503, KeyWrappingFailureCategory.UNAVAILABLE, true);
    }

    /**
     * 验证 timeout 和 malformed success 具有独立稳定分类。
     */
    @Test
    void shouldClassifyTransportAndMalformedResponses() {
        transport.enqueue(VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                KeyWrappingFailureCategory.TIMEOUT, true)));
        KeyWrappingResult<PlaintextDataKey> timeout = provider.unwrap(new KeyUnwrapRequest(
                persisted("vault:v1:wrapped", "1"), context(1)));
        assertThat(timeout.failure().category()).isEqualTo(KeyWrappingFailureCategory.TIMEOUT);

        transport.enqueue(response(200, "{\"data\":{}}"));
        KeyWrappingResult<PlaintextDataKey> malformed = provider.unwrap(new KeyUnwrapRequest(
                persisted("vault:v1:wrapped", "1"), context(1)));
        assertThat(malformed.failure().category()).isEqualTo(KeyWrappingFailureCategory.INVALID_RESPONSE);
    }

    /**
     * 验证 ciphertext prefix 与持久化 provider key version 不一致时不调用 Vault。
     */
    @Test
    void shouldRejectCiphertextVersionMismatchBeforeHttp() {
        KeyWrappingResult<PlaintextDataKey> result = provider.unwrap(new KeyUnwrapRequest(
                persisted("vault:v2:wrapped", "1"), context(1)));

        assertThat(result.failure().category()).isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        assertThat(transport.calls).isZero();
    }

    /**
     * 验证历史读取遇到缺失 Vault 凭据时返回配置分类且不发起 HTTP 请求。
     */
    @Test
    void shouldRejectMissingHistoricalVaultConfigurationBeforeHttp() {
        properties.getProviders().getVaultTransit().setToken(null);

        KeyWrappingResult<PlaintextDataKey> result = provider.unwrap(new KeyUnwrapRequest(
                persisted("vault:v1:wrapped", "1"), context(1)));

        assertThat(result.failure().category()).isEqualTo(KeyWrappingFailureCategory.CONFIGURATION);
        assertThat(transport.calls).isZero();
    }

    /**
     * 验证缺失 mount 或 key name 不会被字面量 null 绕过配置校验。
     */
    @Test
    void shouldRejectMissingVaultPathConfiguration() {
        properties.getProviders().getVaultTransit().setKeyName(null);

        assertThat(provider.diagnostics().available()).isFalse();
        assertThat(provider.activeKeyReference(1).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.CONFIGURATION);
        assertThat(transport.calls).isZero();
    }

    /**
     * 验证不完整认证上下文转换为稳定失败结果，不允许业务异常逃逸 SPI。
     */
    @Test
    void shouldRejectIncompleteContextWithoutThrowing() {
        WrappingContext incomplete = new WrappingContext(
                null, 11L, "hash-sensitive", "OWNER", 13L, 1,
                "RP-AES256-GCM-CHUNK-CHAIN-V1", WrappingContext.EXTERNAL_CONTEXT_V2);

        KeyWrappingResult<WrappedDataKey> result = provider.wrap(new KeyWrapRequest(
                PlaintextDataKey.of("serialized-key"), incomplete,
                provider.activeKeyReference(1).requireValue(), 1));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().category()).isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        assertThat(transport.calls).isZero();
    }

    private void assertFailure(int status,
                               KeyWrappingFailureCategory category,
                               boolean retryable) {
        transport.enqueue(response(status, "{\"errors\":[\"raw-secret-provider-error\"]}"));
        KeyWrappingResult<PlaintextDataKey> result = provider.unwrap(new KeyUnwrapRequest(
                persisted("vault:v1:wrapped", "1"), context(1)));
        assertThat(result.failure().category()).isEqualTo(category);
        assertThat(result.failure().retryable()).isEqualTo(retryable);
        assertThat(result.failure().toString()).doesNotContain("raw-secret-provider-error");
    }

    private WrappingContext context(int logicalVersion) {
        return new WrappingContext(
                7L, 11L, "hash-sensitive", "OWNER", 13L, logicalVersion,
                "RP-AES256-GCM-CHUNK-CHAIN-V1", WrappingContext.EXTERNAL_CONTEXT_V2);
    }

    private PersistedWrappedDataKey persisted(String ciphertext, String providerVersion) {
        return new PersistedWrappedDataKey(
                ciphertext,
                null,
                new WrappingKeyReference(
                        VaultTransitKeyWrappingProvider.PROVIDER_ID,
                        VaultTransitKeyWrappingProvider.CONTRACT_VERSION,
                        "file-key",
                        providerVersion,
                        VaultTransitKeyWrappingProvider.WRAPPING_ALGORITHM,
                        WrappingContext.EXTERNAL_CONTEXT_V2),
                1);
    }

    private VaultTransitHttpResult response(int status, String body) {
        return VaultTransitHttpResult.response(status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class StubTransport implements VaultTransitTransport {

        private final ArrayDeque<VaultTransitHttpResult> responses = new ArrayDeque<>();
        private URI lastUri;
        private Map<String, String> lastHeaders;
        private byte[] lastBody;
        private int calls;

        private void enqueue(VaultTransitHttpResult response) {
            responses.add(response);
        }

        @Override
        public VaultTransitHttpResult post(URI uri,
                                           Map<String, String> headers,
                                           byte[] body,
                                           Duration requestTimeout,
                                           int maxResponseBytes) {
            calls++;
            lastUri = uri;
            lastHeaders = Map.copyOf(headers);
            lastBody = body.clone();
            return responses.removeFirst();
        }
    }
}
