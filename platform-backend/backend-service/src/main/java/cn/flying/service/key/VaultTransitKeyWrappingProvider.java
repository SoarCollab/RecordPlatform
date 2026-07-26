package cn.flying.service.key;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用 derived Vault Transit key 的外部密钥包封 provider。
 */
@Service
public class VaultTransitKeyWrappingProvider implements KeyWrappingProvider {

    public static final String PROVIDER_ID = "vault-transit";
    public static final int CONTRACT_VERSION = 1;
    public static final String WRAPPING_ALGORITHM = "VAULT-TRANSIT-AES256-GCM96-DERIVED";

    private static final Pattern SAFE_PATH_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private static final Pattern SAFE_NAMESPACE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_/-]{0,255}");
    private static final Pattern VAULT_CIPHERTEXT = Pattern.compile("^vault:v([1-9][0-9]{0,9}):[^\\s\\p{Cntrl}]{1,32768}$");
    private static final int MAX_PLAINTEXT_BYTES = 8_192;
    private static final java.time.Duration MAX_CONNECT_TIMEOUT = java.time.Duration.ofSeconds(60);
    private static final java.time.Duration MAX_REQUEST_TIMEOUT = java.time.Duration.ofSeconds(60);

    private final FileKeyEnvelopeProperties properties;
    private final VaultTransitTransport transport;
    private final ObjectMapper objectMapper;

    /**
     * 创建 Vault provider 并复用项目 ObjectMapper 与 JDK transport。
     */
    public VaultTransitKeyWrappingProvider(FileKeyEnvelopeProperties properties,
                                           VaultTransitTransport transport,
                                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回稳定 Vault provider id。
     */
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * 返回持久化路由 contract version。
     */
    @Override
    public int contractVersion() {
        return CONTRACT_VERSION;
    }

    /**
     * 声明 wrap、unwrap 与同 named key 原生 rewrap 能力。
     */
    @Override
    public Set<KeyWrappingCapability> capabilities() {
        return Set.of(
                KeyWrappingCapability.WRAP,
                KeyWrappingCapability.UNWRAP,
                KeyWrappingCapability.NATIVE_REWRAP_SAME_KEY
        );
    }

    /**
     * 返回当前 Vault named key 与显式目标版本。
     */
    @Override
    public KeyWrappingResult<WrappingKeyReference> activeKeyReference(Integer logicalKeyVersion) {
        FileKeyEnvelopeProperties.VaultTransit vault = vault();
        if (logicalKeyVersion == null || logicalKeyVersion <= 0) {
            return failure(KeyWrappingFailureCategory.INVALID_REQUEST, false);
        }
        if (!hasValidConfiguration(vault)) {
            return failure(KeyWrappingFailureCategory.CONFIGURATION, false);
        }
        return KeyWrappingResult.success(new WrappingKeyReference(
                PROVIDER_ID,
                CONTRACT_VERSION,
                vault.getKeyName(),
                String.valueOf(vault.getKeyVersion()),
                WRAPPING_ALGORITHM,
                WrappingContext.EXTERNAL_CONTEXT_V2
        ));
    }

    /**
     * 调用 Transit encrypt 包封明文数据密钥。
     */
    @Override
    public KeyWrappingResult<WrappedDataKey> wrap(KeyWrapRequest request) {
        if (!validWrapRequest(request)) {
            return failure(KeyWrappingFailureCategory.INVALID_REQUEST, false);
        }
        byte[] plaintext = request.plaintextDataKey().reveal().getBytes(StandardCharsets.UTF_8);
        if (plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) {
            return failure(KeyWrappingFailureCategory.INVALID_REQUEST, false);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("plaintext", Base64.getEncoder().encodeToString(plaintext));
        payload.put("context", derivedContext(request.context()));
        payload.put("key_version", parsePositiveVersion(request.target().providerKeyVersion()));
        return exchangeCiphertext("encrypt", request.target().keyId(), payload,
                request.target(), request.logicalKeyVersion());
    }

    /**
     * 调用 Transit decrypt 解封持久化 ciphertext。
     */
    @Override
    public KeyWrappingResult<PlaintextDataKey> unwrap(KeyUnwrapRequest request) {
        if (!validUnwrapRequest(request)) {
            return failure(KeyWrappingFailureCategory.INVALID_REQUEST, false);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ciphertext", request.source().encryptedDataKey());
        payload.put("context", derivedContext(request.context()));
        KeyWrappingResult<JsonNode> response = exchange("decrypt", request.source().keyReference().keyId(), payload);
        if (!response.isSuccess()) {
            return KeyWrappingResult.failure(response.failure());
        }
        JsonNode plaintextNode = response.value().path("data").path("plaintext");
        if (!plaintextNode.isTextual() || plaintextNode.textValue().length() > 16_384) {
            return failure(KeyWrappingFailureCategory.INVALID_RESPONSE, false);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(plaintextNode.textValue());
            if (decoded.length == 0 || decoded.length > MAX_PLAINTEXT_BYTES) {
                return failure(KeyWrappingFailureCategory.INVALID_RESPONSE, false);
            }
            String plaintext = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
            return KeyWrappingResult.success(PlaintextDataKey.of(plaintext));
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            return failure(KeyWrappingFailureCategory.INVALID_RESPONSE, false);
        }
    }

    /**
     * 对同一 Vault named key 和相同 v2 context 执行服务端 rewrap。
     */
    @Override
    public KeyWrappingResult<WrappedDataKey> rewrap(KeyRewrapRequest request) {
        if (!validRewrapRequest(request)) {
            return failure(KeyWrappingFailureCategory.UNSUPPORTED, false);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ciphertext", request.source().encryptedDataKey());
        payload.put("context", derivedContext(request.sourceContext()));
        payload.put("key_version", parsePositiveVersion(request.target().providerKeyVersion()));
        return exchangeCiphertext("rewrap", request.target().keyId(), payload,
                request.target(), request.logicalKeyVersion());
    }

    /**
     * 返回不含地址、token、namespace 和 key name 的安全诊断摘要。
     */
    @Override
    public KeyWrappingProviderDiagnostics diagnostics() {
        boolean configured = hasValidConfiguration(vault());
        return new KeyWrappingProviderDiagnostics(
                PROVIDER_ID,
                CONTRACT_VERSION,
                capabilities(),
                configured,
                configured ? "configured" : "invalid_configuration"
        );
    }

    /**
     * 调用返回 ciphertext 的 Transit endpoint 并校验版本前缀。
     */
    private KeyWrappingResult<WrappedDataKey> exchangeCiphertext(String operation,
                                                                 String keyName,
                                                                 Map<String, Object> payload,
                                                                 WrappingKeyReference target,
                                                                 Integer logicalKeyVersion) {
        KeyWrappingResult<JsonNode> response = exchange(operation, keyName, payload);
        if (!response.isSuccess()) {
            return KeyWrappingResult.failure(response.failure());
        }
        JsonNode ciphertextNode = response.value().path("data").path("ciphertext");
        if (!ciphertextNode.isTextual()) {
            return failure(KeyWrappingFailureCategory.INVALID_RESPONSE, false);
        }
        Matcher matcher = VAULT_CIPHERTEXT.matcher(ciphertextNode.textValue());
        if (!matcher.matches()) {
            return failure(KeyWrappingFailureCategory.INVALID_RESPONSE, false);
        }
        WrappingKeyReference resolvedReference = new WrappingKeyReference(
                PROVIDER_ID,
                CONTRACT_VERSION,
                target.keyId(),
                matcher.group(1),
                WRAPPING_ALGORITHM,
                WrappingContext.EXTERNAL_CONTEXT_V2
        );
        return KeyWrappingResult.success(new WrappedDataKey(
                ciphertextNode.textValue(), null, resolvedReference, logicalKeyVersion));
    }

    /**
     * 序列化请求、调用 transport 并执行稳定 HTTP 错误映射。
     */
    private KeyWrappingResult<JsonNode> exchange(String operation,
                                                  String keyName,
                                                  Map<String, Object> payload) {
        try {
            FileKeyEnvelopeProperties.VaultTransit vault = vault();
            if (!hasValidConfiguration(vault)) {
                return failure(KeyWrappingFailureCategory.CONFIGURATION, false);
            }
            byte[] requestBody = objectMapper.writeValueAsBytes(payload);
            if (requestBody.length == 0 || requestBody.length > vault.getMaxRequestBytes()) {
                return failure(KeyWrappingFailureCategory.INVALID_REQUEST, false);
            }
            VaultTransitHttpResult httpResult = transport.post(
                    endpoint(operation, keyName),
                    headers(vault),
                    requestBody,
                    vault.getRequestTimeout(),
                    vault.getMaxResponseBytes());
            if (!httpResult.hasResponse()) {
                return KeyWrappingResult.failure(httpResult.transportFailure());
            }
            KeyWrappingFailure statusFailure = mapStatus(httpResult.statusCode());
            if (statusFailure != null) {
                return KeyWrappingResult.failure(statusFailure);
            }
            JsonNode root = objectMapper.readTree(httpResult.body());
            if (root == null || !root.isObject()) {
                return failure(KeyWrappingFailureCategory.INVALID_RESPONSE, false);
            }
            return KeyWrappingResult.success(root);
        } catch (RuntimeException exception) {
            return failure(KeyWrappingFailureCategory.CONFIGURATION, false);
        } catch (Exception exception) {
            return failure(KeyWrappingFailureCategory.INVALID_RESPONSE, false);
        }
    }

    /**
     * 将 HTTP status 映射为不依赖 Vault errors 文本的稳定分类。
     */
    private KeyWrappingFailure mapStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return null;
        }
        return switch (statusCode) {
            case 400 -> KeyWrappingFailure.of(KeyWrappingFailureCategory.INVALID_REQUEST, false);
            case 403 -> KeyWrappingFailure.of(KeyWrappingFailureCategory.PERMISSION_DENIED, false);
            case 404 -> KeyWrappingFailure.of(KeyWrappingFailureCategory.KEY_NOT_FOUND, false);
            case 429 -> KeyWrappingFailure.of(KeyWrappingFailureCategory.THROTTLED, true);
            default -> statusCode >= 500
                    ? KeyWrappingFailure.of(KeyWrappingFailureCategory.UNAVAILABLE, true)
                    : KeyWrappingFailure.of(KeyWrappingFailureCategory.INVALID_REQUEST, false);
        };
    }

    /**
     * 仅发送 canonical v2 摘要，避免向 Vault 暴露业务标识原值。
     */
    private String derivedContext(WrappingContext context) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(context.canonicalBytes());
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("context digest unavailable");
        }
    }

    /**
     * 构造固定且已校验的 Vault endpoint URI。
     */
    private URI endpoint(String operation, String keyName) {
        FileKeyEnvelopeProperties.VaultTransit vault = vault();
        URI base = URI.create(vault.getAddress());
        String normalized = base.toString().endsWith("/")
                ? base.toString().substring(0, base.toString().length() - 1)
                : base.toString();
        return URI.create(normalized + "/v1/" + vault.getMount() + "/" + operation + "/" + keyName);
    }

    /**
     * 构造只在内存和单次请求中存在的 Vault 认证 header。
     */
    private Map<String, String> headers(FileKeyEnvelopeProperties.VaultTransit vault) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Vault-Token", vault.getToken());
        if (StringUtils.hasText(vault.getNamespace())) {
            headers.put("X-Vault-Namespace", vault.getNamespace());
        }
        return headers;
    }

    /**
     * 校验 Vault 配置的 URI、路径、凭据和有界资源参数。
     */
    private boolean hasValidConfiguration(FileKeyEnvelopeProperties.VaultTransit vault) {
        if (vault == null || !StringUtils.hasText(vault.getAddress()) || !StringUtils.hasText(vault.getToken())
                || !StringUtils.hasText(vault.getMount()) || !StringUtils.hasText(vault.getKeyName())
                || !SAFE_PATH_SEGMENT.matcher(vault.getMount()).matches()
                || !SAFE_PATH_SEGMENT.matcher(vault.getKeyName()).matches()
                || vault.getKeyVersion() == null || vault.getKeyVersion() <= 0
                || vault.getConnectTimeout() == null || vault.getConnectTimeout().isZero()
                || vault.getConnectTimeout().isNegative()
                || vault.getConnectTimeout().compareTo(MAX_CONNECT_TIMEOUT) > 0
                || vault.getRequestTimeout() == null
                || vault.getRequestTimeout().isZero() || vault.getRequestTimeout().isNegative()
                || vault.getRequestTimeout().compareTo(MAX_REQUEST_TIMEOUT) > 0
                || vault.getMaxRequestBytes() < 1_024 || vault.getMaxRequestBytes() > 1_048_576
                || vault.getMaxResponseBytes() < 1_024 || vault.getMaxResponseBytes() > 1_048_576
                || containsHeaderBreak(vault.getToken()) || containsHeaderBreak(vault.getNamespace())) {
            return false;
        }
        if (StringUtils.hasText(vault.getNamespace())
                && !SAFE_NAMESPACE.matcher(vault.getNamespace()).matches()) {
            return false;
        }
        try {
            URI address = URI.create(vault.getAddress());
            return address.isAbsolute()
                    && address.getHost() != null
                    && address.getUserInfo() == null
                    && address.getQuery() == null
                    && address.getFragment() == null
                    && (address.getPath() == null || address.getPath().isEmpty() || "/".equals(address.getPath()))
                    && ("https".equalsIgnoreCase(address.getScheme())
                    || (vault.isAllowHttp() && "http".equalsIgnoreCase(address.getScheme())));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 校验包封请求与当前 Vault target 一致。
     */
    private boolean validWrapRequest(KeyWrapRequest request) {
        return request != null && request.plaintextDataKey() != null && request.context() != null
                && request.target() != null && request.logicalKeyVersion() != null
                && request.logicalKeyVersion() > 0 && validVaultReference(request.target())
                && isValidContext(request.context(), WrappingContext.EXTERNAL_CONTEXT_V2)
                && parsePositiveVersion(request.target().providerKeyVersion()) != null;
    }

    /**
     * 校验解封请求和持久化 Vault metadata。
     */
    private boolean validUnwrapRequest(KeyUnwrapRequest request) {
        return request != null && request.source() != null && request.context() != null
                && request.source().logicalKeyVersion() != null
                && request.source().logicalKeyVersion() > 0
                && validVaultReference(request.source().keyReference())
                && isValidContext(request.context(), WrappingContext.EXTERNAL_CONTEXT_V2)
                && validCiphertextVersion(request.source().encryptedDataKey(),
                request.source().keyReference().providerKeyVersion());
    }

    /**
     * 校验原生 rewrap 仅发生在同 named key 和相同 v2 context。
     */
    private boolean validRewrapRequest(KeyRewrapRequest request) {
        return request != null && request.source() != null && request.sourceContext() != null
                && request.target() != null && request.targetContext() != null
                && request.logicalKeyVersion() != null && request.logicalKeyVersion() > 0
                && validVaultReference(request.source().keyReference())
                && validVaultReference(request.target())
                && request.source().keyReference().keyId().equals(request.target().keyId())
                && isValidContext(request.sourceContext(), WrappingContext.EXTERNAL_CONTEXT_V2)
                && isValidContext(request.targetContext(), WrappingContext.EXTERNAL_CONTEXT_V2)
                && MessageDigest.isEqual(request.sourceContext().canonicalBytes(), request.targetContext().canonicalBytes())
                && validCiphertextVersion(request.source().encryptedDataKey(),
                request.source().keyReference().providerKeyVersion())
                && parsePositiveVersion(request.target().providerKeyVersion()) != null;
    }

    /**
     * 校验 key reference 严格属于 Vault contract v1。
     */
    private boolean validVaultReference(WrappingKeyReference reference) {
        return reference != null && PROVIDER_ID.equals(reference.providerId())
                && CONTRACT_VERSION == reference.providerContractVersion()
                && StringUtils.hasText(reference.keyId())
                && SAFE_PATH_SEGMENT.matcher(reference.keyId()).matches()
                && WRAPPING_ALGORITHM.equals(reference.wrappingAlgorithm())
                && WrappingContext.EXTERNAL_CONTEXT_V2.equals(reference.contextSchema());
    }

    /**
     * 校验认证上下文完整且使用指定 schema，避免业务异常逃逸 provider 结果边界。
     */
    private boolean isValidContext(WrappingContext context, String expectedSchema) {
        if (context == null || !expectedSchema.equals(context.schema())) {
            return false;
        }
        try {
            context.canonicalBytes();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 校验 Vault ciphertext 结构并确保版本字段一致。
     */
    private boolean validCiphertextVersion(String ciphertext, String providerKeyVersion) {
        if (!StringUtils.hasText(ciphertext)) {
            return false;
        }
        Matcher matcher = VAULT_CIPHERTEXT.matcher(ciphertext);
        return matcher.matches() && matcher.group(1).equals(providerKeyVersion);
    }

    /**
     * 解析正整数 provider key version。
     */
    private Integer parsePositiveVersion(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 拒绝可注入 HTTP header 的换行字符。
     */
    private boolean containsHeaderBreak(String value) {
        return value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0);
    }

    /**
     * 返回 Vault provider 配置。
     */
    private FileKeyEnvelopeProperties.VaultTransit vault() {
        return properties.getProviders().getVaultTransit();
    }

    /**
     * 创建稳定失败结果并避免 raw Vault errors 泄漏。
     */
    private <T> KeyWrappingResult<T> failure(KeyWrappingFailureCategory category, boolean retryable) {
        return KeyWrappingResult.failure(KeyWrappingFailure.of(category, retryable));
    }
}
