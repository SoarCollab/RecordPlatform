package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * Sanitized immutable-target and progress view of one rotation run.
 */
@Schema(description = "Automated key rotation run")
public record KeyRotationRunVO(
        String id,
        String policyId,
        String triggerType,
        String mode,
        String status,
        String targetProvider,
        int targetProviderContract,
        String targetProviderKeyVersion,
        int targetLogicalKeyVersion,
        String snapshotBoundary,
        boolean discoveryComplete,
        long totalCount,
        long pendingCount,
        long runningCount,
        long succeededCount,
        long skippedCount,
        long failedCount,
        long remainingCount,
        String retirementStatus,
        Date retirementEligibleAt,
        String lastErrorCategory,
        Date startedAt,
        Date completedAt,
        Date createTime,
        Date updateTime
) {
}
