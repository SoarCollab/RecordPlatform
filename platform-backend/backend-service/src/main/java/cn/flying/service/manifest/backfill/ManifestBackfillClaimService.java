package cn.flying.service.manifest.backfill;

import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.mapper.ManifestBackfillItemMapper;
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
 * Owns short claim/failure transactions so remote evidence work never holds candidate-page locks.
 */
@Service
@RequiredArgsConstructor
public class ManifestBackfillClaimService {

    private static final int MAX_ATTEMPTS = 3;

    private final ManifestBackfillItemMapper itemMapper;

    /**
     * Claims a bounded page with row locks, a random token, and an expiring lease.
     *
     * @param runId apply run ID
     * @param tenantId tenant ID
     * @param limit claim size
     * @param maxAttempts maximum attempts
     * @param leaseSeconds lease duration
     * @param now claim clock
     * @return claim or null when no item is currently due
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ManifestBackfillClaim claim(
            Long runId,
            Long tenantId,
            int limit,
            int maxAttempts,
            long leaseSeconds,
            Instant now
    ) {
        Date nowDate = Date.from(now);
        List<ManifestBackfillItem> candidates = itemMapper.selectClaimableForUpdate(
                runId, tenantId, nowDate, maxAttempts, limit);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        Date leaseExpiresAt = Date.from(now.plus(leaseSeconds, ChronoUnit.SECONDS));
        List<Long> ids = candidates.stream().map(ManifestBackfillItem::getId).toList();
        int updated = itemMapper.claimSelected(
                runId, tenantId, ids, token, nowDate, leaseExpiresAt);
        if (updated != ids.size()) {
            throw new IllegalStateException("manifest backfill claim lost one or more locked rows");
        }
        candidates.forEach(item -> item
                .setStatus(ManifestBackfillItemStatus.RUNNING.name())
                .setClaimToken(token)
                .setLeaseExpiresAt(leaseExpiresAt)
                .setAttemptCount((item.getAttemptCount() == null ? 0 : item.getAttemptCount()) + 1));
        return new ManifestBackfillClaim(tenantId, runId, token, List.copyOf(candidates));
    }

    /**
     * Terminates or schedules one claimed failure through the same claim-token fence.
     *
     * @param item claimed item
     * @param claimToken ownership token
     * @param reason stable failure reason
     * @param retryable retry classification
     * @param errorClass bounded exception class
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void failClaim(
            ManifestBackfillItem item,
            String claimToken,
            ManifestBackfillReason reason,
            boolean retryable,
            String errorClass
    ) {
        int attempts = item.getAttemptCount() == null ? 1 : item.getAttemptCount();
        boolean mayRetry = retryable && attempts < MAX_ATTEMPTS;
        ManifestBackfillReason persistedReason = retryable && !mayRetry
                ? ManifestBackfillReason.MAX_ATTEMPTS_EXHAUSTED
                : reason;
        Date retryAt = mayRetry
                ? Date.from(Instant.now().plusSeconds(backoffSeconds(attempts)))
                : null;
        int updated = itemMapper.completeClaim(
                item.getTenantId(), item.getRunId(), item.getId(), claimToken,
                ManifestBackfillItemStatus.FAILED.name(),
                ManifestBackfillClassification.FAILED.name(), persistedReason.name(), null,
                mayRetry ? 1 : 0, retryAt, truncate(errorClass));
        if (updated != 1) {
            throw new IllegalStateException(ManifestBackfillReason.CLAIM_LOST.name());
        }
    }

    /**
     * Calculates a bounded exponential retry delay.
     */
    private long backoffSeconds(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 6);
        return Math.min(300L, 5L << exponent);
    }

    /**
     * Bounds persisted error-class text.
     */
    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}
