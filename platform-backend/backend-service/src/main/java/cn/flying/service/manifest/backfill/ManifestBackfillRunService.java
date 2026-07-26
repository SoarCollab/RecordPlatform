package cn.flying.service.manifest.backfill;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.entity.ManifestBackfillRun;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.ManifestBackfillItemMapper;
import cn.flying.dao.mapper.ManifestBackfillRunMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates immutable scans, derivative dry-runs, lease-fenced apply, and administrator controls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManifestBackfillRunService {

    private static final String SNAPSHOT_VERSION = "manifest-backfill-snapshot.v1";
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int DEFAULT_CLAIM_SIZE = 20;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_LEASE_SECONDS = 120L;

    private final ManifestBackfillRunMapper runMapper;
    private final ManifestBackfillItemMapper itemMapper;
    private final FileMapper fileMapper;
    private final ManifestEvidenceResolver evidenceResolver;
    private final ManifestBackfillClaimService claimService;
    private final ChunkManifestBackfillPublisher publisher;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final MeterRegistry meterRegistry;

    @Value("${manifest.backfill.worker-enabled:true}")
    private boolean workerEnabled;

    @Value("${manifest.backfill.apply-enabled:false}")
    private boolean applyEnabled;

    @Value("${manifest.backfill.run-lease-seconds:300}")
    private long runLeaseSeconds;

    /**
     * Creates an asynchronous tenant-scoped run without accepting a caller-selected broader tenant scope.
     *
     * @param tenantId authenticated tenant ID
     * @param userId administrator ID
     * @param mode scan, dry-run, or apply
     * @param requestedSnapshotRunId source scan for derivative modes
     * @return persisted planned run
     */
    public ManifestBackfillRun createRun(
            Long tenantId,
            Long userId,
            ManifestBackfillMode mode,
            Long requestedSnapshotRunId
    ) {
        if (tenantId == null || userId == null || mode == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        if (!Objects.equals(TenantContext.requireTenantId(), tenantId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
        if (mode == ManifestBackfillMode.APPLY && !applyEnabled) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED,
                    "manifest backfill apply is disabled by rollout policy");
        }

        Long snapshotRunId = null;
        if (mode != ManifestBackfillMode.SCAN) {
            ManifestBackfillRun source = requireFrozenSnapshot(tenantId, requestedSnapshotRunId);
            snapshotRunId = source.getSnapshotRunId() != null ? source.getSnapshotRunId() : source.getId();
        } else if (requestedSnapshotRunId != null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                    "SCAN must create a new snapshot");
        }

        ManifestBackfillRun run = new ManifestBackfillRun()
                .setId(snowflakeIdGenerator.nextId())
                .setTenantId(tenantId)
                .setSnapshotRunId(snapshotRunId)
                .setMode(mode.name())
                .setStatus(ManifestBackfillRunStatus.PLANNED.name())
                .setSnapshotVersion(SNAPSHOT_VERSION)
                .setCursorFileId(0L)
                .setCreatedBy(userId)
                .setTotalCount(0L)
                .setPendingCount(0L)
                .setBackfilledCount(0L)
                .setReuploadCount(0L)
                .setUnrecoverableCount(0L)
                .setIgnoredCount(0L)
                .setFailedCount(0L)
                .setDeleted(0);
        runMapper.insert(run);
        return run;
    }

    /**
     * Polls a small global run list, then restores strict tenant isolation for each execution.
     */
    @Scheduled(fixedDelayString = "${manifest.backfill.worker-delay-ms:5000}")
    public void processPlannedRuns() {
        if (!workerEnabled) {
            return;
        }
        Date staleBefore = Date.from(Instant.now().minusSeconds(Math.max(60L, runLeaseSeconds)));
        List<ManifestBackfillRun> runs = runMapper.selectRunnableRuns(staleBefore, 4);
        if (runs == null) {
            return;
        }
        for (ManifestBackfillRun run : runs) {
            TenantContext.runWithTenantIsolation(run.getTenantId(), () -> processPlannedRun(run.getId()));
        }
    }

    /**
     * Claims and processes one planned run, preserving all committed item evidence on failure.
     *
     * @param runId internal run ID
     */
    public void processPlannedRun(Long runId) {
        ManifestBackfillRun run = runMapper.selectRunGlobally(runId);
        if (run == null || !Objects.equals(TenantContext.requireTenantId(), run.getTenantId())) {
            return;
        }
        ManifestBackfillMode mode = ManifestBackfillMode.valueOf(run.getMode());
        String runningStatus = mode == ManifestBackfillMode.APPLY
                ? ManifestBackfillRunStatus.APPLYING.name()
                : ManifestBackfillRunStatus.SCANNING.name();
        Date staleBefore = Date.from(Instant.now().minusSeconds(Math.max(60L, runLeaseSeconds)));
        if (runMapper.claimPlannedRun(runId, runningStatus, staleBefore) != 1) {
            return;
        }
        try {
            if (mode == ManifestBackfillMode.SCAN) {
                executeScan(run);
            } else {
                cloneFrozenSnapshot(run, mode);
                if (mode == ManifestBackfillMode.APPLY) {
                    executeApply(run);
                }
            }
            finalizeRun(runId, mode);
        } catch (RuntimeException failure) {
            if (isPaused(runId)) {
                return;
            }
            markRunFailed(runId, failure);
            log.error("Manifest backfill run failed: runId={}, tenantId={}, mode={}, errorClass={}",
                    runId, run.getTenantId(), mode, failure.getClass().getSimpleName());
        }
    }

    /**
     * Returns a tenant-scoped run, hiding another tenant's existence.
     *
     * @param tenantId authenticated tenant ID
     * @param runId internal run ID
     * @return run row
     */
    public ManifestBackfillRun getRun(Long tenantId, Long runId) {
        if (tenantId == null || !Objects.equals(TenantContext.requireTenantId(), tenantId)) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE);
        }
        ManifestBackfillRun run = runMapper.selectRunGlobally(runId);
        if (run == null || !Objects.equals(run.getTenantId(), tenantId)) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE);
        }
        return run;
    }

    /**
     * Lists a bounded newest-first tenant run history for the administrator console.
     */
    public List<ManifestBackfillRun> getRuns(Long tenantId, int limit) {
        if (tenantId == null || !Objects.equals(TenantContext.requireTenantId(), tenantId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
        return runMapper.selectTenantRuns(tenantId, Math.max(1, Math.min(limit, 100)));
    }

    /**
     * Pages tenant-scoped item details with bounded filters and an opaque decoded cursor.
     *
     * @param tenantId authenticated tenant ID
     * @param runId run ID
     * @param afterId exclusive item cursor
     * @param status optional status
     * @param classification optional classification
     * @param reason optional reason
     * @param limit page size
     * @return item page
     */
    public List<ManifestBackfillItem> getItems(
            Long tenantId,
            Long runId,
            Long afterId,
            String status,
            String classification,
            String reason,
            int limit
    ) {
        getRun(tenantId, runId);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return itemMapper.selectItemPage(runId, tenantId, afterId == null ? 0L : afterId,
                normalizeFilter(status), normalizeFilter(classification), normalizeFilter(reason), boundedLimit);
    }

    /**
     * Pauses a running/planned operation without rolling back committed manifests.
     *
     * @param tenantId authenticated tenant ID
     * @param runId run ID
     * @return updated run
     */
    public ManifestBackfillRun pause(Long tenantId, Long runId) {
        getRun(tenantId, runId);
        int updated = runMapper.update(null, new LambdaUpdateWrapper<ManifestBackfillRun>()
                .set(ManifestBackfillRun::getStatus, ManifestBackfillRunStatus.PAUSED.name())
                .eq(ManifestBackfillRun::getId, runId)
                .eq(ManifestBackfillRun::getTenantId, tenantId)
                .in(ManifestBackfillRun::getStatus,
                        ManifestBackfillRunStatus.PLANNED.name(),
                        ManifestBackfillRunStatus.SCANNING.name(),
                        ManifestBackfillRunStatus.APPLYING.name())
                .eq(ManifestBackfillRun::getDeleted, 0));
        if (updated != 1) {
            throw new GeneralException(ResultEnum.DATA_IS_WRONG,
                    "only an active manifest backfill run can pause");
        }
        return getRun(tenantId, runId);
    }

    /**
     * Resumes a paused run against the same immutable snapshot.
     *
     * @param tenantId authenticated tenant ID
     * @param runId run ID
     * @return updated run
     */
    public ManifestBackfillRun resume(Long tenantId, Long runId) {
        getRun(tenantId, runId);
        int updated = runMapper.update(null, new LambdaUpdateWrapper<ManifestBackfillRun>()
                .set(ManifestBackfillRun::getStatus, ManifestBackfillRunStatus.PLANNED.name())
                .eq(ManifestBackfillRun::getId, runId)
                .eq(ManifestBackfillRun::getTenantId, tenantId)
                .eq(ManifestBackfillRun::getStatus, ManifestBackfillRunStatus.PAUSED.name())
                .eq(ManifestBackfillRun::getDeleted, 0));
        if (updated != 1) {
            throw new GeneralException(ResultEnum.DATA_IS_WRONG,
                    "only a paused manifest backfill run can resume");
        }
        return getRun(tenantId, runId);
    }

    /**
     * Explicitly requeues a failed item and makes its run runnable again.
     *
     * @param tenantId authenticated tenant ID
     * @param itemId internal item ID
     * @return owning run
     */
    public ManifestBackfillRun retryItem(Long tenantId, Long runId, Long itemId) {
        ManifestBackfillItem item = itemMapper.selectById(itemId);
        if (item == null || !Objects.equals(item.getTenantId(), tenantId)
                || !Objects.equals(item.getRunId(), runId)) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE);
        }
        ManifestBackfillRun run = getRun(tenantId, runId);
        if (ManifestBackfillMode.parse(run.getMode()) != ManifestBackfillMode.APPLY) {
            throw new GeneralException(ResultEnum.DATA_IS_WRONG,
                    "only an apply run item can be requeued");
        }
        if (itemMapper.requeueFailed(itemId, tenantId) != 1) {
            throw new GeneralException(ResultEnum.DATA_IS_WRONG,
                    "only a retryable failed manifest backfill item can be requeued");
        }
        runMapper.update(null, new LambdaUpdateWrapper<ManifestBackfillRun>()
                .set(ManifestBackfillRun::getStatus, ManifestBackfillRunStatus.PLANNED.name())
                .set(ManifestBackfillRun::getCompletedAt, null)
                .eq(ManifestBackfillRun::getId, item.getRunId())
                .eq(ManifestBackfillRun::getTenantId, tenantId)
                .eq(ManifestBackfillRun::getDeleted, 0));
        return getRun(tenantId, item.getRunId());
    }

    /**
     * Scans file rows through a crash-safe keyset and persists each deterministic outcome.
     */
    private void executeScan(ManifestBackfillRun run) {
        long cursor = run.getCursorFileId() == null ? 0L : run.getCursorFileId();
        while (true) {
            if (isPaused(run.getId())) {
                return;
            }
            List<File> files = fileMapper.selectManifestBackfillCandidates(
                    run.getTenantId(), cursor, DEFAULT_PAGE_SIZE);
            if (files == null || files.isEmpty()) {
                break;
            }
            for (File file : files) {
                touchRunLease(run, ManifestBackfillRunStatus.SCANNING);
                ManifestEvidenceResolution resolution = resolveSafely(file);
                itemMapper.insertIgnoreSnapshot(toItem(run.getId(), file, resolution));
                recordMetric(run.getMode(), resolution.classification(), resolution.reason(), "classified");
            }
            cursor = files.get(files.size() - 1).getId();
            runMapper.advanceScanCursor(run.getId(), run.getTenantId(), cursor);
        }
    }

    /**
     * Copies a frozen source snapshot so dry-run and apply never mutate scan evidence.
     */
    private void cloneFrozenSnapshot(ManifestBackfillRun run, ManifestBackfillMode mode) {
        ManifestBackfillRun source = requireFrozenSnapshot(run.getTenantId(), run.getSnapshotRunId());
        List<ManifestBackfillItem> sourceItems = itemMapper.selectRunItems(
                source.getId(), source.getTenantId());
        for (ManifestBackfillItem sourceItem : sourceItems) {
            ManifestBackfillItem clone = new ManifestBackfillItem()
                    .setId(snowflakeIdGenerator.nextId())
                    .setRunId(run.getId())
                    .setTenantId(sourceItem.getTenantId())
                    .setFileId(sourceItem.getFileId())
                    .setFileVersion(sourceItem.getFileVersion())
                    .setOwnerUserId(sourceItem.getOwnerUserId())
                    .setStatus(mode == ManifestBackfillMode.APPLY
                            && ManifestBackfillClassification.BACKFILLABLE.name().equals(sourceItem.getClassification())
                            ? ManifestBackfillItemStatus.PENDING.name()
                            : sourceItem.getStatus())
                    .setClassification(sourceItem.getClassification())
                    .setReasonCode(sourceItem.getReasonCode())
                    .setRetryable(sourceItem.getRetryable())
                    .setLegacyDownloadAllowed(sourceItem.getLegacyDownloadAllowed())
                    .setEvidenceDigest(sourceItem.getEvidenceDigest())
                    .setEvidencePayload(sourceItem.getEvidencePayload())
                    .setManifestId(sourceItem.getManifestId())
                    .setAttemptCount(0)
                    .setDeleted(0);
            itemMapper.insertIgnoreSnapshot(clone);
        }
        ManifestBackfillRun update = new ManifestBackfillRun()
                .setId(run.getId())
                .setSnapshotDigest(source.getSnapshotDigest());
        runMapper.updateById(update);
    }

    /**
     * Claims and publishes every currently due backfillable item in bounded batches.
     */
    private void executeApply(ManifestBackfillRun run) {
        while (!isPaused(run.getId())) {
            touchRunLease(run, ManifestBackfillRunStatus.APPLYING);
            ManifestBackfillClaim claim = claimService.claim(
                    run.getId(), run.getTenantId(), DEFAULT_CLAIM_SIZE,
                    DEFAULT_MAX_ATTEMPTS, DEFAULT_LEASE_SECONDS, Instant.now());
            if (claim == null) {
                return;
            }
            for (ManifestBackfillItem item : claim.items()) {
                try {
                    publisher.publish(item.getTenantId(), item.getRunId(), item.getId(), claim.claimToken());
                    recordMetric(run.getMode(), ManifestBackfillClassification.BACKFILLABLE,
                            ManifestBackfillReason.BACKFILLABLE_EVIDENCE, "published");
                } catch (ManifestEvidenceResolver.ManifestEvidenceChangedException evidenceChanged) {
                    failClaimIfOwned(run, item, claim.claimToken(), evidenceChanged.reason(),
                            isRetryableEvidenceFailure(evidenceChanged.reason()),
                            evidenceChanged.getClass().getSimpleName());
                } catch (DataAccessException transientDatabaseFailure) {
                    failClaimIfOwned(run, item, claim.claimToken(),
                            ManifestBackfillReason.DATABASE_TRANSIENT, true,
                            transientDatabaseFailure.getClass().getSimpleName());
                } catch (IllegalStateException claimFailure) {
                    if (isClaimLost(claimFailure)) {
                        recordMetric(run.getMode(), ManifestBackfillClassification.FAILED,
                                ManifestBackfillReason.CLAIM_LOST, "abandoned");
                    } else {
                        failClaimIfOwned(run, item, claim.claimToken(),
                                ManifestBackfillReason.MANUAL_REVIEW, false,
                                claimFailure.getClass().getSimpleName());
                    }
                } catch (RuntimeException failure) {
                    failClaimIfOwned(run, item, claim.claimToken(),
                            ManifestBackfillReason.MANUAL_REVIEW, false,
                            failure.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Records a claimed failure unless the lease fence proves that another worker now owns it.
     */
    private void failClaimIfOwned(
            ManifestBackfillRun run,
            ManifestBackfillItem item,
            String claimToken,
            ManifestBackfillReason reason,
            boolean retryable,
            String errorClass
    ) {
        try {
            claimService.failClaim(item, claimToken, reason, retryable, errorClass);
            recordMetric(run.getMode(), ManifestBackfillClassification.FAILED,
                    reason, retryable ? "retryable" : "rejected");
        } catch (IllegalStateException claimFailure) {
            if (!isClaimLost(claimFailure)) {
                throw claimFailure;
            }
            recordMetric(run.getMode(), ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.CLAIM_LOST, "abandoned");
        }
    }

    /**
     * Recognizes the exact stable lease-fence reason without matching localized text.
     */
    private boolean isClaimLost(IllegalStateException failure) {
        return ManifestBackfillReason.CLAIM_LOST.name().equals(failure.getMessage());
    }

    /**
     * Classifies apply-time evidence failures that are safe to retry without changing the snapshot.
     */
    private boolean isRetryableEvidenceFailure(ManifestBackfillReason reason) {
        return reason == ManifestBackfillReason.STORAGE_RPC_TRANSIENT
                || reason == ManifestBackfillReason.CHAIN_RPC_TRANSIENT
                || reason == ManifestBackfillReason.DATABASE_TRANSIENT;
    }

    /**
     * Converts an evidence resolution into a unique run-scoped item row.
     */
    private ManifestBackfillItem toItem(
            Long runId,
            File file,
            ManifestEvidenceResolution resolution
    ) {
        return new ManifestBackfillItem()
                .setId(snowflakeIdGenerator.nextId())
                .setRunId(runId)
                .setTenantId(file.getTenantId())
                .setFileId(file.getId())
                .setFileVersion(file.getVersion() == null ? 0 : file.getVersion())
                .setOwnerUserId(file.getUid())
                .setStatus(resolution.status().name())
                .setClassification(resolution.classification().name())
                .setReasonCode(resolution.reason().name())
                .setRetryable(resolution.retryable() ? 1 : 0)
                .setLegacyDownloadAllowed(resolution.legacyDownloadAllowed() ? 1 : 0)
                .setEvidenceDigest(resolution.evidenceDigest())
                .setEvidencePayload(resolution.evidencePayload())
                .setManifestId(resolution.manifestId())
                .setAttemptCount(0)
                .setDeleted(0);
    }

    /**
     * Converts resolver infrastructure failures into stable outcomes instead of skipping a file.
     */
    private ManifestEvidenceResolution resolveSafely(File file) {
        try {
            return evidenceResolver.resolve(file);
        } catch (DataAccessException databaseFailure) {
            return resolverFailure(file, databaseFailure, ManifestBackfillReason.DATABASE_TRANSIENT, true);
        } catch (RuntimeException failure) {
            return resolverFailure(file, failure, ManifestBackfillReason.MANUAL_REVIEW, false);
        }
    }

    /**
     * Builds a non-secret deterministic failure record for one resolver exception class.
     */
    private ManifestEvidenceResolution resolverFailure(
            File file,
            RuntimeException failure,
            ManifestBackfillReason reason,
            boolean retryable
    ) {
        String digest = sha256(String.join("\n", SNAPSHOT_VERSION,
                Objects.toString(file.getTenantId(), ""),
                Objects.toString(file.getId(), ""),
                failure.getClass().getSimpleName()));
        return new ManifestEvidenceResolution(
                ManifestBackfillItemStatus.FAILED,
                ManifestBackfillClassification.FAILED,
                reason,
                retryable,
                false,
                digest,
                null,
                null);
    }

    /**
     * Finalizes deterministic counts and the ordered snapshot digest.
     */
    private void finalizeRun(Long runId, ManifestBackfillMode mode) {
        ManifestBackfillRun run = runMapper.selectRunGlobally(runId);
        if (run == null || ManifestBackfillRunStatus.PAUSED.name().equals(run.getStatus())) {
            return;
        }
        List<ManifestBackfillItem> items = itemMapper.selectRunItems(runId, run.getTenantId());
        StringBuilder digestInput = new StringBuilder(SNAPSHOT_VERSION);
        long pending = 0L;
        long backfilled = 0L;
        long reupload = 0L;
        long unrecoverable = 0L;
        long ignored = 0L;
        long failed = 0L;
        boolean retryPending = false;
        for (ManifestBackfillItem item : items) {
            digestInput.append('\n').append(item.getFileId())
                    .append(':').append(item.getFileVersion())
                    .append(':').append(item.getClassification())
                    .append(':').append(item.getReasonCode())
                    .append(':').append(item.getEvidenceDigest());
            switch (ManifestBackfillItemStatus.valueOf(item.getStatus())) {
                case PENDING, RUNNING -> pending++;
                case BACKFILLED -> backfilled++;
                case REUPLOAD_REQUIRED -> reupload++;
                case UNRECOVERABLE -> unrecoverable++;
                case IGNORED -> ignored++;
                case FAILED -> failed++;
            }
            retryPending = retryPending
                    || ManifestBackfillItemStatus.PENDING.name().equals(item.getStatus())
                    || ManifestBackfillItemStatus.RUNNING.name().equals(item.getStatus())
                    || (ManifestBackfillItemStatus.FAILED.name().equals(item.getStatus())
                    && Integer.valueOf(1).equals(item.getRetryable())
                    && (item.getAttemptCount() == null || item.getAttemptCount() < DEFAULT_MAX_ATTEMPTS));
        }
        boolean shouldReplan = mode == ManifestBackfillMode.APPLY && (retryPending || pending > 0L);
        ManifestBackfillRun update = new ManifestBackfillRun()
                .setId(runId)
                .setStatus(mode == ManifestBackfillMode.SCAN
                        ? ManifestBackfillRunStatus.SNAPSHOT_READY.name()
                        : shouldReplan
                        ? ManifestBackfillRunStatus.PLANNED.name()
                        : ManifestBackfillRunStatus.COMPLETED.name())
                .setSnapshotDigest(mode == ManifestBackfillMode.SCAN
                        ? sha256(digestInput.toString())
                        : run.getSnapshotDigest())
                .setTotalCount((long) items.size())
                .setPendingCount(pending)
                .setBackfilledCount(backfilled)
                .setReuploadCount(reupload)
                .setUnrecoverableCount(unrecoverable)
                .setIgnoredCount(ignored)
                .setFailedCount(failed)
                .setCompletedAt(shouldReplan ? null : new Date());
        runMapper.updateById(update);
    }

    /**
     * Refreshes the crash-recovery lease at bounded scan/apply work boundaries.
     */
    private void touchRunLease(ManifestBackfillRun run, ManifestBackfillRunStatus status) {
        if (runMapper.touchRunLease(run.getId(), run.getTenantId(), status.name()) != 1) {
            throw new IllegalStateException(ManifestBackfillReason.CLAIM_LOST.name());
        }
    }

    /**
     * Marks an unexpected run-level failure without deleting item evidence.
     */
    private void markRunFailed(Long runId, RuntimeException failure) {
        ManifestBackfillRun update = new ManifestBackfillRun()
                .setId(runId)
                .setStatus(ManifestBackfillRunStatus.FAILED.name())
                .setLastErrorClass(truncate(failure.getClass().getSimpleName()))
                .setCompletedAt(new Date());
        runMapper.updateById(update);
    }

    /**
     * Loads and validates a source scan as an immutable same-tenant snapshot.
     */
    private ManifestBackfillRun requireFrozenSnapshot(Long tenantId, Long snapshotRunId) {
        if (snapshotRunId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                    "dry-run/apply requires a frozen snapshot run");
        }
        ManifestBackfillRun source = runMapper.selectRunGlobally(snapshotRunId);
        if (source == null
                || !Objects.equals(source.getTenantId(), tenantId)
                || !StringUtils.hasText(source.getSnapshotDigest())
                || !(ManifestBackfillRunStatus.SNAPSHOT_READY.name().equals(source.getStatus())
                || ManifestBackfillRunStatus.COMPLETED.name().equals(source.getStatus()))) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE);
        }
        return source;
    }

    /**
     * Checks the durable pause flag between keyset/claim batches.
     */
    private boolean isPaused(Long runId) {
        ManifestBackfillRun current = runMapper.selectRunGlobally(runId);
        return current == null || ManifestBackfillRunStatus.PAUSED.name().equals(current.getStatus());
    }

    /**
     * Records bounded-label metrics without file, path, hash, tenant, or run identifiers.
     */
    private void recordMetric(
            String mode,
            ManifestBackfillClassification classification,
            ManifestBackfillReason reason,
            String result
    ) {
        meterRegistry.counter("app.manifest.backfill.items", Tags.of(
                "mode", mode,
                "classification", classification.name(),
                "reason_family", reasonFamily(reason),
                "result", result)).increment();
    }

    /**
     * Collapses detailed reasons into a bounded operational label family.
     */
    private String reasonFamily(ManifestBackfillReason reason) {
        return switch (reason) {
            case CHAIN_RPC_TRANSIENT, STORAGE_RPC_TRANSIENT, DATABASE_TRANSIENT,
                    DELETE_RPC_TRANSIENT -> "DEPENDENCY";
            case DUPLICATE_ACTIVE_MANIFEST, ACTIVE_MANIFEST_INVALID, MANUAL_REVIEW -> "CONFLICT";
            case OBJECT_NOT_FOUND, OBJECT_HEAD_MISMATCH, DELETE_HEAD_MISMATCH -> "OBJECT";
            case CROSS_TENANT_PATH -> "TENANT";
            case FILE_NOT_SUCCESS, FILE_DELETED, VERSION_UNSTABLE -> "ELIGIBILITY";
            case BACKFILLABLE_EVIDENCE, ALREADY_MANIFEST -> "SUCCESS";
            default -> "EVIDENCE";
        };
    }

    /**
     * Normalizes optional exact-match administrator filters.
     */
    private String normalizeFilter(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * Calculates a lowercase sha256-prefixed digest.
     */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Bounds an error class to the schema length.
     */
    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 128 ? value : value.substring(0, 128);
    }
}
