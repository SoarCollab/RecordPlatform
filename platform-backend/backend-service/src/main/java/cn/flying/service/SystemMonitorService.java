package cn.flying.service;

import cn.flying.dao.vo.system.ChainStatusVO;
import cn.flying.dao.vo.system.MonitorMetricsVO;
import cn.flying.dao.vo.system.SystemHealthVO;
import cn.flying.dao.vo.system.SystemStatsVO;
import cn.flying.platformapi.response.StorageCapacityVO;

/**
 * 系统监控服务接口
 */
public interface SystemMonitorService {

    /**
     * 获取系统统计信息
     *
     * @return 系统统计 VO
     */
    SystemStatsVO getSystemStats();

    /**
     * 获取区块链状态
     *
     * @return 区块链状态 VO
     */
    ChainStatusVO getChainStatus();

    /**
     * 获取系统健康状态
     *
     * @return 系统健康 VO
     */
    SystemHealthVO getSystemHealth();

    /**
     * 获取存储容量统计信息。
     *
     * @return 存储容量统计
     */
    StorageCapacityVO getStorageCapacity();

    /**
     * 获取聚合监控指标
     *
     * @return 监控指标 VO
     */
    MonitorMetricsVO getMonitorMetrics();
}
