package cn.flying.platformapi.response;

import lombok.Data;

/**
 * @program: RecordPlatform
 * @description: 区块链当前状态信息
 * @author: flyingcoding
 * @create: 2025-03-22 18:02
 */
@Data
public class BlockChainMessage {
    private String blockNumber;
    private String transactionCount;
    private String failedTransactionCount;
}
