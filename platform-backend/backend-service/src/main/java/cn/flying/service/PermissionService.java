package cn.flying.service;

import cn.flying.dao.entity.SysPermission;

import java.util.List;
import java.util.Set;

/**
 * 权限服务接口
 */
public interface PermissionService {

    /**
     * 获取角色的所有权限码
     * @param role 角色标识
     * @param tenantId 租户ID
     * @return 权限码集合
     */
    Set<String> getPermissionCodes(String role, Long tenantId);

    /**
     * 获取多个角色的所有权限码
     * @param roles 角色标识列表
     * @param tenantId 租户ID
     * @return 权限码集合
     */
    Set<String> getPermissionCodes(List<String> roles, Long tenantId);

    /**
     * 检查角色是否拥有指定权限
     * @param role 角色标识
     * @param permissionCode 权限码
     * @param tenantId 租户ID
     * @return true 如果拥有该权限
     */
    boolean hasPermission(String role, String permissionCode, Long tenantId);

    /**
     * 检查当前用户是否拥有指定权限
     * @param permissionCode 权限码
     * @return true 如果拥有该权限
     */
    boolean hasPermission(String permissionCode);

    /**
     * 获取模块下的所有权限
     * @param module 模块名
     * @param tenantId 租户ID
     * @return 权限列表
     */
    List<SysPermission> getPermissionsByModule(String module, Long tenantId);

    /**
     * 清除角色权限缓存
     * @param role 角色标识
     * @param tenantId 租户ID
     */
    void evictCache(String role, Long tenantId);

    /**
     * 清除租户下所有权限缓存
     * @param tenantId 租户ID
     */
    void evictAllCache(Long tenantId);
}
