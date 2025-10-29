package cn.flying.identity.constant;

/**
 * 缓存键常量工具类，统一管理各类缓存键前缀与构建规则
 */
public final class CacheKeyConstants {

    private CacheKeyConstants() {
    }

    /**
     * API 网关多级缓存前缀
     */
    public static final String API_GATEWAY_CACHE_PREFIX = "api:gateway:cache:";

    /**
     * 路由缓存前缀
     */
    public static final String ROUTE_CACHE_PREFIX = "api:route:";

    /**
     * 路由列表缓存键
     */
    public static final String ROUTE_LIST_KEY = "api:route:list";

    /**
     * 路由统计信息缓存前缀
     */
    public static final String ROUTE_STATS_PREFIX = "api:route:stats:";

    /**
     * 构建 API 网关缓存键
     *
     * @param cacheName 缓存名称
     * @param key       缓存键
     * @return 组合后的缓存键
     */
    public static String buildGatewayCacheKey(String cacheName, String key) {
        return API_GATEWAY_CACHE_PREFIX + cacheName + ":" + key;
    }

    /**
     * 构建路由统计信息键
     *
     * @param routeId 路由编号
     * @return 组合后的统计键
     */
    public static String buildRouteStatsKey(Long routeId) {
        return ROUTE_STATS_PREFIX + routeId;
    }
}
