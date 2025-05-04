package cn.flying.platformapi.response;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @program: RecordPlatform
 * @description: 区块链当前状态信息
 * @author: flyingcoding
 * @create: 2025-03-22 18:02
 */
@Data
public class BlockChainMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String blockNumber;
    private String transactionCount;
    private String failedTransactionCount;
}
