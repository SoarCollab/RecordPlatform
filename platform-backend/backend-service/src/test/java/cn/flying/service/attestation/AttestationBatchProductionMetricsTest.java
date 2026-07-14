package cn.flying.service.attestation;

import cn.flying.dao.entity.AttestationBatch;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationBatchProductionMetricsTest {

    /**
     * 验证生产指标只使用固定枚举标签，不引入租户、文件、candidate 或 batch 标识。
     */
    @Test
    void metricsShouldKeepTagCardinalityBounded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            new AttestationBatchProductionMetrics(registry);

            assertThat(tagValues(registry, "app.attestation.candidate.total", "result"))
                    .containsExactlyInAnyOrder("admitted", "batched", "dead_letter");
            assertThat(tagValues(registry, "app.attestation.batch.total", "status"))
                    .containsExactlyInAnyOrder("completed", "retry", "manual_review");
            assertThat(tagValues(registry, "app.attestation.production.run", "result"))
                    .containsExactlyInAnyOrder("completed", "disabled", "failed");
            assertThat(tagValues(registry, "app.attestation.candidate.backlog", "status"))
                    .containsExactlyInAnyOrder("ready", "dead_letter");

            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags())
                            .extracting(tag -> tag.getKey())
                            .doesNotContain("tenant", "tenantId", "file", "fileId",
                                    "candidate", "candidateId", "batch", "batchId"));
        } finally {
            registry.close();
        }
    }

    /**
     * 验证生产指标覆盖空输入、有效状态、回退状态、延迟与 backlog 下界。
     */
    @Test
    void metricsShouldRecordEveryBoundedOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            AttestationBatchProductionMetrics metrics = new AttestationBatchProductionMetrics(registry);
            Instant now = Instant.parse("2026-07-14T00:00:00Z");

            metrics.recordAdmission(null);
            metrics.recordAdmission(new AttestationCandidateAdmissionResult(2, 3));
            metrics.recordBatchCreated(0, null, null);
            metrics.recordBatchCreated(2, Date.from(now.minusSeconds(5)), now);
            metrics.recordBatchCreated(1, null, now);
            metrics.recordBatchCreated(1, Date.from(now.minusSeconds(1)), null);
            metrics.recordBatchCreated(1, Date.from(now.plusSeconds(1)), now);
            metrics.recordCandidateDeadLetter(0);
            metrics.recordCandidateDeadLetter(4);
            metrics.recordBatchStatus(null);
            metrics.recordBatchStatus(new AttestationBatch());
            metrics.recordBatchStatus(new AttestationBatch().setStatus(AttestationBatchStatus.COMPLETED.value()));
            metrics.recordBatchStatus(new AttestationBatch().setStatus(AttestationBatchStatus.MANUAL_REVIEW.value()));
            metrics.recordBatchStatus(new AttestationBatch().setStatus(AttestationBatchStatus.CHAIN_RETRY.value()));
            metrics.refreshGlobalBacklog(-1L, 7L);
            metrics.recordRunCompleted();
            metrics.recordRunDisabled();
            metrics.recordRunFailed();

            assertThat(registry.get("app.attestation.candidate.total")
                    .tag("result", "admitted").counter().count()).isEqualTo(2.0);
            assertThat(registry.get("app.attestation.candidate.total")
                    .tag("result", "batched").counter().count()).isEqualTo(5.0);
            assertThat(registry.get("app.attestation.candidate.total")
                    .tag("result", "dead_letter").counter().count()).isEqualTo(7.0);
            assertThat(registry.get("app.attestation.batch.total")
                    .tag("status", "completed").counter().count()).isEqualTo(1.0);
            assertThat(registry.get("app.attestation.batch.total")
                    .tag("status", "manual_review").counter().count()).isEqualTo(1.0);
            assertThat(registry.get("app.attestation.batch.total")
                    .tag("status", "retry").counter().count()).isEqualTo(3.0);
            assertThat(registry.get("app.attestation.batch.latency").timer().count()).isEqualTo(1L);
            assertThat(registry.get("app.attestation.candidate.backlog")
                    .tag("status", "ready").gauge().value()).isZero();
            assertThat(registry.get("app.attestation.candidate.backlog")
                    .tag("status", "dead_letter").gauge().value()).isEqualTo(7.0);
            assertThat(registry.get("app.attestation.production.run")
                    .tag("result", "completed").counter().count()).isEqualTo(1.0);
            assertThat(registry.get("app.attestation.production.run")
                    .tag("result", "disabled").counter().count()).isEqualTo(1.0);
            assertThat(registry.get("app.attestation.production.run")
                    .tag("result", "failed").counter().count()).isEqualTo(1.0);
        } finally {
            registry.close();
        }
    }

    /**
     * 收集指定 meter 名称和标签键的固定标签值。
     */
    private Set<String> tagValues(SimpleMeterRegistry registry, String meterName, String tagKey) {
        return registry.find(meterName).meters().stream()
                .map(Meter::getId)
                .map(id -> id.getTag(tagKey))
                .collect(Collectors.toSet());
    }
}
