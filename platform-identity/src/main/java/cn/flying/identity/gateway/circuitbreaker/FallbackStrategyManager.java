package cn.flying.identity.gateway.circuitbreaker;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 降级策略管理器
 * 管理和执行各种降级策略
 * <p>
 * 降级策略类型：
 * 1. 返回默认值
 * 2. 返回缓存数据
 * 3. 转发到备用服务
 * 4. 返回静态页面
 * 5. 返回错误提示
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component
public class FallbackStrategyManager {

    /**
     * 默认降级响应缓存
     */
    private final Map<String, Object> defaultFallbackResponses = new ConcurrentHashMap<>();

    /**
     * 备用服务映射
     */
    private final Map<String, String> backupServiceMapping = new ConcurrentHashMap<>();

    /**
     * 缓存的最后成功响应
     */
    private final Map<String, CachedResponse> cachedResponses = new ConcurrentHashMap<>();

    /**
     * 初始化默认降级响应
     */
    public FallbackStrategyManager() {
        // 初始化一些常用的默认降级响应
        initDefaultFallbackResponses();
    }

    /**
     * 初始化默认降级响应
     */
    private void initDefaultFallbackResponses() {
        // 用户服务默认响应
        Map<String, Object> defaultUser = new HashMap<>();
        defaultUser.put("id", 0L);
        defaultUser.put("name", "访客用户");
        defaultUser.put("status", "limited");
        defaultFallbackResponses.put("user-service", defaultUser);

        // 文件服务默认响应
        Map<String, Object> defaultFile = new HashMap<>();
        defaultFile.put("message", "文件服务暂时不可用，请稍后重试");
        defaultFile.put("code", 503);
        defaultFallbackResponses.put("file-service", defaultFile);

        // 认证服务默认响应
        Map<String, Object> defaultAuth = new HashMap<>();
        defaultAuth.put("authenticated", false);
        defaultAuth.put("message", "认证服务暂时不可用");
        defaultFallbackResponses.put("auth-service", defaultAuth);
    }

    /**
     * 执行降级策略 - 返回静态内容
     *
     * @param serviceName  服务名称
     * @param resourcePath 静态资源路径
     * @return 静态内容
     */
    public Result<?> executeStaticContentFallback(String serviceName, String resourcePath) {
        log.info("执行静态内容降级策略: service={}, resource={}", serviceName, resourcePath);

        // 这里简化处理，实际应该读取静态文件
        Map<String, Object> staticContent = new HashMap<>();
        staticContent.put("type", "static");
        staticContent.put("content", "系统维护中，请稍后访问");
        staticContent.put("service", serviceName);

        return Result.success(staticContent);
    }

