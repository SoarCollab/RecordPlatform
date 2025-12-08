package cn.flying.service.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Saga/Outbox 监控指标定时刷新任务。
 * 定期从数据库获取最新状态并更新 Prometheus 指标。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaMetricsScheduler {

    private final SagaMetrics sagaMetrics;

    /**
     * 每分钟刷新 Saga/Outbox 状态指标
     */
    @Scheduled(fixedDelayString = "${saga.metrics.refresh-interval-ms:60000}")
    public void refreshMetrics() {
        try {
            sagaMetrics.refreshStatus();
            log.debug("Saga/Outbox 状态指标已刷新");
        } catch (Exception e) {
            log.warn("刷新 Saga/Outbox 状态指标失败: {}", e.getMessage());
        }
    }
}
