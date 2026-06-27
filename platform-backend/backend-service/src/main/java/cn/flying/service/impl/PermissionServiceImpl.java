package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.CacheUtils;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.common.util.TenantKeyUtils;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.entity.SysRolePermission;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import cn.flying.service.PermissionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 权限服务实现
 * 支持 Redis 缓存，缓存 key 格式: perm:role:{tenantId}:{role}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final CacheUtils cacheUtils;

    @Override
    public Set<String> getPermissionCodes(String role, Long tenantId) {
        String cacheKey = buildCacheKey(role, tenantId);

        // 尝试从缓存获取
        Set<String> cached = getCachedPermissions(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 从数据库查询
        Set<String> permissions = permissionMapper.selectPermissionCodesByRole(role, tenantId);
        if (permissions == null) {
            permissions = new HashSet<>();
        }

        // 存入缓存
        cacheUtils.saveToCache(cacheKey, permissions, Const.PERMISSION_CACHE_TTL);
        return permissions;
    }

    @Override
    public Set<String> getPermissionCodes(List<String> roles, Long tenantId) {
        if (roles == null || roles.isEmpty()) {
            return new HashSet<>();
        }

        Set<String> allPermissions = new HashSet<>();
        List<String> uncachedRoles = new ArrayList<>();

        // 尝试从缓存获取每个角色的权限
        for (String role : roles) {
            String cacheKey = buildCacheKey(role, tenantId);
            Set<String> cached = getCachedPermissions(cacheKey);
            if (cached != null) {
                allPermissions.addAll(cached);
            } else {
                uncachedRoles.add(role);
            }
        }

        // 对未缓存的角色批量查询
        if (!uncachedRoles.isEmpty()) {
            Set<String> dbPermissions = permissionMapper.selectPermissionCodesByRoles(uncachedRoles, tenantId);
            if (dbPermissions != null) {
                allPermissions.addAll(dbPermissions);
            }
            // 分别缓存每个角色的权限
            for (String role : uncachedRoles) {
                Set<String> rolePermissions = permissionMapper.selectPermissionCodesByRole(role, tenantId);
                String cacheKey = buildCacheKey(role, tenantId);
                cacheUtils.saveToCache(cacheKey, rolePermissions != null ? rolePermissions : new HashSet<>(),
                        Const.PERMISSION_CACHE_TTL);
            }
        }

        return allPermissions;
    }

    @Override
    public boolean hasPermission(String role, String permissionCode, Long tenantId) {
        Set<String> permissions = getPermissionCodes(role, tenantId);
        return permissions.contains(permissionCode);
    }

    @Override
    public boolean hasPermission(String permissionCode) {
        String role = SecurityUtils.getLoginUserRole().getRole();
        Long tenantId = SecurityUtils.getTenantId();
        return hasPermission(role, permissionCode, tenantId);
    }

    @Override
    public List<SysPermission> getPermissionsByModule(String module, Long tenantId) {
        return permissionMapper.selectByModule(module, tenantId);
    }

    @Override
    public void evictCache(String role, Long tenantId) {
        String cacheKey = buildCacheKey(role, tenantId);
        cacheUtils.deleteCache(cacheKey);
        log.debug("Evicted permission cache: {}", cacheKey);
    }

    @Override
    public void evictAllCache(Long tenantId) {
        String pattern = TenantKeyUtils.tenantKey(Const.PERMISSION_CACHE_PREFIX, tenantId) + "*";
        cacheUtils.deleteCachePattern(pattern);
        log.debug("Evicted all permission caches for tenant: {}", tenantId);
    }

    /**
     * 清除所有租户下指定角色的权限缓存。
     *
     * @param role 角色标识
     */
    private void evictRoleAcrossTenants(String role) {
        String pattern = "*" + Const.PERMISSION_CACHE_PREFIX + role;
        cacheUtils.deleteCachePattern(pattern);
        log.debug("Evicted permission cache for role across tenants: {}", role);
    }

    /**
     * 清除所有租户下的权限缓存。
     */
    private void evictAllTenantPermissionCaches() {
        String pattern = "*" + Const.PERMISSION_CACHE_PREFIX + "*";
        cacheUtils.deleteCachePattern(pattern);
        log.debug("Evicted all tenant permission caches");
    }

    /**
     * 根据变更租户范围清除权限定义缓存。
     *
     * @param tenantId 权限定义所属租户
     */
    private void evictPermissionDefinitionCaches(Long tenantId) {
        if (isGlobalTenant(tenantId)) {
            evictAllTenantPermissionCaches();
            return;
        }
        evictAllCache(tenantId);
    }

    /**
     * 根据变更租户范围清除角色权限映射缓存。
     *
     * @param role     角色标识
     * @param tenantId 角色权限映射所属租户
     */
    private void evictRolePermissionCaches(String role, Long tenantId) {
        if (isGlobalTenant(tenantId)) {
            evictRoleAcrossTenants(role);
            return;
        }
        evictCache(role, tenantId);
    }

    /**
     * 判断是否为全局租户权限。
     *
     * @param tenantId 租户 ID
     * @return true 表示全局租户权限
     */
    private boolean isGlobalTenant(Long tenantId) {
        return Long.valueOf(0L).equals(tenantId);
    }

    /**
     * 构建缓存 key
     */
    private String buildCacheKey(String role, Long tenantId) {
        return TenantKeyUtils.tenantKey(Const.PERMISSION_CACHE_PREFIX, tenantId) + role;
    }

    /**
     * 从缓存获取权限集合
     */
    @SuppressWarnings("unchecked")
    private Set<String> getCachedPermissions(String cacheKey) {
        List<String> cached = cacheUtils.takeListFormCache(cacheKey, String.class);
        if (cached != null) {
            return new HashSet<>(cached);
        }
        return null;
    }

    // ==================== 权限 CRUD 操作 ====================

    @Override
    public List<SysPermission> getPermissionTree(Long tenantId) {
        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(SysPermission::getTenantId, 0L).or().eq(SysPermission::getTenantId, tenantId));
        wrapper.eq(SysPermission::getStatus, 1);
        wrapper.orderByAsc(SysPermission::getModule, SysPermission::getCode);
        return permissionMapper.selectList(wrapper);
    }

    @Override
    public IPage<SysPermission> listPermissions(Long tenantId, String module, Page<SysPermission> page) {
        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(SysPermission::getTenantId, 0L).or().eq(SysPermission::getTenantId, tenantId));
        if (module != null && !module.isEmpty()) {
            wrapper.eq(SysPermission::getModule, module);
        }
        wrapper.orderByAsc(SysPermission::getModule, SysPermission::getCode);
        return permissionMapper.selectPage(page, wrapper);
    }

    @Override
    public List<String> listModules(Long tenantId) {
        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(SysPermission::getTenantId, 0L).or().eq(SysPermission::getTenantId, tenantId));
        wrapper.select(SysPermission::getModule);
        wrapper.groupBy(SysPermission::getModule);

        List<SysPermission> permissions = permissionMapper.selectList(wrapper);
        return permissions.stream()
                .map(SysPermission::getModule)
                .toList();
    }

    @Override
    public SysPermission createPermission(SysPermission permission) {
        permissionMapper.insert(permission);
        return permission;
    }

    @Override
    @Transactional
    public SysPermission updatePermission(String externalId, String name, String description, Integer status, Long tenantId) {
        Long permissionId = IdUtils.fromExternalId(externalId);
        LambdaQueryWrapper<SysPermission> queryWrapper = new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getId, permissionId)
                .eq(SysPermission::getTenantId, tenantId);
        SysPermission permission = permissionMapper.selectOne(queryWrapper);
        if (permission == null) {
            return null;
        }

        LambdaUpdateWrapper<SysPermission> updateWrapper = new LambdaUpdateWrapper<SysPermission>()
                .eq(SysPermission::getId, permissionId)
                .eq(SysPermission::getTenantId, tenantId);
        boolean changed = false;
        if (name != null) {
            permission.setName(name);
            updateWrapper.set(SysPermission::getName, name);
            changed = true;
        }
        if (description != null) {
            permission.setDescription(description);
            updateWrapper.set(SysPermission::getDescription, description);
            changed = true;
        }
        if (status != null) {
            permission.setStatus(status);
            updateWrapper.set(SysPermission::getStatus, status);
            changed = true;
        }

        if (changed) {
            permissionMapper.update(null, updateWrapper);
            evictPermissionDefinitionCaches(tenantId);
        }
        return permission;
    }

    @Override
    @Transactional
    public void deletePermission(String externalId, Long tenantId) {
        Long permissionId = IdUtils.fromExternalId(externalId);
        LambdaQueryWrapper<SysPermission> permissionScope = new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getId, permissionId)
                .eq(SysPermission::getTenantId, tenantId);
        SysPermission permission = permissionMapper.selectOne(permissionScope);
        if (permission == null) {
            return;
        }

        LambdaQueryWrapper<SysRolePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRolePermission::getPermissionId, permissionId)
                .eq(SysRolePermission::getTenantId, tenantId);
        rolePermissionMapper.delete(wrapper);

        permissionMapper.delete(permissionScope);
        evictPermissionDefinitionCaches(tenantId);
    }

    // ==================== 角色权限映射操作 ====================

    @Override
    @Transactional
    public void assignPermissionToRole(String role, String permissionCode, Long tenantId) {
        SysPermission permission = permissionMapper.selectByCode(permissionCode, tenantId);
        if (permission == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE, "权限码不存在: " + permissionCode);
        }

        int count = rolePermissionMapper.countByRoleAndPermission(role, permissionCode, tenantId);
        if (count > 0) {
            throw new GeneralException(ResultEnum.DATA_ALREADY_EXISTED, "该角色已拥有此权限");
        }

        SysRolePermission mapping = new SysRolePermission()
                .setTenantId(tenantId)
                .setRole(role)
                .setPermissionId(permission.getId());
        rolePermissionMapper.insert(mapping);
        evictRolePermissionCaches(role, tenantId);
    }

    @Override
    @Transactional
    public void revokePermissionFromRole(String role, String permissionCode, Long tenantId) {
        SysPermission permission = permissionMapper.selectByCode(permissionCode, tenantId);
        if (permission == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE, "权限码不存在: " + permissionCode);
        }

        LambdaQueryWrapper<SysRolePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRolePermission::getRole, role)
                .eq(SysRolePermission::getPermissionId, permission.getId())
                .eq(SysRolePermission::getTenantId, tenantId);
        rolePermissionMapper.delete(wrapper);
        evictRolePermissionCaches(role, tenantId);
    }
}
