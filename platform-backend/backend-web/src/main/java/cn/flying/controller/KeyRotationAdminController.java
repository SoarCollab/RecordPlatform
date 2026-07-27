package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.KeyRotationItem;
import cn.flying.dao.entity.KeyRotationPolicy;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.vo.admin.KeyRotationItemPageVO;
import cn.flying.dao.vo.admin.KeyRotationItemVO;
import cn.flying.dao.vo.admin.KeyRotationPolicyRequest;
import cn.flying.dao.vo.admin.KeyRotationPolicyVO;
import cn.flying.dao.vo.admin.KeyRotationRunVO;
import cn.flying.dao.vo.admin.KeyRotationStartRequest;
import cn.flying.service.key.rotation.KeyRotationPolicyCommand;
import cn.flying.service.key.rotation.KeyRotationPolicyService;
import cn.flying.service.key.rotation.KeyRotationRunCreationService;
import cn.flying.service.key.rotation.KeyRotationRunService;
import cn.flying.service.key.rotation.KeyRotationStates;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Tenant-isolated administrator control plane for automated file-key rotation.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/key-rotation")
@PreAuthorize("isAdmin()")
@Tag(name = "Admin - Key Rotation", description = "Automated envelope rotation governance")
public class KeyRotationAdminController {

    private final KeyRotationPolicyService policyService;
    private final KeyRotationRunCreationService runCreationService;
    private final KeyRotationRunService runService;

    /**
     * Creates or replaces the tenant policy from the active provider target snapshot.
     */
    @PutMapping("/policy")
    @Operation(summary = "Create or update the tenant key rotation policy")
    @OperationLog(module = "key-rotation", operationType = "update",
            description = "Update key rotation policy", saveRequestData = false)
    public Result<KeyRotationPolicyVO> savePolicy(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @RequestBody @Valid KeyRotationPolicyRequest request
    ) {
        KeyRotationPolicyCommand command = new KeyRotationPolicyCommand(
                request.expectedProvider(), request.expectedProviderContract(),
                request.expectedProviderKeyVersion(), request.targetLogicalKeyVersion(),
                request.batchSize(), request.maxItemsPerMinute(), request.scheduleEnabled(),
                request.scheduleIntervalSeconds(), request.maxAttempts(),
                request.initialBackoffSeconds(), request.maxBackoffSeconds(),
                request.leaseSeconds(), request.gracePeriodSeconds());
        return Result.success(toPolicyVO(policyService.save(tenantId, userId, command)));
    }

    /**
     * Returns the current tenant policy without its raw provider key identifier.
     */
    @GetMapping("/policy")
    @Operation(summary = "Get the tenant key rotation policy")
    @OperationLog(module = "key-rotation", operationType = "query", description = "Get key rotation policy")
    public Result<KeyRotationPolicyVO> getPolicy(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId
    ) {
        return Result.success(toPolicyVO(policyService.get(tenantId)));
    }

    /**
     * Pauses future policy scheduling without changing an existing run snapshot.
     */
    @PostMapping("/policy/pause")
    @Operation(summary = "Pause the tenant key rotation policy")
    @OperationLog(module = "key-rotation", operationType = "pause", description = "Pause key rotation policy")
    public Result<KeyRotationPolicyVO> pausePolicy(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId
    ) {
        return Result.success(toPolicyVO(policyService.changeStatus(
                tenantId, userId, KeyRotationStates.POLICY_PAUSED)));
    }

    /**
     * Resumes future scheduling from the durable policy boundary.
     */
    @PostMapping("/policy/resume")
    @Operation(summary = "Resume the tenant key rotation policy")
    @OperationLog(module = "key-rotation", operationType = "resume", description = "Resume key rotation policy")
    public Result<KeyRotationPolicyVO> resumePolicy(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId
    ) {
        return Result.success(toPolicyVO(policyService.changeStatus(
                tenantId, userId, KeyRotationStates.POLICY_ACTIVE)));
    }

    /**
     * Disables future scheduling while retaining all historical governance evidence.
     */
    @PostMapping("/policy/disable")
    @Operation(summary = "Disable the tenant key rotation policy")
    @OperationLog(module = "key-rotation", operationType = "disable", description = "Disable key rotation policy")
    public Result<KeyRotationPolicyVO> disablePolicy(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId
    ) {
        return Result.success(toPolicyVO(policyService.changeStatus(
                tenantId, userId, KeyRotationStates.POLICY_DISABLED)));
    }

