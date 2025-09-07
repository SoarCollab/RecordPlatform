package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.flying.identity.service.UserStatisticsService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户统计控制器
 * 提供用户相关的统计分析接口（管理员专用）
 * 
 * @author 王贝强
 */
@RestController
@RequestMapping("/api/admin/user-stats")
@Tag(name = "用户统计", description = "用户相关的统计分析功能（管理员专用）")
@SaCheckRole("admin")
public class UserStatisticsController {

    @Autowired
    private UserStatisticsService userStatisticsService;

    /**
     * 获取用户总数统计
     * 
     * @return 用户总数统计
     */
    @GetMapping("/count")
    @Operation(summary = "获取用户总数统计", description = "获取用户总数、活跃用户数、新增用户数等统计信息")
    public Result<Map<String, Object>> getUserCountStats() {
        return userStatisticsService.getUserCountStats();
    }

    /**
     * 获取用户注册趋势
     * 
     * @param days 统计天数
     * @return 注册趋势数据
     */
    @GetMapping("/registration-trend")
    @Operation(summary = "获取用户注册趋势", description = "获取指定天数内的用户注册趋势数据")
    public Result<Map<String, Object>> getRegistrationTrend(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {
        return userStatisticsService.getRegistrationTrend(days);
    }

    /**
     * 获取用户活跃度统计
     * 
     * @param days 统计天数
     * @return 活跃度统计
     */
    @GetMapping("/activity")
    @Operation(summary = "获取用户活跃度统计", description = "获取用户活跃度相关统计数据")
    public Result<Map<String, Object>> getUserActivityStats(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {
        return userStatisticsService.getUserActivityStats(days);
    }

    /**
     * 获取用户角色分布
     * 
     * @return 角色分布统计
     */
    @GetMapping("/role-distribution")
    @Operation(summary = "获取用户角色分布", description = "获取不同角色用户的数量分布")
    public Result<Map<String, Object>> getUserRoleDistribution() {
        return userStatisticsService.getUserRoleDistribution();
    }

    /**
     * 获取用户地理分布
     * 
     * @return 地理分布统计
     */
    @GetMapping("/geographic-distribution")
    @Operation(summary = "获取用户地理分布", description = "获取用户的地理位置分布统计")
    public Result<Map<String, Object>> getUserGeographicDistribution() {
        return userStatisticsService.getUserGeographicDistribution();
    }

    /**
     * 获取用户登录统计
     * 
     * @param days 统计天数
     * @return 登录统计
     */
    @GetMapping("/login-stats")
    @Operation(summary = "获取用户登录统计", description = "获取用户登录相关统计数据")
    public Result<Map<String, Object>> getUserLoginStats(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {
        return userStatisticsService.getUserLoginStats(days);
    }

    /**
     * 获取用户留存率
     * 
     * @param days 统计天数
     * @return 留存率统计
     */
    @GetMapping("/retention-rate")
    @Operation(summary = "获取用户留存率", description = "获取用户留存率统计数据")
    public Result<Map<String, Object>> getUserRetentionRate(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {
        return userStatisticsService.getUserRetentionRate(days);
    }

    /**
     * 获取用户增长率
     * 
     * @param days 统计天数
     * @return 增长率统计
     */
    @GetMapping("/growth-rate")
    @Operation(summary = "获取用户增长率", description = "获取用户增长率统计数据")
    public Result<Map<String, Object>> getUserGrowthRate(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {
        return userStatisticsService.getUserGrowthRate(days);
    }

    /**
     * 获取用户行为统计
     * 
     * @param userId 用户ID
     * @param days 统计天数
     * @return 行为统计
     */
    @GetMapping("/behavior/{userId}")
    @Operation(summary = "获取用户行为统计", description = "获取指定用户的行为统计数据")
    public Result<Map<String, Object>> getUserBehaviorStats(
            @Parameter(description = "用户ID") @PathVariable Long userId,
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {
        return userStatisticsService.getUserBehaviorStats(userId, days);
    }

    /**
     * 获取用户设备统计
     * 
     * @param days 统计天数
     * @return 设备统计
     */
    @GetMapping("/device-stats")
    @Operation(summary = "获取用户设备统计", description = "获取用户使用设备的统计数据")
    public Result<Map<String, Object>> getUserDeviceStats(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {
        return userStatisticsService.getUserDeviceStats(days);
    }

    /**
     * 获取用户统计仪表板数据
     * 
     * @return 仪表板数据
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取用户统计仪表板", description = "获取用户统计仪表板的综合数据")
    public Result<Map<String, Object>> getUserStatsDashboard() {
        try {
            Map<String, Object> dashboard = new java.util.HashMap<>();
            
            // 获取各项统计数据
            Result<Map<String, Object>> countStats = userStatisticsService.getUserCountStats();
            Result<Map<String, Object>> registrationTrend = userStatisticsService.getRegistrationTrend(30);
            Result<Map<String, Object>> activityStats = userStatisticsService.getUserActivityStats(30);
            Result<Map<String, Object>> roleDistribution = userStatisticsService.getUserRoleDistribution();
            Result<Map<String, Object>> growthRate = userStatisticsService.getUserGrowthRate(30);
            
            // 组装仪表板数据
            if (countStats.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("count_stats", countStats.getData());
            }
            if (registrationTrend.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("registration_trend", registrationTrend.getData());
            }
            if (activityStats.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("activity_stats", activityStats.getData());
            }
            if (roleDistribution.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("role_distribution", roleDistribution.getData());
            }
            if (growthRate.getCode() == ResultEnum.SUCCESS.getCode()) {
                dashboard.put("growth_rate", growthRate.getData());
            }
            
            dashboard.put("last_updated", System.currentTimeMillis());
            
            return Result.success(dashboard);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }
}
