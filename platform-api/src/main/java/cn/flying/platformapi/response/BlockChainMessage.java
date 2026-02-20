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
}
