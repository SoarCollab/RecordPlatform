package cn.flying.service.proof.signed;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 专用 Ed25519 proof signer 的确定性、配置和 fail-closed 测试。
 */
class LocalEd25519ProofSigningProviderTest {

    private ProofSigningProperties properties;
    private ProofCanonicalizer canonicalizer;
    private LocalEd25519ProofSigningProvider provider;

    /**
     * 为每个测试生成独立密钥，并启用 ACTIVE proof signer。
     */
    @BeforeEach
    void setUp() throws Exception {
        properties = activeProperties(generateKeyPair(), "proof-key-main", 1);
        canonicalizer = new ProofCanonicalizer();
        provider = new LocalEd25519ProofSigningProvider(properties, canonicalizer);
    }

    /**
     * 验证相同 manifest 的 Ed25519 compact JWS 可重复且 payload 逐字节绑定。
     */
    @Test
    void shouldSignDeterministicallyAndVerifyExactPayload() throws Exception {
        byte[] manifest = "{\"proofId\":\"rp-proof-test\"}".getBytes(StandardCharsets.UTF_8);
        ProofSigningKeyMetadata key = provider.currentKey();

        ProofSignature first = provider.sign(manifest, key);
        ProofSignature second = provider.sign(manifest, key);

        assertThat(second.compactJws()).isEqualTo(first.compactJws());
        assertThat(first.key()).isEqualTo(key);
        assertThat(provider.verify(manifest, first.compactJws(), key)).isTrue();
        assertThat(provider.verify("{}".getBytes(StandardCharsets.UTF_8),
                first.compactJws(), key)).isFalse();

        String[] parts = first.compactJws().split("\\.");
        JsonNode header = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.path("alg").asText()).isEqualTo("EdDSA");
        assertThat(header.path("kid").asText()).isEqualTo("proof-key-main");
        assertThat(header.path("keyVersion").asInt()).isEqualTo(1);
        assertThat(Base64.getUrlDecoder().decode(parts[1])).isEqualTo(manifest);
    }

    /**
     * 验证错误历史公钥、畸形 JWS 和被修改签名均返回验证失败。
     */
    @Test
    void shouldRejectWrongKeyMalformedJwsAndTamperedSignature() throws Exception {
        byte[] manifest = "{\"schemaVersion\":\"v2\"}".getBytes(StandardCharsets.UTF_8);
        ProofSigningKeyMetadata key = provider.currentKey();
        String compactJws = provider.sign(manifest, key).compactJws();
        String wrongSpki = Base64.getEncoder().encodeToString(generateKeyPair().getPublic().getEncoded());
        ProofSigningKeyMetadata wrongKey = new ProofSigningKeyMetadata(
                key.algorithm(), key.keyId(), key.keyVersion(), wrongSpki, key.publicKeyFingerprint());
        ProofSigningKeyMetadata wrongHeaderKey = new ProofSigningKeyMetadata(
                key.algorithm(), "different-key-id", key.keyVersion(),
                key.publicKeySpki(), key.publicKeyFingerprint());

        assertThat(provider.verify(manifest, compactJws, wrongKey)).isFalse();
        assertThat(provider.verify(manifest, compactJws, wrongHeaderKey)).isFalse();
        assertThat(provider.verify(manifest, "not-a-jws", key)).isFalse();
        char replacement = compactJws.charAt(compactJws.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = compactJws.substring(0, compactJws.length() - 1) + replacement;
        assertThat(provider.verify(manifest, tampered, key)).isFalse();
    }

    /**
     * 验证禁用、非 ACTIVE、算法错误和缺失密钥均拒绝新签发且不泄漏密钥材料。
     */
    @Test
    void shouldFailClosedWhenSigningConfigurationIsUnavailable() {
        properties.setEnabled(false);

        assertSigningUnavailable(provider);

        properties.setEnabled(true);
        properties.setKeyStatus("REVOKED");
        assertSigningUnavailable(provider);

        properties.setKeyStatus("ACTIVE");
        properties.setAlgorithm("RSA");
        assertSigningUnavailable(provider);

        properties.setAlgorithm("Ed25519");
        properties.setPrivateKeyPkcs8("");
        assertSigningUnavailable(provider);
    }

    /**
     * 验证历史导出门禁只读取全局开关，不要求当前私钥仍可用于新签发。
     */
    @Test
    void shouldGateHistoricalExportOnlyByGlobalSwitch() {
        properties.setAlgorithm("unavailable-current-key");
        properties.setPrivateKeyPkcs8("");

        provider.requireExportEnabled();

        properties.setEnabled(false);
        assertThatThrownBy(provider::requireExportEnabled)
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.PERMISSION_SIGNATURE_ERROR));
    }

    /**
     * Proves diagnostics distinguish disabled and invalid configuration without exposing key identities.
     */
    @Test
    void shouldReportDisabledAndInvalidDiagnosticsAndRejectEmptySigningInput() {
        properties.setEnabled(false);
        ProofSigningProviderDiagnostic disabled = provider.diagnostics();
        assertThat(disabled.available()).isFalse();
        assertThat(disabled.configurationState()).isEqualTo("disabled");

        properties.setEnabled(true);
        properties.setPrivateKeyPkcs8("invalid-base64");
        ProofSigningProviderDiagnostic invalid = provider.diagnostics();
        assertThat(invalid.available()).isFalse();
        assertThat(invalid.configurationState()).isEqualTo("invalid_configuration");
        assertThat(invalid.toString()).doesNotContain("invalid-base64", "proof-key-main");

        assertThatThrownBy(() -> provider.sign(null, null))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * 验证不匹配密钥对和 currentKey/sign 之间的轮换都失败关闭。
     */
    @Test
    void shouldRejectMismatchedOrRotatedKey() throws Exception {
        ProofSigningKeyMetadata expected = provider.currentKey();
        KeyPair rotated = generateKeyPair();
        properties.setPrivateKeyPkcs8(Base64.getEncoder().encodeToString(rotated.getPrivate().getEncoded()));
        properties.setPublicKeySpki(Base64.getEncoder().encodeToString(rotated.getPublic().getEncoded()));
        properties.setKeyVersion(2);

        assertThatThrownBy(() -> provider.sign("manifest".getBytes(StandardCharsets.UTF_8), expected))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.PERMISSION_SIGNATURE_ERROR));

        properties.setPublicKeySpki(Base64.getEncoder().encodeToString(generateKeyPair().getPublic().getEncoded()));
        assertSigningUnavailable(provider);
    }

    /**
     * 断言 signer 以统一、不含底层 key material 的异常失败。
     */
    private void assertSigningUnavailable(LocalEd25519ProofSigningProvider signer) {
        String privateMaterial = properties.getPrivateKeyPkcs8();
        assertThatThrownBy(signer::currentKey)
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> {
                    GeneralException exception = (GeneralException) error;
                    assertThat(exception.getResultEnum()).isEqualTo(ResultEnum.PERMISSION_SIGNATURE_ERROR);
                    if (privateMaterial != null && !privateMaterial.isBlank()) {
                        assertThat(String.valueOf(exception.getMessage()) + String.valueOf(exception.getData()))
                                .doesNotContain(privateMaterial);
                    }
                });
    }

    /**
     * 构造一组完整 ACTIVE 配置。
     */
    private ProofSigningProperties activeProperties(KeyPair keyPair, String keyId, int version) {
        ProofSigningProperties value = new ProofSigningProperties();
        value.setEnabled(true);
        value.setAlgorithm("Ed25519");
        value.setKeyId(keyId);
        value.setKeyVersion(version);
        value.setKeyStatus("ACTIVE");
        value.setPrivateKeyPkcs8(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        value.setPublicKeySpki(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        return value;
    }

    /**
     * 使用 JCA 生成测试专用 Ed25519 密钥对。
     */
    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }
}