    /**
     * 智能降级 - 根据服务类型自动选择降级策略
     *
     * @param serviceName 服务名称
     * @param requestPath 请求路径
     * @param throwable   异常信息
     * @return 降级响应
     */
    public Result<?> smartFallback(String serviceName, String requestPath, Throwable throwable) {
        log.warn("执行智能降级: service={}, path={}, error={}",
                serviceName, requestPath, throwable.getMessage());

        // 根据异常类型选择降级策略
        switch (throwable) {
            case io.github.resilience4j.circuitbreaker.CallNotPermittedException ignored2 -> {
                // 熔断器打开，返回缓存或默认值
                String cacheKey = requestPath.replace("/", "_");
                return executeCacheFallback(serviceName, cacheKey);
            }
            case io.github.resilience4j.ratelimiter.RequestNotPermitted ignored1 -> {
                // 限流，返回错误提示
                return executeErrorFallback(serviceName, "请求过于频繁，请稍后重试");
                // 限流，返回错误提示
            }
            case java.net.SocketTimeoutException ignored -> {
                // 超时，尝试备用服务
                String backup = executeBackupServiceFallback(serviceName);
                if (backup != null) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("redirect", backup);
                    response.put("reason", "timeout");
                    return Result.success(response);
                }
                return executeDefaultFallback(serviceName);
            }
            default -> {
                // 其他异常，返回默认降级
                return executeDefaultFallback(serviceName);
            }
        }
    }

    /**
     * 执行降级策略 - 返回缓存数据
     *
     * @param serviceName 服务名称
     * @param cacheKey    缓存键
     * @return 缓存的响应
     */
    public Result<?> executeCacheFallback(String serviceName, String cacheKey) {
        log.info("执行缓存数据降级策略: service={}, key={}", serviceName, cacheKey);

        String fullKey = serviceName + ":" + cacheKey;
        CachedResponse cached = cachedResponses.get(fullKey);

        if (cached != null && !cached.isExpired()) {
            log.debug("返回缓存的响应: key={}, age={}ms",
                    fullKey, System.currentTimeMillis() - cached.getTimestamp());
            return Result.success(cached.getData());
        }

        // 缓存未命中或已过期，返回默认降级
        return executeDefaultFallback(serviceName);
    }

    /**
     * 执行降级策略 - 返回错误提示
     *
     * @param serviceName  服务名称
     * @param errorMessage 错误消息
     * @return 错误响应
     */
    public Result<?> executeErrorFallback(String serviceName, String errorMessage) {
        log.info("执行错误提示降级策略: service={}, error={}", serviceName, errorMessage);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("service", serviceName);
        errorResponse.put("error", errorMessage);
        errorResponse.put("timestamp", System.currentTimeMillis());
        errorResponse.put("fallback", true);

        return Result.error(ResultEnum.SERVICE_ERROR, errorResponse);
    }

    /**
     * 执行降级策略 - 转发到备用服务
     *
     * @param serviceName 服务名称
     * @return 备用服务URL
     */
    public String executeBackupServiceFallback(String serviceName) {
        log.info("执行备用服务降级策略: service={}", serviceName);

        String backupService = backupServiceMapping.get(serviceName);
        if (backupService != null) {
            log.info("转发到备用服务: {} -> {}", serviceName, backupService);
            return backupService;
        }

        // 没有配置备用服务
        log.warn("没有配置备用服务: service={}", serviceName);
        return null;
    }

    /**
     * 执行降级策略 - 返回默认值
     *
     * @param serviceName 服务名称
     * @return 默认响应
     */
    public Result<?> executeDefaultFallback(String serviceName) {
        log.info("执行默认值降级策略: service={}", serviceName);

        Object defaultResponse = defaultFallbackResponses.get(serviceName);
        if (defaultResponse != null) {
            return Result.success(defaultResponse);
        }

        // 通用默认响应
        Map<String, Object> genericResponse = new HashMap<>();
        genericResponse.put("message", "服务暂时不可用，请稍后重试");
        genericResponse.put("service", serviceName);
        genericResponse.put("fallback", true);

        return Result.error(ResultEnum.SERVICE_UNAVAILABLE, genericResponse);
    }

    /**
     * 缓存成功的响应（用于降级时返回）
     *
     * @param serviceName 服务名称
     * @param cacheKey    缓存键
     * @param response    响应数据
     */
    public void cacheSuccessResponse(String serviceName, String cacheKey, Object response) {
        String fullKey = serviceName + ":" + cacheKey;
        cachedResponses.put(fullKey, new CachedResponse(response));
        log.debug("缓存成功响应: key={}", fullKey);
    }

    /**
     * 注册默认降级响应
     *
     * @param serviceName     服务名称
     * @param defaultResponse 默认响应
     */
    public void registerDefaultFallback(String serviceName, Object defaultResponse) {
        defaultFallbackResponses.put(serviceName, defaultResponse);
        log.info("注册默认降级响应: service={}", serviceName);
    }

    /**
     * 注册备用服务
     *
     * @param serviceName      服务名称
     * @param backupServiceUrl 备用服务URL
     */
    public void registerBackupService(String serviceName, String backupServiceUrl) {
        backupServiceMapping.put(serviceName, backupServiceUrl);
        log.info("注册备用服务: {} -> {}", serviceName, backupServiceUrl);
    }

    /**
     * 获取降级统计信息
     *
     * @return 统计信息
     */
    public Map<String, Object> getFallbackStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("defaultFallbacks", defaultFallbackResponses.size());
        stats.put("backupServices", backupServiceMapping.size());
        stats.put("cachedResponses", cachedResponses.size());

        // 计算缓存命中率等
        long validCacheCount = cachedResponses.values().stream()
                .filter(cr -> !cr.isExpired())
                .count();
        stats.put("validCacheCount", validCacheCount);

        return stats;
    }

    /**
     * 缓存响应内部类
     */
    @Getter
    private static class CachedResponse {
        private static final long CACHE_DURATION = 5 * 60 * 1000; // 5分钟
        private final Object data;
        private final long timestamp;

        public CachedResponse(Object data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_DURATION;
        }
    }
}