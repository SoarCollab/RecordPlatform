package cn.flying.service.attestation;

import cn.flying.dao.vo.attestation.AttestationBatchCandidateStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationBatchFlushPolicyTest {

    private AttestationBatchProductionProperties properties;
    private AttestationBatchFlushPolicy policy;

    /**
     * 初始化固定阈值和无外部依赖的 flush policy。
     */
    @BeforeEach
    void setUp() {
        properties = new AttestationBatchProductionProperties();
        properties.setMinBatchSize(3);
        properties.setMaxBatchSize(5);
        properties.setMaxWaitSeconds(60);
        policy = new AttestationBatchFlushPolicy();
    }

    /**
     * 验证达到数量阈值时立即 flush。
     */
    @Test
    void shouldFlushWhenSizeThresholdIsReached() {
        AttestationBatchCandidateStats stats = stats(3, Instant.parse("2026-07-14T00:00:00Z"));

        assertThat(policy.shouldFlush(
                stats, Instant.parse("2026-07-14T00:00:01Z"), false, properties)).isTrue();
    }

    /**
     * 验证小租户超过等待窗口后也能生成 batch。
     */
    @Test
    void shouldFlushWhenOldestCandidateExceedsTimeWindow() {
        AttestationBatchCandidateStats stats = stats(1, Instant.parse("2026-07-14T00:00:00Z"));

        assertThat(policy.shouldFlush(
                stats, Instant.parse("2026-07-14T00:01:00Z"), false, properties)).isTrue();
    }

    /**
     * 验证管理员 force 只绕过 flush 阈值，不会在空 backlog 创建 batch。
     */
    @Test
    void forceShouldFlushNonEmptyBacklogOnly() {
        assertThat(policy.shouldFlush(stats(1, Instant.now()), Instant.now(), true, properties)).isTrue();
        assertThat(policy.shouldFlush(stats(0, null), Instant.now(), true, properties)).isFalse();
    }

    /**
     * 验证 claim 大小不会超过配置最大值，异常配置会被安全收敛。
     */
    @Test
    void claimSizeShouldBeBoundedByEffectiveConfiguration() {
        properties.setMinBatchSize(9_999);
        properties.setMaxBatchSize(4);
        properties.setSeedLimit(9_999);
        properties.setMaxBatchesPerRun(0);

        assertThat(properties.effectiveMinBatchSize()).isEqualTo(4);
        assertThat(properties.effectiveMaxBatchSize()).isEqualTo(4);
        assertThat(properties.effectiveSeedLimit()).isEqualTo(1_000);
        assertThat(properties.effectiveMaxBatchesPerRun()).isEqualTo(1);
        assertThat(policy.claimSize(stats(10, Instant.now()), properties)).isEqualTo(4);
    }

    /**
     * 构造带 READY 数量和最老 admission 时间的统计对象。
     */
    private AttestationBatchCandidateStats stats(long readyCount, Instant oldest) {
        AttestationBatchCandidateStats stats = new AttestationBatchCandidateStats();
        stats.setReadyCount(readyCount);
        stats.setOldestReadyAt(oldest == null ? null : Date.from(oldest));
        return stats;
    }
}
