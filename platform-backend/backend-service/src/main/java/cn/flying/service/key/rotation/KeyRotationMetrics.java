package cn.flying.service.key.rotation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-cardinality progress, failure, and retirement observability for rotation workers.
 */
@Component
public class KeyRotationMetrics {

    private static final Set<String> ALLOWED_OUTCOMES = Set.of(
            "succeeded", "failed", "skipped_already_target",
            "skipped_revoked", "skipped_source_changed", "dry_run_candidate",
            "dry_run_already_target");
    private static final Set<String> ALLOWED_FAILURE_CATEGORIES = Set.of(
            "none", "configuration", "timeout", "throttled", "unavailable",
            "permission_denied", "key_disabled", "key_not_found", "invalid_ciphertext",
            "invalid_request", "invalid_response", "unsupported", "internal");

    private final MeterRegistry meterRegistry;
    private final AtomicLong remaining = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong retirementBlocked = new AtomicLong();

    /**
     * Registers global gauges without tenant, run, key, or recipient labels.
     */
    public KeyRotationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("app.key.rotation.remaining", remaining, AtomicLong::get).register(meterRegistry);
        Gauge.builder("app.key.rotation.failed", failed, AtomicLong::get).register(meterRegistry);
        Gauge.builder("app.key.rotation.retirement_blocked", retirementBlocked, AtomicLong::get)
                .register(meterRegistry);
    }

    /**
     * Increments one stable item outcome and failure-category counter.
     */
    public void recordItem(String outcome, String failureCategory) {
        Counter.builder("app.key.rotation.items")
                .tag("outcome", stableTag(outcome, ALLOWED_OUTCOMES))
                .tag("failure_category", stableTag(failureCategory, ALLOWED_FAILURE_CATEGORIES))
                .register(meterRegistry)
                .increment();
    }

    /**
     * Refreshes aggregate gauges from one durable run snapshot.
     */
    public void refresh(long remainingCount, long failedCount, boolean blocked) {
        remaining.set(Math.max(0L, remainingCount));
        failed.set(Math.max(0L, failedCount));
        retirementBlocked.set(blocked ? 1L : 0L);
    }

    /**
     * Restricts labels to stable lowercase tokens.
     */
    private String stableTag(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_");
        return allowed.contains(normalized) ? normalized : "other";
    }
}
