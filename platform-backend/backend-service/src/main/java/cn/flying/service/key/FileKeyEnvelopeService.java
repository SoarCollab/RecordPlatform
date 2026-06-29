package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Coordinates file data-key envelope metadata, storage, and legacy fallback.
 */
@Service
@RequiredArgsConstructor
public class FileKeyEnvelopeService {

    public static final String RECIPIENT_TYPE_OWNER = "OWNER";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private static final TypeReference<Map<String, Object>> FILE_PARAM_TYPE = new TypeReference<>() {
    };
    private static final String FIELD_INITIAL_KEY = "initialKey";
    private static final String FIELD_KEY_ENVELOPE_STATUS = "keyEnvelopeStatus";
    private static final String FIELD_ALGORITHM_SUITE = "algorithmSuite";
    private static final String FIELD_KEY_VERSION = "keyVersion";
    private static final String FIELD_ENCRYPTION_ALGORITHM = "encryptionAlgorithm";
    private static final String FIELD_UPLOAD_MODE = "uploadMode";
    private static final String ENVELOPE_STATUS_ENVELOPED = "ENVELOPED";
    private static final String ENCRYPTION_NONE = "NONE";

    private final FileKeyEnvelopeMapper fileKeyEnvelopeMapper;
    private final LocalKeyWrappingService wrappingService;
    private final FileKeyEnvelopeProperties properties;

    /**
     * Removes plaintext key material from file_param and returns envelope input metadata.
     */
    public FileParamEnvelopeResult prepareFileParam(String fileParam) {
        if (CommonUtils.isEmpty(fileParam)) {
            return FileParamEnvelopeResult.withoutEnvelope(fileParam);
        }

        Map<String, Object> params = JsonConverter.parse(fileParam, FILE_PARAM_TYPE);
        if (params == null) {
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "文件元数据 JSON 解析失败");
        }

        Map<String, Object> sanitized = new LinkedHashMap<>(params);
        Object rawInitialKey = sanitized.remove(FIELD_INITIAL_KEY);
        if (rawInitialKey == null) {
            return FileParamEnvelopeResult.withoutEnvelope(JsonConverter.toJson(sanitized));
        }
        if (!(rawInitialKey instanceof String initialKey)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件数据密钥格式无效");
        }
        if (!StringUtils.hasText(initialKey)) {
            return FileParamEnvelopeResult.withoutEnvelope(JsonConverter.toJson(sanitized));
        }

        String encryptionAlgorithm = resolveEncryptionAlgorithm(sanitized);
        if (ENCRYPTION_NONE.equalsIgnoreCase(encryptionAlgorithm)) {
            return FileParamEnvelopeResult.withoutEnvelope(JsonConverter.toJson(sanitized));
        }

        String algorithmSuite = properties.getAlgorithmSuite();
        Integer keyVersion = properties.getKeyVersion();
        sanitized.put(FIELD_KEY_ENVELOPE_STATUS, ENVELOPE_STATUS_ENVELOPED);
        sanitized.put(FIELD_ALGORITHM_SUITE, algorithmSuite);
        sanitized.put(FIELD_KEY_VERSION, keyVersion);
        sanitized.putIfAbsent(FIELD_ENCRYPTION_ALGORITHM, encryptionAlgorithm);

