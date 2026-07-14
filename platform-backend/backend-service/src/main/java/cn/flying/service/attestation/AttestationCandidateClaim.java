package cn.flying.service.attestation;

import cn.flying.dao.entity.AttestationBatchCandidate;

import java.util.Date;
import java.util.List;

/**
 * 一个由 claim token 保护的候选批次快照。
 */
public record AttestationCandidateClaim(
        Long tenantId,
        String claimToken,
        List<AttestationBatchCandidate> candidates
) {

    /**
     * 返回本次 claim 的候选数量。
     */
    public int size() {
        return candidates == null ? 0 : candidates.size();
    }

    /**
     * 返回本次 claim 中最早的候选 admission 时间。
     */
    public Date oldestEligibleAt() {
        if (candidates == null) {
            return null;
        }
        return candidates.stream()
                .map(AttestationBatchCandidate::getEligibleAt)
                .filter(java.util.Objects::nonNull)
                .min(Date::compareTo)
                .map(date -> new Date(date.getTime()))
                .orElse(null);
    }
}
