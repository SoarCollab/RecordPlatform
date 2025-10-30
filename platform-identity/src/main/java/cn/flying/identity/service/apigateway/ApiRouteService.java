package cn.flying.identity.service.apigateway;

import cn.flying.identity.dto.apigateway.ApiRoute;

import java.util.List;
import java.util.Map;

/**
 * 动态路由管理服务接口
 * 提供路由的动态配置、刷新、匹配等功能
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public interface ApiRouteService {

    /**
     * 创建路由
     *
     * @param route 路由配置
     * @return 创建结果
     */
    ApiRoute createRoute(ApiRoute route);

    /**
     * 更新路由
     *
     * @param route 路由配置
     * @return 更新结果
     */
    void updateRoute(ApiRoute route);

    /**
     * 删除路由
     *
     * @param routeId 路由ID
     * @return 删除结果
     */
    void deleteRoute(Long routeId);

    /**
     * 启用路由
     *
     * @param routeId 路由ID
     * @return 操作结果
     */
    void enableRoute(Long routeId);

    /**
     * 禁用路由
     *
     * @param routeId 路由ID
     * @return 操作结果
     */
    void disableRoute(Long routeId);

    /**
     * 获取路由详情
     *
     * @param routeId 路由ID
     * @return 路由信息
     */
    ApiRoute getRouteById(Long routeId);

    /**
     * 获取所有启用的路由
     *
     * @return 路由列表
     */
    List<ApiRoute> getActiveRoutes();

    /**
     * 根据服务名获取路由
     *
     * @param serviceName 服务名称
     * @return 路由列表
     */
    List<ApiRoute> getRoutesByService(String serviceName);

    /**
     * 匹配路由
     * 根据请求路径和方法匹配最合适的路由
     *
     * @param path   请求路径
     * @param method HTTP方法
     * @return 匹配的路由信息
     */
    ApiRoute matchRoute(String path, String method);

    /**
     * 批量导入路由配置
     *
     * @param routes 路由列表
     * @return 导入结果
     */
    Map<String, Object> batchImportRoutes(List<ApiRoute> routes);

    /**
     * 导出路由配置
     *
     * @return 路由配置列表
     */
    List<ApiRoute> exportRoutes();

    /**
     * 刷新路由缓存
     * 将路由配置同步到缓存中
     *
     * @return 刷新结果
     */
    void refreshRouteCache();

    /**
     * 获取路由统计信息
     *
     * @param routeId 路由ID
     * @param days    统计天数
     * @return 统计信息
     */
    Map<String, Object> getRouteStatistics(Long routeId, int days);

    /**
     * 测试路由连通性
     *
     * @param routeId 路由ID
     * @return 测试结果
     */
    Map<String, Object> testRoute(Long routeId);

    /**
     * 更新路由优先级
     *
     * @param routeId  路由ID
     * @param priority 新的优先级
     * @return 更新结果
     */
    void updateRoutePriority(Long routeId, Integer priority);

    /**
     * 更新路由限流配置
     *
     * @param routeId   路由ID
     * @param rateLimit 限流QPS
     * @return 更新结果
     */
    void updateRouteRateLimit(Long routeId, Integer rateLimit);

    /**
     * 获取路由健康状态
     *
     * @param routeId 路由ID
     * @return 健康状态信息
     */
    Map<String, Object> getRouteHealth(Long routeId);
}
