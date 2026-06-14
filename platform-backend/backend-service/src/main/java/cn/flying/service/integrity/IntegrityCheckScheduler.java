package cn.flying.service.integrity;

import cn.flying.dao.vo.file.IntegrityCheckStatsVO;
import cn.flying.service.integrity.IntegrityCheckService.IntegrityCheckLevel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Scheduled trigger for periodic storage integrity checks with tiered strategy.
 * Runs daily at 2am by default; controlled via {@code integrity.check.cron}.
 *
 * <p>Uses probabilistic distribution to select check levels:
 * <ul>
 *   <li>LIGHTWEIGHT (99%): Fast existence checks</li>
 *   <li>MEDIUM (0.9%): Hash verification</li>
 *   <li>HEAVY (0.1%): Full blockchain verification</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "integrity.check.enabled", havingValue = "true", matchIfMissing = true)
public class IntegrityCheckScheduler {

    private final IntegrityCheckService integrityCheckService;
    private final Counter lightweightCounter;
    private final Counter mediumCounter;
    private final Counter heavyCounter;

    @Value("${integrity.check.distribution.lightweight:0.99}")
    private double lightweightProbability;

    @Value("${integrity.check.distribution.medium:0.009}")
    private double mediumProbability;

    @Value("${integrity.check.distribution.heavy:0.001}")
    private double heavyProbability;

    public IntegrityCheckScheduler(IntegrityCheckService integrityCheckService,
                                   MeterRegistry meterRegistry) {
        this.integrityCheckService = integrityCheckService;
        this.lightweightCounter = Counter.builder("integrity.check.level")
                .tag("level", "lightweight")
                .description("Number of lightweight integrity checks executed")
                .register(meterRegistry);
        this.mediumCounter = Counter.builder("integrity.check.level")
                .tag("level", "medium")
                .description("Number of medium integrity checks executed")
                .register(meterRegistry);
        this.heavyCounter = Counter.builder("integrity.check.level")
                .tag("level", "heavy")
                .description("Number of heavy integrity checks executed")
                .register(meterRegistry);
    }

    /**
     * Scheduled integrity check with tiered strategy distribution.
     * Randomly selects check level based on configured probabilities.
     */
    @Scheduled(cron = "${integrity.check.schedule.cron:0 0 2 * * ?}")
    public void runScheduledCheck() {
        IntegrityCheckLevel level = selectCheckLevel();

        log.info("[integrity-check] scheduled check started with level={}", level);
        long start = System.currentTimeMillis();

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(level);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[integrity-check] scheduled check completed in {}ms: level={}, checked={}, mismatches={}, errors={}",
                elapsed, level, stats.totalChecked(), stats.mismatchesFound(), stats.errorsEncountered());

        // Track check level in metrics
        incrementLevelCounter(level);
    }

    /**
     * Manual trigger for heavy check (e.g., via admin endpoint or operational need).
     * Bypasses the random distribution and forces HEAVY level.
     */
    public IntegrityCheckStatsVO triggerManualHeavyCheck() {
        log.info("[integrity-check] manual HEAVY check triggered");
        long start = System.currentTimeMillis();

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(IntegrityCheckLevel.HEAVY);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[integrity-check] manual HEAVY check completed in {}ms: checked={}, mismatches={}, errors={}",
                elapsed, stats.totalChecked(), stats.mismatchesFound(), stats.errorsEncountered());

        heavyCounter.increment();
        return stats;
    }

    /**
     * Select check level based on configured probability distribution.
     * Uses cumulative probability ranges to ensure correct distribution.
     *
     * @return the selected check level
     */
    private IntegrityCheckLevel selectCheckLevel() {
        // Validate distribution sums to ~1.0 (allow small floating point tolerance)
        double total = lightweightProbability + mediumProbability + heavyProbability;
        if (Math.abs(total - 1.0) > 0.01) {
            log.warn("[integrity-check] distribution probabilities sum to {}, expected 1.0. Using defaults.",
                    total);
            return selectCheckLevelWithDefaults();
        }

        double random = ThreadLocalRandom.current().nextDouble();

        // Cumulative ranges: [0, lightweight), [lightweight, lightweight+medium), [lightweight+medium, 1.0]
        if (random < lightweightProbability) {
            return IntegrityCheckLevel.LIGHTWEIGHT;
        } else if (random < lightweightProbability + mediumProbability) {
            return IntegrityCheckLevel.MEDIUM;
        } else {
            return IntegrityCheckLevel.HEAVY;
        }
    }

    /**
     * Fallback distribution when configuration is invalid.
     */
    private IntegrityCheckLevel selectCheckLevelWithDefaults() {
        double random = ThreadLocalRandom.current().nextDouble();
        if (random < 0.99) {
            return IntegrityCheckLevel.LIGHTWEIGHT;
        } else if (random < 0.999) {
            return IntegrityCheckLevel.MEDIUM;
        } else {
            return IntegrityCheckLevel.HEAVY;
        }
    }

    private void incrementLevelCounter(IntegrityCheckLevel level) {
        switch (level) {
            case LIGHTWEIGHT -> lightweightCounter.increment();
            case MEDIUM -> mediumCounter.increment();
            case HEAVY -> heavyCounter.increment();
        }
    }
}
