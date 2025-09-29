package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.flying.identity.service.UserStatisticsService;
import cn.flying.identity.util.ResponseConverter;
import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户统计管理Controller
 * 提供用户相关的统计分析功能（管理员专用）
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/user-stats")
@Tag(name = "用户统计", description = "用户相关的统计分析功能（管理员专用）")
@SaCheckRole("admin")
public class UserStatisticsController {

    @Resource
    private UserStatisticsService userStatisticsService;

    /**
     * 获取用户总数统计
     */
    @GetMapping("/count")
    @Operation(summary = "获取用户总数统计", description = "获取用户总数、活跃用户数、新增用户数等统计信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserCountStats() {
        Result<Map<String, Object>> result = userStatisticsService.getUserCountStats();
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户注册趋势
     */
    @GetMapping("/registration-trend")
    @Operation(summary = "获取用户注册趋势", description = "获取指定天数内的用户注册趋势数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getRegistrationTrend(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {

        Result<Map<String, Object>> result = userStatisticsService.getRegistrationTrend(days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户活跃度统计
     */
    @GetMapping("/activity")
    @Operation(summary = "获取用户活跃度统计", description = "获取用户活跃度相关统计数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserActivityStats(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {

        Result<Map<String, Object>> result = userStatisticsService.getUserActivityStats(days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户角色分布
     */
    @GetMapping("/role-distribution")
    @Operation(summary = "获取用户角色分布", description = "获取不同角色用户的数量分布")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserRoleDistribution() {
        Result<Map<String, Object>> result = userStatisticsService.getUserRoleDistribution();
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户地理分布
     */
    @GetMapping("/geographic-distribution")
    @Operation(summary = "获取用户地理分布", description = "获取用户的地理位置分布统计")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserGeographicDistribution() {
        Result<Map<String, Object>> result = userStatisticsService.getUserGeographicDistribution();
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户登录统计
     */
    @GetMapping("/login-stats")
    @Operation(summary = "获取用户登录统计", description = "获取用户登录相关统计数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserLoginStats(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {

        Result<Map<String, Object>> result = userStatisticsService.getUserLoginStats(days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户留存率
     */
    @GetMapping("/retention-rate")
    @Operation(summary = "获取用户留存率", description = "获取用户留存率统计数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserRetentionRate(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {

        Result<Map<String, Object>> result = userStatisticsService.getUserRetentionRate(days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户增长率
     */
    @GetMapping("/growth-rate")
    @Operation(summary = "获取用户增长率", description = "获取用户增长率统计数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserGrowthRate(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {

        Result<Map<String, Object>> result = userStatisticsService.getUserGrowthRate(days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户行为统计
     */
    @GetMapping("/behavior/{userId}")
    @Operation(summary = "获取用户行为统计", description = "获取指定用户的行为统计数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserBehaviorStats(
            @Parameter(description = "用户ID") @PathVariable Long userId,
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {

        Result<Map<String, Object>> result = userStatisticsService.getUserBehaviorStats(userId, days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户设备统计
     */
    @GetMapping("/device-stats")
    @Operation(summary = "获取用户设备统计", description = "获取用户使用设备的统计数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserDeviceStats(
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "30") int days) {

        Result<Map<String, Object>> result = userStatisticsService.getUserDeviceStats(days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户统计仪表板
     */
    @GetMapping("/dashboard")
    @Operation(summary = "获取用户统计仪表板", description = "获取用户统计仪表板的综合数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserStatsDashboard() {
        try {
            Map<String, Object> dashboard = new java.util.HashMap<>();

            // 获取各项统计数据
            Result<Map<String, Object>> countStats = userStatisticsService.getUserCountStats();
            Result<Map<String, Object>> registrationTrend = userStatisticsService.getRegistrationTrend(30);
            Result<Map<String, Object>> activityStats = userStatisticsService.getUserActivityStats(30);
            Result<Map<String, Object>> roleDistribution = userStatisticsService.getUserRoleDistribution();
            Result<Map<String, Object>> growthRate = userStatisticsService.getUserGrowthRate(30);

            // 组装仪表板数据
            if (countStats != null && countStats.getData() != null) {
                dashboard.put("count_stats", countStats.getData());
            }
            if (registrationTrend != null && registrationTrend.getData() != null) {
                dashboard.put("registration_trend", registrationTrend.getData());
            }
            if (activityStats != null && activityStats.getData() != null) {
                dashboard.put("activity_stats", activityStats.getData());
            }
            if (roleDistribution != null && roleDistribution.getData() != null) {
                dashboard.put("role_distribution", roleDistribution.getData());
            }
            if (growthRate != null && growthRate.getData() != null) {
                dashboard.put("growth_rate", growthRate.getData());
            }

            dashboard.put("last_updated", System.currentTimeMillis());

            Result<Map<String, Object>> result = Result.success(dashboard);
            RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        } catch (Exception e) {
            log.error("获取用户统计仪表板数据失败", e);
            RestResponse<Map<String, Object>> response = RestResponse.internalServerError(500, "获取仪表板数据失败");
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }
}
