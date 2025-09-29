package cn.flying.identity.gateway.warmup;

import cn.flying.identity.dto.apigateway.ApiKey;
import cn.flying.identity.dto.apigateway.ApiPermission;
import cn.flying.identity.dto.apigateway.ApiRoute;
import cn.flying.identity.gateway.cache.ApiGatewayCacheManager;
import cn.flying.identity.mapper.apigateway.ApiKeyMapper;
import cn.flying.identity.mapper.apigateway.ApiPermissionMapper;
import cn.flying.identity.mapper.apigateway.ApiRouteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * API网关预热服务
 * 在应用启动时预加载热点数据到缓存，提升系统响应速度
 * <p>
 * 核心功能：
 * 1. 应用启动时自动预热
 * 2. 定期刷新预热数据
 * 3. 支持手动触发预热
 * 4. 异步并发预热提升效率
 * 5. 预热进度监控
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component
@Order(1)  // 确保在其他组件之后执行
public class ApiGatewayWarmupService implements ApplicationRunner {

    /**
     * 预热线程池
     */
    private final ExecutorService warmupExecutor = Executors.newFixedThreadPool(4,
            r -> new Thread(r, "WarmupThread"));
    /**
     * 预热统计信息
     */
    private final WarmupStatistics statistics = new WarmupStatistics();

    @Resource
    private ApiGatewayCacheManager cacheManager;

    @Resource
    private ApiRouteMapper routeMapper;

    @Resource
    private ApiKeyMapper apiKeyMapper;

    @Resource
    private ApiPermissionMapper permissionMapper;


