package cn.flying.service.key;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VaultTransitKeyWrappingProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FileKeyEnvelopeProperties properties;
    private StubTransport transport;
    private VaultTransitKeyWrappingProvider provider;

    @BeforeEach
    void setUp() {
        properties = validProperties();
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

    /**
     * 验证 provider 身份、能力和健康诊断只公开稳定的非敏感合同字段。
     */
    @Test
    void shouldExposeStableIdentityCapabilitiesAndDiagnostics() {
        KeyWrappingProviderDiagnostics diagnostics = provider.diagnostics();

        assertThat(provider.providerId()).isEqualTo(VaultTransitKeyWrappingProvider.PROVIDER_ID);
        assertThat(provider.contractVersion()).isEqualTo(VaultTransitKeyWrappingProvider.CONTRACT_VERSION);
        assertThat(provider.capabilities()).containsExactlyInAnyOrder(
                KeyWrappingCapability.WRAP,
                KeyWrappingCapability.UNWRAP,
                KeyWrappingCapability.NATIVE_REWRAP_SAME_KEY);
        assertThat(diagnostics.available()).isTrue();
        assertThat(diagnostics.configurationState()).isEqualTo("configured");
        assertThat(diagnostics.toString()).doesNotContain("test-vault-token", "file-key", "vault.test");

        FileKeyEnvelopeProperties httpsProperties = validProperties();
        vault(httpsProperties).setAddress("https://vault.test:8200");
        vault(httpsProperties).setAllowHttp(false);
        assertThat(new VaultTransitKeyWrappingProvider(
                httpsProperties, new StubTransport(), objectMapper).diagnostics().available()).isTrue();
    }

    /**
     * 验证 Vault 地址、路径、凭据、超时和资源边界的每个非法分支都失败关闭。
     */
    @Test
    void shouldRejectInvalidConfigurationMatrix() {
        List<Consumer<FileKeyEnvelopeProperties>> invalidConfigurations = List.of(
                candidate -> candidate.getProviders().setVaultTransit(null),
                candidate -> vault(candidate).setAddress(null),
                candidate -> vault(candidate).setAddress("vault.test:8200"),
                candidate -> vault(candidate).setAddress("://bad"),
                candidate -> vault(candidate).setAddress("https://user@vault.test:8200"),
                candidate -> vault(candidate).setAddress("https://vault.test:8200?query=1"),
                candidate -> vault(candidate).setAddress("https://vault.test:8200#fragment"),
                candidate -> vault(candidate).setAddress("https://vault.test:8200/path"),
                candidate -> vault(candidate).setAddress("ftp://vault.test:8200"),
                candidate -> {
                    vault(candidate).setAddress("http://vault.test:8200");
                    vault(candidate).setAllowHttp(false);
                },
                candidate -> vault(candidate).setToken(null),
                candidate -> vault(candidate).setToken("token\nheader"),
                candidate -> vault(candidate).setMount(null),
                candidate -> vault(candidate).setMount("../transit"),
                candidate -> vault(candidate).setKeyName(null),
                candidate -> vault(candidate).setKeyName("bad/key"),
                candidate -> vault(candidate).setKeyVersion(null),
                candidate -> vault(candidate).setKeyVersion(0),
                candidate -> vault(candidate).setConnectTimeout(null),
                candidate -> vault(candidate).setConnectTimeout(Duration.ZERO),
                candidate -> vault(candidate).setConnectTimeout(Duration.ofSeconds(-1)),
                candidate -> vault(candidate).setConnectTimeout(Duration.ofSeconds(61)),
                candidate -> vault(candidate).setRequestTimeout(null),
                candidate -> vault(candidate).setRequestTimeout(Duration.ZERO),
                candidate -> vault(candidate).setRequestTimeout(Duration.ofSeconds(-1)),
                candidate -> vault(candidate).setRequestTimeout(Duration.ofSeconds(61)),
                candidate -> vault(candidate).setMaxRequestBytes(1_023),
                candidate -> vault(candidate).setMaxRequestBytes(1_048_577),
                candidate -> vault(candidate).setMaxResponseBytes(1_023),
                candidate -> vault(candidate).setMaxResponseBytes(1_048_577),
                candidate -> vault(candidate).setNamespace("team\rheader"),
                candidate -> vault(candidate).setNamespace("bad namespace")
        );

        for (Consumer<FileKeyEnvelopeProperties> mutation : invalidConfigurations) {
            FileKeyEnvelopeProperties candidateProperties = validProperties();
            mutation.accept(candidateProperties);
            StubTransport candidateTransport = new StubTransport();
            VaultTransitKeyWrappingProvider candidate = new VaultTransitKeyWrappingProvider(
                    candidateProperties, candidateTransport, objectMapper);

            assertThat(candidate.diagnostics().available()).isFalse();
            assertThat(candidate.diagnostics().configurationState()).isEqualTo("invalid_configuration");
            assertThat(candidate.activeKeyReference(1).failure().category())
                    .isEqualTo(KeyWrappingFailureCategory.CONFIGURATION);
            assertThat(candidateTransport.calls).isZero();
        }
    }

    /**
     * 验证 namespace 与尾斜线地址生成规范 endpoint 和受控认证头。
     */
    @Test
    void shouldUseValidatedNamespaceAndTrailingSlashEndpoint() {
        FileKeyEnvelopeProperties.VaultTransit vault = vault(properties);
        vault.setAddress("http://vault.test:8200/");
        vault.setNamespace("team/project");
        transport.enqueue(response(200, """
                {"data":{"ciphertext":"vault:v1:wrapped"}}
                """));

        KeyWrappingResult<WrappedDataKey> result = provider.wrap(validWrapRequest());

        assertThat(result.isSuccess()).isTrue();
        assertThat(transport.lastUri.toString()).isEqualTo(
                "http://vault.test:8200/v1/transit/encrypt/file-key");
        assertThat(transport.lastHeaders.get("X-Vault-Namespace")).isEqualTo("team/project");
    }

    /**
     * 验证 active key 与 wrap 请求的空值、版本、reference 和 context 边界。
     */
    @Test
    void shouldRejectInvalidWrapRequestMatrix() {
        WrappingKeyReference validTarget = validReference("1");
        WrappingContext validContext = context(1);
        PlaintextDataKey emptyKey = mock(PlaintextDataKey.class);
        when(emptyKey.reveal()).thenReturn("");
        List<KeyWrapRequest> invalidRequests = java.util.Arrays.asList(
                null,
                new KeyWrapRequest(null, validContext, validTarget, 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), null, validTarget, 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext, null, 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext, validTarget, null),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext, validTarget, 0),
                new KeyWrapRequest(PlaintextDataKey.of("key"), contextWithSchema("unknown"), validTarget, 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext,
                        reference("other", 1, "file-key", "1",
                                VaultTransitKeyWrappingProvider.WRAPPING_ALGORITHM,
                                WrappingContext.EXTERNAL_CONTEXT_V2), 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext,
                        reference(VaultTransitKeyWrappingProvider.PROVIDER_ID, 2, "file-key", "1",
                                VaultTransitKeyWrappingProvider.WRAPPING_ALGORITHM,
                                WrappingContext.EXTERNAL_CONTEXT_V2), 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext,
                        reference(VaultTransitKeyWrappingProvider.PROVIDER_ID, 1, "bad/key", "1",
                                VaultTransitKeyWrappingProvider.WRAPPING_ALGORITHM,
                                WrappingContext.EXTERNAL_CONTEXT_V2), 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext,
                        reference(VaultTransitKeyWrappingProvider.PROVIDER_ID, 1, null, "1",
                                VaultTransitKeyWrappingProvider.WRAPPING_ALGORITHM,
                                WrappingContext.EXTERNAL_CONTEXT_V2), 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext,
                        reference(VaultTransitKeyWrappingProvider.PROVIDER_ID, 1, "file-key", "1",
                                "wrong", WrappingContext.EXTERNAL_CONTEXT_V2), 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext,
                        reference(VaultTransitKeyWrappingProvider.PROVIDER_ID, 1, "file-key", "1",
                                VaultTransitKeyWrappingProvider.WRAPPING_ALGORITHM,
                                WrappingContext.LOCAL_AAD_V1), 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext,
                        validReference("0"), 1),
                new KeyWrapRequest(PlaintextDataKey.of("key"), validContext,
                        validReference("not-a-number"), 1)
        );

        assertThat(provider.activeKeyReference(null).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        assertThat(provider.activeKeyReference(0).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        for (KeyWrapRequest request : invalidRequests) {
            assertThat(provider.wrap(request).failure().category())
                    .isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        }
        assertThat(provider.wrap(new KeyWrapRequest(
                PlaintextDataKey.of("x".repeat(8_193)), validContext, validTarget, 1))
                .failure().category()).isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        assertThat(provider.wrap(new KeyWrapRequest(emptyKey, validContext, validTarget, 1))
                .failure().category()).isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        assertThat(transport.calls).isZero();
    }

    /**
     * 验证 unwrap 请求对空值、provider metadata、context 和 ciphertext 版本严格失败关闭。
     */
    @Test
    void shouldRejectInvalidUnwrapRequestMatrix() {
        WrappingContext validContext = context(1);
        List<KeyUnwrapRequest> invalidRequests = java.util.Arrays.asList(
                null,
                new KeyUnwrapRequest(null, validContext),
                new KeyUnwrapRequest(persisted("vault:v1:wrapped", "1"), null),
                new KeyUnwrapRequest(new PersistedWrappedDataKey(
                        "vault:v1:wrapped", null, validReference("1"), null), validContext),
                new KeyUnwrapRequest(new PersistedWrappedDataKey(
                        "vault:v1:wrapped", null, validReference("1"), 0), validContext),
                new KeyUnwrapRequest(new PersistedWrappedDataKey(
                        "vault:v1:wrapped", null, null, 1), validContext),
                new KeyUnwrapRequest(persisted(null, "1"), validContext),
                new KeyUnwrapRequest(persisted("not-vault", "1"), validContext),
                new KeyUnwrapRequest(persisted("vault:v1:wrapped", "2"), validContext),
                new KeyUnwrapRequest(persistedWithReference("vault:v1:wrapped",
                        reference("other", 1, "file-key", "1",
                                VaultTransitKeyWrappingProvider.WRAPPING_ALGORITHM,
                                WrappingContext.EXTERNAL_CONTEXT_V2)), validContext),
                new KeyUnwrapRequest(persisted("vault:v1:wrapped", "1"), contextWithSchema("unknown")),
                new KeyUnwrapRequest(persisted("vault:v1:wrapped", "1"), incompleteContext())
        );

        for (KeyUnwrapRequest request : invalidRequests) {
            assertThat(provider.unwrap(request).failure().category())
                    .isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        }
        assertThat(transport.calls).isZero();
    }

    /**
     * 验证原生 rewrap 只接受同 named key、同规范 context 和合法目标版本。
     */
    @Test
    void shouldRejectInvalidNativeRewrapMatrix() {
        PersistedWrappedDataKey source = persisted("vault:v1:wrapped", "1");
        WrappingContext validContext = context(1);
        WrappingKeyReference target = validReference("2");
        List<KeyRewrapRequest> invalidRequests = java.util.Arrays.asList(
                null,
                new KeyRewrapRequest(null, validContext, target, validContext, 2),
                new KeyRewrapRequest(source, null, target, validContext, 2),
                new KeyRewrapRequest(source, validContext, null, validContext, 2),
                new KeyRewrapRequest(source, validContext, target, null, 2),
                new KeyRewrapRequest(source, validContext, target, validContext, null),
                new KeyRewrapRequest(source, validContext, target, validContext, 0),
                new KeyRewrapRequest(source, validContext,
                        reference(VaultTransitKeyWrappingProvider.PROVIDER_ID, 1, "other-key", "2",
                                VaultTransitKeyWrappingProvider.WRAPPING_ALGORITHM,
                                WrappingContext.EXTERNAL_CONTEXT_V2), validContext, 2),
                new KeyRewrapRequest(persistedWithReference("vault:v1:wrapped",
                        reference("other", 1, "file-key", "1",
                                VaultTransitKeyWrappingProvider.WRAPPING_ALGORITHM,
                                WrappingContext.EXTERNAL_CONTEXT_V2)), validContext,
                        target, validContext, 2),
                new KeyRewrapRequest(source, validContext,
                        reference(VaultTransitKeyWrappingProvider.PROVIDER_ID, 1, "file-key", "2",
                                "wrong", WrappingContext.EXTERNAL_CONTEXT_V2), validContext, 2),
                new KeyRewrapRequest(source, validContext, target, differentContext(), 2),
                new KeyRewrapRequest(source, contextWithSchema(WrappingContext.LOCAL_AAD_V1),
                        target, validContext, 2),
                new KeyRewrapRequest(source, validContext, target,
                        contextWithSchema(WrappingContext.LOCAL_AAD_V1), 2),
                new KeyRewrapRequest(source, validContext, validReference("bad"), validContext, 2),
                new KeyRewrapRequest(persisted("vault:v2:wrapped", "1"),
                        validContext, target, validContext, 2)
        );

        for (KeyRewrapRequest request : invalidRequests) {
            assertThat(provider.rewrap(request).failure().category())
                    .isEqualTo(KeyWrappingFailureCategory.UNSUPPORTED);
        }
        assertThat(transport.calls).isZero();
    }

    /**
     * 验证 wrap/rewrap 的 transport 失败与畸形 ciphertext 均使用稳定分类。
     */
    @Test
    void shouldRejectMalformedCiphertextResponsesAndPropagateTransportFailure() {
        transport.enqueue(VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                KeyWrappingFailureCategory.TIMEOUT, true)));
        assertThat(provider.wrap(validWrapRequest()).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.TIMEOUT);

        for (String body : List.of(
                "{\"data\":{}}",
                "{\"data\":{\"ciphertext\":42}}",
                "{\"data\":{\"ciphertext\":\"not-vault\"}}",
                "{\"data\":{\"ciphertext\":\"vault:v0:wrapped\"}}")) {
            transport.enqueue(response(200, body));
            assertThat(provider.wrap(validWrapRequest()).failure().category())
                    .isEqualTo(KeyWrappingFailureCategory.INVALID_RESPONSE);
        }

        transport.enqueue(VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                KeyWrappingFailureCategory.UNAVAILABLE, true)));
        assertThat(provider.rewrap(new KeyRewrapRequest(
                persisted("vault:v1:wrapped", "1"), context(1),
                validReference("2"), context(1), 2)).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.UNAVAILABLE);
    }

    /**
     * 验证 decrypt 的非文本、超限、Base64 和 UTF-8 畸形响应都被拒绝。
     */
    @Test
    void shouldRejectMalformedPlaintextResponseMatrix() throws Exception {
        List<String> malformedBodies = List.of(
                "{\"data\":{}}",
                "{\"data\":{\"plaintext\":42}}",
                objectMapper.writeValueAsString(Map.of("data", Map.of("plaintext", "a".repeat(16_385)))),
                "{\"data\":{\"plaintext\":\"\"}}",
                objectMapper.writeValueAsString(Map.of("data", Map.of("plaintext",
                        Base64.getEncoder().encodeToString(new byte[8_193])))),
                "{\"data\":{\"plaintext\":\"***\"}}",
                objectMapper.writeValueAsString(Map.of("data", Map.of("plaintext",
                        Base64.getEncoder().encodeToString(new byte[]{(byte) 0xC3, 0x28}))))
        );

        for (String body : malformedBodies) {
            transport.enqueue(response(200, body));
            assertThat(provider.unwrap(new KeyUnwrapRequest(
                    persisted("vault:v1:wrapped", "1"), context(1))).failure().category())
                    .isEqualTo(KeyWrappingFailureCategory.INVALID_RESPONSE);
        }
    }

    /**
     * 验证请求体边界、非对象响应、坏 JSON 和非标准 4xx 映射。
     */
    @Test
    void shouldClassifyExchangeBoundaryFailures() {
        vault(properties).setMaxRequestBytes(1_024);
        KeyWrapRequest oversizedBody = new KeyWrapRequest(
                PlaintextDataKey.of("x".repeat(1_000)), context(1), validReference("1"), 1);
        assertThat(provider.wrap(oversizedBody).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
        assertThat(transport.calls).isZero();

        vault(properties).setMaxRequestBytes(65_536);
        for (String body : List.of("", "null", "[]", "{")) {
            transport.enqueue(response(200, body));
            assertThat(provider.unwrap(new KeyUnwrapRequest(
                    persisted("vault:v1:wrapped", "1"), context(1))).failure().category())
                    .isEqualTo(KeyWrappingFailureCategory.INVALID_RESPONSE);
        }

        assertFailure(300, KeyWrappingFailureCategory.INVALID_REQUEST, false);
        assertFailure(418, KeyWrappingFailureCategory.INVALID_REQUEST, false);
    }

    /**
     * 验证 JSON 序列化的运行时与受检失败不会逃逸 provider 结果边界。
     */
    @Test
    void shouldContainObjectMapperFailures() throws Exception {
        ObjectMapper runtimeMapper = mock(ObjectMapper.class);
        when(runtimeMapper.writeValueAsBytes(any())).thenThrow(new IllegalStateException("runtime"));
        VaultTransitKeyWrappingProvider runtimeProvider = new VaultTransitKeyWrappingProvider(
                validProperties(), new StubTransport(), runtimeMapper);
        assertThat(runtimeProvider.wrap(validWrapRequest()).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.CONFIGURATION);

        ObjectMapper checkedMapper = mock(ObjectMapper.class);
        when(checkedMapper.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("json") {
        });
        VaultTransitKeyWrappingProvider checkedProvider = new VaultTransitKeyWrappingProvider(
                validProperties(), new StubTransport(), checkedMapper);
        assertThat(checkedProvider.wrap(validWrapRequest()).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.INVALID_RESPONSE);

        ObjectMapper emptyMapper = mock(ObjectMapper.class);
        when(emptyMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        VaultTransitKeyWrappingProvider emptyProvider = new VaultTransitKeyWrappingProvider(
                validProperties(), new StubTransport(), emptyMapper);
        assertThat(emptyProvider.wrap(validWrapRequest()).failure().category())
                .isEqualTo(KeyWrappingFailureCategory.INVALID_REQUEST);
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

    /**
     * 构造指定 schema 的完整上下文。
     */
    private WrappingContext contextWithSchema(String schema) {
        return new WrappingContext(
                7L, 11L, "hash-sensitive", "OWNER", 13L, 1,
                "RP-AES256-GCM-CHUNK-CHAIN-V1", schema);
    }

    /**
     * 构造缺少租户的上下文以验证 canonical 校验异常收敛。
     */
    private WrappingContext incompleteContext() {
        return new WrappingContext(
                null, 11L, "hash-sensitive", "OWNER", 13L, 1,
                "RP-AES256-GCM-CHUNK-CHAIN-V1", WrappingContext.EXTERNAL_CONTEXT_V2);
    }

    /**
     * 构造业务身份不同的 v2 context 以验证原生 rewrap 的常量时间绑定。
     */
    private WrappingContext differentContext() {
        return new WrappingContext(
                7L, 11L, "different-hash", "OWNER", 13L, 1,
                "RP-AES256-GCM-CHUNK-CHAIN-V1", WrappingContext.EXTERNAL_CONTEXT_V2);
    }

    /**
     * 构造合法 wrap 请求。
     */
    private KeyWrapRequest validWrapRequest() {
        return new KeyWrapRequest(PlaintextDataKey.of("serialized-key"),
                context(1), validReference("1"), 1);
    }

    /**
     * 构造合法 Vault key reference。
     */
    private WrappingKeyReference validReference(String providerVersion) {
        return reference(
                VaultTransitKeyWrappingProvider.PROVIDER_ID,
                VaultTransitKeyWrappingProvider.CONTRACT_VERSION,
                "file-key",
                providerVersion,
                VaultTransitKeyWrappingProvider.WRAPPING_ALGORITHM,
                WrappingContext.EXTERNAL_CONTEXT_V2);
    }

    /**
     * 构造可定向破坏字段的 Vault key reference。
     */
    private WrappingKeyReference reference(String providerId,
                                           int contractVersion,
                                           String keyId,
                                           String providerVersion,
                                           String wrappingAlgorithm,
                                           String contextSchema) {
        return new WrappingKeyReference(
                providerId, contractVersion, keyId, providerVersion, wrappingAlgorithm, contextSchema);
    }

    private PersistedWrappedDataKey persisted(String ciphertext, String providerVersion) {
        return persistedWithReference(ciphertext, validReference(providerVersion));
    }

    /**
     * 构造带指定路由 reference 的持久化材料。
     */
    private PersistedWrappedDataKey persistedWithReference(String ciphertext,
                                                            WrappingKeyReference reference) {
        return new PersistedWrappedDataKey(ciphertext, null, reference, 1);
    }

    private VaultTransitHttpResult response(int status, String body) {
        return VaultTransitHttpResult.response(status, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造每个配置矩阵用例独立的合法 properties。
     */
    private FileKeyEnvelopeProperties validProperties() {
        FileKeyEnvelopeProperties candidate = new FileKeyEnvelopeProperties();
        FileKeyEnvelopeProperties.VaultTransit vault = vault(candidate);
        vault.setAddress("http://vault.test:8200");
        vault.setAllowHttp(true);
        vault.setToken("test-vault-token");
        vault.setMount("transit");
        vault.setKeyName("file-key");
        vault.setKeyVersion(1);
        return candidate;
    }

    /**
     * 返回指定 properties 的 Vault 配置。
     */
    private FileKeyEnvelopeProperties.VaultTransit vault(FileKeyEnvelopeProperties candidate) {
        return candidate.getProviders().getVaultTransit();
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
