package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 监控指标聚合 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "监控指标聚合信息")
public class MonitorMetricsVO {

    @Schema(description = "系统统计信息")
    private SystemStatsVO systemStats;

    @Schema(description = "区块链状态")
    private ChainStatusVO chainStatus;

    @Schema(description = "系统健康状态")
    private SystemHealthVO health;
}
