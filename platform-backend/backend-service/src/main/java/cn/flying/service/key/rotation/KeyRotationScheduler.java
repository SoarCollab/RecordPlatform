package cn.flying.service.key.rotation;

import cn.flying.common.annotation.TenantScope;
import cn.flying.common.lock.DistributedLock;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.mapper.KeyRotationPolicyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Polls due tenant policies and durable runs across application instances.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "key.rotation.enabled", havingValue = "true")
public class KeyRotationScheduler {

    private final KeyRotationPolicyMapper policyMapper;
    private final KeyRotationRunCreationService runCreationService;
    private final KeyRotationRunService runService;
    private final KeyRotationWorkerService workerService;
    private final KeyRotationProperties properties;

    /**
     * Enumerates a bounded tenant page under an explicit cross-tenant scheduler scope.
     */
    @Scheduled(
            fixedDelayString = "${key.rotation.poll-interval-ms:30000}",
            initialDelayString = "${key.rotation.initial-delay-ms:30000}"
    )
    @DistributedLock(key = "key:rotation:scheduler", leaseTime = 300)
    @TenantScope(ignoreIsolation = true)
    public void runScheduled() {
        int limit = Math.max(1, properties.getMaxTenantsPerPoll());
        Instant pollStartedAt = Instant.now();
        List<Long> tenantIds = policyMapper.selectWorkTenantIds(Date.from(pollStartedAt), limit);
        if (tenantIds == null || tenantIds.isEmpty()) {
            return;
        }
        for (Long tenantId : tenantIds) {
            if (tenantId == null || tenantId <= 0) {
                continue;
            }
            try {
                TenantContext.callWithTenantIsolation(tenantId, () -> {
                    Instant now = Instant.now();
                    runCreationService.startScheduledIfDue(tenantId, now);
                    workerService.runTenant(tenantId);
                    runService.refreshLatestRetirementReadiness(tenantId, now);
                    return null;
                });
            } catch (RuntimeException failure) {
                log.error("Automated key rotation tenant cycle failed: errorClass={}",
                        failure.getClass().getSimpleName());
            }
        }
    }
}
