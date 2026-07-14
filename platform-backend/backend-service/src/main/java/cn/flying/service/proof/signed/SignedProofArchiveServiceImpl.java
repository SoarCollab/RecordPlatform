package cn.flying.service.proof.signed;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationLeaf;
import cn.flying.dao.entity.ProofBundleIssuance;
import cn.flying.dao.entity.ProofSigningKeyRecord;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.mapper.AttestationLeafMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.ProofBundleIssuanceMapper;
import cn.flying.dao.mapper.ProofSigningKeyMapper;
import cn.flying.dao.vo.file.ProofSigningKeyVO;
import cn.flying.dao.vo.file.ProofStatusVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.attestation.AttestationBatchPersistenceService;
import cn.flying.service.attestation.AttestationConfirmationReceiptValidator;
import cn.flying.service.attestation.ContractRegistryEntryValidator;
import cn.flying.service.attestation.MerkleProofNode;
import cn.flying.service.attestation.MerkleTreeService;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.remote.FileRemoteClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 从可信 file/manifest/Merkle/chain registry 快照生成并治理签名 proof ZIP。
 */
@Service
@RequiredArgsConstructor
public class SignedProofArchiveServiceImpl implements SignedProofArchiveService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_INVALID = "INVALID";

    private static final String COMPLETED_STATUS = "COMPLETED";
    private static final String MANIFEST_EVIDENCE = "MANIFEST_HASH";
    private static final String CHUNK_SCHEMA = "record-platform-proof-chunk-manifest.v2";
    private static final String MERKLE_SCHEMA = "record-platform-proof-merkle.v2";
    private static final String CHAIN_SCHEMA = "record-platform-proof-chain-receipt.v2";
    private static final String POLICY_SCHEMA = "record-platform-proof-verification-policy.v2";
    private static final String JWS_SIGNATURE_ALGORITHM = "EdDSA";
    private static final String JCA_SIGNATURE_ALGORITHM = "Ed25519";
    private static final int MAX_MANIFEST_CHUNKS = 128;
    private static final int MAX_CONCURRENT_EXPORTS = 8;
    private static final int MAX_ISSUANCE_TRANSACTION_ATTEMPTS = 3;
    private static final long MAX_STORAGE_VALIDATION_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final ExecutorService STORAGE_VALIDATION_EXECUTOR = new ThreadPoolExecutor(
            MAX_CONCURRENT_EXPORTS,
            MAX_CONCURRENT_EXPORTS,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            Thread.ofPlatform().daemon(true).name("proof-storage-head-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private static final int MAX_MERKLE_PROOF_NODES = 64;
    private static final int MAX_MERKLE_PROOF_JSON_CHARS = 32 * 1024;
    private static final int MAX_PERSISTED_MANIFEST_CHARS = 1024 * 1024;
    private static final String INVALID_SNAPSHOT_REASON = "immutable_snapshot_validation_failed";
    private static final Pattern SHA256_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern PROOF_ID_PATTERN = Pattern.compile("^rp-proof-[0-9a-f]{64}$");
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern EXTERNAL_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,192}$");
    private static final Set<String> PUBLIC_PROOF_STATUS = Set.of(
            STATUS_ACTIVE,
            STATUS_REVOKED,
            STATUS_SUPERSEDED,
            STATUS_INVALID);
    private static final Set<String> ISSUED_PROOF_STATUS = Set.of(
            STATUS_ACTIVE,
            STATUS_SUPERSEDED);
    private static final Set<String> PUBLIC_KEY_STATUS = Set.of("ACTIVE", "RETIRED");

    private static final String README = """
            # RecordPlatform Signed Proof Bundle v2

            1. Verify `issuer-signature.jws` as compact JWS EdDSA and require its decoded payload to equal `manifest.json` byte-for-byte.
            2. Verify every evidence entry listed by `manifest.json` using its SHA-256 and exact byte length.
            3. Hash the original file with SHA-256 and compare it with `file.hash` and `chunk-manifest.json.contentHash`.
            4. Re-split the original bytes by ordered chunk sizes and compare each `plainHash`; inspect `cipherHash` as stored-object evidence.
            5. Recompute the Merkle leaf from `merkle-proof.json.evidenceHash`, apply the ordered proof path, and compare the result with the batch chain root.
            6. Enforce the signed chain receipt matrix: `batchChainRoot` is exactly 64 hexadecimal characters without `0x`; `CHAIN_WRITE` requires a 64-hex transaction hash with optional `0x`; `CHAIN_QUERY_BEFORE_WRITE` and `CHAIN_QUERY_AFTER_WRITE` require an absent transaction hash.
            7. Validate the immutable `record-platform-contract-registry-entry.v1` Sharing entry, its canonical registry fingerprint, semantic version, chain/group identity, 20-byte address, `ABI-CANONICAL-JSON-SHA256-V1` fingerprints, paired deployment transaction/block, effective time, ACTIVE/DEPRECATED status, and `REDEPLOY_ADDRESS` strategy.
            8. The signed `issuedStatus` is only ACTIVE or SUPERSEDED. Query `statusLocation` for the current ACTIVE, REVOKED, SUPERSEDED, or INVALID state. INVALID is terminal and is used only when a previously persisted canonical manifest, JWS, signing-key identity, or immutable issuance snapshot deterministically drifts; its reason is `immutable_snapshot_validation_failed`. Storage, Merkle, registry, or receipt dependency/read failures reject only the current export and do not change lifecycle state.

            Evidence schemas are `record-platform-proof-chunk-manifest.v2`, `record-platform-proof-merkle.v2`, `record-platform-proof-chain-receipt.v2`, and `record-platform-proof-verification-policy.v2`. `verification-policy.json` is signed evidence and contains the machine-readable exact rules.

            The ZIP contains exactly eight STORED entries in fixed order: `manifest.json`, `file.hash`, `chunk-manifest.json`, `merkle-proof.json`, `blockchain-receipt.json`, `issuer-signature.jws`, `verification-policy.json`, and `README.verify.md`. `manifest.json` hashes the six evidence entries; it does not hash itself or `issuer-signature.jws`. Both `file.hash` and `issuer-signature.jws` contain exactly one trailing LF byte.
            """;

    private final FileMapper fileMapper;
    private final AttestationLeafMapper leafMapper;
    private final AttestationBatchMapper batchMapper;
    private final ProofBundleIssuanceMapper issuanceMapper;
    private final ProofSigningKeyMapper signingKeyMapper;
    private final FileRemoteClient fileRemoteClient;
    private final ChunkManifestService chunkManifestService;
    private final AttestationBatchPersistenceService attestationBatchPersistenceService;
    private final MerkleTreeService merkleTreeService;
    private final DeterministicProofArchiveBuilder archiveBuilder;
    private final ProofSigningProvider signingProvider;
    private final ProofCanonicalizer canonicalizer;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final TransactionTemplate transactionTemplate;
    private final Semaphore exportPermits = new Semaphore(MAX_CONCURRENT_EXPORTS, true);
    private volatile LongSupplier nanoTimeSource = System::nanoTime;
    private volatile long storageValidationBudgetNanos = MAX_STORAGE_VALIDATION_NANOS;

    /**
     * 加载当前文件版本的最新 manifest leaf 并导出签名 archive。
     */
    @Override
    public ProofArchive exportByFileId(Long userId, Long fileId) {
        return withExportPermit(() -> {
            signingProvider.requireExportEnabled();
            File file = loadAuthorizedFile(userId, fileId);
            AttestationLeaf leaf = findLatestManifestLeaf(file);
            return exportValidated(file, leaf);
        });
    }

    /**
     * 加载指定 leaf、重新校验其文件权限并导出签名 archive。
     */
    @Override
    public ProofArchive exportByLeafId(Long userId, Long leafId) {
        return withExportPermit(() -> {
            signingProvider.requireExportEnabled();
            Long tenantId = TenantContext.requireTenantId();
            if (leafId == null) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "leafId 不能为空");
            }
            AttestationLeaf leaf = leafMapper.selectOne(new LambdaQueryWrapper<AttestationLeaf>()
                    .eq(AttestationLeaf::getTenantId, tenantId)
                    .eq(AttestationLeaf::getId, leafId)
                    .eq(AttestationLeaf::getDeleted, 0)
                    .last("LIMIT 1"));
            if (leaf == null) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明叶子不存在");
            }
            File file = loadAuthorizedFile(userId, leaf.getFileId());
            return exportValidated(file, leaf);
        });
    }

    /**
     * 以 fail-fast bulkhead 限制完整证据校验并发，防止远程 HEAD 调用被请求重放放大。
     */
    private ProofArchive withExportPermit(Supplier<ProofArchive> operation) {
        if (!exportPermits.tryAcquire()) {
            throw new RetryableException(
                    ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of("reason", "signed proof export capacity exhausted"));
        }
        try {
            return operation.get();
        } finally {
            exportPermits.release();
        }
    }

    /**
     * 幂等撤销已有签发记录；跨租户、非 owner 和不存在的签发记录均失败关闭。
     */
    @Override
    @Transactional
    public ProofStatusVO revokeByLeafId(Long userId, Long leafId, String reason) {
        Long tenantId = TenantContext.requireTenantId();
        if (leafId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "leafId 不能为空");
        }
        AttestationLeaf leaf = leafMapper.selectById(leafId);
        if (leaf == null || !Objects.equals(tenantId, leaf.getTenantId())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明叶子不存在");
        }
        loadAuthorizedFile(userId, leaf.getFileId());

        ProofBundleIssuance issuance = issuanceMapper.selectByLeafForUpdate(tenantId, leafId);
        if (issuance == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE, "证明包尚未签发");
        }
        if (STATUS_REVOKED.equals(issuance.getStatus())) {
            return toStatusVO(issuance);
        }
        if (!ISSUED_PROOF_STATUS.contains(issuance.getStatus())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "当前证明状态不允许撤销");
        }
        if (issuance.getStatusVersion() == null || issuance.getStatusVersion() == Long.MAX_VALUE) {
            throw new GeneralException(ResultEnum.VERSION_CONFLICT, "证明状态版本无效");
        }
        String normalizedReason = normalizeReason(reason, "owner_revoked");
        Date now = new Date();
        int updated = issuanceMapper.update(null, new LambdaUpdateWrapper<ProofBundleIssuance>()
                .eq(ProofBundleIssuance::getTenantId, tenantId)
                .eq(ProofBundleIssuance::getId, issuance.getId())
                .eq(ProofBundleIssuance::getStatusVersion, issuance.getStatusVersion())
                .in(ProofBundleIssuance::getStatus, STATUS_ACTIVE, STATUS_SUPERSEDED)
                .set(ProofBundleIssuance::getStatus, STATUS_REVOKED)
                .set(ProofBundleIssuance::getStatusReason, normalizedReason)
                .set(ProofBundleIssuance::getRevokedAt, now)
                .set(ProofBundleIssuance::getUpdateTime, now)
                .setSql("status_version = status_version + 1"));
        if (updated != 1) {
            throw new GeneralException(ResultEnum.VERSION_CONFLICT, "证明状态并发更新冲突");
        }
        issuance.setStatus(STATUS_REVOKED)
                .setStatusReason(normalizedReason)
                .setRevokedAt(now)
                .setUpdateTime(now)
                .setStatusVersion(issuance.getStatusVersion() + 1);
        return toStatusVO(issuance);
    }

    /**
     * 通过 mapper 上唯一的跨租户公开查询读取 proof 状态。
     */
    @Override
    public ProofStatusVO getPublicStatus(String proofId) {
        String normalizedProofId = normalizeProofId(proofId);
        ProofBundleIssuance issuance = issuanceMapper.selectPublicByProofId(normalizedProofId);
        if (issuance == null || !PUBLIC_PROOF_STATUS.contains(issuance.getStatus())) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE, "证明状态不存在");
        }
        return toStatusVO(issuance);
    }

    /**
     * 从全局唯一 key 注册表读取公开 SPKI，不返回任何租户或文件字段。
     */
    @Override
    public ProofSigningKeyVO getPublicSigningKey(String keyId, Integer keyVersion) {
        if (!StringUtils.hasText(keyId)
                || !KEY_ID_PATTERN.matcher(keyId).matches()
                || keyVersion == null
                || keyVersion <= 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "签名 key 标识不合法");
        }
        ProofSigningKeyRecord keyRecord = signingKeyMapper.selectByIdentity(keyId, keyVersion);
        ProofSigningKeyMetadata key = toKeyMetadata(keyRecord);
        if (keyRecord == null
                || !PUBLIC_KEY_STATUS.contains(keyRecord.getStatus())
                || !hasValidPublicKeyMetadata(key)) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE, "签名公钥不存在");
        }
        return new ProofSigningKeyVO(
                key.keyId(),
                key.keyVersion(),
                key.algorithm(),
                key.publicKeySpki(),
                key.publicKeyFingerprint());
    }

    /**
     * 校验所有证据，首次签发或使用历史签名快照确定性重建 archive。
     */
    private ProofArchive exportValidated(File file, AttestationLeaf leaf) {
        validateLeafBinding(file, leaf);
        ProofBundleIssuance existing = findIssuance(file.getTenantId(), leaf.getId());
        SignedProofBundleModel.Manifest issuedManifest = null;
        if (existing != null) {
            issuedManifest = loadIssuedManifestForEvidence(file, leaf);
        }

        AttestationBatch batch = loadCompletedBatch(file.getTenantId(), leaf.getBatchId());
        ChunkManifestView manifest = loadAndValidateManifest(file, leaf);
        validateStorageEvidence(file, manifest);
        List<MerkleProofNode> proofPath = validateMerkleEvidence(leaf, batch);
        ContractRegistryEntryResponse registry = requireRegistry(batch);
        String externalFileId = issuedManifest == null
                ? IdUtils.toExternalId(file.getId())
                : issuedManifest.fileId();
        SignedProofBundleModel.EvidencePayloads payloads = buildPayloads(
                file,
                leaf,
                batch,
                manifest,
                proofPath,
                registry,
                externalFileId);

        if (existing != null) {
            return rebuildExisting(file, leaf, batch, payloads);
        }
        return issueNew(file, leaf, batch, payloads);
    }

    /**
     * 在版本链共享锁事务内首次签发，并对 MySQL 瞬态锁竞争执行有界整事务重试。
     */
    private ProofArchive issueNew(
            File file,
            AttestationLeaf leaf,
            AttestationBatch batch,
            SignedProofBundleModel.EvidencePayloads payloads
    ) {
        int remainingAttempts = MAX_ISSUANCE_TRANSACTION_ATTEMPTS;
        while (true) {
            try {
                ProofFinalizationOutcome outcome = transactionTemplate.execute(status ->
                        issueNewLocked(file, leaf, batch, payloads));
                return requireSuccessfulOutcome(outcome);
            } catch (TransientDataAccessException contention) {
                remainingAttempts--;
                if (remainingAttempts == 0) {
                    throw new RetryableException(
                            ResultEnum.SERVICE_UNAVAILABLE,
                            Map.of("reason", "signed proof issuance database contention"));
                }
            }
        }
    }

    /**
     * 在版本链与签发行锁内解析历史 manifest，确保 INVALID 推进不依赖事务外旧快照。
     */
    private SignedProofBundleModel.Manifest loadIssuedManifestForEvidence(
            File file,
            AttestationLeaf leaf
    ) {
        ProofManifestLoadOutcome outcome = transactionTemplate.execute(status -> {
            lockVersionGroupForProofLifecycle(file);
            ProofBundleIssuance current = issuanceMapper.selectByLeafForUpdate(
                    file.getTenantId(), leaf.getId());
            if (current == null) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明记录不存在");
            }
            current = synchronizeSupersededStatus(file, current);
            requireExportableStatus(current);
            try {
                return new ProofManifestLoadOutcome(parseIssuedManifest(current), null);
            } catch (GeneralException deterministicFailure) {
                markInvalidLocked(current);
                return new ProofManifestLoadOutcome(null, deterministicFailure);
            }
        });
        if (outcome == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明 manifest 事务未返回结果");
        }
        if (outcome.failure() != null) {
            throw outcome.failure();
        }
        if (outcome.manifest() == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明 manifest 事务缺少解析结果");
        }
        return outcome.manifest();
    }

    /**
     * 持有版本链锚点锁后重查并插入唯一签发记录。
     */
    private ProofFinalizationOutcome issueNewLocked(
            File file,
            AttestationLeaf leaf,
            AttestationBatch batch,
            SignedProofBundleModel.EvidencePayloads payloads
    ) {
        lockVersionGroupForProofLifecycle(file);
        ProofBundleIssuance concurrent = issuanceMapper.selectByLeafForUpdate(
                file.getTenantId(), leaf.getId());
        if (concurrent != null) {
            concurrent = synchronizeSupersededStatus(file, concurrent);
            requireExportableStatus(concurrent);
            return rebuildExistingLocked(file, leaf, batch, payloads, concurrent);
        }

        Date issuedAt = new Date();
        String issuedStatus = hasNewerSuccessfulVersion(file)
                ? STATUS_SUPERSEDED
                : STATUS_ACTIVE;
        String proofId = deriveProofId(file, leaf);
        ProofSigningKeyMetadata key = signingProvider.currentKey();
        registerKeyIdentity(key, issuedAt);
        String externalFileId = payloads.chunkManifest().fileId();
        String externalLeafId = IdUtils.toExternalId(leaf.getId());
        SignedProofBundleModel.ManifestSeed seed = manifestSeed(
                batch,
                proofId,
                externalFileId,
                externalLeafId,
                file.getVersion(),
                issuedAt,
                issuedStatus);
        String fileName = archiveFileName(externalFileId, file.getVersion());
        ProofArchive archive = archiveBuilder.buildNew(fileName, seed, payloads, key);

        byte[] manifestBytes = archive.entries().getFirst().bytes();
        ProofBundleIssuance issuance = new ProofBundleIssuance()
                .setId(snowflakeIdGenerator.nextId())
                .setTenantId(file.getTenantId())
                .setProofId(proofId)
                .setFileId(file.getId())
                .setFileVersion(file.getVersion())
                .setLeafId(leaf.getId())
                .setManifestHash(archive.manifestHash())
                .setManifestJson(new String(manifestBytes, StandardCharsets.UTF_8))
                .setSignatureJws(archive.compactJws())
                .setSignatureAlgorithm(key.algorithm())
                .setKeyId(key.keyId())
                .setKeyVersion(key.keyVersion())
                .setPublicKeySpki(key.publicKeySpki())
                .setPublicKeyFingerprint(key.publicKeyFingerprint())
                .setIssuedStatus(issuedStatus)
                .setStatus(issuedStatus)
                .setStatusVersion(1L)
                .setIssuedAt(cloneDate(issuedAt))
                .setDeleted(0);
        try {
            if (issuanceMapper.insert(issuance) != 1) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明包签发记录写入失败");
            }
            return new ProofFinalizationOutcome(archive, null);
        } catch (DuplicateKeyException concurrentInsert) {
            concurrent = issuanceMapper.selectByLeafForUpdate(file.getTenantId(), leaf.getId());
            if (concurrent == null) {
                throw new GeneralException(ResultEnum.VERSION_CONFLICT, "证明包并发签发冲突");
            }
            concurrent = synchronizeSupersededStatus(file, concurrent);
            requireExportableStatus(concurrent);
            return rebuildExistingLocked(file, leaf, batch, payloads, concurrent);
        }
    }

    /**
     * 在短事务内按 anchor -> issuance 顺序重查在线状态并重建历史 proof。
     */
    private ProofArchive rebuildExisting(
            File file,
            AttestationLeaf leaf,
            AttestationBatch batch,
            SignedProofBundleModel.EvidencePayloads payloads
    ) {
        ProofFinalizationOutcome outcome = transactionTemplate.execute(status -> {
            lockVersionGroupForProofLifecycle(file);
            ProofBundleIssuance current = issuanceMapper.selectByLeafForUpdate(
                    file.getTenantId(), leaf.getId());
            if (current == null) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明记录不存在");
            }
            current = synchronizeSupersededStatus(file, current);
            requireExportableStatus(current);
            return rebuildExistingLocked(file, leaf, batch, payloads, current);
        });
        return requireSuccessfulOutcome(outcome);
    }

    /**
     * 持有 issuance 行锁时验证不可变快照；确定性失败在同一事务提交 INVALID 后返回。
     */
    private ProofFinalizationOutcome rebuildExistingLocked(
            File file,
            AttestationLeaf leaf,
            AttestationBatch batch,
            SignedProofBundleModel.EvidencePayloads payloads,
            ProofBundleIssuance issuance
    ) {
        try {
            SignedProofBundleModel.Manifest issuedManifest = parseIssuedManifest(issuance);
            validateIssuanceBinding(file, leaf, issuance);
            validateIssuedManifest(file, batch, issuance, issuedManifest);
            ProofSigningKeyMetadata key = new ProofSigningKeyMetadata(
                    issuance.getSignatureAlgorithm(),
                    issuance.getKeyId(),
                    issuance.getKeyVersion(),
                    issuance.getPublicKeySpki(),
                    issuance.getPublicKeyFingerprint());
            requireRegisteredKey(key, PUBLIC_KEY_STATUS);
            SignedProofBundleModel.ManifestSeed seed = manifestSeed(issuedManifest);
            ProofArchive archive = archiveBuilder.rebuild(
                    archiveFileName(issuedManifest.fileId(), file.getVersion()),
                    seed,
                    rebindExternalFileId(payloads, issuedManifest.fileId()),
                    key,
                    issuance.getSignatureJws());
            String manifestJson = new String(archive.entries().getFirst().bytes(), StandardCharsets.UTF_8);
            if (!Objects.equals(issuance.getManifestHash(), archive.manifestHash())
                    || !Objects.equals(issuance.getManifestJson(), manifestJson)
                    || !Objects.equals(issuance.getSignatureJws(), archive.compactJws())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明包不可变快照发生漂移");
            }
            return new ProofFinalizationOutcome(archive, null);
        } catch (GeneralException deterministicFailure) {
            markInvalidLocked(issuance);
            return new ProofFinalizationOutcome(null, deterministicFailure);
        }
    }

    /**
     * 在事务提交后统一抛出确定性失败，确保 INVALID 状态不会因异常回滚。
     */
    private ProofArchive requireSuccessfulOutcome(ProofFinalizationOutcome outcome) {
        if (outcome == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明包事务未返回结果");
        }
        if (outcome.failure() != null) {
            throw outcome.failure();
        }
        if (outcome.archive() == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明包事务缺少归档结果");
        }
        return outcome.archive();
    }

    /**
     * 加载文件并执行 tenant、owner、删除、版本和 contentHash 校验。
     */
    private File loadAuthorizedFile(Long userId, Long fileId) {
        Long tenantId = TenantContext.requireTenantId();
        if (fileId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "fileId 不能为空");
        }
        File file = fileMapper.selectById(fileId);
        if (file == null || Integer.valueOf(1).equals(file.getDeleted())) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }
        if (!Objects.equals(tenantId, file.getTenantId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不属于当前租户");
        }
        if (!SecurityUtils.isAdmin() && (userId == null || !Objects.equals(userId, file.getUid()))) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权导出此文件证明包");
        }
        if (!Objects.equals(FileUploadStatus.SUCCESS.getCode(), file.getStatus())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "仅成功上传的文件版本可导出证明包");
        }
        if (file.getVersion() == null || file.getVersion() <= 0) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件版本无效");
        }
        validatePersistedContentHash(file);
        if (!StringUtils.hasText(file.getFileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件缺少 chainRecordId");
        }
        return file;
    }

    /**
     * 交叉校验结构化列与上传时写入的受保护 fileParam，阻断 contentHash 被链 ID 或其他摘要覆盖。
     */
    private void validatePersistedContentHash(File file) {
        requireSha256(file.getContentHash(), "文件缺少可信 contentHash，历史文件需重传或受审计迁移");
        Map<?, ?> fileParam;
        try {
            fileParam = JsonConverter.parse(file.getFileParam(), Map.class);
        } catch (RuntimeException e) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件 contentHash 来源记录不可解析");
        }
        Object sourceValue = fileParam == null ? null : fileParam.get("contentHash");
        String sourceHash = sourceValue instanceof String text
                ? text.trim().toLowerCase(Locale.ROOT)
                : "";
        if (!SHA256_PATTERN.matcher(sourceHash).matches()
                || !sourceHash.equals(file.getContentHash().trim().toLowerCase(Locale.ROOT))) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件 contentHash 与上传来源记录不一致");
        }
    }

    /**
     * 查找当前文件版本最新的 production manifest leaf。
     */
    private AttestationLeaf findLatestManifestLeaf(File file) {
        AttestationLeaf leaf = leafMapper.selectOne(new LambdaQueryWrapper<AttestationLeaf>()
                .eq(AttestationLeaf::getTenantId, file.getTenantId())
                .eq(AttestationLeaf::getFileId, file.getId())
                .eq(AttestationLeaf::getFileVersion, file.getVersion())
                .eq(AttestationLeaf::getEvidenceType, MANIFEST_EVIDENCE)
                .eq(AttestationLeaf::getDeleted, 0)
                .orderByDesc(AttestationLeaf::getCreateTime)
                .orderByDesc(AttestationLeaf::getId)
                .last("LIMIT 1"));
        if (leaf == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件缺少 MANIFEST_HASH 批量存证证明");
        }
        return leaf;
    }

    /**
     * 校验 leaf 只把 manifestHash 用作 Merkle evidence，并单独保存 chainRecordId。
     */
    private void validateLeafBinding(File file, AttestationLeaf leaf) {
        if (leaf == null
                || !Objects.equals(file.getTenantId(), leaf.getTenantId())
                || !Objects.equals(file.getId(), leaf.getFileId())
                || !Objects.equals(file.getVersion(), leaf.getFileVersion())
                || !MANIFEST_EVIDENCE.equals(leaf.getEvidenceType())
                || leaf.getManifestId() == null
                || !Objects.equals(file.getFileHash(), leaf.getChainRecordId())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明叶子与文件版本或 chainRecordId 不一致");
        }
        requireSha256(leaf.getEvidenceHash(), "证明叶子 manifestHash 不合法");
    }

    /**
     * 加载已完成 batch 并校验链根确认字段。
     */
    private AttestationBatch loadCompletedBatch(Long tenantId, Long batchId) {
        AttestationBatch batch = batchMapper.selectById(batchId);
        if (batch == null
                || Integer.valueOf(1).equals(batch.getDeleted())
                || !Objects.equals(tenantId, batch.getTenantId())
                || !COMPLETED_STATUS.equals(batch.getStatus())
                || !StringUtils.hasText(batch.getMerkleRoot())
                || !StringUtils.hasText(batch.getChainFileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "批量存证未完成或链根回执缺失");
        }
        if (!AttestationConfirmationReceiptValidator.isValid(
                batch.getConfirmationSource(),
                batch.getChainTransactionHash(),
                batch.getChainFileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "批量存证确认来源或交易回执不可信");
        }
        if (!batch.getMerkleRoot().equalsIgnoreCase(batch.getChainFileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "批量 Merkle 根与链上回执不一致");
        }
        return batch;
    }

    /**
     * 加载 active manifest 并重算 canonical manifestHash。
     */
    private ChunkManifestView loadAndValidateManifest(File file, AttestationLeaf leaf) {
        ChunkManifestView manifest = chunkManifestService.findActiveManifest(null, file.getId())
                .orElseThrow(() -> new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件缺少 active chunk manifest"));
        if (!Objects.equals(leaf.getManifestId(), manifest.manifestId())
                || !Objects.equals(file.getVersion(), manifest.fileVersion())
                || !Objects.equals(file.getFileHash(), manifest.fileHash())
                || !leaf.getEvidenceHash().equalsIgnoreCase(manifest.manifestHash())
                || manifest.chunks() == null
                || manifest.chunks().isEmpty()
                || manifest.chunks().size() > MAX_MANIFEST_CHUNKS
                || !Objects.equals(manifest.chunkCount(), manifest.chunks().size())
                || file.getFileSize() == null
                || file.getFileSize() != manifest.totalSize()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "chunk manifest 与证明叶子或文件版本不一致");
        }
        validateManifestChunkStructure(manifest);
        ChunkManifestDraft draft = new ChunkManifestDraft(
                manifest.schemaId(),
                manifest.fileHash(),
                manifest.hashAlgorithm(),
                manifest.chunkSize(),
                manifest.totalSize(),
                manifest.merkleRoot(),
                manifest.encryptionAlgorithm(),
                manifest.storageBackend(),
                manifest.chunks());
        String calculated = chunkManifestService.calculateManifestHash(draft);
        if (!manifest.manifestHash().equalsIgnoreCase(calculated)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "chunk manifest canonical hash 不一致");
        }
        return manifest;
    }

    /**
     * 在签名和远程 HEAD 前校验持久化分片的非空、顺序、正长度与聚合大小语义。
     *
     * @param manifest 待签发的持久化 manifest
     */
    private void validateManifestChunkStructure(ChunkManifestView manifest) {
        long aggregateSize = 0L;
        for (int position = 0; position < manifest.chunks().size(); position++) {
            ChunkManifestChunk chunk = manifest.chunks().get(position);
            if (chunk == null || chunk.index() != position || chunk.size() <= 0) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "chunk manifest 分片结构不合法");
            }
            try {
                aggregateSize = Math.addExact(aggregateSize, chunk.size());
            } catch (ArithmeticException e) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "chunk manifest 分片大小溢出");
            }
        }
        if (aggregateSize != manifest.totalSize()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "chunk manifest 分片总长度不一致");
        }
    }

    /**
     * 对每个 manifest chunk 重新执行 storage HEAD 一致性校验。
     */
    private void validateStorageEvidence(File file, ChunkManifestView manifest) {
        long startedAt = nanoTimeSource.getAsLong();
        long deadline = startedAt + storageValidationBudgetNanos;
        for (ChunkManifestChunk chunk : manifest.chunks()) {
            requireSha256(chunk.plainHash(), "分片 plainHash 不合法");
            requireSha256(chunk.cipherHash(), "分片 cipherHash 不合法");
            Result<StorageObjectHeadVO> result = headObjectWithinStorageBudget(chunk, deadline);
            requireStorageValidationBudget(deadline, chunk.index());
            StorageObjectHeadVO head = result != null && result.isSuccess() ? result.getData() : null;
            if (head == null
                    || !head.exists()
                    || !Objects.equals(chunk.storagePath(), head.filePath())
                    || !equalsHash(chunk.cipherHash(), head.fileHash())
                    || !equalsHash(chunk.cipherHash(), head.metadataHash())
                    || !Objects.equals(file.getTenantId(), head.tenantId())
                    || !Objects.equals(file.getTenantId(), head.metadataTenantId())
                    || !Objects.equals(chunk.size(), head.contentLength())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "存储分片 HEAD 证据不一致: " + chunk.index());
            }
        }
    }

    /**
     * 在总 deadline 内执行单次 storage HEAD；超时或执行器饱和均作为可重试依赖故障。
     *
     * @param chunk 当前 manifest 分片
     * @param deadline 本次导出的单调时钟截止点
     * @return deadline 内返回的远程 HEAD 结果
     */
    private Result<StorageObjectHeadVO> headObjectWithinStorageBudget(
            ChunkManifestChunk chunk,
            long deadline
    ) {
        long remaining = requireStorageValidationBudget(deadline, chunk.index());
        Future<Result<StorageObjectHeadVO>> future;
        try {
            future = STORAGE_VALIDATION_EXECUTOR.submit(() -> fileRemoteClient.headObject(
                    chunk.storagePath(),
                    chunk.cipherHash()));
        } catch (RejectedExecutionException e) {
            throw storageValidationUnavailable(
                    "signed proof storage validation capacity exhausted", chunk.index());
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw storageValidationUnavailable(
                    "signed proof storage validation timed out", chunk.index());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw storageValidationUnavailable(
                    "signed proof storage validation interrupted", chunk.index());
        } catch (ExecutionException e) {
            requireStorageValidationBudget(deadline, chunk.index());
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Storage HEAD execution failed", cause);
        }
    }

    /**
     * 返回 storage 校验剩余纳秒数，并在达到或超过 deadline 时失败关闭。
     *
     * @param deadline 本次导出的单调时钟截止点
     * @param chunkIndex 当前分片序号
     * @return 严格为正的剩余纳秒数
     */
    private long requireStorageValidationBudget(long deadline, int chunkIndex) {
        long remaining = deadline - nanoTimeSource.getAsLong();
        if (remaining <= 0L) {
            throw storageValidationUnavailable(
                    "signed proof storage validation timed out", chunkIndex);
        }
        return remaining;
    }

    /**
     * 构造统一的 storage 可重试失败，避免依赖超时被误标为永久证据损坏。
     */
    private RetryableException storageValidationUnavailable(String reason, int chunkIndex) {
        return new RetryableException(
                ResultEnum.SERVICE_UNAVAILABLE,
                Map.of("reason", reason, "chunk", chunkIndex));
    }

    /**
     * 解析、重算并验证 leaf hash、proof path 和 batch root。
     */
    private List<MerkleProofNode> validateMerkleEvidence(AttestationLeaf leaf, AttestationBatch batch) {
        if (!MerkleTreeService.PROOF_ALGORITHM.equals(leaf.getProofAlgorithm())
                || !Objects.equals(batch.getProofAlgorithm(), leaf.getProofAlgorithm())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "Merkle proof 算法不一致");
        }
        if (leaf.getProofPathJson() == null
                || leaf.getProofPathJson().length() > MAX_MERKLE_PROOF_JSON_CHARS) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "Merkle proof path 大小不合法");
        }
        List<MerkleProofNode> proofPath;
        try {
            proofPath = JsonConverter.parse(
                    leaf.getProofPathJson(),
                    new TypeReference<List<MerkleProofNode>>() {
                    });
        } catch (RuntimeException e) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "Merkle proof path 不可解析");
        }
        if (proofPath == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "Merkle proof path 不可解析");
        }
        proofPath = List.copyOf(proofPath);
        if (proofPath.size() > MAX_MERKLE_PROOF_NODES) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "Merkle proof path 节点数超过限制");
        }
        String calculatedLeaf = merkleTreeService.calculateLeafHash(leaf.getEvidenceHash());
        if (!equalsHash(calculatedLeaf, leaf.getLeafHash())
                || !merkleTreeService.verifyProof(calculatedLeaf, proofPath, batch.getMerkleRoot())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "Merkle leaf 或 proof path 校验失败");
        }
        return proofPath;
    }

    /**
     * 读取并验证签发批次绑定的不可变合约注册表快照。
     */
    private ContractRegistryEntryResponse requireRegistry(AttestationBatch batch) {
        ContractRegistryEntryResponse registry;
        try {
            registry = attestationBatchPersistenceService.requireContractRegistry(batch);
        } catch (RuntimeException e) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "合约注册表快照校验失败");
        }
        if (registry == null
                || !ContractRegistryEntryValidator.isValidIssuableSharingRegistry(registry)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "合约注册表快照不可用于签发");
        }
        return registry;
    }

    /**
     * 映射所有明确拆分语义的 ZIP 证据 payload。
     */
    private SignedProofBundleModel.EvidencePayloads buildPayloads(
            File file,
            AttestationLeaf leaf,
            AttestationBatch batch,
            ChunkManifestView manifest,
            List<MerkleProofNode> proofPath,
            ContractRegistryEntryResponse registry,
            String externalFileId
    ) {
        List<SignedProofBundleModel.ChunkEvidence> chunks = manifest.chunks().stream()
                .map(chunk -> new SignedProofBundleModel.ChunkEvidence(
                        chunk.index(),
                        chunk.plainHash(),
                        chunk.cipherHash(),
                        chunk.size(),
                        chunk.storagePath(),
                        chunk.checksumAlgorithm()))
                .toList();
        SignedProofBundleModel.ChunkManifestEvidence chunkEvidence =
                new SignedProofBundleModel.ChunkManifestEvidence(
                        CHUNK_SCHEMA,
                        externalFileId,
                        file.getVersion(),
                        normalizeSha256(file.getContentHash()),
                        leaf.getChainRecordId(),
                        normalizeSha256(manifest.manifestHash()),
                        manifest.schemaId(),
                        manifest.hashAlgorithm(),
                        manifest.chunkSize(),
                        manifest.chunkCount(),
                        manifest.totalSize(),
                        manifest.encryptionAlgorithm(),
                        manifest.storageBackend(),
                        chunks);

        List<SignedProofBundleModel.ProofNode> proofNodes = proofPath.stream()
                .map(node -> new SignedProofBundleModel.ProofNode(node.position(), node.hash()))
                .toList();
        SignedProofBundleModel.MerkleProofEvidence merkleEvidence =
                new SignedProofBundleModel.MerkleProofEvidence(
                        MERKLE_SCHEMA,
                        leaf.getEvidenceType(),
                        normalizeSha256(leaf.getEvidenceHash()),
                        leaf.getProofAlgorithm(),
                        batch.getMerkleRoot(),
                        leaf.getLeafHash(),
                        leaf.getLeafIndex(),
                        proofNodes);

        SignedProofBundleModel.ContractRegistryEvidence registryEvidence =
                new SignedProofBundleModel.ContractRegistryEvidence(
                        registry.schemaVersion(),
                        registry.registryFingerprint(),
                        registry.contractName(),
                        registry.semanticVersion(),
                        registry.chainType(),
                        registry.chainId(),
                        registry.groupId(),
                        registry.contractAddress(),
                        registry.abiFingerprintAlgorithm(),
                        registry.abiSha256(),
                        registry.artifactBytecodeSha256(),
                        registry.onChainCodeSha256(),
                        registry.deploymentTransactionHash(),
                        registry.deploymentBlockNumber(),
                        registry.status(),
                        registry.effectiveAt(),
                        registry.upgradeStrategy());
        SignedProofBundleModel.BlockchainReceiptEvidence chainEvidence =
                new SignedProofBundleModel.BlockchainReceiptEvidence(
                        CHAIN_SCHEMA,
                        leaf.getChainRecordId(),
                        file.getTransactionHash(),
                        batch.getChainTransactionHash(),
                        batch.getChainFileHash(),
                        batch.getConfirmationSource(),
                        registryEvidence);

        SignedProofBundleModel.VerificationPolicyEvidence policy =
                new SignedProofBundleModel.VerificationPolicyEvidence(
                        POLICY_SCHEMA,
                        List.of(CHUNK_SCHEMA, MERKLE_SCHEMA, CHAIN_SCHEMA, POLICY_SCHEMA),
                        "SHA-256",
                        "file.hash = 'sha256:' + lowercase(hex(sha256(originalFileBytes)))",
                        "manifestHash = 'sha256:' + lowercase(hex(sha256(canonical source manifest JSON)))",
                        "lowercase(hex(sha256(utf8('leaf\\n' + evidenceHash.trim()))))",
                        "lowercase(hex(sha256(utf8('node\\n' + leftHash.trim() + '\\n' + rightHash.trim()))))",
                        "apply proofPath from leaf to root; LEFT prepends sibling and RIGHT appends sibling",
                        new SignedProofBundleModel.ChainReceiptPolicy(
                                "^[0-9A-Fa-f]{64}$",
                                "^(?:0x)?[0-9A-Fa-f]{64}$",
                                AttestationConfirmationReceiptValidator.SOURCE_CHAIN_WRITE,
                                List.of(
                                        AttestationConfirmationReceiptValidator.SOURCE_CHAIN_QUERY_BEFORE_WRITE,
                                        AttestationConfirmationReceiptValidator.SOURCE_CHAIN_QUERY_AFTER_WRITE),
                                "write source requires a matching transaction hash",
                                "query sources require transaction hash to be absent"),
                        new SignedProofBundleModel.ContractRegistryPolicy(
                                "record-platform-contract-registry-entry.v1",
                                "Sharing",
                                "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$",
                                List.of("LOCAL_FISCO", "BSN_FISCO", "BSN_BESU"),
                                List.of("LOCAL_FISCO", "BSN_FISCO"),
                                "groupId is required for FISCO chain types and absent for BSN_BESU",
                                "^0x[0-9a-f]{40}$",
                                "ABI-CANONICAL-JSON-SHA256-V1",
                                "^sha256:[0-9a-f]{64}$",
                                "deploymentTransactionHash and non-negative deploymentBlockNumber are both present or both absent",
                                List.of("ACTIVE", "DEPRECATED"),
                                "RFC3339 offset date-time not later than verification time",
                                "REDEPLOY_ADDRESS",
                                "'sha256:' + lowercase(hex(sha256(utf8(join ordered field=value lines with LF))))",
                                List.of(
                                        "schemaVersion",
                                        "contractName",
                                        "semanticVersion",
                                        "chainType",
                                        "chainId",
                                        "groupId",
                                        "contractAddress",
                                        "abiFingerprintAlgorithm",
                                        "abiSha256",
                                        "artifactBytecodeSha256",
                                        "onChainCodeSha256",
                                        "deploymentTransactionHash",
                                        "deploymentBlockNumber",
                                        "status",
                                        "effectiveAt",
                                        "upgradeStrategy")),
                        "JWS compact serialization with EdDSA; decoded payload must equal manifest.json bytes",
                        "issuedStatus is ACTIVE or SUPERSEDED; current status comes from statusLocation; INVALID is terminal only for deterministic persisted immutable snapshot drift with reason immutable_snapshot_validation_failed; dependency/read validation failures do not change lifecycle state",
                        "exactly eight STORED entries, fixed order, fixed timestamp, no nested or additional entries",
                        "UTF-8 unless otherwise stated; file.hash and ASCII issuer-signature.jws each end with exactly one LF byte");
        return new SignedProofBundleModel.EvidencePayloads(
                normalizeSha256(file.getContentHash()),
                chunkEvidence,
                merkleEvidence,
                chainEvidence,
                policy,
                README);
    }

    /**
     * 使用首次签发时已解析的公开标识构建不可变 manifest seed。
     */
    private SignedProofBundleModel.ManifestSeed manifestSeed(
            AttestationBatch batch,
            String proofId,
            String externalFileId,
            String externalLeafId,
            Integer fileVersion,
            Date issuedAt,
            String issuedStatus
    ) {
        requireExternalId(externalFileId, "签发文件公开标识不合法");
        requireExternalId(externalLeafId, "签发叶子公开标识不合法");
        return new SignedProofBundleModel.ManifestSeed(
                proofId,
                externalFileId,
                fileVersion,
                externalLeafId,
                batch.getBatchNo(),
                issuedAt.toInstant().truncatedTo(ChronoUnit.MILLIS).toString(),
                issuedStatus,
                "/api/v1/public/proofs/" + proofId + "/status");
    }

    /**
     * 从已签发 canonical manifest 恢复原始 seed，避免 ID codec 轮换改变历史字节。
     */
    private SignedProofBundleModel.ManifestSeed manifestSeed(SignedProofBundleModel.Manifest manifest) {
        return new SignedProofBundleModel.ManifestSeed(
                manifest.proofId(),
                manifest.fileId(),
                manifest.fileVersion(),
                manifest.leafId(),
                manifest.batchNo(),
                manifest.issuedAt(),
                manifest.issuedStatus(),
                manifest.statusLocation());
    }

    /**
     * 严格解析 canonical manifest，拒绝超限、非 canonical 或带尾随 token 的历史快照。
     */
    private SignedProofBundleModel.Manifest parseIssuedManifest(ProofBundleIssuance issuance) {
        String manifestJson = issuance == null ? null : issuance.getManifestJson();
        if (manifestJson == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明 manifest 大小不合法");
        }
        if (manifestJson.isBlank()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明 manifest 大小不合法");
        }
        if (manifestJson.length() > MAX_PERSISTED_MANIFEST_CHARS) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明 manifest 大小不合法");
        }
        byte[] persistedBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
        if (persistedBytes.length > MAX_PERSISTED_MANIFEST_CHARS) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明 manifest 大小不合法");
        }
        SignedProofBundleModel.Manifest manifest = canonicalizer.parseManifest(manifestJson);
        byte[] canonicalBytes = canonicalizer.canonicalBytes(manifest);
        if (!java.security.MessageDigest.isEqual(persistedBytes, canonicalBytes)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明 manifest 不是 canonical JSON");
        }
        return manifest;
    }

    /**
     * 交叉校验历史 manifest、签发记录和当前不可变业务主键，拒绝快照字段漂移。
     */
    private void validateIssuedManifest(
            File file,
            AttestationBatch batch,
            ProofBundleIssuance issuance,
            SignedProofBundleModel.Manifest manifest
    ) {
        SignedProofBundleModel.SignatureMetadata signature = manifest == null ? null : manifest.signature();
        String expectedIssuedAt = issuance.getIssuedAt()
                .toInstant()
                .truncatedTo(ChronoUnit.MILLIS)
                .toString();
        String expectedStatusLocation = "/api/v1/public/proofs/" + issuance.getProofId() + "/status";
        String expectedKeyLocation = "/api/v1/public/proof-keys/"
                + issuance.getKeyId()
                + "/versions/"
                + issuance.getKeyVersion();
        if (manifest == null
                || !DeterministicProofArchiveBuilder.MANIFEST_SCHEMA.equals(manifest.schemaVersion())
                || !Objects.equals(issuance.getProofId(), manifest.proofId())
                || !Objects.equals(file.getVersion(), manifest.fileVersion())
                || !Objects.equals(issuance.getFileVersion(), manifest.fileVersion())
                || !Objects.equals(batch.getBatchNo(), manifest.batchNo())
                || !Objects.equals(expectedIssuedAt, manifest.issuedAt())
                || !Objects.equals(issuance.getIssuedStatus(), manifest.issuedStatus())
                || !Objects.equals(expectedStatusLocation, manifest.statusLocation())
                || signature == null
                || !Objects.equals(issuance.getSignatureAlgorithm(), signature.algorithm())
                || !Objects.equals(issuance.getKeyId(), signature.keyId())
                || !Objects.equals(issuance.getKeyVersion(), signature.keyVersion())
                || !Objects.equals(issuance.getPublicKeyFingerprint(), signature.publicKeyFingerprint())
                || !Objects.equals(expectedKeyLocation, signature.verificationKeyLocation())
                || manifest.entries() == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明 manifest 与签发记录不一致");
        }
        requireExternalId(manifest.fileId(), "已签发文件公开标识不合法");
        requireExternalId(manifest.leafId(), "已签发叶子公开标识不合法");
    }

    /**
     * 使用签发时公开 fileId 重建 chunk manifest，隔离后续 ID codec 轮换。
     */
    private SignedProofBundleModel.EvidencePayloads rebindExternalFileId(
            SignedProofBundleModel.EvidencePayloads payloads,
            String externalFileId
    ) {
        requireExternalId(externalFileId, "已签发文件公开标识不合法");
        SignedProofBundleModel.ChunkManifestEvidence source = payloads.chunkManifest();
        SignedProofBundleModel.ChunkManifestEvidence rebound =
                new SignedProofBundleModel.ChunkManifestEvidence(
                        source.schemaVersion(),
                        externalFileId,
                        source.fileVersion(),
                        source.contentHash(),
                        source.chainRecordId(),
                        source.manifestHash(),
                        source.sourceSchema(),
                        source.hashAlgorithm(),
                        source.chunkSize(),
                        source.chunkCount(),
                        source.totalSize(),
                        source.encryptionAlgorithm(),
                        source.storageBackend(),
                        source.chunks());
        return new SignedProofBundleModel.EvidencePayloads(
                payloads.contentHash(),
                rebound,
                payloads.merkleProof(),
                payloads.blockchainReceipt(),
                payloads.verificationPolicy(),
                payloads.readme());
    }

    /**
     * 从租户/file/leaf/version 固定 namespace 派生不暴露原始 ID 的公开 proofId。
     */
    private String deriveProofId(File file, AttestationLeaf leaf) {
        String input = "record-platform-proof-id.v1\n"
                + file.getTenantId() + "\n"
                + file.getId() + "\n"
                + file.getVersion() + "\n"
                + leaf.getId();
        return "rp-proof-" + canonicalizer.sha256(input).substring(ProofCanonicalizer.HASH_PREFIX.length());
    }

    /**
     * 生成仅由签发时外部 fileId 和正整数版本组成的固定下载名。
     */
    private String archiveFileName(String externalFileId, Integer fileVersion) {
        requireExternalId(externalFileId, "证明包下载文件标识不合法");
        if (fileVersion == null || fileVersion <= 0) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明包下载文件版本不合法");
        }
        return "record-proof-" + externalFileId + "-" + fileVersion + ".zip";
    }

    /**
     * 查找当前 tenant/leaf 的唯一签发记录。
     */
    private ProofBundleIssuance findIssuance(Long tenantId, Long leafId) {
        return issuanceMapper.selectOne(new LambdaQueryWrapper<ProofBundleIssuance>()
                .eq(ProofBundleIssuance::getTenantId, tenantId)
                .eq(ProofBundleIssuance::getLeafId, leafId)
                .eq(ProofBundleIssuance::getDeleted, 0)
                .last("LIMIT 1"));
    }

    /**
     * 锁定版本链中稳定的首个文件行，使证明首次签发与新版本成功提交串行化。
     */
    private void lockVersionGroupForProofLifecycle(File file) {
        if (file.getTenantId() == null || file.getVersionGroupId() == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件缺少版本链，无法签发证明包");
        }
        Long anchorFileId = fileMapper.lockVersionGroupForProofLifecycle(
                file.getTenantId(),
                file.getVersionGroupId());
        if (anchorFileId == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件版本链不存在，无法签发证明包");
        }
    }

    /**
     * 仅允许 ACTIVE/SUPERSEDED 的历史证明继续导出，终态证明失败关闭。
     */
    private void requireExportableStatus(ProofBundleIssuance issuance) {
        if (issuance == null
                || !ISSUED_PROOF_STATUS.contains(issuance.getStatus())
                || issuance.getStatusVersion() == null
                || issuance.getStatusVersion() <= 0
                || issuance.getStatusVersion() == Long.MAX_VALUE) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "当前证明状态不允许导出");
        }
    }

    /**
     * 持有签发行锁时以状态版本 CAS 推进 INVALID，并同步内存状态供后续决策使用。
     */
    private void markInvalidLocked(ProofBundleIssuance issuance) {
        if (issuance == null
                || !ISSUED_PROOF_STATUS.contains(issuance.getStatus())
                || issuance.getStatusVersion() == null
                || issuance.getStatusVersion() <= 0
                || issuance.getStatusVersion() == Long.MAX_VALUE) {
            throw new GeneralException(ResultEnum.VERSION_CONFLICT, "证明状态无法推进为 INVALID");
        }
        Date now = new Date();
        int updated = issuanceMapper.update(null, new LambdaUpdateWrapper<ProofBundleIssuance>()
                .eq(ProofBundleIssuance::getTenantId, issuance.getTenantId())
                .eq(ProofBundleIssuance::getId, issuance.getId())
                .eq(ProofBundleIssuance::getStatusVersion, issuance.getStatusVersion())
                .in(ProofBundleIssuance::getStatus, STATUS_ACTIVE, STATUS_SUPERSEDED)
                .set(ProofBundleIssuance::getStatus, STATUS_INVALID)
                .set(ProofBundleIssuance::getStatusReason, INVALID_SNAPSHOT_REASON)
                .set(ProofBundleIssuance::getUpdateTime, now)
                .setSql("status_version = status_version + 1"));
        if (updated != 1) {
            throw new GeneralException(ResultEnum.VERSION_CONFLICT, "证明 INVALID 状态并发更新冲突");
        }
        issuance.setStatus(STATUS_INVALID)
                .setStatusReason(INVALID_SNAPSHOT_REASON)
                .setUpdateTime(now)
                .setStatusVersion(issuance.getStatusVersion() + 1);
    }

    /**
     * 当文件已不再是最新版本时只推进在线状态，不修改签名快照。
     */
    private ProofBundleIssuance synchronizeSupersededStatus(File file, ProofBundleIssuance issuance) {
        if (!hasNewerSuccessfulVersion(file) || !STATUS_ACTIVE.equals(issuance.getStatus())) {
            return issuance;
        }
        if (issuance.getStatusVersion() == null || issuance.getStatusVersion() == Long.MAX_VALUE) {
            throw new GeneralException(ResultEnum.VERSION_CONFLICT, "证明状态版本无效");
        }
        Date now = new Date();
        int updated = issuanceMapper.update(null, new LambdaUpdateWrapper<ProofBundleIssuance>()
                .eq(ProofBundleIssuance::getTenantId, file.getTenantId())
                .eq(ProofBundleIssuance::getId, issuance.getId())
                .eq(ProofBundleIssuance::getStatusVersion, issuance.getStatusVersion())
                .eq(ProofBundleIssuance::getStatus, STATUS_ACTIVE)
                .set(ProofBundleIssuance::getStatus, STATUS_SUPERSEDED)
                .set(ProofBundleIssuance::getStatusReason, "newer_file_version")
                .set(ProofBundleIssuance::getUpdateTime, now)
                .setSql("status_version = status_version + 1"));
        if (updated != 1) {
            throw new GeneralException(ResultEnum.VERSION_CONFLICT, "证明状态并发更新冲突");
        }
        return issuance
                .setStatus(STATUS_SUPERSEDED)
                .setStatusReason("newer_file_version")
                .setUpdateTime(now)
                .setStatusVersion(issuance.getStatusVersion() + 1);
    }

    /**
     * 确认数据库签发记录仍绑定同一租户、文件版本、leaf 和派生 proofId。
     */
    private void validateIssuanceBinding(File file, AttestationLeaf leaf, ProofBundleIssuance issuance) {
        if (!Objects.equals(file.getTenantId(), issuance.getTenantId())
                || !Objects.equals(file.getId(), issuance.getFileId())
                || !Objects.equals(file.getVersion(), issuance.getFileVersion())
                || !Objects.equals(leaf.getId(), issuance.getLeafId())
                || !Objects.equals(deriveProofId(file, leaf), issuance.getProofId())
                || !ISSUED_PROOF_STATUS.contains(issuance.getIssuedStatus())
                || issuance.getIssuedAt() == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "签发记录与证明证据不一致");
        }
    }

    /**
     * 确认同一公开 keyId/version 未被复用为不同公钥，避免历史 proof 解析到错误 SPKI。
     */
    private void registerKeyIdentity(ProofSigningKeyMetadata key, Date firstSeenAt) {
        if (!hasValidPublicKeyMetadata(key)) {
            throw new GeneralException(ResultEnum.PERMISSION_SIGNATURE_ERROR, "证明签名公钥元数据不合法");
        }
        ProofSigningKeyRecord candidate = new ProofSigningKeyRecord()
                .setId(snowflakeIdGenerator.nextId())
                .setKeyId(key.keyId())
                .setKeyVersion(key.keyVersion())
                .setSignatureAlgorithm(key.algorithm())
                .setPublicKeySpki(key.publicKeySpki())
                .setPublicKeyFingerprint(key.publicKeyFingerprint())
                .setStatus("ACTIVE")
                .setFirstSeenAt(cloneDate(firstSeenAt))
                .setDeleted(0);
        signingKeyMapper.registerKey(candidate);
        ProofSigningKeyRecord existing = signingKeyMapper.selectByIdentityForUpdate(
                key.keyId(), key.keyVersion());
        if (existing == null
                || !"ACTIVE".equals(existing.getStatus())
                || !matchesKeyIdentity(existing, key)) {
            throw new GeneralException(ResultEnum.PERMISSION_SIGNATURE_ERROR, "证明签名 key 标识发生冲突或不可签发");
        }
    }

    /**
     * 要求历史签发引用的 key 仍存在于唯一注册表且公开材料完全相同。
     */
    private void requireRegisteredKey(ProofSigningKeyMetadata key, Set<String> allowedStatus) {
        ProofSigningKeyRecord existing = signingKeyMapper.selectByIdentity(key.keyId(), key.keyVersion());
        if (existing == null
                || !allowedStatus.contains(existing.getStatus())
                || !matchesKeyIdentity(existing, key)
                || !hasValidPublicKeyMetadata(key)) {
            throw new GeneralException(ResultEnum.PERMISSION_SIGNATURE_ERROR, "历史证明签名 key 注册信息无效");
        }
    }

    /**
     * 比较注册表与 manifest/issuance 使用的公开 key 身份。
     */
    private boolean matchesKeyIdentity(ProofSigningKeyRecord record, ProofSigningKeyMetadata key) {
        return Objects.equals(record.getKeyId(), key.keyId())
                && Objects.equals(record.getKeyVersion(), key.keyVersion())
                && Objects.equals(record.getSignatureAlgorithm(), key.algorithm())
                && Objects.equals(record.getPublicKeySpki(), key.publicKeySpki())
                && Objects.equals(record.getPublicKeyFingerprint(), key.publicKeyFingerprint());
    }

    /**
     * 验证 Ed25519 SPKI、规范 Base64 和 SHA-256 指纹，拒绝数据库或配置漂移。
     */
    private boolean hasValidPublicKeyMetadata(ProofSigningKeyMetadata key) {
        if (key == null
                || !JWS_SIGNATURE_ALGORITHM.equals(key.algorithm())
                || !StringUtils.hasText(key.keyId())
                || !KEY_ID_PATTERN.matcher(key.keyId()).matches()
                || key.keyVersion() == null
                || key.keyVersion() <= 0
                || !StringUtils.hasText(key.publicKeySpki())
                || !StringUtils.hasText(key.publicKeyFingerprint())
                || !SHA256_PATTERN.matcher(key.publicKeyFingerprint()).matches()) {
            return false;
        }
        try {
            byte[] encoded = Base64.getDecoder().decode(key.publicKeySpki());
            if (!Base64.getEncoder().encodeToString(encoded).equals(key.publicKeySpki())) {
                return false;
            }
            PublicKey publicKey = KeyFactory.getInstance(JCA_SIGNATURE_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(encoded));
            return Objects.equals(key.publicKeyFingerprint(), canonicalizer.sha256(publicKey.getEncoded()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 将可空注册实体转换为公开 key 元数据。
     */
    private ProofSigningKeyMetadata toKeyMetadata(ProofSigningKeyRecord record) {
        if (record == null) {
            return null;
        }
        return new ProofSigningKeyMetadata(
                record.getSignatureAlgorithm(),
                record.getKeyId(),
                record.getKeyVersion(),
                record.getPublicKeySpki(),
                record.getPublicKeyFingerprint());
    }

    /**
     * 仅以更高且上传成功的同版本链记录判断 superseded，忽略 PREPARE/FAIL 的临时 latest 状态。
     */
    private boolean hasNewerSuccessfulVersion(File file) {
        if (file.getVersionGroupId() == null || file.getVersion() == null) {
            return false;
        }
        Long count = fileMapper.selectCount(new LambdaQueryWrapper<File>()
                .eq(File::getTenantId, file.getTenantId())
                .eq(File::getVersionGroupId, file.getVersionGroupId())
                .gt(File::getVersion, file.getVersion())
                .eq(File::getStatus, FileUploadStatus.SUCCESS.getCode())
                .eq(File::getDeleted, 0));
        return count != null && count > 0;
    }

    /**
     * 映射公开状态并 defensive-copy 时间字段。
     */
    private ProofStatusVO toStatusVO(ProofBundleIssuance issuance) {
        Date updatedAt = issuance.getUpdateTime() != null
                ? issuance.getUpdateTime()
                : issuance.getCreateTime();
        if (updatedAt == null) {
            updatedAt = issuance.getIssuedAt();
        }
        return new ProofStatusVO(
                issuance.getProofId(),
                issuance.getStatus(),
                issuance.getStatusVersion(),
                issuance.getIssuedStatus(),
                issuance.getKeyId(),
                issuance.getKeyVersion(),
                issuance.getStatusReason(),
                cloneDate(issuance.getIssuedAt()),
                cloneDate(updatedAt));
    }

    /**
     * 校验并规范化公开 proofId。
     */
    private String normalizeProofId(String proofId) {
        String normalized = proofId == null ? "" : proofId.trim().toLowerCase(Locale.ROOT);
        if (!PROOF_ID_PATTERN.matcher(normalized).matches()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "proofId 格式无效");
        }
        return normalized;
    }

    /**
     * 校验 sha256-prefixed 字段并返回小写规范值。
     */
    private String normalizeSha256(String value) {
        requireSha256(value, "SHA-256 字段格式无效");
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验 sha256-prefixed 字段，不允许链记录 ID 兼容回退。
     */
    private void requireSha256(String value, String message) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(normalized).matches()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, message);
        }
    }

    /**
     * 校验签发时公开 ID，避免历史数据库字段影响 ZIP 路径或下载头。
     */
    private void requireExternalId(String value, String message) {
        if (!StringUtils.hasText(value) || !EXTERNAL_ID_PATTERN.matcher(value).matches()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, message);
        }
    }

    /**
     * 比较可能带 sha256 前缀或裸 hex 的摘要字段。
     */
    private boolean equalsHash(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        String normalizedLeft = left.trim().toLowerCase(Locale.ROOT);
        String normalizedRight = right.trim().toLowerCase(Locale.ROOT);
        if (normalizedLeft.startsWith(ProofCanonicalizer.HASH_PREFIX)) {
            normalizedLeft = normalizedLeft.substring(ProofCanonicalizer.HASH_PREFIX.length());
        }
        if (normalizedRight.startsWith(ProofCanonicalizer.HASH_PREFIX)) {
            normalizedRight = normalizedRight.substring(ProofCanonicalizer.HASH_PREFIX.length());
        }
        return normalizedLeft.equals(normalizedRight);
    }

    /**
     * 把状态原因限制为数据库与公开合同允许的长度。
     */
    private String normalizeReason(String reason, String fallback) {
        String normalized = StringUtils.hasText(reason) ? reason.trim() : fallback;
        return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
    }

    /**
     * defensive-copy legacy Date。
     */
    private Date cloneDate(Date value) {
        return value == null ? null : new Date(value.getTime());
    }

    /**
     * 最终短事务的 archive 或可在事务提交后抛出的确定性失败。
     */
    private record ProofFinalizationOutcome(
            ProofArchive archive,
            GeneralException failure
    ) {
    }

    /**
     * 历史 manifest 短事务的解析结果或提交 INVALID 后再抛出的确定性失败。
     */
    private record ProofManifestLoadOutcome(
            SignedProofBundleModel.Manifest manifest,
            GeneralException failure
    ) {
    }
}
