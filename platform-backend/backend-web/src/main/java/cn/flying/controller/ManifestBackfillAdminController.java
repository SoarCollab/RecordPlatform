package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.entity.ManifestBackfillRun;
import cn.flying.dao.entity.ManifestReferenceCensus;
import cn.flying.dao.entity.ManifestReferenceSweepMark;
import cn.flying.dao.vo.manifest.ManifestBackfillCreateRequest;
import cn.flying.dao.vo.manifest.ManifestBackfillItemPageVO;
import cn.flying.dao.vo.manifest.ManifestBackfillItemVO;
import cn.flying.dao.vo.manifest.ManifestBackfillRunVO;
import cn.flying.dao.vo.manifest.ManifestReferenceCensusVO;
import cn.flying.dao.vo.manifest.ManifestReferenceSweepMarkRequest;
import cn.flying.dao.vo.manifest.ManifestReferenceSweepMarkVO;
import cn.flying.service.manifest.backfill.ManifestBackfillMode;
import cn.flying.service.manifest.backfill.ManifestBackfillRunService;
import cn.flying.service.manifest.backfill.ManifestReferenceCensusService;
import cn.flying.service.manifest.backfill.ManifestReferenceSweepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Tenant-isolated administrator operations for manifest backfill and reference cleanup governance.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/manifest-backfill-runs")
@PreAuthorize("isAdmin()")
@Tag(name = "Admin - Manifest Backfill", description = "Manifest backfill and reference sweep governance")
public class ManifestBackfillAdminController {

    private final ManifestBackfillRunService runService;
    private final ManifestReferenceCensusService censusService;
    private final ManifestReferenceSweepService sweepService;

    /**
     * Creates a tenant-scoped asynchronous scan, dry-run, or apply run.
     */
    @PostMapping
    @Operation(summary = "Create a manifest backfill governance run")
    @OperationLog(module = "manifest-backfill", operationType = "create", description = "Create manifest backfill run")
    public Result<ManifestBackfillRunVO> createRun(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @RequestBody @Valid ManifestBackfillCreateRequest request
    ) {
        ManifestBackfillMode mode = parseMode(request.mode());
        Long snapshotRunId = request.snapshotRunId() == null
                ? null : decodeExternalId(request.snapshotRunId());
        return Result.success(toRunVO(runService.createRun(tenantId, userId, mode, snapshotRunId)));
    }

    /**
     * Lists the newest bounded run history for the authenticated tenant.
     */
    @GetMapping
    @Operation(summary = "List manifest backfill runs")
    @OperationLog(module = "manifest-backfill", operationType = "query", description = "List manifest backfill runs")
    public Result<List<ManifestBackfillRunVO>> listRuns(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return Result.success(runService.getRuns(tenantId, limit).stream().map(this::toRunVO).toList());
    }

    /**
     * Returns one run by its non-enumerable external ID.
     */
    @GetMapping("/{runId}")
    @Operation(summary = "Get a manifest backfill run")
    @OperationLog(module = "manifest-backfill", operationType = "query", description = "Get manifest backfill run")
    public Result<ManifestBackfillRunVO> getRun(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId
    ) {
        return Result.success(toRunVO(runService.getRun(tenantId, decodeExternalId(runId))));
    }

