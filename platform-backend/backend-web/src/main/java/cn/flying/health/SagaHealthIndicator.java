package cn.flying.health;

import cn.flying.common.annotation.TenantScope;
import cn.flying.dao.entity.FileSagaStatus;
import cn.flying.dao.mapper.FileSagaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Saga 分布式事务健康指标。
 * 监控 Saga 的运行状态、失败数量和积压情况。
 * 跨租户统计所有租户的 Saga 状态。
 */
@Slf4j
@Component("saga")
@RequiredArgsConstructor
public class SagaHealthIndicator implements HealthIndicator {

    private final FileSagaMapper sagaMapper;

    @Value("${saga.health.running-threshold:100}")
    private long runningThreshold;

    @Value("${saga.health.failed-threshold:10}")
    private long failedThreshold;

    @Value("${saga.health.pending-compensation-threshold:50}")
    private long pendingCompensationThreshold;

    @Override
    @TenantScope(ignoreIsolation = true)
    public Health health() {
        try {
            long runningCount = sagaMapper.countByStatus(FileSagaStatus.RUNNING.name());
            long failedCount = sagaMapper.countByStatus(FileSagaStatus.FAILED.name());
            long pendingCompensationCount = sagaMapper.countByStatus(FileSagaStatus.PENDING_COMPENSATION.name());
            long compensatingCount = sagaMapper.countByStatus(FileSagaStatus.COMPENSATING.name());

            Health.Builder builder = Health.up()
                    .withDetail("running", runningCount)
                    .withDetail("failed", failedCount)
                    .withDetail("pendingCompensation", pendingCompensationCount)
                    .withDetail("compensating", compensatingCount);

            // 失败数量超过阈值，标记为 DOWN
            if (failedCount > failedThreshold) {
                log.warn("Saga 健康检查: 失败数量超过阈值, failed={}, threshold={}",
                        failedCount, failedThreshold);
                return builder.down()
                        .withDetail("reason", "失败 Saga 数量超过阈值")
                        .withDetail("failedThreshold", failedThreshold)
                        .build();
            }

            // 待补偿数量超过阈值，标记为 DEGRADED
            if (pendingCompensationCount > pendingCompensationThreshold) {
                log.warn("Saga 健康检查: 待补偿数量超过阈值, pending={}, threshold={}",
                        pendingCompensationCount, pendingCompensationThreshold);
                return builder.status("DEGRADED")
                        .withDetail("reason", "待补偿 Saga 数量超过阈值")
                        .withDetail("pendingCompensationThreshold", pendingCompensationThreshold)
                        .build();
            }

            // 运行中数量超过阈值，标记为 DEGRADED
            if (runningCount > runningThreshold) {
                log.warn("Saga 健康检查: 运行中数量超过阈值, running={}, threshold={}",
                        runningCount, runningThreshold);
                return builder.status("DEGRADED")
                        .withDetail("reason", "运行中 Saga 数量超过阈值")
                        .withDetail("runningThreshold", runningThreshold)
                        .build();
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Saga 健康检查失败", e);
            return Health.down()
                    .withDetail("reason", "健康检查查询失败")
                    .withException(e)
                    .build();
        }
    }
}
