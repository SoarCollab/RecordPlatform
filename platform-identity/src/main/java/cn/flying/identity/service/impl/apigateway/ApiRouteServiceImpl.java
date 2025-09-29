package cn.flying.identity.service.impl.apigateway;

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
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    @Resource
    private ApiRouteMapper routeMapper;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Resource
    private RestTemplate restTemplate;

    /**
     * Redis缓存键前缀
     */
    private static final String ROUTE_CACHE_PREFIX = "api:route:";
    private static final String ROUTE_LIST_KEY = "api:route:list";
    private static final String ROUTE_STATS_PREFIX = "api:route:stats:";

    /**
     * Ant路径匹配器（用于前缀匹配）
     */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

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
                return cachedRoutes;
            }

            // 缓存未命中，从数据库查询
            LambdaQueryWrapper<ApiRoute> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiRoute::getRouteStatus, 1)
                    .orderByAsc(ApiRoute::getPriority)
                    .orderByDesc(ApiRoute::getCreateTime);

            List<ApiRoute> routes = routeMapper.selectList(wrapper);

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

            // 获取所有活跃路由
            Result<List<ApiRoute>> activeRoutesResult = getActiveRoutes();
            if (!isSuccess(activeRoutesResult)) {
                return null;
            }

            List<ApiRoute> routes = activeRoutesResult.getData();
            if (routes == null || routes.isEmpty()) {
                return null;
            }

            // 按优先级排序
            routes.sort(Comparator.comparing(ApiRoute::getPriority));

            // 遍历路由进行匹配
            for (ApiRoute route : routes) {
                // 检查HTTP方法
                if (!"*".equals(route.getHttpMethod()) &&
                        !route.getHttpMethod().contains(method.toUpperCase())) {
                    continue;
                }

                // 根据路由类型进行匹配
                boolean matched = false;
                switch (route.getRouteType()) {
                    case 1: // 精确匹配
                        matched = path.equals(route.getRoutePath());
                        break;
                    case 2: // 前缀匹配
                        matched = pathMatcher.match(route.getRoutePath(), path);
                        break;
                    case 3: // 正则匹配
                        try {
                            Pattern pattern = Pattern.compile(route.getRoutePath());
                            matched = pattern.matcher(path).matches();
                        } catch (Exception e) {
                            logError("正则表达式匹配失败", e);
                        }
                        break;
                }

                if (matched) {
                    // 记录匹配统计
                    recordRouteMatch(route.getId());
                    return route;
                }
            }

            logWarn("未找到匹配的路由: path={}, method={}", path, method);
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

            // 缓存路由列表
            if (routes != null && !routes.isEmpty()) {
                cacheRoutes(routes);
            } else {
                // 清除缓存
                redisTemplate.delete(ROUTE_LIST_KEY);
            }

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
            String statsKey = ROUTE_STATS_PREFIX + routeId;
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
            String statsKey = ROUTE_STATS_PREFIX + routeId;
            String errorCount = (String) redisTemplate.opsForHash().get(statsKey, "error_count");
            health.put("recent_errors", getOrElse(errorCount, "0"));

            // 计算健康分数（0-100）
            int healthScore = calculateHealthScore(isHealthy, errorCount);
            health.put("health_score", healthScore);

            return health;
        }, "获取路由健康状态失败");
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
     * 缓存路由列表
     *
     * @param routes 路由列表
     */
    private void cacheRoutes(List<ApiRoute> routes) {
        try {
            String routesJson = JSONUtil.toJsonStr(routes);
            redisTemplate.opsForValue().set(ROUTE_LIST_KEY, routesJson, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            logError("缓存路由列表失败", e);
        }
    }

    /**
     * 从缓存获取路由列表
     *
     * @return 路由列表
     */
    private List<ApiRoute> getRoutesFromCache() {
        try {
            String routesJson = redisTemplate.opsForValue().get(ROUTE_LIST_KEY);
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
            String statsKey = ROUTE_STATS_PREFIX + routeId;
            redisTemplate.opsForHash().increment(statsKey, "matches", 1);
            redisTemplate.expire(statsKey, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            logError("记录路由匹配统计失败", e);
        }
    }

    /**
     * 构建测试URL
     *
     * @param route 路由配置
     * @return 测试URL
     */
    private String buildTestUrl(ApiRoute route) {
        // 这里简化处理，实际应该从服务注册中心获取服务地址
        String serviceHost = "http://" + route.getTargetService();
        String targetPath = getOrElse(route.getTargetPath(), route.getRoutePath());

        if (!targetPath.startsWith("/")) {
            targetPath = "/" + targetPath;
        }

        return serviceHost + targetPath;
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
}