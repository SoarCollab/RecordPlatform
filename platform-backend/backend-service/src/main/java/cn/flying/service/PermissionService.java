package cn.flying.service;

import cn.flying.dao.entity.SysPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

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

    // ==================== 权限 CRUD 操作 ====================

    /**
     * 获取权限树（当前租户 + 全局权限）
     * @param tenantId 租户ID
     * @return 权限列表
     */
    List<SysPermission> getPermissionTree(Long tenantId);

    /**
     * 获取权限列表（分页）
     * @param tenantId 租户ID
     * @param module 模块名（可选）
     * @param page 分页参数
     * @return 权限分页
     */
    IPage<SysPermission> listPermissions(Long tenantId, String module, Page<SysPermission> page);

    /**
     * 获取所有权限模块名
     * @param tenantId 租户ID
     * @return 模块名称列表
     */
    List<String> listModules(Long tenantId);

    /**
     * 创建权限定义
     * @param permission 权限实体
     * @return 创建后的权限实体
     */
    SysPermission createPermission(SysPermission permission);

    /**
     * 更新权限定义
     * @param externalId 权限外部ID
     * @param name 名称（可选）
     * @param description 描述（可选）
     * @param status 状态（可选）
     * @param tenantId 租户ID
     * @return 更新后的权限实体，不存在时返回 null
     */
    SysPermission updatePermission(String externalId, String name, String description, Integer status, Long tenantId);

    /**
     * 删除权限定义及其关联的角色权限映射
     * @param externalId 权限外部ID
     * @param tenantId 租户ID
     */
    void deletePermission(String externalId, Long tenantId);

    // ==================== 角色权限映射操作 ====================

    /**
     * 为角色授予权限
     * @param role 角色名
     * @param permissionCode 权限码
     * @param tenantId 租户ID
     */
    void assignPermissionToRole(String role, String permissionCode, Long tenantId);

    /**
     * 撤销角色权限
     * @param role 角色名
     * @param permissionCode 权限码
     * @param tenantId 租户ID
     */
    void revokePermissionFromRole(String role, String permissionCode, Long tenantId);
}
