package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.entity.FileKeyAuditLog;
import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.mapper.FileKeyAuditLogMapper;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates file data-key envelope metadata, storage, and unwrap auditing.
 */
@Service
@RequiredArgsConstructor
public class FileKeyEnvelopeService {

    public static final String RECIPIENT_TYPE_OWNER = "OWNER";
    public static final String RECIPIENT_TYPE_SHARE = "SHARE";
    public static final String RECIPIENT_TYPE_FRIEND_SHARE = "FRIEND_SHARE";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PENDING_VERIFICATION = "PENDING_VERIFICATION";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_REVOKED = "REVOKED";

    private static final String OPERATION_UNWRAP = "UNWRAP";
    private static final String OPERATION_ROTATE = "ROTATE";
    private static final String OPERATION_REVOKE = "REVOKE";
    private static final String OPERATION_GRANT_ISSUE = "GRANT_ISSUE";
    private static final String OPERATION_GRANT_CONSUME = "GRANT_CONSUME";
    private static final String OPERATION_GRANT_DENY = "GRANT_DENY";
    private static final String OPERATION_PLAINTEXT_V0 = "PLAINTEXT_V0";
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAILURE = "FAILURE";
    private static final String RESULT_SKIPPED = "SKIPPED";
    private static final String RESULT_MISSING = "MISSING";

    private static final TypeReference<Map<String, Object>> FILE_PARAM_TYPE = new TypeReference<>() {
    };
    private static final String FIELD_INITIAL_KEY = "initialKey";
    private static final String FIELD_KEY_ENVELOPE_STATUS = "keyEnvelopeStatus";
    private static final String FIELD_ALGORITHM_SUITE = "algorithmSuite";
    private static final String FIELD_SIGNATURE_SUITE = "signatureSuite";
    private static final String FIELD_KEM_SUITE = "kemSuite";
    private static final String FIELD_PROOF_SUITE = "proofSuite";
    private static final String FIELD_KEY_VERSION = "keyVersion";
    private static final String FIELD_DEPRECATED_AFTER = "deprecatedAfter";
    private static final String FIELD_ENCRYPTION_ALGORITHM = "encryptionAlgorithm";
    private static final String ENVELOPE_STATUS_ENVELOPED = "ENVELOPED";
    private static final String ENCRYPTION_NONE = "NONE";

    private final FileKeyEnvelopeMapper fileKeyEnvelopeMapper;
    private final FileKeyAuditLogMapper fileKeyAuditLogMapper;
    private final KeyWrappingProviderRegistry wrappingRegistry;
    private final FileKeyEnvelopeProperties properties;
    private final CryptoSuitePolicyService suitePolicy;
    private final KeyEnvelopeRotationActivationService rotationActivationService;

    /**
     * Removes plaintext key material from file_param and returns envelope input metadata.
     */
    public FileParamEnvelopeResult prepareFileParam(String fileParam) {
        if (!StringUtils.hasText(fileParam)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件元数据不能为空");
        }

        Map<String, Object> params = JsonConverter.parse(fileParam, FILE_PARAM_TYPE);
        if (params == null) {
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "文件元数据 JSON 解析失败");
        }

        Map<String, Object> sanitized = new LinkedHashMap<>(params);
        Object rawInitialKey = sanitized.remove(FIELD_INITIAL_KEY);
        String encryptionAlgorithm = resolveEncryptionAlgorithm(sanitized);
        if (rawInitialKey == null) {
            if (ENCRYPTION_NONE.equalsIgnoreCase(encryptionAlgorithm)) {
                return FileParamEnvelopeResult.withoutEnvelope(JsonConverter.toJson(sanitized));
            }
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件数据密钥不能为空");
        }
        if (!(rawInitialKey instanceof String initialKey)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件数据密钥格式无效");
        }
        if (!StringUtils.hasText(initialKey)) {
            if (ENCRYPTION_NONE.equalsIgnoreCase(encryptionAlgorithm)) {
                return FileParamEnvelopeResult.withoutEnvelope(JsonConverter.toJson(sanitized));
            }
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件数据密钥不能为空");
        }

        if (ENCRYPTION_NONE.equalsIgnoreCase(encryptionAlgorithm)) {
            return FileParamEnvelopeResult.withoutEnvelope(JsonConverter.toJson(sanitized));
        }

        Integer keyVersion = properties.getKeyVersion();
        CryptoSuitePolicySnapshot policySnapshot = suitePolicy.currentPolicy();
        CryptoSuiteMetadata suiteMetadata = suitePolicy.metadataFor(policySnapshot, keyVersion);
        WrappingKeyReference wrappingTarget = suitePolicy.validateWrappingSelection(
                policySnapshot, suiteMetadata.keyVersion());
        String explicitAlgorithmSuite = resolveExplicitAlgorithmSuite(sanitized);
        if (explicitAlgorithmSuite != null) {
            suitePolicy.validateAlgorithmSuite(explicitAlgorithmSuite);
        }
        String algorithmSuite = explicitAlgorithmSuite != null
                ? explicitAlgorithmSuite : suiteMetadata.algorithmSuite();
        sanitized.put(FIELD_KEY_ENVELOPE_STATUS, ENVELOPE_STATUS_ENVELOPED);
        sanitized.put(FIELD_ALGORITHM_SUITE, algorithmSuite);
        sanitized.put(FIELD_SIGNATURE_SUITE, suiteMetadata.signatureSuite());
        sanitized.put(FIELD_KEM_SUITE, suiteMetadata.kemSuite());
        sanitized.put(FIELD_PROOF_SUITE, suiteMetadata.proofSuite());
        sanitized.put(FIELD_KEY_VERSION, keyVersion);
        if (suiteMetadata.deprecatedAfterIso() != null) {
            sanitized.put(FIELD_DEPRECATED_AFTER, suiteMetadata.deprecatedAfterIso());
        }
        sanitized.putIfAbsent(FIELD_ENCRYPTION_ALGORITHM, encryptionAlgorithm);

