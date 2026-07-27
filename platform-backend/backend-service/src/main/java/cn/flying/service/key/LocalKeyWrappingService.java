package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.FileKeyEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

/**
 * 使用本地主密钥兼容历史 AES-GCM 信封的 provider。
 */
@Service
@RequiredArgsConstructor
public class LocalKeyWrappingService implements KeyWrappingProvider {

    public static final String PROVIDER_ID = "local";
    public static final int CONTRACT_VERSION = 1;

    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String AES_ALGORITHM = "AES";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final FileKeyEnvelopeProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 返回稳定 local provider id。
     */
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * 返回历史 local 信封 contract version。
     */
    @Override
    public int contractVersion() {
        return CONTRACT_VERSION;
    }

    /**
     * 声明 local provider 支持包封和解封，不声明原生重包封。
     */
    @Override
    public Set<KeyWrappingCapability> capabilities() {
        return Set.of(KeyWrappingCapability.WRAP, KeyWrappingCapability.UNWRAP);
    }

    /**
     * Declares the exact persisted wrapping algorithm supported by local contract v1.
     */
    @Override
    public Set<String> supportedWrappingAlgorithms() {
        return Set.of(CryptoSuiteIds.LOCAL_WRAPPING);
    }

    /**
     * 返回当前 local provider 的完整包封目标。
     */
    @Override
    public KeyWrappingResult<WrappingKeyReference> activeKeyReference(Integer logicalKeyVersion) {
        try {
            Integer resolvedVersion = resolveKeyVersion(logicalKeyVersion);
            if (!isValidLocalKeyId(properties.getKmsKeyId())
                    || !StringUtils.hasText(properties.getWrappingAlgorithm())) {
                return KeyWrappingResult.failure(KeyWrappingFailure.of(
                        KeyWrappingFailureCategory.CONFIGURATION, false));
            }
            return KeyWrappingResult.success(new WrappingKeyReference(
                    PROVIDER_ID,
                    CONTRACT_VERSION,
                    properties.getKmsKeyId(),
                    String.valueOf(resolvedVersion),
                    properties.getWrappingAlgorithm(),
                    WrappingContext.LOCAL_AAD_V1
            ));
        } catch (GeneralException exception) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.CONFIGURATION, false));
        }
    }

    /**
     * 按 local AES-GCM v1 合同包封数据密钥。
     */
    @Override
    public KeyWrappingResult<WrappedDataKey> wrap(KeyWrapRequest request) {
        if (request == null || request.plaintextDataKey() == null || request.context() == null
                || request.target() == null
                || !isValidContext(request.context(), WrappingContext.LOCAL_AAD_V1)
                || !isLocalReference(request.target(), request.logicalKeyVersion(), true)) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_REQUEST, false));
        }
        try {
            return KeyWrappingResult.success(wrapBytes(
                    request.plaintextDataKey().reveal(),
                    request.context().canonicalBytes(),
                    request.logicalKeyVersion(),
                    request.target()
            ));
        } catch (GeneralException exception) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.CONFIGURATION, false));
        } catch (Exception exception) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INTERNAL, false));
        }
    }

    /**
     * 按 local AES-GCM v1 合同解封历史信封。
     */
    @Override
    public KeyWrappingResult<PlaintextDataKey> unwrap(KeyUnwrapRequest request) {
        if (request == null || request.source() == null || request.context() == null
                || !Objects.equals(request.source().logicalKeyVersion(), request.context().logicalKeyVersion())
                || !isValidContext(request.context(), WrappingContext.LOCAL_AAD_V1)
                || !isLocalReference(request.source().keyReference(),
                request.source().logicalKeyVersion(), false)) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_REQUEST, false));
        }
        PersistedWrappedDataKey source = request.source();
        if (!StringUtils.hasText(source.encryptedDataKey()) || !StringUtils.hasText(source.wrappingIv())) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_REQUEST, false));
        }
        try {
            return KeyWrappingResult.success(PlaintextDataKey.of(unwrapBytes(
                    source.encryptedDataKey(),
                    source.wrappingIv(),
                    source.logicalKeyVersion(),
                    request.context().canonicalBytes()
            )));
        } catch (GeneralException exception) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.CONFIGURATION, false));
        } catch (Exception exception) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_CIPHERTEXT, false));
        }
    }

    /**
     * local provider 不支持不暴露明文的原生重包封。
     */
    @Override
    public KeyWrappingResult<WrappedDataKey> rewrap(KeyRewrapRequest request) {
        return KeyWrappingResult.failure(KeyWrappingFailure.of(
                KeyWrappingFailureCategory.UNSUPPORTED, false));
    }

    /**
     * 返回不含本地主密钥和 key id 的安全诊断摘要。
     */
    @Override
    public KeyWrappingProviderDiagnostics diagnostics() {
        boolean hasMasterKey = StringUtils.hasText(properties.getLocalMasterKey())
                || !properties.getLocalMasterKeys().isEmpty();
        boolean configured = hasMasterKey
                && isValidLocalKeyId(properties.getKmsKeyId())
                && StringUtils.hasText(properties.getWrappingAlgorithm());
        return new KeyWrappingProviderDiagnostics(
                PROVIDER_ID,
                CONTRACT_VERSION,
                capabilities(),
                configured,
                configured ? "configured" : "incomplete_local_configuration"
        );
    }

    /**
     * Encrypts a serialized file data-key token and binds it to the supplied AAD.
     */
    public WrappedDataKey wrap(String plaintextKey, byte[] aad) {
        return wrap(plaintextKey, aad, properties.getKeyVersion());
    }

    /**
     * Encrypts a serialized file data-key token with an explicit wrapping key version.
     */
    public WrappedDataKey wrap(String plaintextKey, byte[] aad, Integer keyVersion) {
        if (!StringUtils.hasText(plaintextKey)) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件数据密钥不能为空");
        }
        try {
            Integer resolvedKeyVersion = resolveKeyVersion(keyVersion);
            WrappingKeyReference target = activeKeyReference(resolvedKeyVersion).requireValue();
            return wrapBytes(plaintextKey, aad, resolvedKeyVersion, target);
        } catch (GeneralException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件数据密钥封装失败");
        }
    }

    /**
     * Decrypts a serialized file data-key token from a persisted envelope.
     */
    public String unwrap(FileKeyEnvelope envelope, byte[] aad) {
        if (envelope == null || !StringUtils.hasText(envelope.getEncryptedDataKey())
                || !StringUtils.hasText(envelope.getWrappingIv())) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件数据密钥信封不完整");
        }
        try {
            return unwrapBytes(envelope.getEncryptedDataKey(), envelope.getWrappingIv(), envelope.getKeyVersion(), aad);
        } catch (GeneralException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件数据密钥解封失败");
        }
    }

    /**
     * 执行保持历史字节格式不变的 AES-GCM 包封。
     */
    private WrappedDataKey wrapBytes(String plaintextKey,
                                     byte[] aad,
                                     Integer keyVersion,
                                     WrappingKeyReference target) throws Exception {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        Integer resolvedKeyVersion = resolveKeyVersion(keyVersion);
        cipher.init(Cipher.ENCRYPT_MODE, resolveMasterKey(resolvedKeyVersion),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        byte[] encrypted = cipher.doFinal(plaintextKey.getBytes(StandardCharsets.UTF_8));
        return new WrappedDataKey(
                Base64.getEncoder().encodeToString(encrypted),
                Base64.getEncoder().encodeToString(iv),
                target,
                resolvedKeyVersion
        );
    }

    /**
     * 执行保持历史 key 解析和 AAD 行为不变的 AES-GCM 解封。
     */
    private String unwrapBytes(String encryptedDataKey,
                               String wrappingIv,
                               Integer keyVersion,
                               byte[] aad) throws Exception {
        byte[] iv = Base64.getDecoder().decode(wrappingIv);
        byte[] encrypted = Base64.getDecoder().decode(encryptedDataKey);
        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, resolveMasterKey(keyVersion), new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    /**
     * 校验请求是否严格指向 local contract v1。
     */
    private boolean isLocalReference(WrappingKeyReference reference,
                                     Integer logicalKeyVersion,
                                     boolean activeOnly) {
        if (reference == null || logicalKeyVersion == null || logicalKeyVersion <= 0) {
            return false;
        }
        Set<String> historicalKeyIds = properties.getProviders().getLocal().getHistoricalKeyIds();
        boolean allowedKeyId = isValidLocalKeyId(reference.keyId())
                && (Objects.equals(properties.getKmsKeyId(), reference.keyId())
                || (!activeOnly && historicalKeyIds != null && historicalKeyIds.contains(reference.keyId())));
        return allowedKeyId
                && PROVIDER_ID.equals(reference.providerId())
                && CONTRACT_VERSION == reference.providerContractVersion()
                && String.valueOf(logicalKeyVersion).equals(reference.providerKeyVersion())
                && Objects.equals(properties.getWrappingAlgorithm(), reference.wrappingAlgorithm())
                && WrappingContext.LOCAL_AAD_V1.equals(reference.contextSchema());
    }

    /**
     * 校验认证上下文完整且使用指定 schema，避免标准异常越过 provider 结果边界。
     */
    private boolean isValidContext(WrappingContext context, String expectedSchema) {
        if (context == null || !expectedSchema.equals(context.schema())) {
            return false;
        }
        try {
            context.canonicalBytes();
            return true;
        } catch (GeneralException exception) {
            return false;
        }
    }

    /**
     * 校验 local key id 可安全持久化且不含控制字符。
     */
    private boolean isValidLocalKeyId(String keyId) {
        if (!StringUtils.hasText(keyId) || keyId.length() > 512) {
            return false;
        }
        return keyId.chars().noneMatch(Character::isISOControl);
    }

    /**
     * Derives a stable AES-256 key from the deployment local master key version.
     */
    private SecretKeySpec resolveMasterKey(Integer keyVersion) {
        Integer resolvedKeyVersion = resolveKeyVersion(keyVersion);
        String masterKey = properties.getLocalMasterKeys().get(resolvedKeyVersion);
        if (!StringUtils.hasText(masterKey) && resolvedKeyVersion.equals(properties.getKeyVersion())) {
            masterKey = properties.getLocalMasterKey();
        }
        if (!StringUtils.hasText(masterKey) && properties.getLocalMasterKeys().isEmpty()) {
            masterKey = properties.getLocalMasterKey();
        }
        if (!StringUtils.hasText(masterKey)) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件密钥信封主密钥版本未配置");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] key = digest.digest(masterKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, AES_ALGORITHM);
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件密钥信封主密钥派生失败");
        }
    }

    /**
     * Resolves the configured current key version when callers omit a target version.
     */
    private Integer resolveKeyVersion(Integer keyVersion) {
        Integer resolved = keyVersion != null ? keyVersion : properties.getKeyVersion();
        if (resolved == null || resolved <= 0) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件密钥信封版本无效");
        }
        return resolved;
    }
}