    /**
     * Acknowledges an externally performed retirement only after all application gates are satisfied.
     */
    @PostMapping("/policy/retirement/acknowledge")
    @Operation(summary = "Acknowledge external old-key retirement")
    @OperationLog(module = "key-rotation", operationType = "retire",
            description = "Acknowledge external key retirement")
    public Result<KeyRotationPolicyVO> acknowledgeRetirement(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId
    ) {
        return Result.success(toPolicyVO(policyService.acknowledgeRetirement(
                tenantId, userId, Instant.now())));
    }

    /**
     * Starts an idempotent manual dry-run or apply execution.
     */
    @PostMapping("/runs")
    @Operation(summary = "Start a manual key rotation run")
    @OperationLog(module = "key-rotation", operationType = "create", description = "Start key rotation run")
    public Result<KeyRotationRunVO> startRun(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @RequestBody @Valid KeyRotationStartRequest request
    ) {
        return Result.success(toRunVO(runCreationService.startManual(
                tenantId, userId, request.mode(), request.requestId(), Instant.now())));
    }

    /**
     * Lists bounded tenant rotation history newest first.
     */
    @GetMapping("/runs")
    @Operation(summary = "List key rotation runs")
    @OperationLog(module = "key-rotation", operationType = "query", description = "List key rotation runs")
    public Result<List<KeyRotationRunVO>> listRuns(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return Result.success(runService.listRuns(tenantId, limit).stream().map(this::toRunVO).toList());
    }

    /**
     * Returns one tenant-owned run by opaque external ID.
     */
    @GetMapping("/runs/{runId}")
    @Operation(summary = "Get a key rotation run")
    @OperationLog(module = "key-rotation", operationType = "query", description = "Get key rotation run")
    public Result<KeyRotationRunVO> getRun(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId
    ) {
        return Result.success(toRunVO(runService.getRun(tenantId, decodeId(runId))));
    }

    /**
     * Pages sanitized per-envelope outcomes using an opaque cursor.
     */
    @GetMapping("/runs/{runId}/items")
    @Operation(summary = "List key rotation run items")
    @OperationLog(module = "key-rotation", operationType = "query", description = "List key rotation items")
    public Result<KeyRotationItemPageVO> listItems(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit
    ) {
        Long internalRunId = decodeId(runId);
        List<KeyRotationItem> items = runService.listItems(
                tenantId, internalRunId, cursor == null ? null : decodeId(cursor), limit);
        List<KeyRotationItemVO> records = items.stream().map(this::toItemVO).toList();
        String nextCursor = items.size() < Math.max(1, Math.min(limit, 100))
                ? null : IdUtils.toExternalId(items.getLast().getId());
        return Result.success(new KeyRotationItemPageVO(records, nextCursor));
    }

    /**
     * Pauses a run at its next durable claim boundary.
     */
    @PostMapping("/runs/{runId}/pause")
    @Operation(summary = "Pause a key rotation run")
    @OperationLog(module = "key-rotation", operationType = "pause", description = "Pause key rotation run")
    public Result<KeyRotationRunVO> pauseRun(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId
    ) {
        return Result.success(toRunVO(runService.pause(tenantId, userId, decodeId(runId))));
    }

    /**
     * Resumes a paused run from its existing cursor and item attempts.
     */
    @PostMapping("/runs/{runId}/resume")
    @Operation(summary = "Resume a key rotation run")
    @OperationLog(module = "key-rotation", operationType = "resume", description = "Resume key rotation run")
    public Result<KeyRotationRunVO> resumeRun(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId
    ) {
        return Result.success(toRunVO(runService.resume(tenantId, userId, decodeId(runId))));
    }

    /**
     * Cancels future discovery and claims for a run.
     */
    @PostMapping("/runs/{runId}/cancel")
    @Operation(summary = "Cancel a key rotation run")
    @OperationLog(module = "key-rotation", operationType = "cancel", description = "Cancel key rotation run")
    public Result<KeyRotationRunVO> cancelRun(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId
    ) {
        return Result.success(toRunVO(runService.cancel(tenantId, userId, decodeId(runId))));
    }

