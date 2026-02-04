package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 监控指标聚合 VO
 */
@Schema(description = "监控指标聚合信息")
public record MonitorMetricsVO(
        @Schema(description = "系统统计信息")
        SystemStatsVO systemStats,
        @Schema(description = "区块链状态")
        ChainStatusVO chainStatus,
        @Schema(description = "系统健康状态")
        SystemHealthVO health
) {

    public SystemStatsVO getSystemStats() {
        return systemStats;
    }

    public ChainStatusVO getChainStatus() {
        return chainStatus;
    }

    public SystemHealthVO getHealth() {
        return health;
    }
}
