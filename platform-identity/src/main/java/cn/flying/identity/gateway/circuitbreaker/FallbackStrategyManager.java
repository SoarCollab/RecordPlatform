package cn.flying.identity.gateway.circuitbreaker;

import cn.flying.identity.exception.ServiceUnavailableException;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
     * 静态内容缓存TTL（毫秒）
     */
    private static final long STATIC_CACHE_TTL_MS = 10 * 60 * 1000L; // 10分钟
    /**
     * 静态内容缓存最大条目数
     */
    private static final int STATIC_CACHE_MAX_ENTRIES = 100;
    /**
     * 成功响应缓存（用于降级）的最大条目数
     */
    private static final int CACHED_RESPONSES_MAX_ENTRIES = 1000;

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
     * 静态内容缓存
     */
    private final Map<String, TimedValue<String>> staticContentCache = new ConcurrentHashMap<>();

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
     * 从classpath:/fallback/目录读取静态文件
     *
     * @param serviceName  服务名称
     * @param resourcePath 静态资源路径
     * @return 静态内容
     */
    public Result<?> executeStaticContentFallback(String serviceName, String resourcePath) {
        log.info("执行静态内容降级策略: service={}, resource={}", serviceName, resourcePath);

        // 构建缓存键
        String cacheKey = serviceName + ":" + resourcePath;

        // 检查缓存（带TTL）
        TimedValue<String> cached = staticContentCache.get(cacheKey);
        if (cached != null) {
            if (!cached.isExpired(STATIC_CACHE_TTL_MS)) {
                log.debug("从缓存返回静态内容: key={}", cacheKey);
                return buildStaticContentResponse(serviceName, cached.value, "cache");
            } else {
                staticContentCache.remove(cacheKey);
            }
        }

        // 尝试从classpath读取静态文件
        // 文件路径: classpath:/fallback/{serviceName}/{resourcePath}
        String filePath = "fallback/" + serviceName + "/" + resourcePath;

        try {
            ClassPathResource resource = new ClassPathResource(filePath);
            if (resource.exists()) {
                try (InputStream inputStream = resource.getInputStream()) {
                    String fileContent = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);

                    // 缓存文件内容(避免重复IO)
                    evictOldestStaticContentIfNecessary();
                    staticContentCache.put(cacheKey, new TimedValue<>(fileContent));

                    log.info("成功读取静态文件: path={}, size={}", filePath, fileContent.length());
                    return buildStaticContentResponse(serviceName, fileContent, "file");
                }
            } else {
                log.warn("静态文件不存在: path={}, 使用默认降级内容", filePath);
            }
        } catch (IOException e) {
            log.error("读取静态文件失败: path={}, error={}", filePath, e.getMessage(), e);
        }

        // 降级到默认内容
        String defaultContent = buildDefaultStaticContent(serviceName);
        evictOldestStaticContentIfNecessary();
        staticContentCache.put(cacheKey, new TimedValue<>(defaultContent));

        return buildStaticContentResponse(serviceName, defaultContent, "default");
    }

    /**
     * 构建静态内容响应
     *
     * @param serviceName 服务名称
     * @param content     内容
     * @param source      来源(cache/file/default)
     * @return 响应结果
     */
    private Result<?> buildStaticContentResponse(String serviceName, String content, String source) {
        Map<String, Object> response = new HashMap<>();
        response.put("type", "static");
        response.put("service", serviceName);
        response.put("content", content);
        response.put("source", source);
        response.put("timestamp", System.currentTimeMillis());

        return Result.success(response);
    }

    /**
     * 构建默认静态内容
     *
     * @param serviceName 服务名称
     * @return 默认内容(HTML格式)
     */
    private String buildDefaultStaticContent(String serviceName) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>服务维护中</title>
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            height: 100vh;
                            margin: 0;
                            background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%);
                        }
                        .container {
                            text-align: center;
                            color: white;
                            padding: 2rem;
                        }
                        h1 { font-size: 3rem; margin-bottom: 1rem; }
                        p { font-size: 1.2rem; opacity: 0.9; }
                        .service-name {
                            font-weight: bold;
                            color: #ffd700;
                            margin-top: 1rem;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>🔧 系统维护中</h1>
                        <p>服务暂时不可用,请稍后再试</p>
                        <p class="service-name">服务: %s</p>
                    </div>
                </body>
                </html>
                """.formatted(serviceName);
    }

    /**
     * 清除静态内容缓存
     */
    public void clearStaticContentCache() {
        staticContentCache.clear();
        log.info("已清除静态内容缓存");
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
        String errorMessage = throwable != null ? throwable.getMessage() : "unknown";
        log.warn("执行智能降级: service={}, path={}, error={}", serviceName, requestPath, errorMessage);

        switch (throwable) {
            case null -> {
                return executeDefaultFallback(serviceName);
            }
            case io.github.resilience4j.circuitbreaker.CallNotPermittedException callNotPermittedException -> {
                String cacheKey = requestPath.replace("/", "_");
                return executeCacheFallback(serviceName, cacheKey);
            }
            case io.github.resilience4j.ratelimiter.RequestNotPermitted requestNotPermitted -> {
                return executeErrorFallback(serviceName, "请求过于频繁，请稍后重试");
            }
            case java.net.SocketTimeoutException socketTimeoutException -> {
                String backup = executeBackupServiceFallback(serviceName);
                if (backup != null) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("redirect", backup);
                    response.put("reason", "timeout");
                    return Result.success(response);
                }
                return executeDefaultFallback(serviceName);
            }
            case ServiceUnavailableException serviceUnavailableException -> {
                return executeErrorFallback(serviceName, "服务暂时不可用，请稍后再试");
            }
            default -> {
            }
        }

        return executeDefaultFallback(serviceName);
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
        evictOldestCachedResponsesIfNecessary();
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

    /**
     * 定时值包装类，用于在内存缓存中实现TTL
     */
    private static class TimedValue<T> {
        private final T value;
        private final long timestamp;

        public TimedValue(T value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }
    }

    /**
     * 当静态内容缓存超过容量上限时淘汰最早的条目
     */
    private void evictOldestStaticContentIfNecessary() {
        if (staticContentCache.size() < STATIC_CACHE_MAX_ENTRIES) {
            return;
        }
        String oldestKey = null;
        long oldestTs = Long.MAX_VALUE;
        for (Map.Entry<String, TimedValue<String>> entry : staticContentCache.entrySet()) {
            long ts = entry.getValue().timestamp;
            if (ts < oldestTs) {
                oldestTs = ts;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            staticContentCache.remove(oldestKey);
        }
    }

    /**
     * 当成功响应缓存超过容量上限时淘汰最早的条目
     */
    private void evictOldestCachedResponsesIfNecessary() {
        if (cachedResponses.size() < CACHED_RESPONSES_MAX_ENTRIES) {
            return;
        }
        String oldestKey = null;
        long oldestTs = Long.MAX_VALUE;
        for (Map.Entry<String, CachedResponse> entry : cachedResponses.entrySet()) {
            long ts = entry.getValue().getTimestamp();
            if (ts < oldestTs) {
                oldestTs = ts;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            cachedResponses.remove(oldestKey);
        }
    }
}