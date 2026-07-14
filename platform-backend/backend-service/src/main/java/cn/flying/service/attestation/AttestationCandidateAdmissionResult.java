package cn.flying.service.attestation;

/**
 * 一次持久化候选发现的新增结果。
 */
public record AttestationCandidateAdmissionResult(
        int readyCandidates,
        int deadLetterCandidates
) {

    /**
     * 返回本轮实际新增的候选总数。
     */
    public int totalCandidates() {
        return readyCandidates + deadLetterCandidates;
    }
}
