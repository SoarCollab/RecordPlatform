package cn.flying.service.attestation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 生产 Merkle batch 的启停、flush、背压和租约配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "attestation.production")
public class AttestationBatchProductionProperties {

    private static final int MAX_CANDIDATE_PAGE = 1000;
    private static final int MAX_BATCHES_PER_RUN = 100;
    private static final long MAX_WAIT_SECONDS = 86_400L;
    private static final long MAX_LEASE_SECONDS = 3_600L;

    private boolean enabled = false;

    private long pollIntervalMs = 30_000L;

    private int minBatchSize = 50;

    private int maxBatchSize = 100;

    private long maxWaitSeconds = 600L;

    private int seedLimit = 200;

    private int maxBatchesPerRun = 2;

    private long claimLeaseSeconds = 120L;

    private int candidateMaxAttempts = 3;

    /**
     * 返回限制在数据库批量查询边界内的最大 batch 大小。
     */
    public int effectiveMaxBatchSize() {
        return clamp(maxBatchSize, 1, MAX_CANDIDATE_PAGE);
    }

    /**
     * 返回不大于最大 batch 的有效 flush 阈值。
     */
    public int effectiveMinBatchSize() {
        return clamp(minBatchSize, 1, effectiveMaxBatchSize());
    }

    /**
     * 返回单轮最多 admission 的候选数量。
     */
    public int effectiveSeedLimit() {
        return clamp(seedLimit, 1, MAX_CANDIDATE_PAGE);
    }

    /**
     * 返回单轮最多创建或恢复的 batch 数量。
     */
    public int effectiveMaxBatchesPerRun() {
        return clamp(maxBatchesPerRun, 1, MAX_BATCHES_PER_RUN);
    }

    /**
     * 返回有界的候选最大等待时间。
     */
    public long effectiveMaxWaitSeconds() {
        return clamp(maxWaitSeconds, 1L, MAX_WAIT_SECONDS);
    }

    /**
     * 返回有界的 candidate claim 租约秒数。
     */
    public long effectiveClaimLeaseSeconds() {
        return clamp(claimLeaseSeconds, 30L, MAX_LEASE_SECONDS);
    }

    /**
     * 返回有界的 candidate 最大处理次数。
     */
    public int effectiveCandidateMaxAttempts() {
        return clamp(candidateMaxAttempts, 1, 10);
    }

    /**
     * 把整数配置限制到闭区间。
     */
    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /**
     * 把长整数配置限制到闭区间。
     */
    private long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
