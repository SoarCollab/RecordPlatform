package cn.flying.service.manifest.backfill;

import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.ManifestReferenceSweepMark;
import cn.flying.dao.mapper.ManifestReferenceSweepMarkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Keeps sweep row locks inside short transactions and returns lease-fenced claims.
 */
@Service
@RequiredArgsConstructor
public class ManifestReferenceSweepClaimService {

    private final ManifestReferenceSweepMarkMapper markMapper;

    /**
     * Claims a bounded due batch without holding database locks during storage RPCs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ManifestReferenceSweepMark> claimDue(int limit, long leaseSeconds) {
        Long tenantId = TenantContext.requireTenantId();
        Date now = new Date();
        Date leaseExpiresAt = Date.from(Instant.now().plusSeconds(leaseSeconds));
        List<ManifestReferenceSweepMark> due = markMapper.selectDueForUpdate(tenantId, now, limit);
        List<ManifestReferenceSweepMark> claimed = new ArrayList<>(due.size());
        for (ManifestReferenceSweepMark mark : due) {
            String token = UUID.randomUUID().toString();
            if (markMapper.claimMark(mark.getId(), tenantId, token, now, leaseExpiresAt) == 1) {
                mark.setClaimToken(token);
                mark.setLeaseExpiresAt(leaseExpiresAt);
                mark.setAttemptCount((mark.getAttemptCount() == null ? 0 : mark.getAttemptCount()) + 1);
                claimed.add(mark);
            }
        }
        return claimed;
    }
}
