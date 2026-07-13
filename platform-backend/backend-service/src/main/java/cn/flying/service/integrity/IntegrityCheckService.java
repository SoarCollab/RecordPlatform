package cn.flying.service.integrity;

import cn.flying.common.annotation.TenantScope;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.IntegrityAlert;
import cn.flying.dao.entity.IntegrityAlert.AlertStatus;
import cn.flying.dao.entity.IntegrityAlert.AlertType;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.IntegrityAlertMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.dao.vo.file.IntegrityCheckStatsVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.manifest.ChunkManifestBatchView;
import cn.flying.service.manifest.ChunkManifestCanonicalizer;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Verifies tenant-owned storage objects from active chunk manifests and compares
 * sampled content with the explicit blockchain record identifier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrityCheckService {

    private static final String LOCK_KEY = "integrity-check-lock";
    private static final String HASH_PREFIX_SHA256 = "sha256:";
    private static final String CHECKSUM_ALGORITHM_SHA256 = "SHA-256";
    private static final String ENCRYPTION_NONE = "NONE";
    private static final int MAX_BATCH_FILE_IDS = 1000;
    private static final int MAX_MANIFEST_CHUNKS = 10_000;
    private static final int MAX_EVIDENCE_LENGTH = 1024;
    private static final int MAX_HASH_EVIDENCE_LENGTH = 128;
    private static final Pattern SHA256_HASH_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern OPTIONAL_PREFIX_SHA256_PATTERN = Pattern.compile("(?:sha256:)?[0-9a-f]{64}");

    private final FileMapper fileMapper;
    private final IntegrityAlertMapper integrityAlertMapper;
    private final TenantMapper tenantMapper;
    private final FileRemoteClient fileRemoteClient;
    private final SseEmitterManager sseEmitterManager;
    private final RedissonClient redissonClient;
    private final ChunkManifestService chunkManifestService;

    @Value("${integrity.check.sample-rate:0.01}")
    private double sampleRate;

    @Value("${integrity.check.batch-size:50}")
    private int batchSize;

    @Value("${integrity.check.lock-timeout-seconds:1800}")
    private long lockTimeoutSeconds;

    @Value("${integrity.check.heavy.sample-chunks:1}")
    private int heavySampleChunks;

    @Value("${integrity.check.heavy.max-download-bytes:83886080}")
    private long heavyMaxDownloadBytes;

    /**
     * Integrity check levels with progressively stronger verification.
     */
    public enum IntegrityCheckLevel {
        /** Validates active manifest object metadata without downloading bytes. */
        LIGHTWEIGHT,
        /** Adds canonical manifest and ordered chunk-set validation. */
        MEDIUM,
        /** Adds bounded chunk-content sampling and blockchain consistency. */
        HEAVY
    }

    /**
     * Runs the default heavy check across sampled files for every active tenant.
     */
    @TenantScope(ignoreIsolation = true)
    public IntegrityCheckStatsVO checkIntegrity() {
        return executeWithLock(() -> doCheckAllTenantsWithLevel(IntegrityCheckLevel.HEAVY));
    }

    /**
     * Runs a requested check level across sampled files for every active tenant.
     *
     * @param level requested integrity depth
     * @return aggregated check statistics
     */
    @TenantScope(ignoreIsolation = true)
    public IntegrityCheckStatsVO checkIntegrityWithLevel(IntegrityCheckLevel level) {
        IntegrityCheckLevel effectiveLevel = Objects.requireNonNull(level, "integrity check level is required");
        return executeWithLock(() -> doCheckAllTenantsWithLevel(effectiveLevel));
    }

    /**
     * Runs an unsampled heavy check for the specified tenant.
     *
     * @param tenantId tenant to check
     * @return tenant check statistics
     */
    @TenantScope(ignoreIsolation = true)
    public IntegrityCheckStatsVO triggerManualCheck(Long tenantId) {
        if (tenantId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "tenantId is required");
        }
        log.info("[integrity-check] manual check triggered for tenantId={}", tenantId);
        return executeWithLock(() -> TenantContext.callWithTenant(tenantId, () -> {
            List<File> files = querySuccessFilesPaged(tenantId);
            return checkFilesWithLevel(files, tenantId, IntegrityCheckLevel.HEAVY);
        }));
    }

    /**
     * Lists tenant-scoped integrity alerts with optional status/type filters.
     */
    public IPage<IntegrityAlert> listAlerts(Long tenantId, Integer status, String alertType,
                                            Page<IntegrityAlert> page) {
        LambdaQueryWrapper<IntegrityAlert> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IntegrityAlert::getTenantId, tenantId);
        if (status != null) {
            wrapper.eq(IntegrityAlert::getStatus, status);
        }
        if (StringUtils.hasText(alertType)) {
            wrapper.eq(IntegrityAlert::getAlertType, alertType);
        }
        wrapper.orderByDesc(IntegrityAlert::getCreateTime);
        return integrityAlertMapper.selectPage(page, wrapper);
    }

    /**
     * Marks an alert as acknowledged by an administrator.
     */
    public void acknowledgeAlert(Long alertId, Long adminId) {
        IntegrityAlert alert = integrityAlertMapper.selectById(alertId);
        if (alert == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE);
        }
        alert.setStatus(AlertStatus.ACKNOWLEDGED.getCode());
        integrityAlertMapper.updateById(alert);
        log.info("[integrity-check] alert {} acknowledged by admin {}", alertId, adminId);
    }

    /**
     * Marks an alert as resolved and records the administrator note.
     */
    public void resolveAlert(Long alertId, Long adminId, String note) {
        IntegrityAlert alert = integrityAlertMapper.selectById(alertId);
        if (alert == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE);
        }
        alert.setStatus(AlertStatus.RESOLVED.getCode())
                .setResolvedBy(adminId)
                .setResolvedAt(new java.util.Date())
                .setNote(note);
        integrityAlertMapper.updateById(alert);
        log.info("[integrity-check] alert {} resolved by admin {}", alertId, adminId);
    }

    /**
     * Serializes scheduler/manual runs with the repository-wide distributed lock.
     */
    private IntegrityCheckStatsVO executeWithLock(Supplier<IntegrityCheckStatsVO> task) {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, lockTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[integrity-check] interrupted while acquiring lock");
            return new IntegrityCheckStatsVO(0, 0, 0);
        }
        if (!acquired) {
            log.info("[integrity-check] lock already held, skipping this run");
            return new IntegrityCheckStatsVO(0, 0, 0);
        }
        try {
            return task.get();
        } finally {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.warn("[integrity-check] failed to release lock: {}", e.getMessage());
            }
        }
    }

    /**
     * Checks each active tenant in an explicit tenant context and aggregates failures.
     */
    private IntegrityCheckStatsVO doCheckAllTenantsWithLevel(IntegrityCheckLevel level) {
        List<Long> tenantIds = tenantMapper.selectActiveTenantIds();
        if (tenantIds == null || tenantIds.isEmpty()) {
            log.info("[integrity-check] no active tenants found");
            return new IntegrityCheckStatsVO(0, 0, 0);
        }

        long totalChecked = 0;
        long totalMismatches = 0;
        long totalErrors = 0;
        for (Long tenantId : tenantIds) {
            if (tenantId == null) {
                totalErrors++;
                continue;
            }
            try {
                IntegrityCheckStatsVO stats = TenantContext.callWithTenant(tenantId, () -> {
                    List<File> files = querySuccessFilesPaged(tenantId);
                    return checkFilesWithLevel(sampleFiles(files), tenantId, level);
                });
                totalChecked += stats.totalChecked();
                totalMismatches += stats.mismatchesFound();
                totalErrors += stats.errorsEncountered();
            } catch (Exception e) {
                totalErrors++;
                log.warn("[integrity-check] tenant check failed: tenantId={}, reason={}",
                        tenantId, safeMessage(e));
            }
        }
        return new IntegrityCheckStatsVO(totalChecked, totalMismatches, totalErrors);
    }

    /**
     * Loads successful files in bounded pages without materializing unrelated columns.
     */
    private List<File> querySuccessFilesPaged(Long tenantId) {
        List<File> allFiles = new ArrayList<>();
        long current = 1;
        Page<File> page;
        do {
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(File::getTenantId, tenantId)
                    .eq(File::getStatus, FileUploadStatus.SUCCESS.getCode())
                    .eq(File::getDeleted, 0)
                    .select(File::getId, File::getTenantId, File::getUid, File::getOrigin,
                            File::getFileHash, File::getFileParam, File::getFileName, File::getVersion);
            page = fileMapper.selectPage(new Page<>(current, 500, false), wrapper);
            if (page == null || page.getRecords() == null) {
                throw new GeneralException(ResultEnum.FAIL, "file page query returned no result");
            }
            allFiles.addAll(page.getRecords());
            current++;
        } while (page.getRecords().size() == 500);
        return allFiles;
    }

    /**
     * Applies the configured independent per-file sampling rate.
     */
    private List<File> sampleFiles(List<File> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        double effectiveRate = Math.max(0.0, Math.min(1.0, sampleRate));
        List<File> sampled = new ArrayList<>();
        for (File file : files) {
            if (ThreadLocalRandom.current().nextDouble() < effectiveRate) {
                sampled.add(file);
            }
        }
        return sampled;
    }

    /**
     * Batch-loads manifests and independently accounts for every file result.
     */
    private IntegrityCheckStatsVO checkFilesWithLevel(List<File> files, Long tenantId,
                                                       IntegrityCheckLevel level) {
        if (files == null || files.isEmpty()) {
            return new IntegrityCheckStatsVO(0, 0, 0);
        }
        long checked = 0;
        long mismatches = 0;
        long errors = 0;
        int effectiveBatchSize = Math.max(1, Math.min(MAX_BATCH_FILE_IDS, batchSize));

        for (int start = 0; start < files.size(); start += effectiveBatchSize) {
            List<File> batch = files.subList(start, Math.min(start + effectiveBatchSize, files.size()));
            checked += batch.size();
            List<File> tenantFiles = new ArrayList<>(batch.size());
            for (File file : batch) {
                try {
                    requireFileTenantScope(file, tenantId);
                    tenantFiles.add(file);
                } catch (Exception e) {
                    errors++;
                    log.warn("[integrity-check] rejected out-of-scope file record: tenantId={}, fileId={}, reason={}",
                            tenantId, file == null ? null : file.getId(), safeMessage(e));
                }
            }
            if (tenantFiles.isEmpty()) {
                continue;
            }

            ChunkManifestBatchView manifestBatch;
            try {
                manifestBatch = chunkManifestService.findActiveManifests(tenantFiles.stream()
                        .map(File::getId)
                        .toList());
            } catch (Exception e) {
                errors += tenantFiles.size();
                log.warn("[integrity-check] manifest batch query failed: tenantId={}, files={}, reason={}",
                        tenantId, tenantFiles.size(), safeMessage(e));
                continue;
            }

            for (File file : tenantFiles) {
                try {
                    VerifyResult result = resolveAndVerifyFile(file, manifestBatch, level);
                    if (result != null) {
                        mismatches++;
                        createAlert(file, tenantId, result);
                    }
                } catch (Exception e) {
                    errors++;
                    log.warn("[integrity-check] file check failed: tenantId={}, fileId={}, reason={}",
                            tenantId, file == null ? null : file.getId(), safeMessage(e));
                }
            }
        }
        return new IntegrityCheckStatsVO(checked, mismatches, errors);
    }

    /**
     * Rejects malformed or cross-tenant file rows before manifest, storage, or chain access.
     */
    private void requireFileTenantScope(File file, Long tenantId) {
        Long contextTenantId = TenantContext.requireTenantId();
        if (!Objects.equals(tenantId, contextTenantId)) {
            throw new IllegalStateException("integrity tenant context does not match requested tenant");
        }
        if (file == null || file.getId() == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "integrity file record has no ID");
        }
        if (file.getTenantId() == null || !Objects.equals(tenantId, file.getTenantId())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                    "integrity file record is outside the active tenant scope");
        }
    }

    /**
     * Resolves missing/duplicate manifest states before running tiered validation.
     */
    private VerifyResult resolveAndVerifyFile(File file, ChunkManifestBatchView batch,
                                              IntegrityCheckLevel level) {
        if (file == null || file.getId() == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "integrity file record has no ID");
        }
        if (batch.duplicateFileIds().contains(file.getId())) {
            return issue(AlertType.MANIFEST_INVALID, null, null,
                    "reason=multiple_active_manifests;fileId=" + file.getId());
        }
        ChunkManifestView manifest = batch.manifests().get(file.getId());
        if (manifest == null) {
            return issue(AlertType.MANIFEST_MISSING, null, null,
                    "reason=active_manifest_missing;fileId=" + file.getId());
        }
        return verifyFile(file, manifest, level);
    }

    /**
     * Executes manifest, metadata, sampled-content, and chain checks for one file.
     */
    private VerifyResult verifyFile(File file, ChunkManifestView manifest, IntegrityCheckLevel level) {
        VerifyResult basicManifestResult = validateManifestSafetyContract(file, manifest);
        if (basicManifestResult != null) {
            return basicManifestResult;
        }

        if (level != IntegrityCheckLevel.LIGHTWEIGHT) {
            VerifyResult canonicalResult = validateCanonicalManifest(file, manifest);
            if (canonicalResult != null) {
                return canonicalResult;
            }
        }

        VerifyResult metadataResult = checkStorageMetadata(file, manifest);
        if (metadataResult != null || level != IntegrityCheckLevel.HEAVY) {
            return metadataResult;
        }

        VerifyResult contentResult = checkSampledContent(file, manifest);
        if (contentResult != null) {
            return contentResult;
        }
        return checkBlockchainRecord(file);
    }

    /**
     * Validates fields required to safely address tenant-owned chunk objects at every level.
     */
    private VerifyResult validateManifestSafetyContract(File file, ChunkManifestView manifest) {
        if (!Objects.equals(file.getId(), manifest.fileId())) {
            return manifestInvalid("file_id_mismatch", file.getId(), manifest.fileId(), null);
        }
        if (file.getVersion() == null || manifest.fileVersion() == null
                || !Objects.equals(file.getVersion(), manifest.fileVersion())) {
            return manifestInvalid("file_version_mismatch", file.getVersion(), manifest.fileVersion(), null);
        }
        if (!StringUtils.hasText(file.getFileHash())
                || !Objects.equals(file.getFileHash().trim(), trimToEmpty(manifest.fileHash()))) {
            return manifestInvalid("chain_record_id_mismatch", file.getFileHash(), manifest.fileHash(), null);
        }
        if (!ChunkManifestCanonicalizer.SCHEMA_ID.equals(manifest.schemaId())) {
            return manifestInvalid("schema_mismatch", ChunkManifestCanonicalizer.SCHEMA_ID,
                    manifest.schemaId(), null);
        }
        if (!CHECKSUM_ALGORITHM_SHA256.equalsIgnoreCase(trimToEmpty(manifest.hashAlgorithm()))) {
            return manifestInvalid("manifest_hash_algorithm_mismatch", CHECKSUM_ALGORITHM_SHA256,
                    manifest.hashAlgorithm(), null);
        }
        if (manifest.chunkSize() <= 0 || manifest.totalSize() <= 0) {
            return manifestInvalid("manifest_size_invalid", ">0", manifest.totalSize(), null);
        }
        if (!StringUtils.hasText(manifest.storageBackend())) {
            return manifestInvalid("storage_backend_missing", "non-blank", manifest.storageBackend(), null);
        }
        List<ChunkManifestChunk> chunks = manifest.chunks();
        if (chunks == null || chunks.isEmpty()) {
            return manifestInvalid("chunks_missing", "1.." + MAX_MANIFEST_CHUNKS, 0, null);
        }
        if (chunks.size() > MAX_MANIFEST_CHUNKS) {
            return manifestInvalid("chunk_count_exceeds_limit", MAX_MANIFEST_CHUNKS, chunks.size(), null);
        }

        for (int position = 0; position < chunks.size(); position++) {
            ChunkManifestChunk chunk = chunks.get(position);
            if (chunk == null) {
                return manifestInvalid("chunk_null", "non-null", null, position);
            }
            if (chunk.size() <= 0) {
                return manifestInvalid("chunk_size_invalid", ">0", chunk.size(), chunk.index());
            }
            if (!isSha256Hash(chunk.cipherHash())) {
                return manifestInvalid("cipher_hash_invalid", "sha256:<64-lowercase-hex>",
                        chunk.cipherHash(), chunk.index());
            }
            String expectedPath = expectedStoragePath(file.getTenantId(), chunk.cipherHash());
            if (!Objects.equals(expectedPath, chunk.storagePath())) {
                return manifestInvalid("storage_path_mismatch", expectedPath, chunk.storagePath(), chunk.index());
            }
            if (!trimToEmpty(manifest.storageBackend()).equalsIgnoreCase(trimToEmpty(chunk.storageBackend()))) {
                return manifestInvalid("chunk_storage_backend_mismatch", manifest.storageBackend(),
                        chunk.storageBackend(), chunk.index());
            }
            if (!CHECKSUM_ALGORITHM_SHA256.equalsIgnoreCase(trimToEmpty(chunk.checksumAlgorithm()))) {
                return manifestInvalid("chunk_checksum_algorithm_mismatch", CHECKSUM_ALGORITHM_SHA256,
                        chunk.checksumAlgorithm(), chunk.index());
            }
        }
        return null;
    }

    /**
     * Validates ordered chunk semantics and the canonical manifest hash for medium/heavy checks.
     */
    private VerifyResult validateCanonicalManifest(File file, ChunkManifestView manifest) {
        List<ChunkManifestChunk> chunks = manifest.chunks();
        if (manifest.chunkCount() == null || manifest.chunkCount() != chunks.size()) {
            return manifestInvalid("chunk_count_mismatch", manifest.chunkCount(), chunks.size(), null);
        }
        long aggregateSize = 0;
        for (int position = 0; position < chunks.size(); position++) {
            ChunkManifestChunk chunk = chunks.get(position);
            if (chunk.index() != position) {
                return manifestInvalid("chunk_order_invalid", position, chunk.index(), chunk.index());
            }
            if (!isSha256Hash(chunk.plainHash())) {
                return manifestInvalid("plain_hash_invalid", "sha256:<64-lowercase-hex>",
                        chunk.plainHash(), chunk.index());
            }
            try {
                aggregateSize = Math.addExact(aggregateSize, chunk.size());
            } catch (ArithmeticException e) {
                return manifestInvalid("chunk_size_overflow", "signed-long", "overflow", chunk.index());
            }
        }
        if (aggregateSize != manifest.totalSize()) {
            return manifestInvalid("aggregate_size_mismatch", manifest.totalSize(), aggregateSize, null);
        }
        if (StringUtils.hasText(manifest.merkleRoot())
                && !OPTIONAL_PREFIX_SHA256_PATTERN.matcher(normalizeHash(manifest.merkleRoot())).matches()) {
            return manifestInvalid("merkle_root_invalid", "[sha256:]<64-lowercase-hex>",
                    manifest.merkleRoot(), null);
        }

        if (ENCRYPTION_NONE.equalsIgnoreCase(trimToEmpty(manifest.encryptionAlgorithm()))) {
            VerifyResult directResult = validateUnencryptedChunkPlan(file, manifest);
            if (directResult != null) {
                return directResult;
            }
        }

        ChunkManifestDraft draft = toDraft(manifest);
        String calculatedHash;
        try {
            calculatedHash = chunkManifestService.calculateManifestHash(draft);
        } catch (GeneralException | ArithmeticException e) {
            return issue(AlertType.MANIFEST_INVALID, null, null,
                    boundedEvidence("reason=canonical_manifest_invalid;message=" + safeMessage(e)));
        }
        if (!isSha256Hash(manifest.manifestHash())
                || !normalizeHash(calculatedHash).equals(normalizeHash(manifest.manifestHash()))) {
            return manifestInvalid("manifest_hash_mismatch", manifest.manifestHash(), calculatedHash, null);
        }
        return null;
    }

    /**
     * Validates direct-upload NONE encryption invariants and nominal chunk sizing.
     */
    private VerifyResult validateUnencryptedChunkPlan(File file, ChunkManifestView manifest) {
        List<ChunkManifestChunk> chunks = manifest.chunks();
        for (int position = 0; position < chunks.size(); position++) {
            ChunkManifestChunk chunk = chunks.get(position);
            if (!normalizeHash(chunk.plainHash()).equals(normalizeHash(chunk.cipherHash()))) {
                return manifestInvalid("unencrypted_hash_mismatch", chunk.plainHash(),
                        chunk.cipherHash(), chunk.index());
            }
            long expectedSize = position == chunks.size() - 1
                    ? manifest.totalSize() - (manifest.chunkSize() * position)
                    : manifest.chunkSize();
            if (expectedSize <= 0 || chunk.size() != expectedSize) {
                return manifestInvalid("direct_chunk_size_mismatch", expectedSize,
                        chunk.size(), chunk.index());
            }
        }
        Long fileSize = file.getFileSize();
        if (fileSize == null || fileSize <= 0 || fileSize != manifest.totalSize()) {
            return manifestInvalid("file_size_mismatch", fileSize, manifest.totalSize(), null);
        }
        return null;
    }

    /**
     * Converts a persisted view back to the canonical hashing contract.
     */
    private ChunkManifestDraft toDraft(ChunkManifestView manifest) {
        return new ChunkManifestDraft(
                manifest.schemaId(),
                manifest.fileHash(),
                manifest.hashAlgorithm(),
                manifest.chunkSize(),
                manifest.totalSize(),
                manifest.merkleRoot(),
                manifest.encryptionAlgorithm(),
                manifest.storageBackend(),
                manifest.chunks());
    }

    /**
     * HEAD-checks every manifest chunk and fails closed on missing metadata fields.
     */
    private VerifyResult checkStorageMetadata(File file, ChunkManifestView manifest) {
        for (ChunkManifestChunk chunk : manifest.chunks()) {
            Result<StorageObjectHeadVO> headResult = fileRemoteClient.headObject(
                    chunk.storagePath(), chunk.cipherHash());
            if (headResult == null || !headResult.isSuccess()) {
                throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "storage HEAD request failed");
            }
            StorageObjectHeadVO head = headResult.getData();
            if (head == null) {
                throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "storage HEAD response is missing");
            }
            if (!head.exists()) {
                return issue(AlertType.OBJECT_NOT_FOUND, null, null,
                        boundedEvidence("reason=object_missing;chunkIndex=" + chunk.index()
                                + ";path=" + chunk.storagePath()));
            }

            VerifyResult mismatch = validateHeadFields(file, chunk, head);
            if (mismatch != null) {
                return mismatch;
            }
        }
        return null;
    }

    /**
     * Compares a successful HEAD response with manifest and tenant expectations.
     */
    private VerifyResult validateHeadFields(File file, ChunkManifestChunk chunk, StorageObjectHeadVO head) {
        if (!Objects.equals(chunk.storagePath(), head.filePath())) {
            return metadataMismatch(chunk, "filePath", chunk.storagePath(), head.filePath(), null);
        }
        if (!normalizeHash(chunk.cipherHash()).equals(normalizeHash(head.fileHash()))) {
            return metadataMismatch(chunk, "fileHash", chunk.cipherHash(), head.fileHash(), head.fileHash());
        }
        if (head.tenantId() == null || !Objects.equals(file.getTenantId(), head.tenantId())) {
            return metadataMismatch(chunk, "pathTenantId", file.getTenantId(), head.tenantId(), null);
        }
        if (head.metadataTenantId() == null || !Objects.equals(file.getTenantId(), head.metadataTenantId())) {
            return metadataMismatch(chunk, "metadataTenantId", file.getTenantId(), head.metadataTenantId(), null);
        }
        if (head.contentLength() == null || head.contentLength() != chunk.size()) {
            return metadataMismatch(chunk, "contentLength", chunk.size(), head.contentLength(), null);
        }
        if (!StringUtils.hasText(head.metadataHash())
                || !normalizeHash(chunk.cipherHash()).equals(normalizeHash(head.metadataHash()))) {
            return metadataMismatch(chunk, "metadataHash", chunk.cipherHash(),
                    head.metadataHash(), head.metadataHash());
        }
        if (StringUtils.hasText(chunk.etag())
                && (!StringUtils.hasText(head.eTag())
                || !normalizeEtag(chunk.etag()).equals(normalizeEtag(head.eTag())))) {
            return metadataMismatch(chunk, "etag", chunk.etag(), head.eTag(), null);
        }
        return null;
    }

    /**
     * Downloads only configured sampled chunks and recomputes their cipher hashes.
     */
    private VerifyResult checkSampledContent(File file, ChunkManifestView manifest) {
        List<ChunkManifestChunk> samples = selectHeavySamples(manifest.chunks());
        if (heavyMaxDownloadBytes <= 0) {
            throw new IllegalStateException("integrity.check.heavy.max-download-bytes must be positive");
        }

        long plannedBytes = 0;
        for (ChunkManifestChunk sample : samples) {
            try {
                plannedBytes = Math.addExact(plannedBytes, sample.size());
            } catch (ArithmeticException e) {
                throw new IllegalStateException("heavy integrity sample size overflow", e);
            }
        }
        if (plannedBytes > heavyMaxDownloadBytes) {
            throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR,
                    "heavy integrity sample exceeds configured download limit");
        }

        for (ChunkManifestChunk sample : samples) {
            Result<List<byte[]>> storageResult = fileRemoteClient.getFileListByHash(
                    List.of(sample.storagePath()), List.of(sample.cipherHash()));
            if (storageResult == null || !storageResult.isSuccess()) {
                throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "sampled object download failed");
            }
            List<byte[]> data = storageResult.getData();
            if (data == null || data.isEmpty() || data.getFirst() == null) {
                return issue(AlertType.OBJECT_NOT_FOUND, null, null,
                        boundedEvidence("reason=sampled_object_missing;chunkIndex=" + sample.index()
                                + ";path=" + sample.storagePath()));
            }
            if (data.size() != 1) {
                throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR,
                        "sampled object download returned an unexpected item count");
            }

            byte[] content = data.getFirst();
            String actualHash = calculateSha256(content);
            if (content.length != sample.size()
                    || !normalizeHash(sample.cipherHash()).equals(normalizeHash(actualHash))) {
                return issue(AlertType.CONTENT_HASH_MISMATCH, actualHash, null,
                        boundedEvidence("reason=sampled_content_mismatch;chunkIndex=" + sample.index()
                                + ";expectedSize=" + sample.size() + ";actualSize=" + content.length
                                + ";expectedHash=" + sample.cipherHash() + ";actualHash=" + actualHash));
            }
        }
        log.debug("[integrity-check][heavy] sampled content verified: fileId={}, chunks={}",
                file.getId(), samples.size());
        return null;
    }

    /**
     * Selects unique random chunk objects without mutating the persisted manifest view.
     */
    private List<ChunkManifestChunk> selectHeavySamples(List<ChunkManifestChunk> chunks) {
        int requested = Math.max(1, heavySampleChunks);
        int count = Math.min(requested, chunks.size());
        List<ChunkManifestChunk> shuffled = new ArrayList<>(chunks);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        return List.copyOf(shuffled.subList(0, count));
    }

    /**
     * Looks up the blockchain record with the explicit chain record ID from file.fileHash.
     */
    private VerifyResult checkBlockchainRecord(File file) {
        String chainRecordId = file.getFileHash().trim();
        Long uploaderId = resolveBlockchainUploaderId(file);
        if (uploaderId == null) {
            return issue(AlertType.CHAIN_NOT_FOUND, null, null,
                    boundedEvidence("reason=chain_uploader_missing;chainRecordId=" + chainRecordId));
        }
        String uploader = String.valueOf(uploaderId);
        Result<FileDetailVO> chainResult = fileRemoteClient.getFile(uploader, chainRecordId);
        if (isBlockchainDependencyFailure(chainResult)) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR);
        }
        if (!chainResult.isSuccess() || chainResult.getData() == null) {
            return issue(AlertType.CHAIN_NOT_FOUND, null, null,
                    boundedEvidence("reason=chain_record_missing;chainRecordId=" + chainRecordId));
        }

        String actualChainRecordId = chainResult.getData().fileHash();
        if (!StringUtils.hasText(actualChainRecordId)
                || !chainRecordId.equalsIgnoreCase(actualChainRecordId.trim())) {
            return issue(AlertType.CHAIN_MISMATCH, null, actualChainRecordId,
                    boundedEvidence("reason=chain_record_mismatch;expected=" + chainRecordId
                            + ";actual=" + trimToEmpty(actualChainRecordId)));
        }
        return null;
    }

    /**
     * Distinguishes downstream availability failures from a valid record-not-found response.
     */
    private boolean isBlockchainDependencyFailure(Result<FileDetailVO> chainResult) {
        if (chainResult == null) {
            return true;
        }
        Integer code = chainResult.getCode();
        return Objects.equals(code, cn.flying.platformapi.constant.ResultEnum.BLOCKCHAIN_ERROR.getCode())
                || Objects.equals(code, cn.flying.platformapi.constant.ResultEnum.BLOCKCHAIN_TIMEOUT.getCode())
                || Objects.equals(code, cn.flying.platformapi.constant.ResultEnum.BLOCKCHAIN_UNREACHABLE.getCode())
                || Objects.equals(code, cn.flying.platformapi.constant.ResultEnum.SERVICE_CIRCUIT_OPEN.getCode())
                || Objects.equals(code, cn.flying.platformapi.constant.ResultEnum.SERVICE_TIMEOUT.getCode());
    }

    /**
     * Inserts and broadcasts an alert only when no open alert of the same type exists.
     */
    private void createAlert(File file, Long tenantId, VerifyResult result) {
        Long openCount = integrityAlertMapper.selectCount(new LambdaQueryWrapper<IntegrityAlert>()
                .eq(IntegrityAlert::getTenantId, tenantId)
                .eq(IntegrityAlert::getFileId, file.getId())
                .eq(IntegrityAlert::getAlertType, result.alertType().name())
                .in(IntegrityAlert::getStatus,
                        List.of(AlertStatus.PENDING.getCode(), AlertStatus.ACKNOWLEDGED.getCode()))
                .eq(IntegrityAlert::getDeleted, 0));
        if (openCount != null && openCount > 0) {
            log.info("[integrity-check] open alert already exists: tenantId={}, fileId={}, type={}",
                    tenantId, file.getId(), result.alertType());
            return;
        }

        String severity = result.alertType().getDefaultSeverity().name();
        IntegrityAlert alert = new IntegrityAlert()
                .setTenantId(tenantId)
                .setFileId(file.getId())
                .setFileHash(file.getFileHash())
                .setActualHash(bound(result.actualHash(), MAX_HASH_EVIDENCE_LENGTH))
                .setChainHash(bound(result.chainHash(), MAX_HASH_EVIDENCE_LENGTH))
                .setAlertType(result.alertType().name())
                .setSeverity(severity)
                .setEvidence(boundedEvidence(result.evidence()))
                .setStatus(AlertStatus.PENDING.getCode());
        integrityAlertMapper.insert(alert);

        Map<String, Object> payload = new HashMap<>();
        payload.put("alertId", alert.getId());
        payload.put("fileId", file.getId());
        payload.put("fileName", file.getFileName() == null ? "" : file.getFileName());
        payload.put("alertType", result.alertType().name());
        payload.put("severity", severity);
        payload.put("fileHash", file.getFileHash());
        payload.put("actualHash", alert.getActualHash());
        payload.put("chainHash", alert.getChainHash());
        payload.put("evidence", alert.getEvidence());
        try {
            TenantContext.callWithTenant(tenantId, () -> {
                sseEmitterManager.broadcastToAdmins(tenantId,
                        SseEvent.of(SseEventType.INTEGRITY_ALERT, payload));
                return null;
            });
        } catch (Exception e) {
            log.warn("[integrity-check] failed to broadcast SSE alert: tenantId={}, reason={}",
                    tenantId, safeMessage(e));
        }
        log.warn("[integrity-check] alert created: type={}, severity={}, fileId={}, tenantId={}",
                result.alertType(), severity, file.getId(), tenantId);
    }

    /**
     * Resolves the original uploader used by share-saved blockchain records.
     */
    private Long resolveBlockchainUploaderId(File file) {
        if (file.getOrigin() != null) {
            File originFile = fileMapper.selectByIdIncludeDeleted(file.getOrigin());
            if (originFile != null && originFile.getUid() != null) {
                return originFile.getUid();
            }
        }
        return file.getUid();
    }

    /**
     * Builds a compact metadata mismatch finding for one chunk.
     */
    private VerifyResult metadataMismatch(ChunkManifestChunk chunk, String field,
                                          Object expected, Object actual, String actualHash) {
        return issue(AlertType.METADATA_MISMATCH, actualHash, null,
                boundedEvidence("reason=metadata_mismatch;chunkIndex=" + chunk.index()
                        + ";field=" + field + ";expected=" + expected + ";actual=" + actual));
    }

    /**
     * Builds a compact manifest-invalid finding with optional chunk context.
     */
    private VerifyResult manifestInvalid(String reason, Object expected, Object actual, Integer chunkIndex) {
        String chunkEvidence = chunkIndex == null ? "" : ";chunkIndex=" + chunkIndex;
        String actualHash = "manifest_hash_mismatch".equals(reason) && actual instanceof String
                ? (String) actual
                : null;
        return issue(AlertType.MANIFEST_INVALID, actualHash, null,
                boundedEvidence("reason=" + reason + chunkEvidence
                        + ";expected=" + expected + ";actual=" + actual));
    }

    /**
     * Creates one immutable verification result while bounding persisted evidence later.
     */
    private VerifyResult issue(AlertType alertType, String actualHash, String chainHash, String evidence) {
        return new VerifyResult(alertType, actualHash, chainHash, evidence);
    }

    /**
     * Builds the exact tenant-scoped storage path for a canonical cipher hash.
     */
    private String expectedStoragePath(Long tenantId, String cipherHash) {
        return "storage/tenant/" + tenantId + "/chunk/" + normalizeHash(cipherHash);
    }

    /**
     * Validates the canonical sha256-prefixed hash format used by stored chunks.
     */
    private boolean isSha256Hash(String value) {
        return SHA256_HASH_PATTERN.matcher(normalizeHash(value)).matches();
    }

    /**
     * Normalizes hash-like values for deterministic case-insensitive comparison.
     */
    private String normalizeHash(String value) {
        return trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /**
     * Removes provider-added quote characters before ETag comparison.
     */
    private String normalizeEtag(String value) {
        String normalized = trimToEmpty(value);
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Computes a canonical sha256-prefixed content digest.
     */
    private String calculateSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM_SHA256);
            return HASH_PREFIX_SHA256 + HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    /**
     * Normalizes a nullable string without changing non-whitespace content.
     */
    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Removes control-line characters and bounds persisted evidence to the schema limit.
     */
    private String boundedEvidence(String evidence) {
        if (evidence == null) {
            return null;
        }
        String sanitized = evidence.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return bound(sanitized, MAX_EVIDENCE_LENGTH);
    }

    /**
     * Truncates a nullable value to a database-safe length.
     */
    private String bound(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * Returns a bounded exception message suitable for logs and evidence.
     */
    private String safeMessage(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        if (!StringUtils.hasText(message) && exception != null) {
            message = exception.getClass().getSimpleName();
        }
        return bound(message, 256);
    }

    /**
     * Internal finding returned when a deterministic integrity mismatch is detected.
     */
    private record VerifyResult(
            AlertType alertType,
            String actualHash,
            String chainHash,
            String evidence
    ) {
    }
}
