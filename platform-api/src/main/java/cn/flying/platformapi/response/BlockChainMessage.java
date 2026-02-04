package cn.flying.platformapi.response;

import java.io.Serial;
import java.io.Serializable;

/**
 * 区块链当前状态信息
 */
public record BlockChainMessage(
        Long blockNumber,
        Long transactionCount,
        Long failedTransactionCount,
        Integer nodeCount,
        String chainType
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 兼容旧版本构造方法（仅包含区块高度与交易统计字段）。
     *
     * @param blockNumber            当前区块高度
     * @param transactionCount       总交易数
     * @param failedTransactionCount 失败交易数
     */
    public BlockChainMessage(Long blockNumber, Long transactionCount, Long failedTransactionCount) {
        this(blockNumber, transactionCount, failedTransactionCount, null, null);
    }

    // Backward-compatible JavaBean-style getters (used widely in current codebase)
    public Long getBlockNumber() {
        return blockNumber;
    }

    public Long getTransactionCount() {
        return transactionCount;
    }

    public Long getFailedTransactionCount() {
        return failedTransactionCount;
    }

    public Integer getNodeCount() {
        return nodeCount;
    }

    public String getChainType() {
        return chainType;
    }
}
