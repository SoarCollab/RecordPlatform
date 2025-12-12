package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 区块链状态 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "区块链状态信息")
public class ChainStatusVO {

    @Schema(description = "当前区块高度")
    private Long blockNumber;

    @Schema(description = "总交易数")
    private Long transactionCount;

    @Schema(description = "失败交易数")
    private Long failedTransactionCount;

    @Schema(description = "节点数量")
    private Integer nodeCount;

    @Schema(description = "链类型", example = "LOCAL_FISCO")
    private String chainType;

    @Schema(description = "是否健康")
    private Boolean healthy;

    @Schema(description = "最后更新时间戳（毫秒）")
    private Long lastUpdateTime;
}
