package cn.flying.fisco_bcos.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FISCO 监控指标定时刷新任务。
 * 定期从区块链获取最新状态并更新 Prometheus 指标。
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class FiscoMetricsScheduler {

    private final FiscoMetrics fiscoMetrics;

    /**
     * 每分钟刷新区块链状态指标
     */
    @Scheduled(fixedDelayString = "${fisco.metrics.refresh-interval-ms:60000}")
    public void refreshMetrics() {
        try {
            fiscoMetrics.refreshBlockchainStatus();
            log.debug("区块链状态指标已刷新");
        } catch (Exception e) {
            log.warn("刷新区块链状态指标失败: {}", e.getMessage());
        }
    }
}
