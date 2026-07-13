package cn.flying.service.integrity;

import cn.flying.dao.vo.file.IntegrityCheckStatsVO;
import cn.flying.service.integrity.IntegrityCheckService.IntegrityCheckLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for integrity check level scheduling and configuration safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Integrity check scheduler")
class IntegrityCheckSchedulerTest {

    @Mock
    private IntegrityCheckService integrityCheckService;

    private SimpleMeterRegistry meterRegistry;
    private IntegrityCheckScheduler scheduler;

    /**
     * Creates an in-memory metrics registry and scheduler for each test.
     */
    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new IntegrityCheckScheduler(integrityCheckService, meterRegistry);
    }

    /**
     * Verifies deterministic probability configurations select each supported level.
     */
    @Test
    void selectCheckLevel_shouldHonorEachDeterministicDistribution() {
        setDistribution(1.0, 0.0, 0.0);
        assertThat(selectedLevel()).isEqualTo(IntegrityCheckLevel.LIGHTWEIGHT);

        setDistribution(0.0, 1.0, 0.0);
        assertThat(selectedLevel()).isEqualTo(IntegrityCheckLevel.MEDIUM);

        setDistribution(0.0, 0.0, 1.0);
        assertThat(selectedLevel()).isEqualTo(IntegrityCheckLevel.HEAVY);
    }

    /**
     * Verifies non-finite and out-of-range values are rejected before random selection.
     */
    @Test
    void distributionValidation_shouldRejectUnsafeProbabilities() {
        setDistribution(Double.NaN, 0.0, 1.0);
        assertThat(isDistributionValid()).isFalse();

        setDistribution(-0.1, 0.1, 1.0);
        assertThat(isDistributionValid()).isFalse();

        setDistribution(Double.POSITIVE_INFINITY, 0.0, 0.0);
        assertThat(isDistributionValid()).isFalse();
    }

    /**
     * Verifies the scheduled entry point executes the selected level and records its metric.
     */
    @Test
    void runScheduledCheck_shouldExecuteSelectedLevelAndIncrementCounter() {
        setDistribution(0.0, 1.0, 0.0);
        when(integrityCheckService.checkIntegrityWithLevel(IntegrityCheckLevel.MEDIUM))
                .thenReturn(new IntegrityCheckStatsVO(3, 1, 0));

        scheduler.runScheduledCheck();

        verify(integrityCheckService).checkIntegrityWithLevel(IntegrityCheckLevel.MEDIUM);
        assertThat(meterRegistry.get("integrity.check.level")
                .tag("level", "medium")
                .counter()
                .count()).isEqualTo(1.0);
    }

    /**
     * Updates the scheduler's injected probability configuration for one scenario.
     */
    private void setDistribution(double lightweight, double medium, double heavy) {
        ReflectionTestUtils.setField(scheduler, "lightweightProbability", lightweight);
        ReflectionTestUtils.setField(scheduler, "mediumProbability", medium);
        ReflectionTestUtils.setField(scheduler, "heavyProbability", heavy);
    }

    /**
     * Invokes the private level selector after a deterministic configuration is installed.
     */
    private IntegrityCheckLevel selectedLevel() {
        return ReflectionTestUtils.invokeMethod(scheduler, "selectCheckLevel");
    }

    /**
     * Invokes the private distribution guard with the currently configured total.
     */
    private boolean isDistributionValid() {
        double lightweight = (double) ReflectionTestUtils.getField(scheduler, "lightweightProbability");
        double medium = (double) ReflectionTestUtils.getField(scheduler, "mediumProbability");
        double heavy = (double) ReflectionTestUtils.getField(scheduler, "heavyProbability");
        Boolean valid = ReflectionTestUtils.invokeMethod(
                scheduler, "hasValidDistribution", lightweight + medium + heavy);
        return Boolean.TRUE.equals(valid);
    }
}