    /**
     * 定期刷新预热数据（每天凌晨2点执行）
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledWarmup() {
        log.info("开始执行定期数据预热...");

        // 重置统计信息
        statistics.reset();

        // 执行预热
        run(null);
    }

    /**
     * 应用启动时执行预热
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("开始执行API网关数据预热...");
        long startTime = System.currentTimeMillis();

        try {
            // 并发执行预热任务
            CompletableFuture<Void> routeFuture = CompletableFuture.runAsync(this::warmupRoutes, warmupExecutor);
            CompletableFuture<Void> apiKeyFuture = CompletableFuture.runAsync(this::warmupApiKeys, warmupExecutor);
            CompletableFuture<Void> permissionFuture = CompletableFuture.runAsync(this::warmupPermissions, warmupExecutor);

            // 等待所有预热任务完成
            CompletableFuture.allOf(routeFuture, apiKeyFuture, permissionFuture)
                    .get(30, TimeUnit.SECONDS);

            long elapsedTime = System.currentTimeMillis() - startTime;
            log.info("API网关数据预热完成，耗时: {}ms, 统计信息: {}", elapsedTime, statistics);

        } catch (Exception e) {
            log.error("API网关数据预热失败", e);
        }
    }

    /**
     * 预热路由数据
     */
    private void warmupRoutes() {
        try {
            log.info("开始预热路由数据...");

            // 查询所有启用的路由
            LambdaQueryWrapper<ApiRoute> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiRoute::getRouteStatus, 1)
                    .orderByAsc(ApiRoute::getPriority);

            List<ApiRoute> routes = routeMapper.selectList(wrapper);
            if (routes == null || routes.isEmpty()) {
                log.warn("没有找到需要预热的路由数据");
                return;
            }

            // 构建缓存数据
            Map<String, Object> cacheData = new HashMap<>();
            for (ApiRoute route : routes) {
                // 根据路由类型生成不同的缓存键
                String cacheKey = buildRouteCacheKey(route);
                cacheData.put(cacheKey, route);
            }

            // 批量预热到缓存
            cacheManager.warmup("route", cacheData);

            statistics.recordRouteWarmup(routes.size());
            log.info("路由数据预热完成，预热数量: {}", routes.size());

        } catch (Exception e) {
            log.error("预热路由数据失败", e);
            statistics.recordError("route", e.getMessage());
        }
    }

    /**
     * 预热API密钥数据
     */
    private void warmupApiKeys() {
        try {
            log.info("开始预热API密钥数据...");

            // 查询所有启用的密钥（只预热最近使用的）
            LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiKey::getKeyStatus, 1)
                    .orderByDesc(ApiKey::getLastUsedTime)
                    .last("LIMIT 1000");  // 限制预热数量

            List<ApiKey> apiKeys = apiKeyMapper.selectList(wrapper);
            if (apiKeys == null || apiKeys.isEmpty()) {
                log.warn("没有找到需要预热的API密钥数据");
                return;
            }

            // 构建缓存数据
            Map<String, Object> cacheData = new HashMap<>();
            for (ApiKey apiKey : apiKeys) {
                cacheData.put(apiKey.getApiKey(), apiKey);
            }

            // 批量预热到缓存
            cacheManager.warmup("apiKey", cacheData);

            statistics.recordApiKeyWarmup(apiKeys.size());
            log.info("API密钥数据预热完成，预热数量: {}", apiKeys.size());

        } catch (Exception e) {
            log.error("预热API密钥数据失败", e);
            statistics.recordError("apiKey", e.getMessage());
        }
    }

    /**
     * 预热权限数据
     */
    private void warmupPermissions() {
        try {
            log.info("开始预热权限数据...");

            // 查询所有有效的权限
            LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiPermission::getPermissionStatus, 1);

            List<ApiPermission> permissions = permissionMapper.selectList(wrapper);
            if (permissions == null || permissions.isEmpty()) {
                log.warn("没有找到需要预热的权限数据");
                return;
            }

            // 构建缓存数据
            Map<String, Object> cacheData = new HashMap<>();
            for (ApiPermission permission : permissions) {
                String cacheKey = permission.getAppId() + ":" + permission.getInterfaceId();
                cacheData.put(cacheKey, true);  // 简化处理，只缓存权限存在标识
            }

            // 批量预热到缓存
            cacheManager.warmup("permission", cacheData);

            statistics.recordPermissionWarmup(permissions.size());
            log.info("权限数据预热完成，预热数量: {}", permissions.size());

        } catch (Exception e) {
            log.error("预热权限数据失败", e);
            statistics.recordError("permission", e.getMessage());
        }
    }

    /**
     * 构建路由缓存键
     *
     * @param route 路由对象
     * @return 缓存键
     */
    private String buildRouteCacheKey(ApiRoute route) {
        // 根据路由类型生成不同的缓存键格式
        return switch (route.getRouteType()) {
            case 1 ->  // 精确匹配
                    route.getRoutePath() + ":" + route.getHttpMethod();
            case 2 ->  // 前缀匹配
                    "prefix:" + route.getRoutePath();
            case 3 ->  // 正则匹配
                    "regex:" + route.getId();
            default -> route.getRoutePath();
        };
    }

    /**
     * 手动触发预热（供管理接口调用）
     *
     * @return 预热结果
     */
    public Map<String, Object> manualWarmup() {
        log.info("手动触发数据预热...");

        // 重置统计信息
        statistics.reset();

        // 执行预热
        run(null);

        // 返回预热结果
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("statistics", statistics.toMap());
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    /**
     * 获取预热统计信息
     *
     * @return 统计信息
     */
    public Map<String, Object> getStatistics() {
        return statistics.toMap();
    }

    /**
     * 预热统计信息内部类
     */
    private static class WarmupStatistics {
        private final Map<String, String> errors = new HashMap<>();
        private int routeCount = 0;
        private int apiKeyCount = 0;
        private int permissionCount = 0;
        private long startTime = System.currentTimeMillis();

        public synchronized void recordRouteWarmup(int count) {
            this.routeCount = count;
        }

        public synchronized void recordApiKeyWarmup(int count) {
            this.apiKeyCount = count;
        }

        public synchronized void recordPermissionWarmup(int count) {
            this.permissionCount = count;
        }

        public synchronized void recordError(String type, String error) {
            this.errors.put(type, error);
        }

        public synchronized void reset() {
            this.routeCount = 0;
            this.apiKeyCount = 0;
            this.permissionCount = 0;
            this.errors.clear();
            this.startTime = System.currentTimeMillis();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("routeCount", routeCount);
            map.put("apiKeyCount", apiKeyCount);
            map.put("permissionCount", permissionCount);
            map.put("totalCount", routeCount + apiKeyCount + permissionCount);
            map.put("errors", errors);
            map.put("elapsedTime", System.currentTimeMillis() - startTime);
            return map;
        }

        @Override
        public String toString() {
            return String.format("routes=%d, apiKeys=%d, permissions=%d, errors=%d",
                    routeCount, apiKeyCount, permissionCount, errors.size());
        }
    }
}