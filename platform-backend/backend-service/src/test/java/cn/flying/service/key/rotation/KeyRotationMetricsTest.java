package cn.flying.service.key.rotation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies rotation metrics remain low-cardinality and cannot embed key or provider data in tags.
 */
class KeyRotationMetricsTest {

    /**
     * Proves stable outcomes and failure categories preserve their expected metric dimensions.
     */
    @Test
    void shouldRecordStableDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KeyRotationMetrics metrics = new KeyRotationMetrics(registry);

        metrics.recordItem("SKIPPED_REVOKED", "NONE");

        assertThat(registry.get("app.key.rotation.items")
                .tags("outcome", "skipped_revoked", "failure_category", "none")
                .counter().count()).isEqualTo(1.0);
    }

    /**
     * Proves arbitrary values collapse to other instead of becoming raw high-cardinality labels.
     */
    @Test
    void shouldCollapseUnknownValuesWithoutLeakingThem() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KeyRotationMetrics metrics = new KeyRotationMetrics(registry);

        metrics.recordItem("tenant-key-secret", "provider-error-secret");

        assertThat(registry.get("app.key.rotation.items")
                .tags("outcome", "other", "failure_category", "other")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags().toString())
                        .doesNotContain("tenant-key-secret", "provider-error-secret"));
    }

    /**
     * Proves gauges clamp negative durable counters and blank labels normalize to the stable none token.
     */
    @Test
    void shouldClampGaugesAndNormalizeBlankDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KeyRotationMetrics metrics = new KeyRotationMetrics(registry);

        metrics.refresh(-1L, -2L, true);
        metrics.recordItem(" ", null);

        assertThat(registry.get("app.key.rotation.remaining").gauge().value()).isZero();
        assertThat(registry.get("app.key.rotation.failed").gauge().value()).isZero();
        assertThat(registry.get("app.key.rotation.retirement_blocked").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("app.key.rotation.items")
                .tags("outcome", "none", "failure_category", "none")
                .counter().count()).isEqualTo(1.0);
    }
}
