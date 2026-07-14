package cn.flying.service.attestation;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

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
     * 收集指定 meter 名称和标签键的固定标签值。
     */
    private Set<String> tagValues(SimpleMeterRegistry registry, String meterName, String tagKey) {
        return registry.find(meterName).meters().stream()
                .map(Meter::getId)
                .map(id -> id.getTag(tagKey))
                .collect(Collectors.toSet());
    }
}
