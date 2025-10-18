package cn.flying.monitor.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于角色的访问控制服务
 */
@Slf4j
@Service
public class RbacService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String USER_ROLES_PREFIX = "rbac:user:roles:";
    private static final String ROLE_PERMISSIONS_PREFIX = "rbac:role:permissions:";
    private static final String USER_PERMISSIONS_CACHE_PREFIX = "rbac:user:permissions:";
    private static final Duration CACHE_DURATION = Duration.ofMinutes(30);

    // 预定义角色
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";
    public static final String ROLE_MONITOR_ADMIN = "MONITOR_ADMIN";
    public static final String ROLE_OPERATOR = "OPERATOR";
    public static final String ROLE_VIEWER = "VIEWER";
    public static final String ROLE_CLIENT = "CLIENT";

    public RbacService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        initializeDefaultRoles();
    }

    /**
     * 初始化默认角色权限
     */
    private void initializeDefaultRoles() {
        // 超级管理员 - 所有权限
        Set<String> superAdminPermissions = Arrays.stream(Permission.values())
                .map(Permission::getCode)
                .collect(Collectors.toSet());
        setRolePermissions(ROLE_SUPER_ADMIN, superAdminPermissions);

        // 系统管理员 - 系统管理相关权限
        Set<String> systemAdminPermissions = Set.of(
                Permission.SYSTEM_CONFIG.getCode(),
                Permission.SYSTEM_AUDIT.getCode(),
                Permission.USER_CREATE.getCode(),
                Permission.USER_READ.getCode(),
                Permission.USER_UPDATE.getCode(),
                Permission.USER_DELETE.getCode(),
                Permission.USER_ROLE_ASSIGN.getCode(),
                Permission.SECURITY_AUDIT_READ.getCode(),
                Permission.SECURITY_CERT_MANAGE.getCode(),
                Permission.SECURITY_MFA_MANAGE.getCode()
        );
        setRolePermissions(ROLE_SYSTEM_ADMIN, systemAdminPermissions);

        // 监控管理员 - 监控相关权限
        Set<String> monitorAdminPermissions = Set.of(
                Permission.MONITOR_DATA_READ.getCode(),
                Permission.MONITOR_DATA_WRITE.getCode(),
                Permission.MONITOR_DATA_DELETE.getCode(),
                Permission.MONITOR_DATA_EXPORT.getCode(),
                Permission.ALERT_CREATE.getCode(),
                Permission.ALERT_READ.getCode(),
                Permission.ALERT_UPDATE.getCode(),
                Permission.ALERT_DELETE.getCode(),
                Permission.ALERT_ACKNOWLEDGE.getCode(),
                Permission.SERVER_CREATE.getCode(),
                Permission.SERVER_READ.getCode(),
                Permission.SERVER_UPDATE.getCode(),
                Permission.SERVER_DELETE.getCode(),
                Permission.CLIENT_CREATE.getCode(),
                Permission.CLIENT_READ.getCode(),
                Permission.CLIENT_UPDATE.getCode(),
                Permission.CLIENT_DELETE.getCode(),
                Permission.NOTIFICATION_CREATE.getCode(),
                Permission.NOTIFICATION_READ.getCode(),
                Permission.NOTIFICATION_UPDATE.getCode(),
                Permission.NOTIFICATION_DELETE.getCode(),
                Permission.NOTIFICATION_SEND.getCode(),
                Permission.WEBSOCKET_CONNECT.getCode(),
                Permission.WEBSOCKET_MONITOR.getCode()
        );
        setRolePermissions(ROLE_MONITOR_ADMIN, monitorAdminPermissions);

        // 操作员 - 基本操作权限
        Set<String> operatorPermissions = Set.of(
                Permission.MONITOR_DATA_READ.getCode(),
                Permission.ALERT_READ.getCode(),
                Permission.ALERT_ACKNOWLEDGE.getCode(),
                Permission.SERVER_READ.getCode(),
                Permission.SERVER_CONTROL.getCode(),
                Permission.CLIENT_READ.getCode(),
                Permission.NOTIFICATION_READ.getCode(),
                Permission.WEBSOCKET_CONNECT.getCode(),
                Permission.WEBSOCKET_TERMINAL.getCode(),
                Permission.WEBSOCKET_MONITOR.getCode()
        );
        setRolePermissions(ROLE_OPERATOR, operatorPermissions);

        // 查看者 - 只读权限
        Set<String> viewerPermissions = Set.of(
                Permission.MONITOR_DATA_READ.getCode(),
                Permission.ALERT_READ.getCode(),
                Permission.SERVER_READ.getCode(),
                Permission.CLIENT_READ.getCode(),
                Permission.NOTIFICATION_READ.getCode(),
                Permission.REPORT_READ.getCode(),
                Permission.WEBSOCKET_CONNECT.getCode(),
                Permission.WEBSOCKET_MONITOR.getCode()
        );
        setRolePermissions(ROLE_VIEWER, viewerPermissions);

        // 客户端 - 数据上报权限
        Set<String> clientPermissions = Set.of(
                Permission.MONITOR_DATA_WRITE.getCode(),
                Permission.WEBSOCKET_CONNECT.getCode()
        );
        setRolePermissions(ROLE_CLIENT, clientPermissions);

        log.info("Default RBAC roles initialized");
    }

    /**
     * 为用户分配角色
     */
    public void assignRoleToUser(String userId, String role) {
        Set<String> userRoles = getUserRoles(userId);
        userRoles.add(role);
        redisTemplate.opsForValue().set(USER_ROLES_PREFIX + userId, userRoles);
        
        // 清除用户权限缓存
        clearUserPermissionsCache(userId);
        
        log.info("Role {} assigned to user {}", role, userId);
    }

    /**
     * 从用户移除角色
     */
    public void removeRoleFromUser(String userId, String role) {
        Set<String> userRoles = getUserRoles(userId);
        userRoles.remove(role);
        redisTemplate.opsForValue().set(USER_ROLES_PREFIX + userId, userRoles);
        
        // 清除用户权限缓存
        clearUserPermissionsCache(userId);
        
        log.info("Role {} removed from user {}", role, userId);
    }

    /**
     * 获取用户角色
     */
    @SuppressWarnings("unchecked")
    public Set<String> getUserRoles(String userId) {
        Set<String> roles = (Set<String>) redisTemplate.opsForValue().get(USER_ROLES_PREFIX + userId);
        return roles != null ? new HashSet<>(roles) : new HashSet<>();
    }

    /**
     * 设置角色权限
     */
    public void setRolePermissions(String role, Set<String> permissions) {
        redisTemplate.opsForValue().set(ROLE_PERMISSIONS_PREFIX + role, permissions);
        
        // 清除所有拥有此角色的用户的权限缓存
        clearRolePermissionsCache(role);
        
        log.info("Permissions set for role {}: {}", role, permissions);
    }

    /**
     * 获取角色权限
     */
    @SuppressWarnings("unchecked")
    public Set<String> getRolePermissions(String role) {
        Set<String> permissions = (Set<String>) redisTemplate.opsForValue().get(ROLE_PERMISSIONS_PREFIX + role);
        return permissions != null ? new HashSet<>(permissions) : new HashSet<>();
    }

    /**
     * 获取用户所有权限 (通过用户ID)
     */
    @SuppressWarnings("unchecked")
    public Set<String> getUserPermissions(String userId) {
        // 先检查缓存
        String cacheKey = USER_PERMISSIONS_CACHE_PREFIX + userId;
        Set<String> cachedPermissions = (Set<String>) redisTemplate.opsForValue().get(cacheKey);
        if (cachedPermissions != null) {
            return new HashSet<>(cachedPermissions);
        }

        // 计算用户权限
        Set<String> userPermissions = new HashSet<>();
        Set<String> userRoles = getUserRoles(userId);
        
        for (String role : userRoles) {
            Set<String> rolePermissions = getRolePermissions(role);
            userPermissions.addAll(rolePermissions);
        }

        // 缓存结果
        redisTemplate.opsForValue().set(cacheKey, userPermissions, CACHE_DURATION);
        
        return userPermissions;
    }

    /**
     * 检查用户是否有指定权限
     */
    public boolean hasPermission(String userId, String permission) {
        Set<String> userPermissions = getUserPermissions(userId);
        return userPermissions.contains(permission);
    }

    /**
     * 检查用户是否有指定权限
     */
    public boolean hasPermission(String userId, Permission permission) {
        return hasPermission(userId, permission.getCode());
    }

    /**
     * 检查用户是否有任一权限
     */
    public boolean hasAnyPermission(String userId, String... permissions) {
        Set<String> userPermissions = getUserPermissions(userId);
        return Arrays.stream(permissions).anyMatch(userPermissions::contains);
    }

    /**
     * 检查用户是否有所有权限
     */
    public boolean hasAllPermissions(String userId, String... permissions) {
        Set<String> userPermissions = getUserPermissions(userId);
        return Arrays.stream(permissions).allMatch(userPermissions::contains);
    }

    /**
     * 检查用户是否有指定角色
     */
    public boolean hasRole(String userId, String role) {
        Set<String> userRoles = getUserRoles(userId);
        return userRoles.contains(role);
    }

    /**
     * 清除用户权限缓存
     */
    private void clearUserPermissionsCache(String userId) {
        redisTemplate.delete(USER_PERMISSIONS_CACHE_PREFIX + userId);
    }

    /**
     * 清除角色相关的权限缓存
     */
    private void clearRolePermissionsCache(String role) {
        // 这里简化处理，实际应该找到所有拥有此角色的用户并清除其缓存
        Set<String> keys = redisTemplate.keys(USER_PERMISSIONS_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /**
     * 获取所有可用角色
     */
    public Set<String> getAllRoles() {
        return Set.of(ROLE_SUPER_ADMIN, ROLE_SYSTEM_ADMIN, ROLE_MONITOR_ADMIN, 
                     ROLE_OPERATOR, ROLE_VIEWER, ROLE_CLIENT);
    }

    /**
     * 获取所有可用权限
     */
    public Set<String> getAllPermissions() {
        return Arrays.stream(Permission.values())
                .map(Permission::getCode)
                .collect(Collectors.toSet());
    }

    /**
     * 获取用户所有权限 (通过User对象)
     */
    public List<String> getUserPermissions(cn.flying.monitor.common.entity.User user) {
        Set<String> permissions = getUserPermissions(user.getId().toString());
        return new ArrayList<>(permissions);
    }
}