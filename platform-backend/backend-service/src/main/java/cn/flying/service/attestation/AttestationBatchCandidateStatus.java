package cn.flying.service.attestation;

/**
 * 生产 Merkle batch 候选的持久化状态。
 */
public enum AttestationBatchCandidateStatus {
    READY,
    CLAIMED,
    BATCHED,
    DEAD_LETTER;

    /**
     * 返回数据库使用的稳定状态值。
     */
    public String value() {
        return name();
    }
}
