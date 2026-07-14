package cn.flying.service.attestation;

import cn.flying.common.lock.DistributedLock;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时枚举活跃租户并触发生产 Merkle batch。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "attestation.production.enabled", havingValue = "true")
public class AttestationBatchProductionScheduler {

    private final TenantMapper tenantMapper;
    private final AttestationBatchProductionService productionService;
    private final AttestationBatchProductionMetrics metrics;

    /**
     * 使用分布式锁串行化正常调度，并在每个租户独立上下文中运行。
     */
    @Scheduled(
            fixedDelayString = "${attestation.production.poll-interval-ms:30000}",
            initialDelayString = "${attestation.production.initial-delay-ms:30000}"
    )
    @DistributedLock(key = "attestation:production:scheduler", leaseTime = 300)
    public void runScheduled() {
        List<Long> tenantIds = tenantMapper.selectActiveTenantIds();
        if (tenantIds == null || tenantIds.isEmpty()) {
            metrics.refreshGlobalBacklog(0L, 0L);
            return;
        }
        long readyCandidates = 0L;
        long deadLetterCandidates = 0L;
        for (Long tenantId : tenantIds) {
            if (tenantId == null) {
                continue;
            }
            try {
                TenantContext.callWithTenant(tenantId, () -> {
                    productionService.runTenant(tenantId, false);
                    return null;
                });
            } catch (RuntimeException failure) {
                log.error("Scheduled production attestation failed: tenantId={}, reason={}",
                        tenantId, safeMessage(failure), failure);
            }
            try {
                AttestationBatchProductionStatus status = TenantContext.callWithTenant(
                        tenantId,
                        () -> productionService.getStatus(tenantId));
                readyCandidates += status.readyCandidates();
                deadLetterCandidates += status.deadLetterCandidates();
            } catch (RuntimeException statusFailure) {
                log.error("Failed to read attestation backlog status: tenantId={}, reason={}",
                        tenantId, safeMessage(statusFailure), statusFailure);
            }
        }
        metrics.refreshGlobalBacklog(readyCandidates, deadLetterCandidates);
    }

    /**
     * 提取不为空的调度错误摘要。
     */
    private String safeMessage(Throwable throwable) {
        return throwable.getMessage() != null
                ? throwable.getMessage()
                : throwable.getClass().getSimpleName();
    }
}
