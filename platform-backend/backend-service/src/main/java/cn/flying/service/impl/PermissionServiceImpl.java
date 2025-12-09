package cn.flying.service.impl;

import cn.flying.common.util.CacheUtils;
import cn.flying.common.util.Const;
import cn.flying.common.util.SecurityUtils;
import cn.flying.common.util.TenantKeyUtils;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import cn.flying.service.PermissionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 权限服务实现
 * 支持 Redis 缓存，缓存 key 格式: perm:role:{tenantId}:{role}
 */
@Slf4j
@Service
public class PermissionServiceImpl implements PermissionService {

    @Resource
    private SysPermissionMapper permissionMapper;

    @Resource
    private SysRolePermissionMapper rolePermissionMapper;

    @Resource
    private CacheUtils cacheUtils;

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
        String role = SecurityUtils.getLoginUserRole().name();
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
}
