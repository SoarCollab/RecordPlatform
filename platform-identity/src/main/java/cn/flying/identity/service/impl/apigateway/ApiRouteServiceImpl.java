package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.constant.CacheKeyConstants;
import cn.flying.identity.dto.apigateway.ApiRoute;
import cn.flying.identity.mapper.apigateway.ApiRouteMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiRouteService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.Result;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 动态路由管理服务实现类
 * 提供路由的动态配置、刷新、匹配等功能
 * <p>
 * 核心功能：
 * 1. 动态路由配置管理
 * 2. 路由匹配算法（精确、前缀、正则）
 * 3. 路由缓存和刷新机制
 * 4. 路由健康检查
 * 5. 负载均衡策略配置
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Service
public class ApiRouteServiceImpl extends BaseService implements ApiRouteService {

    /**
     * Ant路径匹配器（用于前缀匹配）
     */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    /**
     * 路由匹配结果本地缓存（key=METHOD+空格+PATH），用于加速重复请求
     */
    private final Map<String, MatchCacheEntry> matchResultCache = new ConcurrentHashMap<>();
    @Resource
    private ApiRouteMapper routeMapper;
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;
    @Resource
    private RestTemplate restTemplate;
    @Resource
    private cn.flying.identity.gateway.discovery.NacosServiceDiscovery nacosServiceDiscovery;
    @Resource
    private cn.flying.identity.gateway.loadbalance.LoadBalanceManager loadBalanceManager;
    /**
     * 路由匹配缓存，按不同匹配方式构建索引用于加速匹配
     */
    private volatile Map<String, ApiRoute> exactRouteCache = new ConcurrentHashMap<>();
    private volatile Map<String, List<ApiRoute>> prefixRouteCache = new ConcurrentHashMap<>();
    private volatile Map<Long, Pattern> regexRouteCache = new ConcurrentHashMap<>();
    private volatile Map<Long, ApiRoute> routeIdCache = new ConcurrentHashMap<>();
    /**
     * 正则路由按优先级排序后的列表，避免无序遍历
     */
    private volatile List<Long> orderedRegexRouteIds = new java.util.ArrayList<>();

