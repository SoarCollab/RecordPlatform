package cn.flying.service.attestation;

/**
 * Merkle 批量存证链提交状态。
 */
public enum AttestationBatchStatus {
    CHAIN_PENDING,
    CHAIN_SUBMITTING,
    CHAIN_RETRY,
    COMPLETED,
    MANUAL_REVIEW;

    /**
     * 返回数据库使用的稳定状态文本。
     */
    public String value() {
        return name();
    }
}
