package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.entity.FileKeyAuditLog;
import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.mapper.FileKeyAuditLogMapper;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
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
    public static final String RECIPIENT_TYPE_SHARE = "SHARE";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_REVOKED = "REVOKED";

    private static final String OPERATION_UNWRAP = "UNWRAP";
    private static final String OPERATION_ROTATE = "ROTATE";
    private static final String OPERATION_REVOKE = "REVOKE";
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAILURE = "FAILURE";
    private static final String RESULT_SKIPPED = "SKIPPED";
    private static final String RESULT_MISSING = "MISSING";
    private static final int MAX_AUDIT_ERROR_LENGTH = 512;

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
    private final FileKeyAuditLogMapper fileKeyAuditLogMapper;
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
        WrappedDataKey wrapped = wrappingService.wrap(envelopeResult.initialKey(), aad, envelopeResult.keyVersion());

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
        return unwrapActiveOwnerInitialKey(file, fileHash, ownerId, ownerId, "OWNER_DECRYPT");
    }

    /**
     * Resolves an active owner envelope and audits the unwrap attempt.
     */
    public Optional<String> unwrapActiveOwnerInitialKey(File file,
                                                        String fileHash,
                                                        Long ownerId,
                                                        Long actorId,
                                                        String reason) {
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
            audit(tenantId, file.getId(), resolvedFileHash, RECIPIENT_TYPE_OWNER, ownerId, null,
                    OPERATION_UNWRAP, actorId, RESULT_MISSING, reason, null);
            return Optional.empty();
        }

        return unwrapEnvelope(envelope, actorId, reason);
    }

    /**
     * Saves share-code recipient envelopes for every file included in a share.
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveShareEnvelopes(FileShare share, List<File> files, Long actorId, String reason) {
        if (share == null || share.getId() == null || files == null || files.isEmpty()) {
            return;
        }
        Long tenantId = resolveTenantId(share);
        if (tenantId == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "分享密钥信封租户上下文不完整");
        }

        for (File file : files) {
            if (file == null || file.getId() == null || !StringUtils.hasText(file.getFileHash())) {
                continue;
            }
            String initialKey = resolveOwnerOrLegacyInitialKey(file, file.getFileHash(), share.getUserId(), actorId,
                    "SHARE_ENVELOPE_CREATE");
            saveRecipientEnvelope(
                    tenantId,
                    file,
                    file.getFileHash(),
                    RECIPIENT_TYPE_SHARE,
                    share.getId(),
                    initialKey
            );
        }
    }

    /**
     * Resolves an active share-code recipient envelope and audits the unwrap attempt.
     */
    public Optional<String> unwrapActiveShareInitialKey(File file,
                                                        String fileHash,
                                                        FileShare share,
                                                        Long actorId,
                                                        String reason) {
        if (file == null || file.getId() == null || share == null || share.getId() == null) {
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
                .eq(FileKeyEnvelope::getRecipientType, RECIPIENT_TYPE_SHARE)
                .eq(FileKeyEnvelope::getRecipientId, share.getId())
                .eq(FileKeyEnvelope::getStatus, STATUS_ACTIVE)
                .orderByDesc(FileKeyEnvelope::getKeyVersion)
                .orderByDesc(FileKeyEnvelope::getCreateTime)
                .last("LIMIT 1"));
        if (envelope == null) {
            audit(tenantId, file.getId(), resolvedFileHash, RECIPIENT_TYPE_SHARE, share.getId(), null,
                    OPERATION_UNWRAP, actorId, RESULT_MISSING, reason, null);
            return Optional.empty();
        }

        return unwrapEnvelope(envelope, actorId, reason);
    }

    /**
     * Revokes all active share-code recipient envelopes for a share.
     */
    @Transactional(rollbackFor = Exception.class)
    public void revokeShareEnvelopes(FileShare share, Long actorId, String reason) {
        if (share == null || share.getId() == null) {
            return;
        }
        Long tenantId = resolveTenantId(share);
        List<FileKeyEnvelope> envelopes = fileKeyEnvelopeMapper.selectList(new LambdaQueryWrapper<FileKeyEnvelope>()
                .eq(tenantId != null, FileKeyEnvelope::getTenantId, tenantId)
                .eq(FileKeyEnvelope::getRecipientType, RECIPIENT_TYPE_SHARE)
                .eq(FileKeyEnvelope::getRecipientId, share.getId())
                .eq(FileKeyEnvelope::getStatus, STATUS_ACTIVE));

        if (envelopes == null || envelopes.isEmpty()) {
            audit(tenantId, null, null, RECIPIENT_TYPE_SHARE, share.getId(), null,
                    OPERATION_REVOKE, actorId, RESULT_MISSING, reason, null);
            return;
        }

        for (FileKeyEnvelope envelope : envelopes) {
            FileKeyEnvelope update = new FileKeyEnvelope()
                    .setId(envelope.getId())
                    .setStatus(STATUS_REVOKED);
            fileKeyEnvelopeMapper.updateById(update);
            audit(envelope, OPERATION_REVOKE, actorId, RESULT_SUCCESS, reason, null);
        }
    }

    /**
     * Rotates active envelopes for a file to the configured current key version.
     */
    @Transactional(rollbackFor = Exception.class)
    public KeyEnvelopeRotationResult rotateActiveFileEnvelopes(File file, Long actorId, String reason) {
        if (file == null || file.getId() == null) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }
        Long tenantId = resolveTenantId(file);
        if (tenantId == null || !StringUtils.hasText(file.getFileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件密钥信封上下文不完整");
        }

        Integer targetKeyVersion = properties.getKeyVersion();
        List<FileKeyEnvelope> envelopes = fileKeyEnvelopeMapper.selectList(new LambdaQueryWrapper<FileKeyEnvelope>()
                .eq(FileKeyEnvelope::getTenantId, tenantId)
                .eq(FileKeyEnvelope::getFileId, file.getId())
                .eq(FileKeyEnvelope::getFileHash, file.getFileHash())
                .eq(FileKeyEnvelope::getStatus, STATUS_ACTIVE)
                .orderByAsc(FileKeyEnvelope::getRecipientType)
                .orderByAsc(FileKeyEnvelope::getRecipientId)
                .orderByDesc(FileKeyEnvelope::getKeyVersion));

        int rotated = 0;
        int skipped = 0;
        if (envelopes == null || envelopes.isEmpty()) {
            audit(tenantId, file.getId(), file.getFileHash(), null, null, targetKeyVersion,
                    OPERATION_ROTATE, actorId, RESULT_MISSING, reason, null);
            return new KeyEnvelopeRotationResult(file.getFileHash(), targetKeyVersion, rotated, skipped);
        }
        for (FileKeyEnvelope envelope : envelopes) {
            if (targetKeyVersion.equals(envelope.getKeyVersion())) {
                skipped++;
                audit(envelope, OPERATION_ROTATE, actorId, RESULT_SKIPPED, reason, "already target key version");
                continue;
            }
            if (hasActiveEnvelopeVersion(envelope, targetKeyVersion)) {
                markEnvelopeSuperseded(envelope);
                skipped++;
                audit(envelope, OPERATION_ROTATE, actorId, RESULT_SKIPPED, reason, "target key version already active");
                continue;
            }

            try {
                String plaintextKey = unwrapEnvelopeForRotation(envelope);
                byte[] targetAad = buildEnvelopeAad(
                        envelope.getTenantId(),
                        envelope.getFileId(),
                        envelope.getFileHash(),
                        envelope.getRecipientType(),
                        envelope.getRecipientId(),
                        targetKeyVersion,
                        envelope.getAlgorithmSuite()
                );
                WrappedDataKey wrapped = wrappingService.wrap(plaintextKey, targetAad, targetKeyVersion);
                markEnvelopeSuperseded(envelope);
                FileKeyEnvelope rotatedEnvelope = copyForRotation(envelope, wrapped, targetAad);
                fileKeyEnvelopeMapper.insert(rotatedEnvelope);
                rotated++;
                audit(rotatedEnvelope, OPERATION_ROTATE, actorId, RESULT_SUCCESS, reason, null);
            } catch (GeneralException e) {
                audit(envelope, OPERATION_ROTATE, actorId, RESULT_FAILURE, reason, e.getMessage());
                throw e;
            }
        }

        return new KeyEnvelopeRotationResult(file.getFileHash(), targetKeyVersion, rotated, skipped);
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
        markActiveRecipientEnvelopesSuperseded(tenantId, fileId, fileHash, RECIPIENT_TYPE_OWNER, ownerId);
    }

    /**
     * Marks active envelopes inactive before writing a replacement for a recipient.
     */
    private void markActiveRecipientEnvelopesSuperseded(Long tenantId,
                                                        Long fileId,
                                                        String fileHash,
                                                        String recipientType,
                                                        Long recipientId) {
        FileKeyEnvelope update = new FileKeyEnvelope().setStatus(STATUS_SUPERSEDED);
        fileKeyEnvelopeMapper.update(update, new LambdaUpdateWrapper<FileKeyEnvelope>()
                .eq(FileKeyEnvelope::getTenantId, tenantId)
                .eq(FileKeyEnvelope::getFileId, fileId)
                .eq(FileKeyEnvelope::getFileHash, fileHash)
                .eq(FileKeyEnvelope::getRecipientType, recipientType)
                .eq(FileKeyEnvelope::getRecipientId, recipientId)
                .eq(FileKeyEnvelope::getStatus, STATUS_ACTIVE));
    }

    /**
     * Persists a non-owner recipient envelope for the supplied plaintext data key.
     */
    private void saveRecipientEnvelope(Long tenantId,
                                       File file,
                                       String fileHash,
                                       String recipientType,
                                       Long recipientId,
                                       String initialKey) {
        Integer keyVersion = properties.getKeyVersion();
        String algorithmSuite = properties.getAlgorithmSuite();
        markActiveRecipientEnvelopesSuperseded(tenantId, file.getId(), fileHash, recipientType, recipientId);
        byte[] aad = buildEnvelopeAad(tenantId, file.getId(), fileHash, recipientType, recipientId, keyVersion, algorithmSuite);
        WrappedDataKey wrapped = wrappingService.wrap(initialKey, aad, keyVersion);

        FileKeyEnvelope envelope = new FileKeyEnvelope()
                .setTenantId(tenantId)
                .setFileId(file.getId())
                .setFileHash(fileHash)
                .setRecipientType(recipientType)
                .setRecipientId(recipientId)
                .setKeyVersion(wrapped.keyVersion())
                .setAlgorithmSuite(algorithmSuite)
                .setEncryptionAlgorithm(properties.getEncryptionAlgorithm())
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
     * Resolves a data key from owner envelope first and legacy file_param second.
     */
    private String resolveOwnerOrLegacyInitialKey(File file,
                                                  String fileHash,
                                                  Long ownerId,
                                                  Long actorId,
                                                  String reason) {
        Optional<String> ownerEnvelope = unwrapActiveOwnerInitialKey(file, fileHash, ownerId, actorId, reason);
        if (ownerEnvelope.isPresent()) {
            return ownerEnvelope.get();
        }
        Optional<String> legacyKey = parseLegacyInitialKey(file);
        if (legacyKey.isPresent()) {
            return legacyKey.get();
        }
        throw new GeneralException(ResultEnum.FAIL, "文件解密密钥不存在");
    }

    /**
     * Extracts legacy key metadata from a file row.
     */
    private Optional<String> parseLegacyInitialKey(File file) {
        if (file == null || !StringUtils.hasText(file.getFileParam())) {
            return Optional.empty();
        }
        Map<String, Object> params = JsonConverter.parse(file.getFileParam(), FILE_PARAM_TYPE);
        return legacyInitialKey(params);
    }

    /**
     * Unwraps an envelope and records key access audit evidence.
     */
    private Optional<String> unwrapEnvelope(FileKeyEnvelope envelope, Long actorId, String reason) {
        try {
            String plaintextKey = unwrapEnvelopeForRotation(envelope);
            audit(envelope, OPERATION_UNWRAP, actorId, RESULT_SUCCESS, reason, null);
            return Optional.of(plaintextKey);
        } catch (GeneralException e) {
            audit(envelope, OPERATION_UNWRAP, actorId, RESULT_FAILURE, reason, e.getMessage());
            throw e;
        }
    }

    /**
     * Unwraps an envelope without writing an unwrap audit event for internal rotation use.
     */
    private String unwrapEnvelopeForRotation(FileKeyEnvelope envelope) {
        byte[] aad = buildEnvelopeAad(
                envelope.getTenantId(),
                envelope.getFileId(),
                envelope.getFileHash(),
                envelope.getRecipientType(),
                envelope.getRecipientId(),
                envelope.getKeyVersion(),
                envelope.getAlgorithmSuite()
        );
        return wrappingService.unwrap(envelope, aad);
    }

    /**
     * Checks whether a target key version is already active for the same envelope recipient.
     */
    private boolean hasActiveEnvelopeVersion(FileKeyEnvelope envelope, Integer keyVersion) {
        return fileKeyEnvelopeMapper.selectCount(new LambdaQueryWrapper<FileKeyEnvelope>()
                .eq(FileKeyEnvelope::getTenantId, envelope.getTenantId())
                .eq(FileKeyEnvelope::getFileId, envelope.getFileId())
                .eq(FileKeyEnvelope::getFileHash, envelope.getFileHash())
                .eq(FileKeyEnvelope::getRecipientType, envelope.getRecipientType())
                .eq(FileKeyEnvelope::getRecipientId, envelope.getRecipientId())
                .eq(FileKeyEnvelope::getKeyVersion, keyVersion)
                .eq(FileKeyEnvelope::getStatus, STATUS_ACTIVE)) > 0;
    }

    /**
     * Marks one envelope superseded by primary key.
     */
    private void markEnvelopeSuperseded(FileKeyEnvelope envelope) {
        FileKeyEnvelope update = new FileKeyEnvelope()
                .setId(envelope.getId())
                .setStatus(STATUS_SUPERSEDED);
        fileKeyEnvelopeMapper.updateById(update);
    }

    /**
     * Creates a replacement envelope for rotation while preserving recipient metadata.
     */
    private FileKeyEnvelope copyForRotation(FileKeyEnvelope source, WrappedDataKey wrapped, byte[] aad) {
        return new FileKeyEnvelope()
                .setTenantId(source.getTenantId())
                .setFileId(source.getFileId())
                .setFileHash(source.getFileHash())
                .setRecipientType(source.getRecipientType())
                .setRecipientId(source.getRecipientId())
                .setKeyVersion(wrapped.keyVersion())
                .setAlgorithmSuite(source.getAlgorithmSuite())
                .setEncryptionAlgorithm(source.getEncryptionAlgorithm())
                .setWrappingAlgorithm(wrapped.wrappingAlgorithm())
                .setKmsProvider(wrapped.kmsProvider())
                .setKmsKeyId(wrapped.kmsKeyId())
                .setEncryptedDataKey(wrapped.encryptedDataKey())
                .setWrappingIv(wrapped.wrappingIv())
                .setAadHash(hashAad(aad))
                .setStatus(STATUS_ACTIVE)
                .setDeleted(0);
    }

    /**
     * Writes a key operation audit record from an existing envelope.
     */
    private void audit(FileKeyEnvelope envelope,
                       String operation,
                       Long actorId,
                       String result,
                       String reason,
                       String errorMessage) {
        audit(envelope.getTenantId(), envelope.getFileId(), envelope.getFileHash(), envelope.getRecipientType(),
                envelope.getRecipientId(), envelope.getKeyVersion(), operation, actorId, result, reason, errorMessage);
    }

    /**
     * Writes a key operation audit record.
     */
    private void audit(Long tenantId,
                       Long fileId,
                       String fileHash,
                       String recipientType,
                       Long recipientId,
                       Integer keyVersion,
                       String operation,
                       Long actorId,
                       String result,
                       String reason,
                       String errorMessage) {
        FileKeyAuditLog auditLog = new FileKeyAuditLog()
                .setTenantId(resolveAuditTenantId(tenantId))
                .setFileId(fileId)
                .setFileHash(fileHash)
                .setRecipientType(recipientType)
                .setRecipientId(recipientId)
                .setKeyVersion(keyVersion)
                .setOperation(operation)
                .setActorId(actorId)
                .setResult(result)
                .setReason(reason)
                .setErrorMessage(truncate(errorMessage))
                .setDeleted(0);
        fileKeyAuditLogMapper.insert(auditLog);
    }

    /**
     * Keeps audit failure messages bounded and free of large exception payloads.
     */
    private String truncate(String value) {
        if (value == null || value.length() <= MAX_AUDIT_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_AUDIT_ERROR_LENGTH);
    }

    /**
     * Resolves a non-null tenant id for audit rows.
     */
    private Long resolveAuditTenantId(Long tenantId) {
        if (tenantId != null) {
            return tenantId;
        }
        Long currentTenantId = TenantContext.getTenantId();
        return currentTenantId != null ? currentTenantId : 0L;
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
     * Resolves tenant from the share row and falls back to current tenant context.
     */
    private Long resolveTenantId(FileShare share) {
        return share.getTenantId() != null ? share.getTenantId() : TenantContext.getTenantId();
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
