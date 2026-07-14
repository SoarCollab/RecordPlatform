package cn.flying.service.attestation;

import cn.flying.dao.vo.attestation.AttestationBatchCandidateStats;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 根据 candidate 数量、等待窗口和人工 force 标志决定是否 flush。
 */
@Component
public class AttestationBatchFlushPolicy {

    /**
     * 判断当前租户是否应当领取候选创建 batch。
     */
    public boolean shouldFlush(AttestationBatchCandidateStats stats,
                               Instant now,
                               boolean force,
                               AttestationBatchProductionProperties properties) {
        if (stats == null || stats.getReadyCount() <= 0) {
            return false;
        }
        if (force || stats.getReadyCount() >= properties.effectiveMinBatchSize()) {
            return true;
        }
        if (stats.getOldestReadyAt() == null) {
            return false;
        }
        long waitingSeconds = Math.max(0L, Duration.between(
                stats.getOldestReadyAt().toInstant(), now).getSeconds());
        return waitingSeconds >= properties.effectiveMaxWaitSeconds();
    }

    /**
     * 返回本轮 claim 的有界大小。
     */
    public int claimSize(AttestationBatchCandidateStats stats,
                         AttestationBatchProductionProperties properties) {
        if (stats == null || stats.getReadyCount() <= 0) {
            return 0;
        }
        return (int) Math.min(stats.getReadyCount(), properties.effectiveMaxBatchSize());
    }
}
