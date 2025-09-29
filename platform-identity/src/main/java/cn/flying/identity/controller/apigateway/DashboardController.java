package cn.flying.identity.controller.apigateway;

import cn.flying.identity.gateway.alert.AlertService;
import cn.flying.identity.gateway.cache.ApiGatewayCacheManager;
import cn.flying.identity.gateway.circuitbreaker.CircuitBreakerService;
import cn.flying.identity.gateway.circuitbreaker.FallbackStrategyManager;
import cn.flying.identity.gateway.loadbalance.LoadBalanceManager;
import cn.flying.identity.gateway.pool.ApiGatewayConnectionPoolManager;
import cn.flying.identity.gateway.warmup.ApiGatewayWarmupService;
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

import java.util.HashMap;
import java.util.Map;

/**
 * API网关监控Dashboard控制器
 * 提供符合RESTful规范的实时监控数据和管理功能
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/dashboard")
@Tag(name = "API网关监控Dashboard", description = "提供实时监控数据和管理功能")
public class DashboardController {

    @Resource
    private ApiGatewayConnectionPoolManager connectionPoolManager;

    @Resource
    private ApiGatewayCacheManager cacheManager;

    @Resource
    private ApiGatewayWarmupService warmupService;

    @Resource
    private LoadBalanceManager loadBalanceManager;

    @Resource
    private CircuitBreakerService circuitBreakerService;

    @Resource
    private FallbackStrategyManager fallbackStrategyManager;

    @Resource
    private AlertService alertService;

    /**
     * 获取总体概览
     * GET /api/gateway/dashboard/overview - 获取总览
     */
    @GetMapping("/overview")
    @Operation(summary = "获取总体概览", description = "获取API网关的总体运行状态和关键指标")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getOverview() {
        log.info("获取网关总体概览");
        
        Map<String, Object> overview = new HashMap<>();
        
        try {
            overview.put("connectionPool", connectionPoolManager.getPoolStatistics());
            overview.put("cache", cacheManager.getStatistics());
            overview.put("circuitBreakers", circuitBreakerService.getAllCircuitBreakerMetrics());
            overview.put("alerts", alertService.getStatistics());
            overview.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(RestResponse.ok(overview));
        } catch (Exception e) {
            log.error("获取概览数据失败", e);
            return ResponseEntity.status(500)
                .body(RestResponse.internalServerError(500, "获取概览数据失败: " + e.getMessage()));
        }
    }

    /**
     * 获取连接池监控
     * GET /api/gateway/dashboard/connection-pool - 获取连接池状态
     */
    @GetMapping("/connection-pool")
    @Operation(summary = "获取连接池监控", description = "查看HTTP连接池的详细统计信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getConnectionPoolMetrics() {
        log.debug("获取连接池监控数据");
        
        Map<String, Object> metrics = connectionPoolManager.getPoolStatistics();
        return ResponseEntity.ok(RestResponse.ok(metrics));
    }

    /**
     * 获取缓存监控
     * GET /api/gateway/dashboard/cache - 获取缓存状态
     */
    @GetMapping("/cache")
    @Operation(summary = "获取缓存监控", description = "查看多级缓存的命中率和统计信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getCacheMetrics() {
        log.debug("获取缓存监控数据");
        
        Map<String, Object> metrics = cacheManager.getStatistics();
        return ResponseEntity.ok(RestResponse.ok(metrics));
    }

    /**
     * 获取负载均衡监控
     * GET /api/gateway/dashboard/load-balance/{serviceName} - 获取负载均衡状态
     */
    @GetMapping("/load-balance/{serviceName}")
    @Operation(summary = "获取负载均衡监控", description = "查看指定服务的负载均衡统计信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "404", description = "服务不存在"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getLoadBalanceMetrics(
            @Parameter(description = "服务名称", required = true) @PathVariable String serviceName) {
        
        log.debug("获取负载均衡监控数据: serviceName={}", serviceName);
        Map<String, Object> metrics = loadBalanceManager.getServiceStatistics(serviceName);
        
        return ResponseEntity.ok(RestResponse.ok(metrics));
    }

    /**
     * 获取熔断器监控
     * GET /api/gateway/dashboard/circuit-breaker/{serviceName} - 获取熔断器状态
     */
    @GetMapping("/circuit-breaker/{serviceName}")
    @Operation(summary = "获取熔断器监控", description = "查看指定服务的熔断器状态和统计信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "404", description = "服务不存在"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getCircuitBreakerMetrics(
            @Parameter(description = "服务名称", required = true) @PathVariable String serviceName) {
        
        log.debug("获取熔断器监控数据: serviceName={}", serviceName);
        Map<String, Object> metrics = circuitBreakerService.getCircuitBreakerMetrics(serviceName);
        
        return ResponseEntity.ok(RestResponse.ok(metrics));
    }

    /**
     * 获取降级统计
     * GET /api/gateway/dashboard/fallback - 获取降级统计
     */
    @GetMapping("/fallback")
    @Operation(summary = "获取降级统计", description = "查看降级策略的执行统计信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getFallbackStatistics() {
        log.debug("获取降级统计数据");
        
        Map<String, Object> statistics = fallbackStrategyManager.getFallbackStatistics();
        return ResponseEntity.ok(RestResponse.ok(statistics));
    }

    /**
     * 获取告警统计
     * GET /api/gateway/dashboard/alerts - 获取告警统计
     */
    @GetMapping("/alerts")
    @Operation(summary = "获取告警统计", description = "查看告警服务的统计信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getAlertStatistics() {
        log.debug("获取告警统计数据");
        
        Map<String, Object> statistics = alertService.getStatistics();
        return ResponseEntity.ok(RestResponse.ok(statistics));
    }

    /**
     * 获取预热统计
     * GET /api/gateway/dashboard/warmup - 获取预热统计
     */
    @GetMapping("/warmup")
    @Operation(summary = "获取预热统计", description = "查看缓存预热的统计信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getWarmupStatistics() {
        log.debug("获取预热统计数据");
        
        Map<String, Object> statistics = warmupService.getStatistics();
        return ResponseEntity.ok(RestResponse.ok(statistics));
    }

    /**
     * 触发预热
     * POST /api/gateway/dashboard/warmup/trigger - 触发预热
     */
    @PostMapping("/warmup/trigger")
    @Operation(summary = "触发预热", description = "手动触发缓存预热操作")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "预热完成"),
        @ApiResponse(responseCode = "500", description = "预热失败")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> triggerWarmup() {
        log.info("手动触发缓存预热");
        
        Map<String, Object> result = warmupService.manualWarmup();
        return ResponseEntity.ok(RestResponse.ok(result));
    }

    /**
     * 清空缓存
     * DELETE /api/gateway/dashboard/cache/{cacheName} - 清空缓存
     */
    @DeleteMapping("/cache/{cacheName}")
    @Operation(summary = "清空缓存", description = "清空指定的缓存")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "清空成功"),
        @ApiResponse(responseCode = "400", description = "缓存名称无效"),
        @ApiResponse(responseCode = "500", description = "操作失败")
    })
    public ResponseEntity<Void> clearCache(
            @Parameter(description = "缓存名称", required = true) @PathVariable String cacheName) {
        
        log.info("清空缓存: cacheName={}", cacheName);
        cacheManager.clear(cacheName);
        
        return ResponseEntity.noContent().build();
    }

    /**
     * 调整连接池
     * PUT /api/gateway/dashboard/connection-pool/config - 调整连接池
     */
    @PutMapping("/connection-pool/config")
    @Operation(summary = "调整连接池", description = "动态调整HTTP连接池大小")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "调整成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "500", description = "操作失败")
    })
    public ResponseEntity<RestResponse<Void>> resizeConnectionPool(
            @Parameter(description = "最大连接数", required = true) @RequestParam int maxTotal,
            @Parameter(description = "每个路由最大连接数", required = true) @RequestParam int maxPerRoute) {
        
        log.info("调整连接池大小: maxTotal={}, maxPerRoute={}", maxTotal, maxPerRoute);
        connectionPoolManager.resizePool(maxTotal, maxPerRoute);
        
        return ResponseEntity.ok(RestResponse.ok());
    }

    /**
     * 控制熔断器
     * PUT /api/gateway/dashboard/circuit-breaker/{serviceName}/{action} - 控制熔断器
     */
    @PutMapping("/circuit-breaker/{serviceName}/{action}")
    @Operation(summary = "控制熔断器", description = "手动打开、关闭或重置熔断器")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "操作成功"),
        @ApiResponse(responseCode = "400", description = "无效的操作"),
        @ApiResponse(responseCode = "404", description = "服务不存在"),
        @ApiResponse(responseCode = "500", description = "操作失败")
    })
    public ResponseEntity<RestResponse<String>> controlCircuitBreaker(
            @Parameter(description = "服务名称", required = true) @PathVariable String serviceName,
            @Parameter(description = "操作: open|close|reset", required = true) @PathVariable String action) {
        
        log.info("控制熔断器: serviceName={}, action={}", serviceName, action);
        
        String message;
        switch (action.toLowerCase()) {
            case "open" -> {
                circuitBreakerService.openCircuitBreaker(serviceName);
                message = "熔断器已打开";
            }
            case "close" -> {
                circuitBreakerService.closeCircuitBreaker(serviceName);
                message = "熔断器已关闭";
            }
            case "reset" -> {
                circuitBreakerService.resetCircuitBreaker(serviceName);
                message = "熔断器已重置";
            }
            default -> {
                return ResponseEntity.badRequest()
                    .body(RestResponse.badRequest(400, "无效的操作: " + action));
            }
        }
        
        return ResponseEntity.ok(RestResponse.ok(message));
    }

    /**
     * 更新限流配置
     * PUT /api/gateway/dashboard/rate-limiter/{serviceName} - 更新限流
     */
    @PutMapping("/rate-limiter/{serviceName}")
    @Operation(summary = "更新限流配置", description = "动态调整服务的限流QPS")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "404", description = "服务不存在"),
        @ApiResponse(responseCode = "500", description = "操作失败")
    })
    public ResponseEntity<RestResponse<Void>> updateRateLimiter(
            @Parameter(description = "服务名称", required = true) @PathVariable String serviceName,
            @Parameter(description = "QPS限制", required = true) @RequestParam int qps) {
        
        log.info("更新限流配置: serviceName={}, qps={}", serviceName, qps);
        circuitBreakerService.updateRateLimiterConfig(serviceName, qps);
        
        return ResponseEntity.ok(RestResponse.ok());
    }

    /**
     * 获取实时数据
     * GET /api/gateway/dashboard/realtime - 获取实时数据
     */
    @GetMapping("/realtime")
    @Operation(summary = "获取实时数据", description = "获取用于实时监控的数据")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "500", description = "系统错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getRealtimeMetrics() {
        log.debug("获取实时监控数据");
        
        Map<String, Object> metrics = new HashMap<>();
        
        try {
            // 连接池状态
            Map<String, Object> poolStats = connectionPoolManager.getPoolStatistics();
            metrics.put("poolActive", poolStats.get("activeConnections"));
            metrics.put("poolIdle", poolStats.get("idleConnections"));
            
            // 缓存命中率
            Map<String, Object> cacheStats = cacheManager.getStatistics();
            metrics.put("cacheHitRate", extractCacheHitRate(cacheStats));
            
            // 熔断器状态
            Map<String, Map<String, Object>> cbMetrics = circuitBreakerService.getAllCircuitBreakerMetrics();
            metrics.put("circuitBreakerStates", countCircuitBreakerStates(cbMetrics));
            
            // 告警统计
            Map<String, Object> alertStats = alertService.getStatistics();
            metrics.put("alertCount", alertStats.get("totalSent"));
            
            metrics.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(RestResponse.ok(metrics));
        } catch (Exception e) {
            log.error("获取实时数据失败", e);
            return ResponseEntity.status(500)
                .body(RestResponse.internalServerError(500, "获取实时数据失败: " + e.getMessage()));
        }
    }

    private Map<String, Double> extractCacheHitRate(Map<String, Object> cacheStats) {
        Map<String, Double> hitRates = new HashMap<>();
        if (cacheStats.containsKey("cacheStats")) {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> stats = (Map<String, Map<String, Object>>) cacheStats.get("cacheStats");
            stats.forEach((name, stat) -> {
                if (stat.containsKey("overallHitRate")) {
                    hitRates.put(name, (Double) stat.get("overallHitRate"));
                }
            });
        }
        return hitRates;
    }

    private Map<String, Integer> countCircuitBreakerStates(Map<String, Map<String, Object>> cbMetrics) {
        Map<String, Integer> stateCounts = new HashMap<>();
        stateCounts.put("CLOSED", 0);
        stateCounts.put("OPEN", 0);
        stateCounts.put("HALF_OPEN", 0);
        
        cbMetrics.values().forEach(metrics -> {
            String state = (String) metrics.get("state");
            stateCounts.merge(state, 1, Integer::sum);
        });
        
        return stateCounts;
    }

    /**
     * 健康检查
     * GET /api/gateway/dashboard/health - 健康检查
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查API网关各组件的健康状态")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "系统健康"),
        @ApiResponse(responseCode = "503", description = "系统异常")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getHealthStatus() {
        log.debug("执行健康检查");
        
        Map<String, Object> health = new HashMap<>();
        boolean allHealthy = true;
        
        // 连接池健康检查
        Map<String, Object> poolHealth = checkConnectionPoolHealth();
        health.put("connectionPool", poolHealth);
        if (!"UP".equals(poolHealth.get("status"))) {
            allHealthy = false;
        }
        
        // 缓存健康检查
        Map<String, Object> cacheHealth = checkCacheHealth();
        health.put("cache", cacheHealth);
        if (!"UP".equals(cacheHealth.get("status"))) {
            allHealthy = false;
        }
        
        // 熔断器健康检查
        Map<String, Object> cbHealth = checkCircuitBreakerHealth();
        health.put("circuitBreaker", cbHealth);
        if (!"UP".equals(cbHealth.get("status"))) {
            allHealthy = false;
        }
        
        health.put("overall", allHealthy ? "UP" : "DOWN");
        health.put("timestamp", System.currentTimeMillis());
        
        if (allHealthy) {
            return ResponseEntity.ok(RestResponse.ok(health));
        } else {
            RestResponse<Map<String, Object>> response = RestResponse.serviceUnavailable(503, "系统部分组件异常");
            response.setData(health);
            return ResponseEntity.status(503).body(response);
        }
    }

    private Map<String, Object> checkConnectionPoolHealth() {
        Map<String, Object> health = new HashMap<>();
        try {
            Map<String, Object> stats = connectionPoolManager.getPoolStatistics();
            int available = (int) stats.getOrDefault("available", 0);
            int maxTotal = (int) stats.getOrDefault("maxTotal", 0);
            
            if (available > 0 && maxTotal > 0) {
                health.put("status", "UP");
                health.put("available", available);
                health.put("maxTotal", maxTotal);
            } else {
                health.put("status", "DOWN");
                health.put("reason", "无可用连接");
            }
        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
        }
        return health;
    }

    private Map<String, Object> checkCacheHealth() {
        Map<String, Object> health = new HashMap<>();
        try {
            Map<String, Object> stats = cacheManager.getStatistics();
            if (stats != null && !stats.isEmpty()) {
                health.put("status", "UP");
                health.put("cacheCount", stats.size());
            } else {
                health.put("status", "DOWN");
                health.put("reason", "缓存不可用");
            }
        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
        }
        return health;
    }

    private Map<String, Object> checkCircuitBreakerHealth() {
        Map<String, Object> health = new HashMap<>();
        try {
            Map<String, Map<String, Object>> metrics = circuitBreakerService.getAllCircuitBreakerMetrics();
            long openCount = metrics.values().stream()
                .filter(m -> "OPEN".equals(m.get("state")))
                .count();
            
            health.put("status", openCount == 0 ? "UP" : "DEGRADED");
            health.put("totalCircuitBreakers", metrics.size());
            health.put("openCircuitBreakers", openCount);
        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
        }
        return health;
    }
}