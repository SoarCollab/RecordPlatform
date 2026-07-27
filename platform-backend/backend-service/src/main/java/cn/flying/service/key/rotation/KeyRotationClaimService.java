package cn.flying.service.key.rotation;

import cn.flying.dao.entity.KeyRotationItem;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.mapper.KeyRotationItemMapper;
import cn.flying.dao.mapper.KeyRotationRunMapper;
import cn.flying.service.key.AutomatedEnvelopeRotationResult;
import cn.flying.service.key.KeyWrappingFailureCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Owns short claim, rate-window, lease, and terminal item transactions.
 */
@Service
@RequiredArgsConstructor
public class KeyRotationClaimService {

    private final KeyRotationRunMapper runMapper;
    private final KeyRotationItemMapper itemMapper;

    /**
     * Claims a rate-limited batch with SKIP LOCKED and an expiring token.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public KeyRotationClaim claim(KeyRotationRun run, int requestedLimit, Instant now) {
        KeyRotationRun lockedRun = runMapper.selectRunForUpdate(run.getTenantId(), run.getId());
        if (lockedRun == null || !KeyRotationStates.RUN_RUNNING.equals(lockedRun.getStatus())) {
            return null;
        }
        int allowance = reserveAllowance(lockedRun, requestedLimit, now);
        if (allowance <= 0) {
            return null;
        }
        Date nowDate = Date.from(now);
        List<KeyRotationItem> candidates = itemMapper.selectClaimableForUpdate(
                lockedRun.getTenantId(), lockedRun.getId(), nowDate,
                lockedRun.getMaxAttempts(), allowance);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        Date leaseExpiresAt = Date.from(now.plus(lockedRun.getLeaseSeconds(), ChronoUnit.SECONDS));
        List<Long> ids = candidates.stream().map(KeyRotationItem::getId).toList();
        int updated = itemMapper.claimSelected(
                lockedRun.getTenantId(), lockedRun.getId(), ids, token, nowDate,
                leaseExpiresAt, lockedRun.getMaxAttempts());
        if (updated != ids.size()) {
            throw new IllegalStateException("rotation claim lost one or more locked rows");
        }
        lockedRun.setRateWindowCount(value(lockedRun.getRateWindowCount()) + updated);
        runMapper.updateById(lockedRun);
        candidates.forEach(item -> item
                .setStatus(KeyRotationStates.ITEM_RUNNING)
                .setClaimToken(token)
                .setLeaseExpiresAt(leaseExpiresAt)
                .setAttemptCount(value(item.getAttemptCount()) + 1));
        return new KeyRotationClaim(
                lockedRun.getTenantId(), lockedRun.getId(), token, List.copyOf(candidates));
    }

    /**
     * Renews one item before its bounded remote provider operation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void renew(KeyRotationRun run, KeyRotationItem item, String token, Instant now) {
        Date leaseExpiresAt = Date.from(now.plus(run.getLeaseSeconds(), ChronoUnit.SECONDS));
        int updated = itemMapper.renewLease(
                run.getTenantId(), run.getId(), item.getId(), token, leaseExpiresAt);
        if (updated != 1) {
            throw new IllegalStateException("rotation claim lease was lost");
        }
        item.setLeaseExpiresAt(leaseExpiresAt);
    }

    /**
     * Persists a sanitized provider result or a bounded retry under the same token fence.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void complete(KeyRotationRun run,
                         KeyRotationItem item,
                         String token,
                         AutomatedEnvelopeRotationResult result,
                         Instant now) {
        boolean success = result.failureCategory() == KeyWrappingFailureCategory.NONE;
        int attempts = value(item.getAttemptCount());
        boolean mayRetry = !success && result.retryable() && attempts < run.getMaxAttempts();
        String status = success
                ? ("SUCCEEDED".equals(result.outcome())
                ? KeyRotationStates.ITEM_SUCCEEDED : KeyRotationStates.ITEM_SKIPPED)
                : KeyRotationStates.ITEM_FAILED;
        Date retryAt = mayRetry ? Date.from(now.plusSeconds(backoffSeconds(run, attempts))) : null;
        int updated = itemMapper.completeClaim(
                run.getTenantId(), run.getId(), item.getId(), token,
                status, success ? result.outcome() : "FAILED",
                !success && result.retryable() ? 1 : 0, result.candidateEnvelopeId(), retryAt,
                result.failureCategory().name(), null);
        if (updated != 1) {
            throw new IllegalStateException("rotation item completion lost its claim fence");
        }
    }

    /**
     * Converts an unexpected internal failure into a bounded retry without persisting its message.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void failUnexpected(KeyRotationRun run,
                               KeyRotationItem item,
                               String token,
                               RuntimeException failure,
                               Instant now) {
        int attempts = value(item.getAttemptCount());
        boolean mayRetry = attempts < run.getMaxAttempts();
        Date retryAt = mayRetry ? Date.from(now.plusSeconds(backoffSeconds(run, attempts))) : null;
        int updated = itemMapper.completeClaim(
                run.getTenantId(), run.getId(), item.getId(), token,
                KeyRotationStates.ITEM_FAILED, "FAILED", 1,
                null, retryAt, KeyWrappingFailureCategory.INTERNAL.name(),
                truncateClass(failure));
        if (updated != 1) {
            throw new IllegalStateException("rotation item failure lost its claim fence");
        }
    }

    /**
     * Reserves capacity in a durable one-minute tenant run window.
     */
    private int reserveAllowance(KeyRotationRun run, int requestedLimit, Instant now) {
        Instant windowStart = run.getRateWindowStartedAt() == null
                ? null : run.getRateWindowStartedAt().toInstant();
        if (windowStart == null || !windowStart.isAfter(now.minusSeconds(60))) {
            run.setRateWindowStartedAt(Date.from(now));
            run.setRateWindowCount(0);
        }
        int remaining = Math.max(0, run.getMaxItemsPerMinute() - value(run.getRateWindowCount()));
        return Math.min(Math.max(1, requestedLimit), remaining);
    }

    /**
     * Calculates the run's bounded exponential retry delay.
     */
    private long backoffSeconds(KeyRotationRun run, int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 20);
        long multiplier = 1L << exponent;
        long delay;
        try {
            delay = Math.multiplyExact(run.getInitialBackoffSeconds(), multiplier);
        } catch (ArithmeticException overflow) {
            delay = run.getMaxBackoffSeconds();
        }
        return Math.min(run.getMaxBackoffSeconds(), delay);
    }

    /**
     * Bounds an unexpected exception to its class name only.
     */
    private String truncateClass(RuntimeException failure) {
        String name = failure == null ? "RuntimeException" : failure.getClass().getName();
        return name.length() <= 128 ? name : name.substring(0, 128);
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