    /**
     * Requeues retryable terminal failures for an explicit operator retry.
     */
    @PostMapping("/runs/{runId}/retry")
    @Operation(summary = "Retry retryable key rotation items")
    @OperationLog(module = "key-rotation", operationType = "retry", description = "Retry key rotation run")
    public Result<KeyRotationRunVO> retryRun(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @PathVariable String runId
    ) {
        return Result.success(toRunVO(runService.retry(tenantId, userId, decodeId(runId))));
    }

    /**
     * Decodes a public ID into an internal Snowflake identifier with stable error semantics.
     */
    private Long decodeId(String externalId) {
        try {
            Long decoded = IdUtils.fromExternalId(externalId);
            if (decoded == null) {
                throw new IllegalArgumentException("decoded ID is null");
            }
            return decoded;
        } catch (RuntimeException invalidId) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "外部标识无效");
        }
    }

    /**
     * Maps a policy while deliberately omitting its raw provider key ID.
     */
    private KeyRotationPolicyVO toPolicyVO(KeyRotationPolicy policy) {
        return new KeyRotationPolicyVO(
                IdUtils.toExternalId(policy.getId()), policy.getStatus(), policy.getTargetProvider(),
                value(policy.getTargetProviderContract()), policy.getTargetProviderKeyVersion(),
                value(policy.getTargetLogicalKeyVersion()), value(policy.getBatchSize()),
                value(policy.getMaxItemsPerMinute()), Integer.valueOf(1).equals(policy.getScheduleEnabled()),
                policy.getScheduleIntervalSeconds(), policy.getNextRunAt(), value(policy.getMaxAttempts()),
                value(policy.getInitialBackoffSeconds()), value(policy.getMaxBackoffSeconds()),
                value(policy.getLeaseSeconds()), value(policy.getGracePeriodSeconds()),
                value(policy.getPolicyVersion()), IdUtils.toExternalId(policy.getLastRunId()),
                policy.getRetirementStatus(), policy.getRetirementEligibleAt(),
                policy.getRetirementAcknowledgedAt(), policy.getUpdateTime());
    }

    /**
     * Maps run progress without exposing the raw target key ID or recipient identities.
     */
    private KeyRotationRunVO toRunVO(KeyRotationRun run) {
        return new KeyRotationRunVO(
                IdUtils.toExternalId(run.getId()), IdUtils.toExternalId(run.getPolicyId()),
                run.getTriggerType(), run.getMode(), run.getStatus(), run.getTargetProvider(),
                value(run.getTargetProviderContract()), run.getTargetProviderKeyVersion(),
                value(run.getTargetLogicalKeyVersion()), externalId(run.getSnapshotMaxEnvelopeId()),
                Integer.valueOf(1).equals(run.getDiscoveryComplete()), value(run.getTotalCount()),
                value(run.getPendingCount()), value(run.getRunningCount()), value(run.getSucceededCount()),
                value(run.getSkippedCount()), value(run.getFailedCount()), value(run.getRemainingCount()),
                run.getRetirementStatus(), run.getRetirementEligibleAt(), run.getLastErrorCategory(),
                run.getStartedAt(), run.getCompletedAt(), run.getCreateTime(), run.getUpdateTime());
    }

    /**
     * Maps item progress while omitting source/candidate envelope and recipient IDs.
     */
    private KeyRotationItemVO toItemVO(KeyRotationItem item) {
        return new KeyRotationItemVO(
                IdUtils.toExternalId(item.getId()), IdUtils.toExternalId(item.getRunId()),
                IdUtils.toExternalId(item.getFileId()), item.getRecipientType(), item.getStatus(),
                item.getOutcome(), Integer.valueOf(1).equals(item.getRetryable()),
                value(item.getAttemptCount()), item.getFailureCategory(),
                item.getNextRetryAt(), item.getUpdateTime());
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * Encodes a positive internal boundary without exposing a raw Snowflake identifier.
     */
    private String externalId(Long value) {
        return value == null || value <= 0 ? null : IdUtils.toExternalId(value);
    }
}
