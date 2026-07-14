package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.vo.attestation.AttestationBatchProductionRunVO;
import cn.flying.dao.vo.attestation.AttestationBatchProductionStatusVO;
import cn.flying.service.attestation.AttestationBatchProductionRunResult;
import cn.flying.service.attestation.AttestationBatchProductionService;
import cn.flying.service.attestation.AttestationBatchProductionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前租户生产 Merkle batch 的管理员运维入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/attestation-batches/production")
@PreAuthorize("isAdmin()")
@Tag(name = "Admin - Attestation Batches", description = "Production Merkle batch operations")
public class AttestationBatchAdminController {

    private final AttestationBatchProductionService productionService;

    /**
     * 对当前租户执行一次受背压上限保护的人工 force flush。
     */
    @PostMapping("/trigger")
    @Operation(summary = "Trigger production Merkle batches for the current tenant")
    @OperationLog(module = "attestation", operationType = "execute", description = "Trigger production Merkle batches")
    public Result<AttestationBatchProductionRunVO> trigger(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId) {
        return Result.success(toRunVO(productionService.runTenant(tenantId, true)));
    }

    /**
     * 查询当前租户的 feature flag、阈值、candidate backlog 和 due batch。
     */
    @GetMapping("/status")
    @Operation(summary = "Get production Merkle batch status for the current tenant")
    @OperationLog(module = "attestation", operationType = "query", description = "Get production Merkle batch status")
    public Result<AttestationBatchProductionStatusVO> status(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId) {
        return Result.success(toStatusVO(productionService.getStatus(tenantId)));
    }

    /**
     * 把内部 batch ID 转换为外部不可枚举 ID。
     */
    private AttestationBatchProductionRunVO toRunVO(AttestationBatchProductionRunResult result) {
        return new AttestationBatchProductionRunVO(
                result.enabled(),
                result.force(),
                result.candidatesAdmitted(),
                result.candidatesClaimed(),
                result.candidatesDeadLettered(),
                result.batchesRecovered(),
                result.batchesCreated(),
                result.batchesCompleted(),
                result.batchesRetrying(),
                result.batchesManualReview(),
                result.thresholdDeferred(),
                result.batchIds().stream().map(IdUtils::toExternalId).toList());
    }

    /**
     * 把内部状态转换为稳定的管理员响应合同。
     */
    private AttestationBatchProductionStatusVO toStatusVO(AttestationBatchProductionStatus status) {
        return new AttestationBatchProductionStatusVO(
                status.enabled(),
                status.minBatchSize(),
                status.maxBatchSize(),
                status.maxWaitSeconds(),
                status.seedLimit(),
                status.maxBatchesPerRun(),
                status.readyCandidates(),
                status.claimedCandidates(),
                status.batchedCandidates(),
                status.deadLetterCandidates(),
                status.oldestReadyAt(),
                status.dueBatches());
    }
}
