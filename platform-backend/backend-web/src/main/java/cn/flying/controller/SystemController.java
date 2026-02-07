package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.dao.vo.system.*;
import cn.flying.platformapi.response.StorageCapacityVO;
import cn.flying.service.SystemMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统监控控制器
 * 提供系统统计、区块链状态、健康检查等监控接口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "系统监控", description = "系统监控相关接口，包括系统统计、区块链状态、健康检查等")
public class SystemController {

    @Resource
    private SystemMonitorService systemMonitorService;

    /**
     * 获取系统统计信息
     */
    @GetMapping("/stats")
    @Operation(summary = "获取系统统计信息", description = "返回总用户数、总文件数、存储用量、交易数等统计信息")
    @PreAuthorize("isAdminOrMonitor()")
    @OperationLog(module = "系统监控", operationType = "查询", description = "获取系统统计信息")
    public Result<SystemStatsVO> getSystemStats() {
        return Result.success(systemMonitorService.getSystemStats());
    }

    /**
     * 获取区块链状态
     */
    @GetMapping("/chain-status")
    @Operation(summary = "获取区块链状态", description = "返回区块高度、交易数、节点数、链类型等信息")
    @PreAuthorize("isAdminOrMonitor()")
    @OperationLog(module = "系统监控", operationType = "查询", description = "获取区块链状态")
    public Result<ChainStatusVO> getChainStatus() {
        return Result.success(systemMonitorService.getChainStatus());
    }

    /**
     * 获取系统健康状态
     */
    @GetMapping("/health")
    @Operation(summary = "获取系统健康状态", description = "返回数据库、Redis、区块链、存储等组件的健康状态")
    @PreAuthorize("isAdminOrMonitor()")
    @OperationLog(module = "系统监控", operationType = "查询", description = "获取系统健康状态")
    public Result<SystemHealthVO> getSystemHealth() {
        return Result.success(systemMonitorService.getSystemHealth());
    }

    /**
     * 获取存储容量统计信息
     */
    @GetMapping("/storage-capacity")
    @Operation(summary = "获取存储容量统计", description = "返回存储总容量、已用容量及节点/故障域维度汇总信息")
    @PreAuthorize("isAdminOrMonitor()")
    @OperationLog(module = "系统监控", operationType = "查询", description = "获取存储容量统计")
    public Result<StorageCapacityVO> getStorageCapacity() {
        return Result.success(systemMonitorService.getStorageCapacity());
    }

    /**
     * 获取聚合监控指标
     */
    @GetMapping("/monitor")
    @Operation(summary = "获取聚合监控指标", description = "一次性返回系统统计、区块链状态、健康状态的聚合数据")
    @PreAuthorize("isAdminOrMonitor()")
    @OperationLog(module = "系统监控", operationType = "查询", description = "获取聚合监控指标")
    public Result<MonitorMetricsVO> getMonitorMetrics() {
        return Result.success(systemMonitorService.getMonitorMetrics());
    }
}
