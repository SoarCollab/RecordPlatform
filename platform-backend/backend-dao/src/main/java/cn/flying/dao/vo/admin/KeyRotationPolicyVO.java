package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * Sanitized tenant policy view without a raw provider key identifier.
 */
@Schema(description = "Automated key rotation policy")
public record KeyRotationPolicyVO(
        String id,
        String status,
        String targetProvider,
        int targetProviderContract,
        String targetProviderKeyVersion,
        int targetLogicalKeyVersion,
        int batchSize,
        int maxItemsPerMinute,
        boolean scheduleEnabled,
        Long scheduleIntervalSeconds,
        Date nextRunAt,
        int maxAttempts,
        long initialBackoffSeconds,
        long maxBackoffSeconds,
        long leaseSeconds,
        long gracePeriodSeconds,
        long policyVersion,
        String lastRunId,
        String retirementStatus,
        Date retirementEligibleAt,
        Date retirementAcknowledgedAt,
        Date updateTime
) {
}