    /**
     * Pages access-controlled per-file outcomes without returning raw evidence payloads.
     */
    @GetMapping("/{runId}/items")
    @Operation(summary = "List manifest backfill run items")
    @OperationLog(module = "manifest-backfill", operationType = "query", description = "List manifest backfill items")
    public Result<ManifestBackfillItemPageVO> listItems(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String classification,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "50") int limit
    ) {
        Long internalRunId = decodeExternalId(runId);
        List<ManifestBackfillItem> items = runService.getItems(tenantId, internalRunId,
                cursor == null ? null : decodeExternalId(cursor), status, classification, reason, limit);
        List<ManifestBackfillItemVO> records = items.stream().map(this::toItemVO).toList();
        String nextCursor = items.size() < Math.max(1, Math.min(limit, 100))
                ? null : IdUtils.toExternalId(items.getLast().getId());
        return Result.success(new ManifestBackfillItemPageVO(records, nextCursor));
    }

    /**
     * Pauses a run at the next durable keyset or claim boundary.
     */
    @PostMapping("/{runId}/pause")
    @Operation(summary = "Pause a manifest backfill run")
    @OperationLog(module = "manifest-backfill", operationType = "pause", description = "Pause manifest backfill run")
    public Result<ManifestBackfillRunVO> pause(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId
    ) {
        return Result.success(toRunVO(runService.pause(tenantId, decodeExternalId(runId))));
    }

    /**
     * Resumes a paused run against its same durable snapshot and cursor.
     */
    @PostMapping("/{runId}/resume")
    @Operation(summary = "Resume a manifest backfill run")
    @OperationLog(module = "manifest-backfill", operationType = "resume", description = "Resume manifest backfill run")
    public Result<ManifestBackfillRunVO> resume(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId
    ) {
        return Result.success(toRunVO(runService.resume(tenantId, decodeExternalId(runId))));
    }

    /**
     * Requeues one retryable failed item under its owning run.
     */
    @PostMapping("/{runId}/items/{itemId}/retry")
    @Operation(summary = "Retry a failed manifest backfill item")
    @OperationLog(module = "manifest-backfill", operationType = "retry", description = "Retry manifest backfill item")
    public Result<ManifestBackfillRunVO> retryItem(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId,
            @PathVariable String itemId
    ) {
        return Result.success(toRunVO(runService.retryItem(
                tenantId, decodeExternalId(runId), decodeExternalId(itemId))));
    }

    /**
     * Creates and seals a fresh reference census for the current tenant.
     */
    @PostMapping("/reference-census")
    @Operation(summary = "Create a fresh manifest reference census")
    @OperationLog(module = "manifest-reference", operationType = "census", description = "Create reference census")
    public Result<ManifestReferenceCensusVO> createCensus(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId
    ) {
        return Result.success(toCensusVO(censusService.createCensus(tenantId)));
    }

    /**
     * Starts an independently feature-gated grace mark for one exact storage object.
     */
    @PostMapping("/reference-sweep/marks")
    @Operation(summary = "Mark an unreferenced storage object for grace-period sweep")
    @OperationLog(module = "manifest-reference", operationType = "mark",
            description = "Mark reference sweep object", saveRequestData = false)
    public Result<ManifestReferenceSweepMarkVO> markSweepObject(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @RequestBody @Valid ManifestReferenceSweepMarkRequest request
    ) {
        return Result.success(toSweepMarkVO(sweepService.markObject(
                tenantId, request.storagePath(), request.cipherHash())));
    }

    /**
     * Parses the bounded run mode contract without accepting unknown values.
     */
    private ManifestBackfillMode parseMode(String value) {
        try {
            return ManifestBackfillMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalidMode) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "unsupported manifest backfill mode");
        }
    }

    /**
     * Decodes an external ID and normalizes invalid input into a stable parameter error.
     */
    private Long decodeExternalId(String value) {
        try {
            Long decoded = IdUtils.fromExternalId(value);
            if (decoded == null) {
                throw new IllegalArgumentException("external ID decoded to null");
            }
            return decoded;
        } catch (RuntimeException invalidId) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "invalid external ID");
        }
    }

    /**
     * Maps an internal run without exposing tenant, owner, or keyset identifiers.
     */
    private ManifestBackfillRunVO toRunVO(ManifestBackfillRun run) {
        return new ManifestBackfillRunVO(
                IdUtils.toExternalId(run.getId()),
                IdUtils.toExternalId(run.getSnapshotRunId()),
                run.getMode(), run.getStatus(), run.getSnapshotVersion(), run.getSnapshotDigest(),
                value(run.getTotalCount()), value(run.getPendingCount()), value(run.getBackfilledCount()),
                value(run.getReuploadCount()), value(run.getUnrecoverableCount()), value(run.getIgnoredCount()),
                value(run.getFailedCount()), run.getLastErrorClass(), run.getStartedAt(), run.getCompletedAt(),
                run.getCreateTime(), run.getUpdateTime());
    }

    /**
     * Maps one item while withholding its potentially sensitive evidence payload.
     */
    private ManifestBackfillItemVO toItemVO(ManifestBackfillItem item) {
        return new ManifestBackfillItemVO(
                IdUtils.toExternalId(item.getId()), IdUtils.toExternalId(item.getRunId()),
                IdUtils.toExternalId(item.getFileId()), item.getFileVersion(), item.getStatus(),
                item.getClassification(), item.getReasonCode(), Integer.valueOf(1).equals(item.getRetryable()),
                Integer.valueOf(1).equals(item.getLegacyDownloadAllowed()), item.getEvidenceDigest(),
                IdUtils.toExternalId(item.getManifestId()), item.getAttemptCount() == null ? 0 : item.getAttemptCount(),
                item.getNextRetryAt(), item.getLastErrorClass(), item.getUpdateTime());
    }

    /**
     * Maps the census completion evidence into an external-ID response.
     */
    private ManifestReferenceCensusVO toCensusVO(ManifestReferenceCensus census) {
        return new ManifestReferenceCensusVO(
                IdUtils.toExternalId(census.getId()), census.getStatus(), census.getCensusDigest(),
                value(census.getKnownReferenceCount()), value(census.getUnknownHoldCount()),
                census.getLastErrorClass(), census.getCompletedAt(), census.getCreateTime());
    }

    /**
     * Maps a sweep mark without exposing tenant internals or claim tokens.
     */
    private ManifestReferenceSweepMarkVO toSweepMarkVO(ManifestReferenceSweepMark mark) {
        return new ManifestReferenceSweepMarkVO(
                IdUtils.toExternalId(mark.getId()), mark.getStoragePath(), mark.getCipherHash(),
                value(mark.getContentLength()), mark.getEtag(), IdUtils.toExternalId(mark.getMarkCensusId()),
                mark.getStatus(), mark.getProtectionUntil(), mark.getReasonCode(), mark.getDeletedAt());
    }

    /**
     * Normalizes nullable database counters.
     */
    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
