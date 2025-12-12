package cn.flying.fisco_bcos.adapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 区块链状态信息
 * 用于健康检查和监控
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainStatus {

    /**
     * 当前区块高度
     */
    private Long blockNumber;

    /**
     * 总交易数
     */
    private Long transactionCount;

    /**
     * 失败交易数
     */
    private Long failedTransactionCount;

    /**
     * 节点数量
     */
    private Integer nodeCount;

    /**
     * 链类型
     */
    private ChainType chainType;

    /**
     * 是否健康
     */
    private boolean healthy;

    /**
     * 最后更新时间
     */
    private Long lastUpdateTime;
}
