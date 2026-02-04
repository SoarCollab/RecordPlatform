package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 区块链状态 VO
 */
@Schema(description = "区块链状态信息")
public record ChainStatusVO(
        @Schema(description = "当前区块高度")
        Long blockNumber,
        @Schema(description = "总交易数")
        Long transactionCount,
        @Schema(description = "失败交易数")
        Long failedTransactionCount,
        @Schema(description = "节点数量")
        Integer nodeCount,
        @Schema(description = "链类型", example = "LOCAL_FISCO")
        String chainType,
        @Schema(description = "是否健康")
        Boolean healthy,
        @Schema(description = "最后更新时间戳（毫秒）")
        Long lastUpdateTime
) {

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

    public Boolean getHealthy() {
        return healthy;
    }

    public Long getLastUpdateTime() {
        return lastUpdateTime;
    }
}
