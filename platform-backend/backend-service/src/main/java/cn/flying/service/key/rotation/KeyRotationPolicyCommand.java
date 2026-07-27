package cn.flying.service.key.rotation;

/**
 * Validated administrator intent for one tenant rotation policy.
 */
public record KeyRotationPolicyCommand(
        String expectedProvider,
        Integer expectedProviderContract,
        String expectedProviderKeyVersion,
        Integer targetLogicalKeyVersion,
        Integer batchSize,
        Integer maxItemsPerMinute,
        Boolean scheduleEnabled,
        Long scheduleIntervalSeconds,
        Integer maxAttempts,
        Long initialBackoffSeconds,
        Long maxBackoffSeconds,
        Long leaseSeconds,
        Long gracePeriodSeconds
) {
}