        String sanitizedJson = JsonConverter.toJson(sanitized);
        if (sanitizedJson == null) {
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "文件元数据 JSON 序列化失败");
        }
        return new FileParamEnvelopeResult(
                sanitizedJson,
                initialKey,
                algorithmSuite,
                suiteMetadata.signatureSuite(),
                suiteMetadata.kemSuite(),
                suiteMetadata.proofSuite(),
                encryptionAlgorithm,
                keyVersion,
                suiteMetadata.deprecatedAfterIso(),
                wrappingTarget
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

        WrappingKeyReference target = envelopeResult.wrappingTarget();
        if (target == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件密钥信封缺少冻结的包封目标");
        }
        WrappingContext context = buildWrappingContext(
                tenantId,
                fileId,
                resolvedFileHash,
                RECIPIENT_TYPE_OWNER,
                ownerId,
                envelopeResult.keyVersion(),
                envelopeResult.algorithmSuite(),
                target.contextSchema()
        );
        WrappedDataKey wrapped = wrappingRegistry.wrap(new KeyWrapRequest(
                PlaintextDataKey.of(envelopeResult.initialKey()),
                context,
                target,
                envelopeResult.keyVersion()
        )).requireValue();
        markActiveOwnerEnvelopesSuperseded(tenantId, fileId, resolvedFileHash, ownerId);

        FileKeyEnvelope envelope = createEnvelope(
                tenantId,
                fileId,
                resolvedFileHash,
                RECIPIENT_TYPE_OWNER,
                ownerId,
                envelopeResult.algorithmSuite(),
                envelopeResult.signatureSuite(),
                envelopeResult.kemSuite(),
                envelopeResult.proofSuite(),
                envelopeResult.encryptionAlgorithm(),
                parseDeprecatedAfter(envelopeResult.deprecatedAfter()),
                wrapped,
                context
        );
        fileKeyEnvelopeMapper.insert(envelope);
    }

    /**
     * 基于 provider 结果创建完整持久化信封。
     */
    private FileKeyEnvelope createEnvelope(Long tenantId,
                                           Long fileId,
                                           String fileHash,
                                           String recipientType,
                                           Long recipientId,
                                           String algorithmSuite,
                                           String signatureSuite,
                                           String kemSuite,
                                           String proofSuite,
                                           String encryptionAlgorithm,
                                           Date deprecatedAfter,
                                           WrappedDataKey wrapped,
                                           WrappingContext context) {
        WrappingKeyReference reference = wrapped.keyReference();
        return new FileKeyEnvelope()
                .setTenantId(tenantId)
                .setFileId(fileId)
                .setFileHash(fileHash)
                .setRecipientType(recipientType)
                .setRecipientId(recipientId)
                .setKeyVersion(wrapped.keyVersion())
                .setAlgorithmSuite(algorithmSuite)
                .setSignatureSuite(signatureSuite)
                .setKemSuite(kemSuite)
                .setProofSuite(proofSuite)
                .setEncryptionAlgorithm(encryptionAlgorithm)
                .setWrappingAlgorithm(wrapped.wrappingAlgorithm())
                .setKmsProvider(wrapped.kmsProvider())
                .setProviderContractVersion(reference.providerContractVersion())
                .setKmsKeyId(wrapped.kmsKeyId())
                .setProviderKeyVersion(reference.providerKeyVersion())
                .setContextSchema(reference.contextSchema())
                .setEncryptedDataKey(wrapped.encryptedDataKey())
                .setWrappingIv(wrapped.wrappingIv())
                .setAadHash(context.sha256Hex())
                .setStatus(STATUS_ACTIVE)
                .setDeprecatedAfter(deprecatedAfter)
                .setDeleted(0);
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

        Optional<String> envelopeInitialKey = unwrapActiveRecipientInitialKey(
                file,
                resolvedFileHash,
                tenantId,
                RECIPIENT_TYPE_OWNER,
                ownerId,
                actorId,
                reason
        );
        return envelopeInitialKey.isPresent()
                ? envelopeInitialKey
                : resolveLegacyInitialKey(file, ownerId);
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
            Optional<String> initialKey = resolveExistingOwnerInitialKey(
                    file,
                    file.getFileHash(),
                    share.getUserId(),
                    actorId,
                    "SHARE_ENVELOPE_CREATE"
            );
            if (initialKey.isEmpty()) {
                continue;
            }
            saveRecipientEnvelope(
                    tenantId,
                    file,
                    file.getFileHash(),
                    RECIPIENT_TYPE_SHARE,
                    share.getId(),
                    initialKey.get()
            );
        }
    }

    /**
     * Saves friend-share recipient envelopes for every file included in a friend share.
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveFriendShareEnvelopes(FriendFileShare share, List<File> files, Long actorId, String reason) {
        if (share == null || share.getId() == null || files == null || files.isEmpty()) {
            return;
        }
        Long tenantId = resolveTenantId(share);
        if (tenantId == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "好友分享密钥信封租户上下文不完整");
        }

        for (File file : files) {
            if (file == null || file.getId() == null || !StringUtils.hasText(file.getFileHash())) {
                continue;
            }
            Optional<String> initialKey = resolveExistingOwnerInitialKey(
                    file,
                    file.getFileHash(),
                    share.getSharerId(),
                    actorId,
                    "FRIEND_SHARE_ENVELOPE_CREATE"
            );
            if (initialKey.isEmpty()) {
                continue;
            }
            saveRecipientEnvelope(
                    tenantId,
                    file,
                    file.getFileHash(),
                    RECIPIENT_TYPE_FRIEND_SHARE,
                    share.getId(),
                    initialKey.get()
            );
        }
    }

    /**
     * Rewraps a shared file's data key as an owner envelope for the recipient's newly copied file record.
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveCopiedOwnerEnvelope(File sourceFile,
                                        File copiedFile,
                                        FileShare share,
                                        Long recipientUserId,
                                        Long actorId,
                                        String reason) {
        if (sourceFile == null || copiedFile == null || share == null || recipientUserId == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "保存分享文件的密钥信封上下文不完整");
        }
        String fileHash = StringUtils.hasText(copiedFile.getFileHash())
                ? copiedFile.getFileHash()
                : sourceFile.getFileHash();
        Long sourceTenantId = resolveTenantId(sourceFile);
        Long copiedTenantId = resolveTenantId(copiedFile);
        if (copiedFile.getId() == null || sourceTenantId == null || copiedTenantId == null
                || !StringUtils.hasText(fileHash)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "保存分享文件的密钥信封上下文不完整");
        }
        if (isExplicitlyUnencrypted(sourceFile)) {
            return;
        }

        Optional<String> initialKey = TenantContext.callWithTenant(sourceTenantId, () -> {
            Optional<String> shareInitialKey = unwrapActiveShareInitialKey(
                    sourceFile,
                    sourceFile.getFileHash(),
                    share,
                    actorId,
                    reason
            );
            if (shareInitialKey.isPresent()) {
                return shareInitialKey;
            }
            return unwrapActiveOwnerInitialKey(
                    sourceFile,
                    sourceFile.getFileHash(),
                    share.getUserId(),
                    actorId,
                    reason
            );
        });
        if (initialKey.isEmpty()) {
            if (hasExplicitEncryptionAlgorithm(sourceFile)) {
                throw new GeneralException(ResultEnum.FAIL, "文件解密密钥不存在");
            }
            return;
        }

        String copiedInitialKey = initialKey.get();
        TenantContext.callWithTenant(copiedTenantId, () -> {
            saveRecipientEnvelope(
                    copiedTenantId,
                    copiedFile,
                    fileHash,
                    RECIPIENT_TYPE_OWNER,
                    recipientUserId,
                    copiedInitialKey
            );
            return null;
        });
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

        return unwrapActiveRecipientInitialKey(
                file,
                resolvedFileHash,
                tenantId,
                RECIPIENT_TYPE_SHARE,
                share.getId(),
                actorId,
                reason
        );
    }

    /**
     * Resolves an active friend-share recipient envelope and audits the unwrap attempt.
     */
    public Optional<String> unwrapActiveFriendShareInitialKey(File file,
                                                              String fileHash,
                                                              FriendFileShare share,
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

        return unwrapActiveRecipientInitialKey(
                file,
                resolvedFileHash,
                tenantId,
                RECIPIENT_TYPE_FRIEND_SHARE,
                share.getId(),
                actorId,
                reason
        );
    }

    /**
     * 解析 owner 下载 grant 所绑定的精确信封，不执行解封。
     */
    public Optional<FileKeyGrantEnvelopeBinding> resolveOwnerGrantBinding(File file,
                                                                          String fileHash,
                                                                          Long ownerId) {
        Optional<FileKeyGrantEnvelopeBinding> binding = resolveGrantBinding(
                file, fileHash, RECIPIENT_TYPE_OWNER, ownerId);
        if (binding.isPresent()) {
            return binding;
        }
        return resolveLegacyInitialKey(file, ownerId).isPresent()
                ? Optional.of(legacyGrantBinding(file, fileHash, ownerId))
                : Optional.empty();
    }

    /**
     * 解析分享码 recipient 下载 grant 所绑定的精确信封，不执行解封。
     */
    public Optional<FileKeyGrantEnvelopeBinding> resolveShareGrantBinding(File file,
                                                                          String fileHash,
                                                                          FileShare share) {
        if (share == null) {
            return Optional.empty();
        }
        return resolveGrantBinding(file, fileHash, RECIPIENT_TYPE_SHARE, share.getId());
    }

    /**
     * 解析好友分享 recipient 下载 grant 所绑定的精确信封，不执行解封。
     */
    public Optional<FileKeyGrantEnvelopeBinding> resolveFriendShareGrantBinding(File file,
                                                                                String fileHash,
                                                                                FriendFileShare share) {
        if (share == null) {
            return Optional.empty();
        }
        return resolveGrantBinding(file, fileHash, RECIPIENT_TYPE_FRIEND_SHARE, share.getId());
    }

    /**
     * 按 grant 快照解封精确 ACTIVE 信封，rotation、撤销或路由字段变化均失败关闭。
     */
    public Optional<String> unwrapGrantBinding(File file,
                                               FileKeyGrantEnvelopeBinding binding,
                                               Long actorId,
                                               String reason) {
        if (!matchesGrantFile(file, binding)) {
            auditGrant(binding, OPERATION_GRANT_DENY, actorId, RESULT_FAILURE, "FILE_BINDING_MISMATCH");
            return Optional.empty();
        }
        if (binding.legacyPlaintextAtRest()) {
            Optional<String> legacy = resolveLegacyInitialKey(file, binding.recipientId());
            auditGrant(binding, OPERATION_GRANT_CONSUME, actorId,
                    legacy.isPresent() ? RESULT_SUCCESS : RESULT_MISSING, reason);
            return legacy;
        }

        FileKeyEnvelope envelope = fileKeyEnvelopeMapper.selectById(binding.envelopeId());
        if (!matchesGrantEnvelope(envelope, binding) || !STATUS_ACTIVE.equals(envelope.getStatus())) {
            auditGrant(binding, OPERATION_GRANT_DENY, actorId, RESULT_FAILURE, "ENVELOPE_NOT_ACTIVE");
            return Optional.empty();
        }
        Optional<String> initialKey = unwrapEnvelope(envelope, actorId, reason);
        if (initialKey.isPresent()) {
            auditGrant(binding, OPERATION_GRANT_CONSUME, actorId, RESULT_SUCCESS, reason);
        }
        return initialKey;
    }

    /**
     * 记录下载 grant 生命周期审计，事件不包含 grant、会话或客户端地址。
     */
    public void auditGrantIssue(FileKeyGrantEnvelopeBinding binding, Long actorId, String reason) {
        auditGrant(binding, OPERATION_GRANT_ISSUE, actorId, RESULT_SUCCESS, reason);
    }

    /**
     * 记录受控 plaintext-v0 兼容使用审计。
     */
    public void auditLegacyPlaintextDelivery(FileKeyGrantEnvelopeBinding binding, Long actorId) {
        auditGrant(binding, OPERATION_PLAINTEXT_V0, actorId, RESULT_SUCCESS, "COMPATIBILITY_WINDOW");
    }

    /**
     * 记录 grant 校验拒绝，原因必须是调用方提供的稳定枚举文本。
     */
    public void auditGrantDenial(FileKeyGrantEnvelopeBinding binding, Long actorId, String reason) {
        auditGrant(binding, OPERATION_GRANT_DENY, actorId, RESULT_FAILURE, reason);
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
        revokeActiveRecipientEnvelopes(tenantId, RECIPIENT_TYPE_SHARE, share.getId(), actorId, reason);
    }

    /**
     * Revokes all active friend-share recipient envelopes for a friend share.
     */
    @Transactional(rollbackFor = Exception.class)
    public void revokeFriendShareEnvelopes(FriendFileShare share, Long actorId, String reason) {
        if (share == null || share.getId() == null) {
            return;
        }
        Long tenantId = resolveTenantId(share);
        revokeActiveRecipientEnvelopes(tenantId, RECIPIENT_TYPE_FRIEND_SHARE, share.getId(), actorId, reason);
    }

    /**
     * Revokes all active recipient envelopes matching a share-like recipient.
     */
    private void revokeActiveRecipientEnvelopes(Long tenantId,
                                                String recipientType,
                                                Long recipientId,
                                                Long actorId,
                                                String reason) {
        List<FileKeyEnvelope> envelopes = fileKeyEnvelopeMapper.selectActiveRecipientForUpdate(
                tenantId, recipientType, recipientId);

        if (envelopes == null || envelopes.isEmpty()) {
            audit(tenantId, null, null, recipientType, recipientId, null,
                    OPERATION_REVOKE, actorId, RESULT_MISSING, reason, null);
            return;
        }

        for (FileKeyEnvelope envelope : envelopes) {
            int updated = fileKeyEnvelopeMapper.compareAndSetStatus(
                    tenantId, envelope.getId(), STATUS_ACTIVE, STATUS_REVOKED);
            if (updated == 1) {
                audit(envelope, OPERATION_REVOKE, actorId, RESULT_SUCCESS, reason, null);
            }
        }
    }

    /**
     * Rotates active envelopes for a file to the configured current key version.
     */
    public KeyEnvelopeRotationResult rotateActiveFileEnvelopes(File file, Long actorId, String reason) {
        if (file == null || file.getId() == null) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }
        Long tenantId = resolveTenantId(file);
        if (tenantId == null || !StringUtils.hasText(file.getFileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件密钥信封上下文不完整");
        }

        CryptoSuitePolicySnapshot policySnapshot = suitePolicy.currentPolicy();
        CryptoSuiteMetadata targetMetadata = suitePolicy.metadataFor(
                policySnapshot, properties.getKeyVersion());
        Integer targetKeyVersion = targetMetadata.keyVersion();
        WrappingKeyReference targetReference = suitePolicy.validateWrappingSelection(
                policySnapshot, targetKeyVersion);
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
            AutomatedEnvelopeRotationResult result = rotateEnvelopeForAutomation(
                    envelope.getId(), IdWorker.getId(), targetReference, targetKeyVersion, actorId, reason);
            if ("SUCCEEDED".equals(result.outcome())) {
                rotated++;
            } else if (result.failureCategory() == KeyWrappingFailureCategory.NONE) {
                skipped++;
            } else {
                throw KeyWrappingFailure.of(result.failureCategory(), result.retryable()).toException();
            }
        }

        return new KeyEnvelopeRotationResult(file.getFileHash(), targetKeyVersion, rotated, skipped);
    }

    /**
     * Builds, verifies, and atomically activates one deterministic automated-rotation candidate.
     */
    public AutomatedEnvelopeRotationResult rotateEnvelopeForAutomation(Long sourceEnvelopeId,
                                                                        Long candidateEnvelopeId,
                                                                        WrappingKeyReference targetReference,
                                                                        Integer targetKeyVersion,
                                                                        Long actorId,
                                                                        String reason) {
        if (sourceEnvelopeId == null || candidateEnvelopeId == null || targetReference == null
                || targetKeyVersion == null || targetKeyVersion <= 0) {
            return AutomatedEnvelopeRotationResult.failed(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_REQUEST, false));
        }
        FileKeyEnvelope source = fileKeyEnvelopeMapper.selectById(sourceEnvelopeId);
        Long tenantId = TenantContext.getTenantId();
        if (source == null || tenantId == null || !tenantId.equals(source.getTenantId())) {
            return AutomatedEnvelopeRotationResult.completed("SKIPPED_SOURCE_CHANGED", null);
        }
        if (STATUS_REVOKED.equals(source.getStatus())) {
            return AutomatedEnvelopeRotationResult.completed("SKIPPED_REVOKED", null);
        }
        if (!STATUS_ACTIVE.equals(source.getStatus())) {
            FileKeyEnvelope existingCandidate = fileKeyEnvelopeMapper.selectById(candidateEnvelopeId);
            return AutomatedEnvelopeRotationResult.completed(
                    existingCandidate != null && STATUS_ACTIVE.equals(existingCandidate.getStatus())
                            ? "SUCCEEDED" : "SKIPPED_SOURCE_CHANGED",
                    existingCandidate == null ? null : existingCandidate.getId());
        }

        WrappingContext sourceContext;
        try {
            validatePersistedEnvelopeMetadata(source);
            suitePolicy.validateRewrapTransition(source, targetReference);
            sourceContext = buildContextFromEnvelope(source);
            sourceContext.canonicalBytes();
        } catch (GeneralException exception) {
            audit(source, OPERATION_ROTATE, actorId, RESULT_FAILURE, reason,
                    KeyWrappingFailureCategory.INVALID_REQUEST);
            return AutomatedEnvelopeRotationResult.failed(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_REQUEST, false));
        }
        if (!sourceContext.matchesHash(source.getAadHash())) {
            audit(source, OPERATION_ROTATE, actorId, RESULT_FAILURE, reason,
                    KeyWrappingFailureCategory.INVALID_CIPHERTEXT);
            return AutomatedEnvelopeRotationResult.failed(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_CIPHERTEXT, false));
        }

        KeyWrappingResult<PlaintextDataKey> sourcePlaintext = unwrapEnvelopeMaterial(source);
        if (!sourcePlaintext.isSuccess()) {
            audit(source, OPERATION_ROTATE, actorId, RESULT_FAILURE, reason,
                    sourcePlaintext.failure().category());
            return AutomatedEnvelopeRotationResult.failed(sourcePlaintext.failure());
        }
        if (hasTargetIdentity(source, targetReference, targetKeyVersion)) {
            audit(source, OPERATION_ROTATE, actorId, RESULT_SKIPPED, reason,
                    KeyWrappingFailureCategory.NONE);
            return AutomatedEnvelopeRotationResult.completed("SKIPPED_ALREADY_TARGET", null);
        }

        WrappingContext targetContext = buildWrappingContext(
                source.getTenantId(), source.getFileId(), source.getFileHash(),
                source.getRecipientType(), source.getRecipientId(), targetKeyVersion,
                source.getAlgorithmSuite(), targetReference.contextSchema());
        FileKeyEnvelope candidate = fileKeyEnvelopeMapper.selectById(candidateEnvelopeId);
        if (candidate == null) {
            KeyWrappingResult<WrappedDataKey> rotationResult;
            try {
                rotationResult = rotateEnvelopeMaterial(
                        source, sourceContext, targetReference, targetContext, targetKeyVersion);
            } catch (GeneralException exception) {
                rotationResult = KeyWrappingResult.failure(KeyWrappingFailure.of(
                        KeyWrappingFailureCategory.INVALID_REQUEST, false));
            }
            if (!rotationResult.isSuccess()) {
                audit(source, OPERATION_ROTATE, actorId, RESULT_FAILURE, reason,
                        rotationResult.failure().category());
                return AutomatedEnvelopeRotationResult.failed(rotationResult.failure());
            }
            candidate = copyForRotation(source, rotationResult.value(), targetContext)
                    .setId(candidateEnvelopeId)
                    .setStatus(STATUS_PENDING_VERIFICATION);
            fileKeyEnvelopeMapper.insert(candidate);
        }
        if (!sameRecipientAndTarget(source, candidate, targetReference, targetKeyVersion)
                || (!STATUS_PENDING_VERIFICATION.equals(candidate.getStatus())
                && !STATUS_ACTIVE.equals(candidate.getStatus()))) {
            return AutomatedEnvelopeRotationResult.failed(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_REQUEST, false));
        }

        KeyWrappingResult<PlaintextDataKey> candidatePlaintext = unwrapEnvelopeMaterial(candidate);
        if (!candidatePlaintext.isSuccess()) {
            audit(source, OPERATION_ROTATE, actorId, RESULT_FAILURE, reason,
                    candidatePlaintext.failure().category());
            return AutomatedEnvelopeRotationResult.failed(candidatePlaintext.failure());
        }
        if (!constantTimeSamePlaintext(sourcePlaintext.value(), candidatePlaintext.value())) {
            fileKeyEnvelopeMapper.compareAndSetStatus(
                    tenantId, candidateEnvelopeId, STATUS_PENDING_VERIFICATION, STATUS_SUPERSEDED);
            audit(source, OPERATION_ROTATE, actorId, RESULT_FAILURE, reason,
                    KeyWrappingFailureCategory.INVALID_CIPHERTEXT);
            return AutomatedEnvelopeRotationResult.failed(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_CIPHERTEXT, false));
        }

        String outcome = rotationActivationService.activateVerifiedCandidate(
                tenantId, sourceEnvelopeId, candidateEnvelopeId, targetReference, targetKeyVersion);
        FileKeyEnvelope auditEnvelope = fileKeyEnvelopeMapper.selectById(candidateEnvelopeId);
        audit(auditEnvelope == null ? source : auditEnvelope, OPERATION_ROTATE, actorId,
                "SUCCEEDED".equals(outcome) ? RESULT_SUCCESS : RESULT_SKIPPED,
                reason, KeyWrappingFailureCategory.NONE);
        return AutomatedEnvelopeRotationResult.completed(outcome, candidateEnvelopeId);
    }

    /**
     * Resolves and unwraps the latest active envelope for a specific recipient.
     */
    private Optional<String> unwrapActiveRecipientInitialKey(File file,
                                                            String fileHash,
                                                            Long tenantId,
                                                            String recipientType,
                                                            Long recipientId,
                                                            Long actorId,
                                                            String reason) {
        FileKeyEnvelope envelope = fileKeyEnvelopeMapper.selectOne(new LambdaQueryWrapper<FileKeyEnvelope>()
                .eq(FileKeyEnvelope::getTenantId, tenantId)
                .eq(FileKeyEnvelope::getFileId, file.getId())
                .eq(FileKeyEnvelope::getFileHash, fileHash)
                .eq(FileKeyEnvelope::getRecipientType, recipientType)
                .eq(FileKeyEnvelope::getRecipientId, recipientId)
                .eq(FileKeyEnvelope::getStatus, STATUS_ACTIVE)
                .orderByDesc(FileKeyEnvelope::getKeyVersion)
                .orderByDesc(FileKeyEnvelope::getCreateTime)
                .last("LIMIT 1"));
        if (envelope == null) {
            audit(tenantId, file.getId(), fileHash, recipientType, recipientId, null,
                    OPERATION_UNWRAP, actorId, RESULT_MISSING, reason, null);
            return Optional.empty();
        }

        return unwrapEnvelope(envelope, actorId, reason);
    }

    /**
     * 查询指定 recipient 的最新 ACTIVE 信封并转换为无敏感材料的 grant 绑定。
     */
    private Optional<FileKeyGrantEnvelopeBinding> resolveGrantBinding(File file,
                                                                      String fileHash,
                                                                      String recipientType,
                                                                      Long recipientId) {
        if (file == null || file.getId() == null || recipientId == null) {
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
                .eq(FileKeyEnvelope::getRecipientType, recipientType)
                .eq(FileKeyEnvelope::getRecipientId, recipientId)
                .eq(FileKeyEnvelope::getStatus, STATUS_ACTIVE)
                .orderByDesc(FileKeyEnvelope::getKeyVersion)
                .orderByDesc(FileKeyEnvelope::getCreateTime)
                .last("LIMIT 1"));
        return Optional.ofNullable(envelope).map(value -> toGrantBinding(file, value));
    }

    /**
     * 将持久化信封投影为下载 grant 允许持久化的路由字段。
     */
    private FileKeyGrantEnvelopeBinding toGrantBinding(File file, FileKeyEnvelope envelope) {
        return new FileKeyGrantEnvelopeBinding(
                envelope.getId(), envelope.getTenantId(), envelope.getFileId(), normalizedFileVersion(file),
                envelope.getFileHash(),
                envelope.getRecipientType(), envelope.getRecipientId(), envelope.getKeyVersion(),
                envelope.getAlgorithmSuite(), envelope.getSignatureSuite(), envelope.getKemSuite(),
                envelope.getProofSuite(), envelope.getEncryptionAlgorithm(), envelope.getKmsProvider(),
                envelope.getProviderContractVersion(), envelope.getProviderKeyVersion(), false);
    }

    /**
     * 为仍在迁移窗口内的 owner 历史明文记录创建无密钥 grant 绑定。
     */
    private FileKeyGrantEnvelopeBinding legacyGrantBinding(File file, String fileHash, Long ownerId) {
        Map<String, Object> params = parsePersistedFileParam(file);
        String encryptionAlgorithm = resolvePersistedEncryptionAlgorithm(params);
        return new FileKeyGrantEnvelopeBinding(
                null, resolveTenantId(file), file.getId(), normalizedFileVersion(file), fileHash,
                RECIPIENT_TYPE_OWNER, ownerId,
                numberValue(params.get(FIELD_KEY_VERSION)), stringValue(params.get(FIELD_ALGORITHM_SUITE)),
                stringValue(params.get(FIELD_SIGNATURE_SUITE)), stringValue(params.get(FIELD_KEM_SUITE)),
                stringValue(params.get(FIELD_PROOF_SUITE)),
                StringUtils.hasText(encryptionAlgorithm) ? encryptionAlgorithm : "CHUNK_KEY_CHAIN",
                "legacy-plaintext", 1, "legacy", true);
    }

    /**
     * 校验当前文件仍与 grant 的不可变文件快照一致。
     */
    private boolean matchesGrantFile(File file, FileKeyGrantEnvelopeBinding binding) {
        return file != null && binding != null
                && Objects.equals(file.getTenantId(), binding.tenantId())
                && Objects.equals(file.getId(), binding.fileId())
                && Objects.equals(normalizedFileVersion(file), binding.fileVersion())
                && Objects.equals(file.getFileHash(), binding.fileHash());
    }

    /**
     * 将遗留空版本规范化为历史首版本，保证 grant 始终绑定不可变版本号。
     */
    private Integer normalizedFileVersion(File file) {
        return file != null && file.getVersion() != null ? file.getVersion() : 1;
    }

    /**
     * 校验数据库中的精确信封仍与 grant 全部路由字段一致。
     */
    private boolean matchesGrantEnvelope(FileKeyEnvelope envelope, FileKeyGrantEnvelopeBinding binding) {
        return envelope != null && binding != null
                && Objects.equals(envelope.getId(), binding.envelopeId())
                && Objects.equals(envelope.getTenantId(), binding.tenantId())
                && Objects.equals(envelope.getFileId(), binding.fileId())
                && Objects.equals(envelope.getFileHash(), binding.fileHash())
                && Objects.equals(envelope.getRecipientType(), binding.recipientType())
                && Objects.equals(envelope.getRecipientId(), binding.recipientId())
                && Objects.equals(envelope.getKeyVersion(), binding.keyVersion())
                && Objects.equals(envelope.getAlgorithmSuite(), binding.algorithmSuite())
                && Objects.equals(envelope.getSignatureSuite(), binding.signatureSuite())
                && Objects.equals(envelope.getKemSuite(), binding.kemSuite())
                && Objects.equals(envelope.getProofSuite(), binding.proofSuite())
                && Objects.equals(envelope.getEncryptionAlgorithm(), binding.encryptionAlgorithm())
                && Objects.equals(envelope.getKmsProvider(), binding.kmsProvider())
                && Objects.equals(envelope.getProviderContractVersion(), binding.providerContractVersion())
                && Objects.equals(envelope.getProviderKeyVersion(), binding.providerKeyVersion());
    }

    /**
     * 记录 grant 事件；如精确信封仍存在则复用完整 provider 审计字段。
     */
    private void auditGrant(FileKeyGrantEnvelopeBinding binding,
                            String operation,
                            Long actorId,
                            String result,
                            String reason) {
        if (binding == null) {
            return;
        }
        FileKeyEnvelope envelope = binding.envelopeId() == null
                ? null : fileKeyEnvelopeMapper.selectById(binding.envelopeId());
        if (envelope != null && matchesGrantEnvelope(envelope, binding)) {
            audit(envelope, operation, actorId, result, reason, KeyWrappingFailureCategory.NONE);
            return;
        }
        audit(binding.tenantId(), binding.fileId(), binding.fileHash(), binding.recipientType(),
                binding.recipientId(), binding.keyVersion(), operation, actorId, result, reason, null);
    }

    /**
     * 将历史文件参数中的版本号安全转换为整数。
     */
    private Integer numberValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 将历史文件参数中的 suite 字段安全转换为文本。
     */
    private String stringValue(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text : "legacy-unspecified";
    }

    /**
     * Builds stable AAD for envelope wrapping and unwrapping.
     */
    private WrappingContext buildWrappingContext(Long tenantId,
                                                 Long fileId,
                                                 String fileHash,
                                                 String recipientType,
                                                 Long recipientId,
                                                 Integer keyVersion,
                                                 String algorithmSuite,
                                                 String contextSchema) {
        return new WrappingContext(tenantId, fileId, fileHash, recipientType, recipientId,
                keyVersion, algorithmSuite, contextSchema);
    }

    /**
     * Marks existing owner envelopes inactive before writing the current active envelope.
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
        CryptoSuitePolicySnapshot policySnapshot = suitePolicy.currentPolicy();
        CryptoSuiteMetadata suiteMetadata = suitePolicy.metadataFor(
                policySnapshot, properties.getKeyVersion());
        Integer keyVersion = suiteMetadata.keyVersion();
        String algorithmSuite = suiteMetadata.algorithmSuite();
        WrappingKeyReference target = suitePolicy.validateWrappingSelection(policySnapshot, keyVersion);
        WrappingContext context = buildWrappingContext(tenantId, file.getId(), fileHash,
                recipientType, recipientId, keyVersion, algorithmSuite, target.contextSchema());
        WrappedDataKey wrapped = wrappingRegistry.wrap(new KeyWrapRequest(
                PlaintextDataKey.of(initialKey), context, target, keyVersion)).requireValue();
        markActiveRecipientEnvelopesSuperseded(tenantId, file.getId(), fileHash, recipientType, recipientId);
        FileKeyEnvelope envelope = createEnvelope(
                tenantId, file.getId(), fileHash, recipientType, recipientId,
                algorithmSuite, suiteMetadata.signatureSuite(), suiteMetadata.kemSuite(),
                suiteMetadata.proofSuite(), properties.getEncryptionAlgorithm(),
                suiteMetadata.deprecatedAfterDate(), wrapped, context);
        fileKeyEnvelopeMapper.insert(envelope);
    }

    /**
     * Resolves an existing owner's key while preserving compatibility for legacy/plain metadata.
     */
    private Optional<String> resolveExistingOwnerInitialKey(File file,
                                                            String fileHash,
                                                            Long ownerId,
                                                            Long actorId,
                                                            String reason) {
        if (isExplicitlyUnencrypted(file)) {
            return Optional.empty();
        }
        Optional<String> initialKey = unwrapActiveOwnerInitialKey(file, fileHash, ownerId, actorId, reason);
        if (initialKey.isPresent()) {
            return initialKey;
        }
        if (hasExplicitEncryptionAlgorithm(file)) {
            throw new GeneralException(ResultEnum.FAIL, "文件解密密钥不存在");
        }
        return Optional.empty();
    }

    /**
     * Reads legacy plaintext key material only when metadata does not explicitly mark the file unencrypted.
     */
    private Optional<String> resolveLegacyInitialKey(File file, Long ownerId) {
        if (file == null || file.getUid() == null || !file.getUid().equals(ownerId)) {
            return Optional.empty();
        }
        Map<String, Object> params = parsePersistedFileParam(file);
        String encryptionAlgorithm = resolvePersistedEncryptionAlgorithm(params);
        if (ENCRYPTION_NONE.equalsIgnoreCase(encryptionAlgorithm)) {
            return Optional.empty();
        }
        Object rawInitialKey = params.get(FIELD_INITIAL_KEY);
        if (rawInitialKey instanceof String initialKey && StringUtils.hasText(initialKey)) {
            return Optional.of(initialKey);
        }
        return Optional.empty();
    }

    /**
     * Returns whether existing metadata explicitly marks the stored bytes as unencrypted.
     */
    private boolean isExplicitlyUnencrypted(File file) {
        return ENCRYPTION_NONE.equalsIgnoreCase(
                resolvePersistedEncryptionAlgorithm(parsePersistedFileParam(file))
        );
    }

    /**
     * Returns whether existing metadata explicitly declares any encryption algorithm.
     */
    private boolean hasExplicitEncryptionAlgorithm(File file) {
        return StringUtils.hasText(resolvePersistedEncryptionAlgorithm(parsePersistedFileParam(file)));
    }

    /**
     * Parses persisted file metadata for compatibility decisions.
     */
    private Map<String, Object> parsePersistedFileParam(File file) {
        if (file == null || !StringUtils.hasText(file.getFileParam())) {
            return Map.of();
        }
        Map<String, Object> params = JsonConverter.parse(file.getFileParam(), FILE_PARAM_TYPE);
        return params == null ? Map.of() : params;
    }

    /**
     * Reads a normalized encryption algorithm from persisted metadata.
     */
    private String resolvePersistedEncryptionAlgorithm(Map<String, Object> params) {
        Object rawAlgorithm = params.get(FIELD_ENCRYPTION_ALGORITHM);
        if (rawAlgorithm instanceof String algorithm && StringUtils.hasText(algorithm)) {
            return algorithm.trim();
        }
        return null;
    }

    /**
     * Unwraps an envelope and records key access audit evidence.
     */
    private Optional<String> unwrapEnvelope(FileKeyEnvelope envelope, Long actorId, String reason) {
        KeyWrappingResult<PlaintextDataKey> result = unwrapEnvelopeMaterial(envelope);
        if (!result.isSuccess()) {
            audit(envelope, OPERATION_UNWRAP, actorId, RESULT_FAILURE, reason,
                    result.failure().category());
            throw result.failure().toException();
        }
        audit(envelope, OPERATION_UNWRAP, actorId, RESULT_SUCCESS, reason,
                KeyWrappingFailureCategory.NONE);
        return Optional.of(result.value().reveal());
    }

    /**
     * 校验上下文摘要后按持久化 provider 路由解封。
     */
    private KeyWrappingResult<PlaintextDataKey> unwrapEnvelopeMaterial(FileKeyEnvelope envelope) {
        try {
            validatePersistedEnvelopeMetadata(envelope);
        } catch (GeneralException exception) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.CONFIGURATION, false));
        }
        try {
            WrappingContext context = buildContextFromEnvelope(envelope);
            if (!context.matchesHash(envelope.getAadHash())) {
                return KeyWrappingResult.failure(KeyWrappingFailure.of(
                        KeyWrappingFailureCategory.INVALID_CIPHERTEXT, false));
            }
            return wrappingRegistry.unwrap(new KeyUnwrapRequest(toPersistedMaterial(envelope), context));
        } catch (GeneralException exception) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_REQUEST, false));
        }
    }

    /**
     * 优先执行同 Vault named key 原生 rewrap，否则受控解封后重新包封。
     */
    private KeyWrappingResult<WrappedDataKey> rotateEnvelopeMaterial(FileKeyEnvelope envelope,
                                                                     WrappingContext sourceContext,
                                                                     WrappingKeyReference targetReference,
                                                                     WrappingContext targetContext,
                                                                     Integer targetKeyVersion) {
        if (!sourceContext.matchesHash(envelope.getAadHash())) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_CIPHERTEXT, false));
        }
        PersistedWrappedDataKey source = toPersistedMaterial(envelope);
        boolean nativeEligible = source.keyReference().providerId().equals(targetReference.providerId())
                && source.keyReference().providerContractVersion() == targetReference.providerContractVersion()
                && source.keyReference().keyId().equals(targetReference.keyId())
                && WrappingContext.EXTERNAL_CONTEXT_V2.equals(sourceContext.schema())
                && WrappingContext.EXTERNAL_CONTEXT_V2.equals(targetContext.schema());
        if (nativeEligible) {
            KeyWrappingResult<WrappedDataKey> nativeResult = wrappingRegistry.rewrap(new KeyRewrapRequest(
                    source, sourceContext, targetReference, targetContext, targetKeyVersion));
            if (nativeResult.isSuccess()
                    || nativeResult.failure().category() != KeyWrappingFailureCategory.UNSUPPORTED) {
                return nativeResult;
            }
        }
        KeyWrappingResult<PlaintextDataKey> unwrapped = wrappingRegistry.unwrap(
                new KeyUnwrapRequest(source, sourceContext));
        if (!unwrapped.isSuccess()) {
            return KeyWrappingResult.failure(unwrapped.failure());
        }
        return wrappingRegistry.wrap(new KeyWrapRequest(
                unwrapped.value(), targetContext, targetReference, targetKeyVersion));
    }

    /**
     * 判断当前信封是否已与完整目标身份一致。
     */
    private boolean hasTargetIdentity(FileKeyEnvelope envelope,
                                      WrappingKeyReference target,
                                      Integer keyVersion) {
        return java.util.Objects.equals(envelope.getKeyVersion(), keyVersion)
                && java.util.Objects.equals(envelope.getKmsProvider(), target.providerId())
                && java.util.Objects.equals(envelope.getProviderContractVersion(), target.providerContractVersion())
                && java.util.Objects.equals(envelope.getKmsKeyId(), target.keyId())
                && java.util.Objects.equals(envelope.getProviderKeyVersion(), target.providerKeyVersion())
                && java.util.Objects.equals(envelope.getWrappingAlgorithm(), target.wrappingAlgorithm())
                && java.util.Objects.equals(envelope.getContextSchema(), target.contextSchema());
    }

    /**
     * Validates that a resumed deterministic candidate preserves authority and the frozen target.
     */
    private boolean sameRecipientAndTarget(FileKeyEnvelope source,
                                           FileKeyEnvelope candidate,
                                           WrappingKeyReference target,
                                           Integer targetKeyVersion) {
        return java.util.Objects.equals(source.getTenantId(), candidate.getTenantId())
                && java.util.Objects.equals(source.getFileId(), candidate.getFileId())
                && java.util.Objects.equals(source.getFileHash(), candidate.getFileHash())
                && java.util.Objects.equals(source.getRecipientType(), candidate.getRecipientType())
                && java.util.Objects.equals(source.getRecipientId(), candidate.getRecipientId())
                && hasTargetIdentity(candidate, target, targetKeyVersion);
    }

    /**
     * Compares verified plaintext keys without data-dependent early exit.
     */
    private boolean constantTimeSamePlaintext(PlaintextDataKey source, PlaintextDataKey candidate) {
        byte[] sourceBytes = source.reveal().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] candidateBytes = candidate.reveal().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return MessageDigest.isEqual(sourceBytes, candidateBytes);
    }

    /**
     * Creates a replacement envelope for rotation while preserving recipient metadata.
     */
    private FileKeyEnvelope copyForRotation(FileKeyEnvelope source,
                                            WrappedDataKey wrapped,
                                            WrappingContext context) {
        return createEnvelope(
                source.getTenantId(), source.getFileId(), source.getFileHash(),
                source.getRecipientType(), source.getRecipientId(), source.getAlgorithmSuite(),
                source.getSignatureSuite(), source.getKemSuite(), source.getProofSuite(),
                source.getEncryptionAlgorithm(), cloneDate(source.getDeprecatedAfter()), wrapped, context);
    }

    /**
     * 从持久化信封重建严格 provider 路由材料。
     */
    private PersistedWrappedDataKey toPersistedMaterial(FileKeyEnvelope envelope) {
        WrappingKeyReference reference = new WrappingKeyReference(
                envelope.getKmsProvider(),
                envelope.getProviderContractVersion(),
                envelope.getKmsKeyId(),
                envelope.getProviderKeyVersion(),
                envelope.getWrappingAlgorithm(),
                envelope.getContextSchema()
        );
        return new PersistedWrappedDataKey(
                envelope.getEncryptedDataKey(), envelope.getWrappingIv(), reference, envelope.getKeyVersion());
    }

    /**
     * 按持久化 context schema 重建精确认证上下文。
     */
    private WrappingContext buildContextFromEnvelope(FileKeyEnvelope envelope) {
        return buildWrappingContext(
                envelope.getTenantId(), envelope.getFileId(), envelope.getFileHash(),
                envelope.getRecipientType(), envelope.getRecipientId(), envelope.getKeyVersion(),
                envelope.getAlgorithmSuite(), envelope.getContextSchema());
    }

    /**
     * Parses ISO deprecation metadata from file_param into a persistence date.
     */
    private Date parseDeprecatedAfter(String deprecatedAfter) {
        if (!StringUtils.hasText(deprecatedAfter)) {
            return null;
        }
        try {
            return Date.from(java.time.Instant.parse(deprecatedAfter));
        } catch (RuntimeException e) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "密码套件废弃时间格式无效");
        }
    }

    /**
     * Returns a defensive copy of the source date.
     */
    private Date cloneDate(Date source) {
        return source == null ? null : new Date(source.getTime());
    }

    /**
     * Requires the persisted suite field that participates in envelope AAD before unwrap.
     */
    private void validatePersistedEnvelopeMetadata(FileKeyEnvelope envelope) {
        if (envelope == null || !StringUtils.hasText(envelope.getAlgorithmSuite())
                || !StringUtils.hasText(envelope.getKmsProvider())
                || envelope.getProviderContractVersion() == null || envelope.getProviderContractVersion() <= 0
                || !StringUtils.hasText(envelope.getKmsKeyId())
                || !StringUtils.hasText(envelope.getProviderKeyVersion())
                || !StringUtils.hasText(envelope.getWrappingAlgorithm())
                || !StringUtils.hasText(envelope.getContextSchema())
                || !StringUtils.hasText(envelope.getEncryptedDataKey())
                || !StringUtils.hasText(envelope.getAadHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件密钥信封 provider metadata 不完整");
        }
        suitePolicy.validatePersistedEnvelopeForRead(envelope);
    }

    /**
     * Writes a key operation audit record from an existing envelope.
     */
    private void audit(FileKeyEnvelope envelope,
                       String operation,
                       Long actorId,
                       String result,
                       String reason,
                       KeyWrappingFailureCategory failureCategory) {
        FileKeyAuditLog auditLog = baseAudit(
                envelope.getTenantId(), envelope.getFileId(), envelope.getFileHash(), envelope.getRecipientType(),
                envelope.getRecipientId(), envelope.getKeyVersion(), operation, actorId, result, reason)
                .setKmsProvider(envelope.getKmsProvider())
                .setProviderContractVersion(envelope.getProviderContractVersion())
                .setProviderKeyVersion(envelope.getProviderKeyVersion())
                .setKeyIdFingerprint(fingerprint(envelope.getKmsKeyId()))
                .setWrappingAlgorithm(envelope.getWrappingAlgorithm())
                .setAlgorithmSuite(envelope.getAlgorithmSuite())
                .setFailureCategory(categoryName(failureCategory));
        fileKeyAuditLogMapper.insert(auditLog);
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
                       KeyWrappingFailureCategory failureCategory) {
        FileKeyAuditLog auditLog = baseAudit(tenantId, fileId, fileHash, recipientType, recipientId,
                keyVersion, operation, actorId, result, reason)
                .setFailureCategory(categoryName(failureCategory));
        fileKeyAuditLogMapper.insert(auditLog);
    }

    /**
     * 创建不含 provider secret 的基础密钥审计记录。
     */
    private FileKeyAuditLog baseAudit(Long tenantId,
                                      Long fileId,
                                      String fileHash,
                                      String recipientType,
                                      Long recipientId,
                                      Integer keyVersion,
                                      String operation,
                                      Long actorId,
                                      String result,
                                      String reason) {
        return new FileKeyAuditLog()
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
                .setErrorMessage(null)
                .setDeleted(0);
    }

    /**
     * 返回可持久化的稳定失败分类名称。
     */
    private String categoryName(KeyWrappingFailureCategory category) {
        return (category == null ? KeyWrappingFailureCategory.NONE : category).name();
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
        return properties.getEncryptionAlgorithm();
    }

    /**
     * 读取并规范化 file_param 中显式声明的算法套件，未知值交由策略服务拒绝。
     */
    private String resolveExplicitAlgorithmSuite(Map<String, Object> params) {
        Object value = params.get(FIELD_ALGORITHM_SUITE);
        return value instanceof String suite && StringUtils.hasText(suite) ? suite.trim() : null;
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
     * Resolves tenant from the friend-share row and falls back to current tenant context.
     */
    private Long resolveTenantId(FriendFileShare share) {
        return share.getTenantId() != null ? share.getTenantId() : TenantContext.getTenantId();
    }

    /**
     * 计算 provider key reference 指纹，审计中不保存原始 key id。
     */
    private String fingerprint(String keyId) {
        if (!StringUtils.hasText(keyId)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(keyId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "密钥引用指纹计算失败");
        }
    }
}