    /**
     * 前缀路由分桶索引（method -> firstSegment -> routes），缩小候选集
     */
    private volatile Map<String, Map<String, List<ApiRoute>>> prefixBucketCache = new ConcurrentHashMap<>();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<ApiRoute> createRoute(ApiRoute route) {
        return safeExecuteData(() -> {
            // 参数验证
            requireNonBlank(route.getRouteName(), "路由名称不能为空");
            requireNonBlank(route.getRoutePath(), "路由路径不能为空");
            requireNonBlank(route.getTargetService(), "目标服务不能为空");
            requireNonNull(route.getRouteType(), "路由类型不能为空");

            // 检查路由路径是否已存在
            if (isRoutePathExists(route.getRoutePath(), route.getHttpMethod())) {
                throw new RuntimeException("路由路径已存在");
            }

            // 设置默认值
            route.setId(IdUtils.nextEntityId());
            route.setRouteStatus(getOrElse(route.getRouteStatus(), 1));
            route.setPriority(getOrElse(route.getPriority(), 100));
            route.setHttpMethod(getOrElse(route.getHttpMethod(), "*"));
            route.setRequireAuth(getOrElse(route.getRequireAuth(), 1));
            route.setEnableRateLimit(getOrElse(route.getEnableRateLimit(), 1));
            route.setRateLimit(getOrElse(route.getRateLimit(), 100));
            route.setLoadBalance(getOrElse(route.getLoadBalance(), 1));
            route.setTimeout(getOrElse(route.getTimeout(), 30000));
            route.setRetryTimes(getOrElse(route.getRetryTimes(), 3));
            route.setCreateTime(LocalDateTime.now());
            route.setUpdateTime(LocalDateTime.now());

            // 保存到数据库
            int inserted = routeMapper.insert(route);
            requireCondition(inserted, count -> count > 0, "创建路由失败");

            // 刷新缓存
            refreshRouteCache();

            logInfo("创建路由成功: routeId={}, path={}", route.getId(), route.getRoutePath());
            return route;
        }, "创建路由失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateRoute(ApiRoute route) {
        return safeExecuteAction(() -> {
            requireNonNull(route.getId(), "路由ID不能为空");

            // 检查路由是否存在
            ApiRoute existingRoute = routeMapper.selectById(route.getId());
            requireNonNull(existingRoute, "路由不存在");

            // 如果修改了路径，检查新路径是否冲突
            if (!existingRoute.getRoutePath().equals(route.getRoutePath())) {
                if (isRoutePathExists(route.getRoutePath(), route.getHttpMethod())) {
                    throw new RuntimeException("新的路由路径已存在");
                }
            }

            route.setUpdateTime(LocalDateTime.now());
            int updated = routeMapper.updateById(route);
            requireCondition(updated, count -> count > 0, "更新路由失败");

            // 刷新缓存
            refreshRouteCache();

            logInfo("更新路由成功: routeId={}", route.getId());
        }, "更新路由失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteRoute(Long routeId) {
        return safeExecuteAction(() -> {
            requireNonNull(routeId, "路由ID不能为空");

            int deleted = routeMapper.deleteById(routeId);
            requireCondition(deleted, count -> count > 0, "删除路由失败");

            // 刷新缓存
            refreshRouteCache();

            logInfo("删除路由成功: routeId={}", routeId);
        }, "删除路由失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> enableRoute(Long routeId) {
        return safeExecuteAction(() -> {
            requireNonNull(routeId, "路由ID不能为空");

            ApiRoute route = new ApiRoute();
            route.setId(routeId);
            route.setRouteStatus(1);
            route.setUpdateTime(LocalDateTime.now());

            int updated = routeMapper.updateById(route);
            requireCondition(updated, count -> count > 0, "启用路由失败");

            // 刷新缓存
            refreshRouteCache();

            logInfo("启用路由成功: routeId={}", routeId);
        }, "启用路由失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> disableRoute(Long routeId) {
        return safeExecuteAction(() -> {
            requireNonNull(routeId, "路由ID不能为空");

            ApiRoute route = new ApiRoute();
            route.setId(routeId);
            route.setRouteStatus(0);
            route.setUpdateTime(LocalDateTime.now());

            int updated = routeMapper.updateById(route);
            requireCondition(updated, count -> count > 0, "禁用路由失败");

            // 刷新缓存
            refreshRouteCache();

            logInfo("禁用路由成功: routeId={}", routeId);
        }, "禁用路由失败");
    }

    @Override
    public Result<ApiRoute> getRouteById(Long routeId) {
        return safeExecuteData(() -> {
            requireNonNull(routeId, "路由ID不能为空");

            ApiRoute route = routeMapper.selectById(routeId);
            requireNonNull(route, "路由不存在");

            return route;
        }, "查询路由失败");
    }

    @Override
    public Result<List<ApiRoute>> getActiveRoutes() {
        return safeExecuteData(() -> {
            // 先从缓存获取
            List<ApiRoute> cachedRoutes = getRoutesFromCache();
            if (cachedRoutes != null && !cachedRoutes.isEmpty()) {
                ensureRouteCacheInitialized(cachedRoutes);
                return cachedRoutes;
            }

            // 缓存未命中，从数据库查询
            LambdaQueryWrapper<ApiRoute> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiRoute::getRouteStatus, 1)
                    .orderByAsc(ApiRoute::getPriority)
                    .orderByDesc(ApiRoute::getCreateTime);

            List<ApiRoute> routes = routeMapper.selectList(wrapper);
            rebuildRouteCaches(routes);

            // 缓存结果
            if (routes != null && !routes.isEmpty()) {
                cacheRoutes(routes);
            }

            return routes != null ? routes : new ArrayList<>();
        }, "查询活跃路由失败");
    }

    @Override
    public Result<List<ApiRoute>> getRoutesByService(String serviceName) {
        return safeExecuteData(() -> {
            requireNonBlank(serviceName, "服务名称不能为空");

            LambdaQueryWrapper<ApiRoute> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiRoute::getTargetService, serviceName)
                    .orderByAsc(ApiRoute::getPriority);

            List<ApiRoute> routes = routeMapper.selectList(wrapper);
            return routes != null ? routes : new ArrayList<>();
        }, "查询服务路由失败");
    }

    @Override
    public Result<ApiRoute> matchRoute(String path, String method) {
        return safeExecuteData(() -> {
            requireNonBlank(path, "请求路径不能为空");
            requireNonBlank(method, "HTTP方法不能为空");

            if (exactRouteCache.isEmpty() && prefixRouteCache.isEmpty() && regexRouteCache.isEmpty()) {
                Result<List<ApiRoute>> activeRoutesResult = getActiveRoutes();
                if (isSuccess(activeRoutesResult)) {
                    ensureRouteCacheInitialized(activeRoutesResult.getData());
                }
            }

            String normalizedMethod = method.toUpperCase(Locale.ROOT);

            // 0. 本地匹配缓存命中
            ApiRoute cached = getCachedMatch(path, normalizedMethod);
            if (cached != null) {
                recordRouteMatch(cached.getId());
                return cached;
            }

            // 1. 精确匹配命中
            ApiRoute exactMatch = exactRouteCache.get(buildExactMatchKey(path, normalizedMethod));
            if (exactMatch == null) {
                exactMatch = exactRouteCache.get(buildExactMatchKey(path, "*"));
            }
            if (exactMatch != null) {
                recordRouteMatch(exactMatch.getId());
                return exactMatch;
            }

            // 2. 前缀匹配命中
            List<ApiRoute> prefixCandidates = new ArrayList<>();
            prefixCandidates.addAll(getPrefixCandidates(normalizedMethod, path));
            prefixCandidates.addAll(getPrefixCandidates("*", path));
            for (ApiRoute candidate : prefixCandidates) {
                if (pathMatcher.match(candidate.getRoutePath(), path)) {
                    recordRouteMatch(candidate.getId());
                    cacheMatch(path, normalizedMethod, candidate);
                    return candidate;
                }
            }

            // 3. 正则匹配命中
            for (Long routeId : orderedRegexRouteIds) {
                Pattern pattern = regexRouteCache.get(routeId);
                if (pattern != null && pattern.matcher(path).matches()) {
                    ApiRoute route = routeIdCache.get(routeId);
                    if (route != null && isHttpMethodMatch(route.getHttpMethod(), normalizedMethod)) {
                        recordRouteMatch(route.getId());
                        cacheMatch(path, normalizedMethod, route);
                        return route;
                    }
                }
            }

            logDebug("未找到匹配的路由: path={}, method={}", path, method);
            return null;
        }, "匹配路由失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> batchImportRoutes(List<ApiRoute> routes) {
        return safeExecuteData(() -> {
            requireNonEmpty(routes, "路由列表不能为空");

            int successCount = 0;
            int failedCount = 0;
            List<String> errors = new ArrayList<>();

            for (ApiRoute route : routes) {
                try {
                    // 设置默认值
                    route.setId(IdUtils.nextEntityId());
                    route.setCreateTime(LocalDateTime.now());
                    route.setUpdateTime(LocalDateTime.now());

                    // 检查路径是否已存在
                    if (isRoutePathExists(route.getRoutePath(), route.getHttpMethod())) {
                        failedCount++;
                        errors.add("路径已存在: " + route.getRoutePath());
                        continue;
                    }

                    routeMapper.insert(route);
                    successCount++;
                } catch (Exception e) {
                    failedCount++;
                    errors.add("导入失败: " + route.getRouteName() + " - " + e.getMessage());
                    logError("批量导入路由异常", e);
                }
            }

            // 刷新缓存
            refreshRouteCache();

            Map<String, Object> result = new HashMap<>();
            result.put("total", routes.size());
            result.put("success", successCount);
            result.put("failed", failedCount);
            result.put("errors", errors);

            logInfo("批量导入路由完成: total={}, success={}, failed={}",
                    routes.size(), successCount, failedCount);

            return result;
        }, "批量导入路由失败");
    }

    @Override
    public Result<List<ApiRoute>> exportRoutes() {
        return safeExecuteData(() -> {
            List<ApiRoute> routes = routeMapper.selectList(null);
            return routes != null ? routes : new ArrayList<>();
        }, "导出路由失败");
    }

    @Override
    public Result<Void> refreshRouteCache() {
        return safeExecuteAction(() -> {
            // 查询所有启用的路由
            LambdaQueryWrapper<ApiRoute> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiRoute::getRouteStatus, 1)
                    .orderByAsc(ApiRoute::getPriority);

            List<ApiRoute> routes = routeMapper.selectList(wrapper);
            rebuildRouteCaches(routes);

            // 缓存路由列表
            if (routes != null && !routes.isEmpty()) {
                cacheRoutes(routes);
            } else {
                // 清除缓存
                redisTemplate.delete(CacheKeyConstants.ROUTE_LIST_KEY);
            }

            // 清空匹配结果本地缓存
            matchResultCache.clear();

            logInfo("刷新路由缓存成功: count={}", routes != null ? routes.size() : 0);
        }, "刷新路由缓存失败");
    }

    @Override
    public Result<Map<String, Object>> getRouteStatistics(Long routeId, int days) {
        return safeExecuteData(() -> {
            requireNonNull(routeId, "路由ID不能为空");
            requireCondition(days, d -> d > 0 && d <= 90, "统计天数必须在1-90之间");

            Map<String, Object> stats = new HashMap<>();

            // 获取路由基本信息
            ApiRoute route = routeMapper.selectById(routeId);
            requireNonNull(route, "路由不存在");

            stats.put("route_id", routeId);
            stats.put("route_name", route.getRouteName());
            stats.put("route_path", route.getRoutePath());
            stats.put("target_service", route.getTargetService());

            // 从Redis获取统计数据
            String statsKey = CacheKeyConstants.buildRouteStatsKey(routeId);
            Map<Object, Object> redisStats = redisTemplate.opsForHash().entries(statsKey);

            long totalRequests = 0;
            long successRequests = 0;
            long failedRequests = 0;
            double avgResponseTime = 0;

            if (!redisStats.isEmpty()) {
                totalRequests = Long.parseLong(getOrElse(redisStats.get("total"), "0").toString());
                successRequests = Long.parseLong(getOrElse(redisStats.get("success"), "0").toString());
                failedRequests = Long.parseLong(getOrElse(redisStats.get("failed"), "0").toString());
                avgResponseTime = Double.parseDouble(getOrElse(redisStats.get("avg_time"), "0").toString());
            }

            stats.put("total_requests", totalRequests);
            stats.put("success_requests", successRequests);
            stats.put("failed_requests", failedRequests);
            stats.put("avg_response_time", avgResponseTime);
            stats.put("success_rate", totalRequests > 0 ? (successRequests * 100.0 / totalRequests) : 0);
            stats.put("stat_days", days);
            stats.put("stat_time", LocalDateTime.now());

            return stats;
        }, "获取路由统计信息失败");
    }

    @Override
    public Result<Map<String, Object>> testRoute(Long routeId) {
        return safeExecuteData(() -> {
            requireNonNull(routeId, "路由ID不能为空");

            ApiRoute route = routeMapper.selectById(routeId);
            requireNonNull(route, "路由不存在");

            Map<String, Object> result = new HashMap<>();
            result.put("route_id", routeId);
            result.put("route_path", route.getRoutePath());
            result.put("target_service", route.getTargetService());

            // 构建测试URL
            String testUrl = buildTestUrl(route);
            result.put("test_url", testUrl);

            // 执行测试请求
            long startTime = System.currentTimeMillis();
            boolean success = false;
            String errorMsg = null;

            try {
                // 发送HEAD请求测试连通性
                restTemplate.headForHeaders(testUrl);
                success = true;
            } catch (Exception e) {
                errorMsg = e.getMessage();
                logWarn("路由测试失败: routeId={}, error={}", routeId, errorMsg);
            }

            long responseTime = System.currentTimeMillis() - startTime;

            result.put("success", success);
            result.put("response_time", responseTime);
            result.put("error_message", errorMsg);
            result.put("test_time", LocalDateTime.now());

            return result;
        }, "测试路由失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateRoutePriority(Long routeId, Integer priority) {
        return safeExecuteAction(() -> {
            requireNonNull(routeId, "路由ID不能为空");
            requireNonNull(priority, "优先级不能为空");
            requireCondition(priority, p -> p >= 0, "优先级必须大于等于0");

            ApiRoute route = new ApiRoute();
            route.setId(routeId);
            route.setPriority(priority);
            route.setUpdateTime(LocalDateTime.now());

            int updated = routeMapper.updateById(route);
            requireCondition(updated, count -> count > 0, "更新路由优先级失败");

            // 刷新缓存
            refreshRouteCache();

            logInfo("更新路由优先级成功: routeId={}, priority={}", routeId, priority);
        }, "更新路由优先级失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateRouteRateLimit(Long routeId, Integer rateLimit) {
        return safeExecuteAction(() -> {
            requireNonNull(routeId, "路由ID不能为空");
            requireNonNull(rateLimit, "限流QPS不能为空");
            requireCondition(rateLimit, limit -> limit > 0, "限流QPS必须大于0");

            ApiRoute route = new ApiRoute();
            route.setId(routeId);
            route.setRateLimit(rateLimit);
            route.setUpdateTime(LocalDateTime.now());

            int updated = routeMapper.updateById(route);
            requireCondition(updated, count -> count > 0, "更新路由限流配置失败");

            // 刷新缓存
            refreshRouteCache();

            logInfo("更新路由限流配置成功: routeId={}, rateLimit={}", routeId, rateLimit);
        }, "更新路由限流配置失败");
    }

    @Override
    public Result<Map<String, Object>> getRouteHealth(Long routeId) {
        return safeExecuteData(() -> {
            requireNonNull(routeId, "路由ID不能为空");

            ApiRoute route = routeMapper.selectById(routeId);
            requireNonNull(route, "路由不存在");

            Map<String, Object> health = new HashMap<>();
            health.put("route_id", routeId);
            health.put("route_name", route.getRouteName());
            health.put("route_status", route.getRouteStatus());

            // 执行健康检查
            Result<Map<String, Object>> testResult = testRoute(routeId);
            boolean isHealthy = isSuccess(testResult) &&
                    Boolean.TRUE.equals(testResult.getData().get("success"));

            health.put("is_healthy", isHealthy);
            health.put("check_time", LocalDateTime.now());

            // 获取最近的统计信息
            String statsKey = CacheKeyConstants.buildRouteStatsKey(routeId);
            String errorCount = (String) redisTemplate.opsForHash().get(statsKey, "error_count");
            health.put("recent_errors", getOrElse(errorCount, "0"));

            // 计算健康分数（0-100）
            int healthScore = calculateHealthScore(isHealthy, errorCount);
            health.put("health_score", healthScore);

            return health;
        }, "获取路由健康状态失败");
    }

    /**
     * 计算健康分数
     *
     * @param isHealthy  是否健康
     * @param errorCount 错误数量
     * @return 健康分数（0-100）
     */
    private int calculateHealthScore(boolean isHealthy, String errorCount) {
        if (!isHealthy) {
            return 0;
        }

        int errors = Integer.parseInt(getOrElse(errorCount, "0"));
        if (errors == 0) {
            return 100;
        } else if (errors < 10) {
            return 80;
        } else if (errors < 50) {
            return 60;
        } else if (errors < 100) {
            return 40;
        } else {
            return 20;
        }
    }

    /**
     * 构建测试URL
     * 从Nacos服务注册中心获取健康的服务实例地址
     *
     * @param route 路由配置
     * @return 测试URL,如果无可用实例则返回降级URL
     */
    private String buildTestUrl(ApiRoute route) {
        String targetPath = getOrElse(route.getTargetPath(), route.getRoutePath());
        if (!targetPath.startsWith("/")) {
            targetPath = "/" + targetPath;
        }

        try {
            // 从Nacos获取服务实例列表
            List<cn.flying.identity.gateway.discovery.NacosServiceDiscovery.ServiceInstance> instances =
                    nacosServiceDiscovery.getServiceInstances(route.getTargetService());

            if (instances != null && !instances.isEmpty()) {
                // 从健康实例中随机选择一个(避免固定选择第一个造成压力不均)
                List<cn.flying.identity.gateway.discovery.NacosServiceDiscovery.ServiceInstance> healthyInstances =
                        instances.stream()
                                .filter(cn.flying.identity.gateway.discovery.NacosServiceDiscovery.ServiceInstance::isHealthy)
                                .toList();

                if (!healthyInstances.isEmpty()) {
                    // 使用随机策略选择实例,分散测试压力
                    cn.flying.identity.gateway.discovery.NacosServiceDiscovery.ServiceInstance selectedInstance =
                            healthyInstances.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(healthyInstances.size()));

                    // 构建完整URL: http(s)://host:port/path
                    String protocol = "http"; // 默认HTTP,如果实例元数据中包含protocol则使用
                    if (selectedInstance.getMetadata() != null &&
                            "https".equalsIgnoreCase(selectedInstance.getMetadata().get("protocol"))) {
                        protocol = "https";
                    }

                    String testUrl = String.format("%s://%s:%d%s",
                            protocol,
                            selectedInstance.getHost(),
                            selectedInstance.getPort(),
                            targetPath);

                    logDebug("从Nacos获取到测试URL: service={}, instance={}:{}, url={}",
                            route.getTargetService(),
                            selectedInstance.getHost(),
                            selectedInstance.getPort(),
                            testUrl);

                    return testUrl;
                } else {
                    logWarn("服务 {} 没有健康的实例,使用降级URL", route.getTargetService());
                }
            } else {
                logWarn("从Nacos未找到服务 {} 的实例,使用降级URL", route.getTargetService());
            }
        } catch (Exception e) {
            logError("从Nacos获取服务实例失败,使用降级URL: service={}", route.getTargetService(), e);
        }

        // 降级处理: Nacos不可用或无健康实例时,使用服务名作为host(依赖DNS或K8s Service)
        String fallbackUrl = "http://" + route.getTargetService() + targetPath;
        logWarn("使用降级测试URL: {}", fallbackUrl);
        return fallbackUrl;
    }

    /**
     * 检查路由路径是否已存在
     *
     * @param routePath  路由路径
     * @param httpMethod HTTP方法
     * @return 是否存在
     */
    private boolean isRoutePathExists(String routePath, String httpMethod) {
        LambdaQueryWrapper<ApiRoute> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiRoute::getRoutePath, routePath);

        if (!"*".equals(httpMethod)) {
            wrapper.and(w -> w.eq(ApiRoute::getHttpMethod, "*")
                    .or()
                    .eq(ApiRoute::getHttpMethod, httpMethod));
        }

        return routeMapper.selectCount(wrapper) > 0;
    }

    /**
     * 重建内存中的路由匹配缓存，提高路由匹配性能
     *
     * @param routes 当前有效路由集合
     */
    private synchronized void rebuildRouteCaches(List<ApiRoute> routes) {
        Map<String, ApiRoute> newExactCache = new ConcurrentHashMap<>();
        Map<String, List<ApiRoute>> newPrefixCache = new ConcurrentHashMap<>();
        Map<Long, Pattern> newRegexCache = new ConcurrentHashMap<>();
        Map<Long, ApiRoute> newRouteIdCache = new ConcurrentHashMap<>();
        Map<String, Map<String, List<ApiRoute>>> newPrefixBuckets = new ConcurrentHashMap<>();
        List<Long> newOrderedRegexIds = new ArrayList<>();

        if (routes != null) {
            for (ApiRoute route : routes) {
                if (route == null || StrUtil.isBlank(route.getRoutePath())) {
                    continue;
                }
                Long routeId = route.getId();
                if (routeId != null) {
                    newRouteIdCache.put(routeId, route);
                }

                Integer routeType = route.getRouteType();
                List<String> methodKeys = resolveMethodKeys(route.getHttpMethod());

                if (routeType == null) {
                    methodKeys.forEach(method -> newExactCache.put(buildExactMatchKey(route.getRoutePath(), method), route));
                    continue;
                }

                switch (routeType) {
                    case 1 -> methodKeys.forEach(method ->
                            newExactCache.put(buildExactMatchKey(route.getRoutePath(), method), route));
                    case 2 -> {
                        methodKeys.forEach(method ->
                                newPrefixCache.computeIfAbsent(method, key -> new ArrayList<>()).add(route));
                        // 分桶：按首段分组
                        String first = extractFirstSegment(route.getRoutePath());
                        methodKeys.forEach(method ->
                                newPrefixBuckets
                                        .computeIfAbsent(method, m -> new ConcurrentHashMap<>())
                                        .computeIfAbsent(first, f -> new ArrayList<>())
                                        .add(route));
                    }
                    case 3 -> {
                        if (routeId != null) {
                            try {
                                newRegexCache.put(routeId, Pattern.compile(route.getRoutePath()));
                                newOrderedRegexIds.add(routeId);
                            } catch (Exception e) {
                                logError("编译路由正则失败", e);
                            }
                        }
                    }
                    default -> methodKeys.forEach(method ->
                            newExactCache.put(buildExactMatchKey(route.getRoutePath(), method), route));
                }
            }
        }

        Map<String, List<ApiRoute>> normalizedPrefixCache = new ConcurrentHashMap<>();
        newPrefixCache.forEach((key, value) -> {
            List<ApiRoute> sortedList = value.stream()
                    .sorted(
                            Comparator
                                    .comparing((ApiRoute route) -> Optional.ofNullable(route.getPriority()).orElse(Integer.MAX_VALUE))
                                    .thenComparing(
                                            (ApiRoute route) -> Optional.ofNullable(route.getRoutePath()).map(String::length).orElse(0),
                                            Comparator.reverseOrder()
                                    )
                    )
                    .toList();
            normalizedPrefixCache.put(key, sortedList);
        });

        // 归一化分桶：同样按优先级+路径长度排序
        Map<String, Map<String, List<ApiRoute>>> normalizedBuckets = new ConcurrentHashMap<>();
        newPrefixBuckets.forEach((method, bucket) -> {
            Map<String, List<ApiRoute>> sortedBucket = new ConcurrentHashMap<>();
            bucket.forEach((segment, list) -> {
                List<ApiRoute> sorted = list.stream()
                        .sorted(
                                Comparator
                                        .comparing((ApiRoute route) -> Optional.ofNullable(route.getPriority()).orElse(Integer.MAX_VALUE))
                                        .thenComparing(
                                                (ApiRoute route) -> Optional.ofNullable(route.getRoutePath()).map(String::length).orElse(0),
                                                Comparator.reverseOrder()
                                        )
                        )
                        .toList();
                sortedBucket.put(segment, sorted);
            });
            normalizedBuckets.put(method, sortedBucket);
        });

        // 正则路由排序：按优先级（小到大）+ 路径长度（长到短）
        newOrderedRegexIds = newOrderedRegexIds.stream()
                .sorted(
                        Comparator
                                .comparing((Long id) -> Optional.ofNullable(newRouteIdCache.get(id)).map(ApiRoute::getPriority).orElse(Integer.MAX_VALUE))
                                .thenComparing(
                                        (Long id) -> Optional.ofNullable(newRouteIdCache.get(id)).map(ApiRoute::getRoutePath).map(String::length).orElse(0),
                                        Comparator.reverseOrder()
                                )
                )
                .toList();

        this.exactRouteCache = newExactCache;
        this.prefixRouteCache = normalizedPrefixCache;
        this.regexRouteCache = newRegexCache;
        this.routeIdCache = newRouteIdCache;
        this.prefixBucketCache = normalizedBuckets;
        this.orderedRegexRouteIds = newOrderedRegexIds;
    }

    /**
     * 缓存路由列表
     *
     * @param routes 路由列表
     */
    private void cacheRoutes(List<ApiRoute> routes) {
        try {
            String routesJson = JSONUtil.toJsonStr(routes);
            redisTemplate.opsForValue().set(CacheKeyConstants.ROUTE_LIST_KEY, routesJson, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            logError("缓存路由列表失败", e);
        }
    }

    /**
     * 解析 HTTP 方法配置，拆分为标准方法列表
     *
     * @param methodValue HTTP 方法字符串
     * @return 方法列表
     */
    private List<String> resolveMethodKeys(String methodValue) {
        if (StrUtil.isBlank(methodValue) || "*".equals(methodValue.trim())) {
            return Collections.singletonList("*");
        }
        String[] methods = methodValue.split(",");
        List<String> result = new ArrayList<>();
        for (String item : methods) {
            String trimmed = item.trim();
            if (StrUtil.isBlank(trimmed)) {
                continue;
            }
            result.add(trimmed.toUpperCase(Locale.ROOT));
        }
        if (result.isEmpty()) {
            result.add("*");
        }
        return result;
    }

    /**
     * 构建精确匹配缓存键
     *
     * @param routePath 路由路径
     * @param method    HTTP 方法
     * @return 精确匹配键
     */
    private String buildExactMatchKey(String routePath, String method) {
        return routePath + ":" + method;
    }

    /**
     * 根据请求路径提取首段（用于分桶），示例：/api/user/1 -> api
     */
    private String extractFirstSegment(String path) {
        if (StrUtil.isBlank(path)) {
            return "*";
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        int idx = normalized.indexOf('/');
        return idx < 0 ? normalized : normalized.substring(0, idx);
    }

    /**
     * 从缓存获取路由列表
     *
     * @return 路由列表
     */
    private List<ApiRoute> getRoutesFromCache() {
        try {
            String routesJson = redisTemplate.opsForValue().get(CacheKeyConstants.ROUTE_LIST_KEY);
            if (StrUtil.isNotBlank(routesJson)) {
                return JSONUtil.toList(routesJson, ApiRoute.class);
            }
        } catch (Exception e) {
            logError("从缓存获取路由列表失败", e);
        }
        return null;
    }

    /**
     * 记录路由匹配统计
     *
     * @param routeId 路由ID
     */
    private void recordRouteMatch(Long routeId) {
        try {
            String statsKey = CacheKeyConstants.buildRouteStatsKey(routeId);
            redisTemplate.opsForHash().increment(statsKey, "matches", 1);
            redisTemplate.expire(statsKey, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            logError("记录路由匹配统计失败", e);
        }
    }

    /**
     * 判断指定 HTTP 方法是否与路由配置匹配
     *
     * @param configuredMethod 路由配置的方法
     * @param requestMethod    请求的方法（大写）
     * @return 是否匹配
     */
    private boolean isHttpMethodMatch(String configuredMethod, String requestMethod) {
        List<String> methodKeys = resolveMethodKeys(Optional.ofNullable(configuredMethod).orElse("*"));
        return methodKeys.contains("*") || methodKeys.contains(requestMethod);
    }

    /**
     * 确保内存路由缓存已初始化，避免首次访问命中空缓存
     *
     * @param routes 当前路由数据
     */
    private void ensureRouteCacheInitialized(List<ApiRoute> routes) {
        if (routes == null || routes.isEmpty()) {
            return;
        }
        if (exactRouteCache.isEmpty() && prefixRouteCache.isEmpty() && regexRouteCache.isEmpty()) {
            rebuildRouteCaches(routes);
        }
    }

    /**
     * 获取前缀匹配候选集（按 method 与首段分桶）
     */
    private List<ApiRoute> getPrefixCandidates(String method, String requestPath) {
        Map<String, List<ApiRoute>> bucket = prefixBucketCache.get(method);
        if (bucket == null || bucket.isEmpty()) {
            // 退化为原有列表
            return Optional.ofNullable(prefixRouteCache.get(method)).orElse(Collections.emptyList());
        }
        String first = extractFirstSegment(requestPath);
        List<ApiRoute> list = new ArrayList<>();
        List<ApiRoute> exactBucket = bucket.get(first);
        if (exactBucket != null) {
            list.addAll(exactBucket);
        }
        List<ApiRoute> wildcardBucket = bucket.get("*");
        if (wildcardBucket != null) {
            list.addAll(wildcardBucket);
        }
        return list.isEmpty() ? Optional.ofNullable(prefixRouteCache.get(method)).orElse(Collections.emptyList()) : list;
    }

    /**
     * 从本地匹配缓存获取（命中且未过期时返回）
     */
    private ApiRoute getCachedMatch(String path, String method) {
        String key = buildMatchCacheKey(path, method);
        MatchCacheEntry entry = matchResultCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return routeIdCache.get(entry.routeId);
        }
        return null;
    }

    /**
     * 写入本地匹配缓存
     */
    private void cacheMatch(String path, String method, ApiRoute route) {
        if (route == null || route.getId() == null) {
            return;
        }
        String key = buildMatchCacheKey(path, method);
        matchResultCache.put(key, new MatchCacheEntry(route.getId()));
    }

    private String buildMatchCacheKey(String path, String method) {
        return method + " " + path;
    }

    /**
     * 匹配结果缓存条目
     */
    private static class MatchCacheEntry {
        private static final long TTL_MS = 60_000L;
        private final long timestamp;
        private final Long routeId;

        MatchCacheEntry(Long routeId) {
            this.routeId = routeId;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TTL_MS;
        }
    }
}