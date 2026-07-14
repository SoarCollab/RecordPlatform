package cn.flying.service.attestation;

import cn.flying.dao.entity.AttestationBatch;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记录生产 Merkle batch 的有界标签指标。
 */
@Component
public class AttestationBatchProductionMetrics {

    private final Counter candidateAdmitted;
    private final Counter candidateBatched;
    private final Counter candidateDeadLetter;
    private final Counter batchCompleted;
    private final Counter batchRetry;
    private final Counter batchManualReview;
    private final Counter runCompleted;
    private final Counter runDisabled;
    private final Counter runFailed;
    private final DistributionSummary batchSize;
    private final Timer batchLatency;
    private final AtomicLong readyBacklog = new AtomicLong();
    private final AtomicLong deadLetterBacklog = new AtomicLong();

    /**
     * 注册所有固定 tag 的计数器、分布和 backlog gauge。
     */
    public AttestationBatchProductionMetrics(MeterRegistry registry) {
        candidateAdmitted = candidateCounter(registry, "admitted");
        candidateBatched = candidateCounter(registry, "batched");
        candidateDeadLetter = candidateCounter(registry, "dead_letter");
        batchCompleted = batchCounter(registry, "completed");
        batchRetry = batchCounter(registry, "retry");
        batchManualReview = batchCounter(registry, "manual_review");
        runCompleted = runCounter(registry, "completed");
        runDisabled = runCounter(registry, "disabled");
        runFailed = runCounter(registry, "failed");
        batchSize = DistributionSummary.builder("app.attestation.batch.size")
                .description("Number of manifest evidence leaves in a production attestation batch")
                .register(registry);
        batchLatency = Timer.builder("app.attestation.batch.latency")
                .description("Time from candidate admission to production batch creation")
                .register(registry);
        Gauge.builder("app.attestation.candidate.backlog", readyBacklog, AtomicLong::get)
                .tag("status", "ready")
                .description("Last observed ready production candidate backlog")
                .register(registry);
        Gauge.builder("app.attestation.candidate.backlog", deadLetterBacklog, AtomicLong::get)
                .tag("status", "dead_letter")
                .description("Last observed dead-letter production candidate backlog")
                .register(registry);
    }

    /**
     * 记录实际新增的 candidate 数量。
     */
    public void recordAdmission(AttestationCandidateAdmissionResult admission) {
        if (admission == null) {
            return;
        }
        candidateAdmitted.increment(admission.readyCandidates());
        candidateDeadLetter.increment(admission.deadLetterCandidates());
    }

    /**
     * 记录 candidate 与 batch 的原子绑定、大小和 admission 延迟。
     */
    public void recordBatchCreated(int candidateCount, Date oldestEligibleAt, Instant now) {
        if (candidateCount <= 0) {
            return;
        }
        candidateBatched.increment(candidateCount);
        batchSize.record(candidateCount);
        if (oldestEligibleAt != null && now != null && !oldestEligibleAt.toInstant().isAfter(now)) {
            batchLatency.record(Duration.between(oldestEligibleAt.toInstant(), now));
        }
    }

    /**
     * 记录 candidate 进入 dead-letter 的数量。
     */
    public void recordCandidateDeadLetter(int count) {
        if (count > 0) {
            candidateDeadLetter.increment(count);
        }
    }

    /**
     * 根据链提交后的 batch 状态记录成功、重试或人工处理。
     */
    public void recordBatchStatus(AttestationBatch batch) {
        if (batch == null || batch.getStatus() == null) {
            batchRetry.increment();
            return;
        }
        if (AttestationBatchStatus.COMPLETED.value().equals(batch.getStatus())) {
            batchCompleted.increment();
        } else if (AttestationBatchStatus.MANUAL_REVIEW.value().equals(batch.getStatus())) {
            batchManualReview.increment();
        } else {
            batchRetry.increment();
        }
    }

    /**
     * 刷新当前执行最后观察到的 candidate backlog。
     */
    public void refreshGlobalBacklog(long readyCount, long deadLetterCount) {
        readyBacklog.set(Math.max(0L, readyCount));
        deadLetterBacklog.set(Math.max(0L, deadLetterCount));
    }

    /**
     * 记录正常完成的一轮运行。
     */
    public void recordRunCompleted() {
        runCompleted.increment();
    }

    /**
     * 记录因 feature flag 关闭而跳过的一轮运行。
     */
    public void recordRunDisabled() {
        runDisabled.increment();
    }

    /**
     * 记录未完成的一轮运行。
     */
    public void recordRunFailed() {
        runFailed.increment();
    }

    /**
     * 创建固定 candidate result tag 的计数器。
     */
    private Counter candidateCounter(MeterRegistry registry, String result) {
        return Counter.builder("app.attestation.candidate.total")
                .tag("result", result)
                .description("Production attestation candidate transitions")
                .register(registry);
    }

    /**
     * 创建固定 batch status tag 的计数器。
     */
    private Counter batchCounter(MeterRegistry registry, String status) {
        return Counter.builder("app.attestation.batch.total")
                .tag("status", status)
                .description("Production attestation batch outcomes")
                .register(registry);
    }

    /**
     * 创建固定 run result tag 的计数器。
     */
    private Counter runCounter(MeterRegistry registry, String result) {
        return Counter.builder("app.attestation.production.run")
                .tag("result", result)
                .description("Production attestation scheduler and manual runs")
                .register(registry);
    }
}
