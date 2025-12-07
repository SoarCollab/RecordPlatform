package cn.flying.health;

import cn.flying.dao.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Outbox 事件发布健康指标。
 * 监控 Outbox 待发送事件的积压和失败情况。
 */
@Slf4j
@Component("outbox")
@RequiredArgsConstructor
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxEventMapper outboxEventMapper;

    @Value("${outbox.health.pending-threshold:500}")
    private long pendingThreshold;

    @Value("${outbox.health.failed-threshold:20}")
    private long failedThreshold;

    @Value("${outbox.publisher.max-retries:5}")
    private int maxRetries;

    @Override
    public Health health() {
        try {
            long pendingCount = outboxEventMapper.countByStatus("PENDING");
            long failedCount = outboxEventMapper.countByStatus("FAILED");
            long exhaustedCount = outboxEventMapper.countExhaustedRetries(maxRetries);

            Health.Builder builder = Health.up()
                    .withDetail("pending", pendingCount)
                    .withDetail("failed", failedCount)
                    .withDetail("exhaustedRetries", exhaustedCount);

            // 超过最大重试次数的失败事件，标记为 DOWN
            if (exhaustedCount > 0) {
                log.warn("Outbox 健康检查: 存在超过最大重试次数的事件, count={}", exhaustedCount);
                return builder.down()
                        .withDetail("reason", "存在无法发送的事件（超过最大重试次数）")
                        .build();
            }

            // 失败事件超过阈值，标记为 DOWN
            if (failedCount > failedThreshold) {
                log.warn("Outbox 健康检查: 失败事件超过阈值, failed={}, threshold={}",
                        failedCount, failedThreshold);
                return builder.down()
                        .withDetail("reason", "失败事件数量超过阈值")
                        .withDetail("failedThreshold", failedThreshold)
                        .build();
            }

            // 待发送事件超过阈值，标记为 DEGRADED
            if (pendingCount > pendingThreshold) {
                log.warn("Outbox 健康检查: 待发送事件积压, pending={}, threshold={}",
                        pendingCount, pendingThreshold);
                return builder.status("DEGRADED")
                        .withDetail("reason", "待发送事件积压")
                        .withDetail("pendingThreshold", pendingThreshold)
                        .build();
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Outbox 健康检查失败", e);
            return Health.down()
                    .withDetail("reason", "健康检查查询失败")
                    .withException(e)
                    .build();
        }
    }
}
