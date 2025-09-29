package cn.flying.identity.service.apigateway;

import cn.flying.identity.dto.apigateway.ApiPermission;
import cn.flying.platformapi.constant.Result;

import java.util.List;
import java.util.Map;

/**
 * API权限控制服务接口
 * 提供细粒度的API访问权限管理
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public interface ApiPermissionService {

    /**
     * 授予应用访问接口的权限
     *
     * @param appId 应用ID
     * @param interfaceId 接口ID
     * @param grantBy 授权人ID
     * @param expireDays 权限有效天数(NULL表示永久)
     * @return 授权结果
     */
    Result<Void> grantPermission(Long appId, Long interfaceId, Long grantBy, Integer expireDays);

    /**
     * 批量授予权限
     *
     * @param appId 应用ID
     * @param interfaceIds 接口ID列表
     * @param grantBy 授权人ID
     * @param expireDays 权限有效天数
     * @return 授权结果
     */
    Result<Map<String, Object>> grantBatchPermissions(Long appId, List<Long> interfaceIds,
                                                       Long grantBy, Integer expireDays);

    /**
     * 撤销应用访问接口的权限
     *
     * @param appId 应用ID
     * @param interfaceId 接口ID
     * @return 撤销结果
     */
    Result<Void> revokePermission(Long appId, Long interfaceId);

    /**
     * 启用权限
     *
     * @param permissionId 权限ID
     * @return 操作结果
     */
    Result<Void> enablePermission(Long permissionId);

    /**
     * 禁用权限
     *
     * @param permissionId 权限ID
     * @return 操作结果
     */
    Result<Void> disablePermission(Long permissionId);

    /**
     * 检查应用是否有访问接口的权限
     *
     * @param appId 应用ID
     * @param interfaceId 接口ID
     * @return 是否有权限
     */
    Result<Boolean> hasPermission(Long appId, Long interfaceId);

    /**
     * 检查应用是否有访问接口路径的权限
     * 通过接口路径和方法匹配接口ID
     *
     * @param appId 应用ID
     * @param interfacePath 接口路径
     * @param interfaceMethod 接口方法
     * @return 是否有权限
     */
    Result<Boolean> hasPermissionByPath(Long appId, String interfacePath, String interfaceMethod);

    /**
     * 获取应用的所有权限列表
     *
     * @param appId 应用ID
     * @return 权限列表
     */
    Result<List<ApiPermission>> getPermissionsByApp(Long appId);

    /**
     * 获取接口的所有授权应用列表
     *
     * @param interfaceId 接口ID
     * @return 权限列表
     */
    Result<List<ApiPermission>> getPermissionsByInterface(Long interfaceId);

    /**
     * 获取应用可访问的接口列表
     * 返回完整的接口信息
     *
     * @param appId 应用ID
     * @return 接口列表
     */
    Result<List<Map<String, Object>>> getAccessibleInterfaces(Long appId);

    /**
     * 更新权限过期时间
     *
     * @param permissionId 权限ID
     * @param expireDays 延长天数
     * @return 更新结果
     */
    Result<Void> extendPermission(Long permissionId, int expireDays);

    /**
     * 获取即将过期的权限列表
     *
     * @param days 提前天数
     * @return 即将过期的权限列表
     */
    Result<List<ApiPermission>> getExpiringPermissions(int days);

    /**
     * 清理过期权限
     * 自动禁用或删除已过期的权限
     *
     * @return 清理数量
     */
    Result<Integer> cleanExpiredPermissions();

    /**
     * 同步应用权限到缓存
     * 用于提升权限验证性能
     *
     * @param appId 应用ID
     * @return 同步结果
     */
    Result<Void> syncPermissionsToCache(Long appId);
}