        String sanitizedJson = JsonConverter.toJson(sanitized);
        if (sanitizedJson == null) {
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "文件元数据 JSON 序列化失败");
        }
        return new FileParamEnvelopeResult(
                sanitizedJson,
                initialKey,
                algorithmSuite,
                encryptionAlgorithm,
                keyVersion
        );
    }

    /**
     * Persists an owner envelope for a successfully stored encrypted file.
     */
    public void saveOwnerEnvelope(File file, String fileHash, Long ownerId, FileParamEnvelopeResult envelopeResult) {
        if (file == null || envelopeResult == null || !envelopeResult.requiresEnvelope()) {
            return;
        }
        Long tenantId = resolveTenantId(file);
        Long fileId = file.getId();
        String resolvedFileHash = StringUtils.hasText(fileHash) ? fileHash : file.getFileHash();
        if (tenantId == null || fileId == null || ownerId == null || !StringUtils.hasText(resolvedFileHash)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件密钥信封上下文不完整");
        }

        markActiveOwnerEnvelopesSuperseded(tenantId, fileId, resolvedFileHash, ownerId);
        byte[] aad = buildEnvelopeAad(
                tenantId,
                fileId,
                resolvedFileHash,
                RECIPIENT_TYPE_OWNER,
                ownerId,
                envelopeResult.keyVersion(),
                envelopeResult.algorithmSuite()
        );
        WrappedDataKey wrapped = wrappingService.wrap(envelopeResult.initialKey(), aad);

        FileKeyEnvelope envelope = new FileKeyEnvelope()
                .setTenantId(tenantId)
                .setFileId(fileId)
                .setFileHash(resolvedFileHash)
                .setRecipientType(RECIPIENT_TYPE_OWNER)
                .setRecipientId(ownerId)
                .setKeyVersion(wrapped.keyVersion())
                .setAlgorithmSuite(envelopeResult.algorithmSuite())
                .setEncryptionAlgorithm(envelopeResult.encryptionAlgorithm())
                .setWrappingAlgorithm(wrapped.wrappingAlgorithm())
                .setKmsProvider(wrapped.kmsProvider())
                .setKmsKeyId(wrapped.kmsKeyId())
                .setEncryptedDataKey(wrapped.encryptedDataKey())
                .setWrappingIv(wrapped.wrappingIv())
                .setAadHash(hashAad(aad))
                .setStatus(STATUS_ACTIVE)
                .setDeleted(0);
        fileKeyEnvelopeMapper.insert(envelope);
    }

    /**
     * Resolves an active owner envelope and unwraps its serialized initial key.
     */
    public Optional<String> unwrapActiveOwnerInitialKey(File file, String fileHash, Long ownerId) {
        if (file == null || file.getId() == null || ownerId == null) {
            return Optional.empty();
        }
        Long tenantId = resolveTenantId(file);
        String resolvedFileHash = StringUtils.hasText(fileHash) ? fileHash : file.getFileHash();
        if (tenantId == null || !StringUtils.hasText(resolvedFileHash)) {
            return Optional.empty();
        }

        FileKeyEnvelope envelope = fileKeyEnvelopeMapper.selectOne(new LambdaQueryWrapper<FileKeyEnvelope>()
                .eq(FileKeyEnvelope::getTenantId, tenantId)
                .eq(FileKeyEnvelope::getFileId, file.getId())
                .eq(FileKeyEnvelope::getFileHash, resolvedFileHash)
                .eq(FileKeyEnvelope::getRecipientType, RECIPIENT_TYPE_OWNER)
                .eq(FileKeyEnvelope::getRecipientId, ownerId)
                .eq(FileKeyEnvelope::getStatus, STATUS_ACTIVE)
                .orderByDesc(FileKeyEnvelope::getKeyVersion)
                .orderByDesc(FileKeyEnvelope::getCreateTime)
                .last("LIMIT 1"));
        if (envelope == null) {
            return Optional.empty();
        }

        byte[] aad = buildEnvelopeAad(
                envelope.getTenantId(),
                envelope.getFileId(),
                envelope.getFileHash(),
                envelope.getRecipientType(),
                envelope.getRecipientId(),
                envelope.getKeyVersion(),
                envelope.getAlgorithmSuite()
        );
        return Optional.of(wrappingService.unwrap(envelope, aad));
    }

    /**
     * Returns the legacy plaintext initial key from file_param when no envelope exists.
     */
    public Optional<String> legacyInitialKey(Map<String, Object> fileParam) {
        if (fileParam == null) {
            return Optional.empty();
        }
        Object value = fileParam.get(FIELD_INITIAL_KEY);
        if (value instanceof String key && StringUtils.hasText(key)) {
            return Optional.of(key);
        }
        return Optional.empty();
    }

    /**
     * Builds stable AAD for envelope wrapping and unwrapping.
     */
    private byte[] buildEnvelopeAad(Long tenantId,
                                    Long fileId,
                                    String fileHash,
                                    String recipientType,
                                    Long recipientId,
                                    Integer keyVersion,
                                    String algorithmSuite) {
        String aad = tenantId + "|" + fileId + "|" + fileHash + "|" + recipientType
                + "|" + recipientId + "|" + keyVersion + "|" + algorithmSuite;
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Marks old owner envelopes inactive before writing the current active envelope.
     */
    private void markActiveOwnerEnvelopesSuperseded(Long tenantId, Long fileId, String fileHash, Long ownerId) {
        FileKeyEnvelope update = new FileKeyEnvelope().setStatus(STATUS_SUPERSEDED);
        fileKeyEnvelopeMapper.update(update, new LambdaUpdateWrapper<FileKeyEnvelope>()
                .eq(FileKeyEnvelope::getTenantId, tenantId)
                .eq(FileKeyEnvelope::getFileId, fileId)
                .eq(FileKeyEnvelope::getFileHash, fileHash)
                .eq(FileKeyEnvelope::getRecipientType, RECIPIENT_TYPE_OWNER)
                .eq(FileKeyEnvelope::getRecipientId, ownerId)
                .eq(FileKeyEnvelope::getStatus, STATUS_ACTIVE));
    }

    /**
     * Uses the configured encryption algorithm unless metadata already selected one.
     */
    private String resolveEncryptionAlgorithm(Map<String, Object> params) {
        Object value = params.get(FIELD_ENCRYPTION_ALGORITHM);
        if (value instanceof String algorithm && StringUtils.hasText(algorithm)) {
            return algorithm;
        }
        Object uploadMode = params.get(FIELD_UPLOAD_MODE);
        if (uploadMode instanceof String mode && "DIRECT_MULTIPART".equalsIgnoreCase(mode)) {
            return ENCRYPTION_NONE;
        }
        return properties.getEncryptionAlgorithm();
    }

    /**
     * Resolves tenant from the file row and falls back to current tenant context.
     */
    private Long resolveTenantId(File file) {
        return file.getTenantId() != null ? file.getTenantId() : TenantContext.getTenantId();
    }

    /**
     * Hashes envelope AAD for audit/debug comparison without storing raw AAD.
     */
    private String hashAad(byte[] aad) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(aad);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件密钥信封 AAD 哈希失败");
        }
    }
}
