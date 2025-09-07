package cn.flying.platformapi.response;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 区块链状态信息视图对象
 * 用于返回区块链网络的实时状态信息
 * 包含区块高度、交易统计等关键指标
 * 
 * @program: RecordPlatform
 * @description: 区块链当前状态信息
 * @author: 王贝强
 * @create: 2025-03-22 18:02
 */
@Data
public class BlockChainMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前区块高度
     * 表示区块链网络中最新区块的编号
     */
    private String blockNumber;
    
    /**
     * 总交易数量
     * 区块链网络中已确认的交易总数
     */
    private String transactionCount;
    
    /**
     * 失败交易数量
     * 执行失败或被拒绝的交易数量
     */
    private String failedTransactionCount;
}
