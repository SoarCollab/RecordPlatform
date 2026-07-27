package cn.flying.service.key.rotation;

import cn.flying.dao.entity.KeyRotationItem;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.mapper.KeyRotationItemMapper;
import cn.flying.dao.mapper.KeyRotationRunMapper;
import cn.flying.service.key.AutomatedEnvelopeRotationResult;
import cn.flying.service.key.KeyWrappingFailure;
import cn.flying.service.key.KeyWrappingFailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies rate limits, crash leases, retry backoff, and token-fenced item completion.
 */
@ExtendWith(MockitoExtension.class)
class KeyRotationClaimServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T01:00:00Z");

    @Mock
    private KeyRotationRunMapper runMapper;

    @Mock
    private KeyRotationItemMapper itemMapper;

    private KeyRotationClaimService service;

    /**
     * Creates the claim coordinator around isolated mapper doubles.
     */
    @BeforeEach
    void setUp() {
        service = new KeyRotationClaimService(runMapper, itemMapper);
    }

    /**
     * Proves the durable minute allowance bounds a SKIP LOCKED claim and increments attempts once.
     */
    @Test
    void shouldClaimOnlyRemainingRateAllowance() {
        KeyRotationRun run = run().setMaxItemsPerMinute(3).setRateWindowCount(1);
        KeyRotationItem first = item(201L, 0);
        KeyRotationItem second = item(202L, 1);
        when(runMapper.selectRunForUpdate(11L, 101L)).thenReturn(run);
        when(itemMapper.selectClaimableForUpdate(
                eq(11L), eq(101L), eq(Date.from(NOW)), eq(3), eq(2)))
                .thenReturn(List.of(first, second));
        when(itemMapper.claimSelected(
                eq(11L), eq(101L), eq(List.of(201L, 202L)), any(), eq(Date.from(NOW)), any(), eq(3)))
                .thenReturn(2);

        KeyRotationClaim claim = service.claim(run, 50, NOW);

        assertThat(claim).isNotNull();
        assertThat(claim.claimToken()).isNotBlank();
        assertThat(claim.items()).extracting(KeyRotationItem::getAttemptCount).containsExactly(1, 2);
        assertThat(run.getRateWindowCount()).isEqualTo(3);
        verify(runMapper).updateById(run);
    }

    /**
     * Proves a provider throttle remains retryable with the first bounded exponential delay.
     */
    @Test
    void shouldScheduleRetryableThrottleWithBackoff() {
        KeyRotationRun run = run();
        KeyRotationItem item = item(201L, 1);
        AutomatedEnvelopeRotationResult failure = AutomatedEnvelopeRotationResult.failed(
                KeyWrappingFailure.of(KeyWrappingFailureCategory.THROTTLED, true));
        when(itemMapper.completeClaim(
                eq(11L), eq(101L), eq(201L), eq("claim"),
                eq(KeyRotationStates.ITEM_FAILED), eq("FAILED"), eq(1),
                eq(null), any(Date.class), eq("THROTTLED"), eq(null)))
                .thenReturn(1);

        service.complete(run, item, "claim", failure, NOW);

        ArgumentCaptor<Date> retryAt = ArgumentCaptor.forClass(Date.class);
        verify(itemMapper).completeClaim(
                eq(11L), eq(101L), eq(201L), eq("claim"),
                eq(KeyRotationStates.ITEM_FAILED), eq("FAILED"), eq(1),
                eq(null), retryAt.capture(), eq("THROTTLED"), eq(null));
        assertThat(retryAt.getValue().toInstant()).isEqualTo(NOW.plusSeconds(5));
    }

    /**
     * Proves the final attempt stops automatic backoff but retains explicit-retry classification.
     */
    @Test
    void shouldStopRetryingAtImmutableAttemptLimit() {
        KeyRotationRun run = run();
        KeyRotationItem item = item(201L, 3);
        AutomatedEnvelopeRotationResult failure = AutomatedEnvelopeRotationResult.failed(
                KeyWrappingFailure.of(KeyWrappingFailureCategory.UNAVAILABLE, true));
        when(itemMapper.completeClaim(
                11L, 101L, 201L, "claim",
                KeyRotationStates.ITEM_FAILED, "FAILED", 1,
                null, null, "UNAVAILABLE", null)).thenReturn(1);

        service.complete(run, item, "claim", failure, NOW);

        verify(itemMapper).completeClaim(
                11L, 101L, 201L, "claim",
                KeyRotationStates.ITEM_FAILED, "FAILED", 1,
                null, null, "UNAVAILABLE", null);
    }

    /**
     * Proves a worker cannot complete an item after another worker has taken its expired lease.
     */
    @Test
    void shouldRejectLostCompletionFence() {
        KeyRotationRun run = run();
        KeyRotationItem item = item(201L, 1);
        when(itemMapper.completeClaim(
                11L, 101L, 201L, "stale-token",
                KeyRotationStates.ITEM_SUCCEEDED, "SUCCEEDED", 0,
                301L, null, "NONE", null)).thenReturn(0);

        assertThatThrownBy(() -> service.complete(
                run, item, "stale-token",
                AutomatedEnvelopeRotationResult.completed("SUCCEEDED", 301L), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim fence");
    }

    /**
     * Proves unexpected errors persist only a bounded exception class and never the exception message.
     */
    @Test
    void shouldSanitizeUnexpectedFailureEvidence() {
        KeyRotationRun run = run();
        KeyRotationItem item = item(201L, 1);
        when(itemMapper.completeClaim(
                eq(11L), eq(101L), eq(201L), eq("claim"),
                eq(KeyRotationStates.ITEM_FAILED), eq("FAILED"), eq(1),
                eq(null), any(Date.class), eq("INTERNAL"), eq(IllegalArgumentException.class.getName())))
                .thenReturn(1);

        service.failUnexpected(run, item, "claim",
                new IllegalArgumentException("provider-secret-response"), NOW);

        verify(itemMapper).completeClaim(
                eq(11L), eq(101L), eq(201L), eq("claim"),
                eq(KeyRotationStates.ITEM_FAILED), eq("FAILED"), eq(1),
                eq(null), any(Date.class), eq("INTERNAL"), eq(IllegalArgumentException.class.getName()));
    }

    /**
     * Builds one immutable run snapshot with deterministic retry and lease bounds.
     */
    private KeyRotationRun run() {
        return new KeyRotationRun()
                .setId(101L)
                .setTenantId(11L)
                .setStatus(KeyRotationStates.RUN_RUNNING)
                .setMaxItemsPerMinute(10)
                .setRateWindowStartedAt(Date.from(NOW.minusSeconds(10)))
                .setRateWindowCount(0)
                .setBatchSize(50)
                .setMaxAttempts(3)
                .setInitialBackoffSeconds(5L)
                .setMaxBackoffSeconds(20L)
                .setLeaseSeconds(120L);
    }

    /**
     * Builds one claimable item with a caller-selected attempt count.
     */
    private KeyRotationItem item(Long id, int attempts) {
        return new KeyRotationItem()
                .setId(id)
                .setTenantId(11L)
                .setRunId(101L)
                .setAttemptCount(attempts);
    }
}
