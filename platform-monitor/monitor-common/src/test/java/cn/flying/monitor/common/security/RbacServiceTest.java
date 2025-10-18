package cn.flying.monitor.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RBAC权限控制服务测试
 * 测试角色分配、权限管理和权限校验功能
 */
@ExtendWith(MockitoExtension.class)
class RbacServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private RbacService rbacService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Mock 默认角色权限初始化时的 Redis 操作
        doNothing().when(valueOperations).set(anyString(), any());

        // 手动创建实例，而不是使用 @InjectMocks
        // 因为构造函数会调用 initializeDefaultRoles()，需要先配置 mock
        rbacService = new RbacService(redisTemplate);
    }

    @Test
    void shouldAssignRoleToUser() {
        // Given
        String userId = "user123";
        String role = RbacService.ROLE_VIEWER;

        when(valueOperations.get(anyString())).thenReturn(new HashSet<>());

        // When
        rbacService.assignRoleToUser(userId, role);

        // Then
        ArgumentCaptor<Set<String>> rolesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(valueOperations).set(eq("rbac:user:roles:" + userId), rolesCaptor.capture());

        Set<String> roles = rolesCaptor.getValue();
        assertTrue(roles.contains(role));
    }

    @Test
    void shouldRemoveRoleFromUser() {
        // Given
        String userId = "user123";
        String role = RbacService.ROLE_VIEWER;

        Set<String> existingRoles = new HashSet<>();
        existingRoles.add(role);
        existingRoles.add(RbacService.ROLE_OPERATOR);

        when(valueOperations.get("rbac:user:roles:" + userId)).thenReturn(existingRoles);

        // When
        rbacService.removeRoleFromUser(userId, role);

        // Then
        ArgumentCaptor<Set<String>> rolesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(valueOperations).set(eq("rbac:user:roles:" + userId), rolesCaptor.capture());

        Set<String> roles = rolesCaptor.getValue();
        assertFalse(roles.contains(role));
        assertTrue(roles.contains(RbacService.ROLE_OPERATOR));
    }

    @Test
    void shouldGetUserRoles() {
        // Given
        String userId = "user123";
        Set<String> expectedRoles = Set.of(
            RbacService.ROLE_VIEWER,
            RbacService.ROLE_OPERATOR
        );

        when(valueOperations.get("rbac:user:roles:" + userId)).thenReturn(expectedRoles);

        // When
        Set<String> roles = rbacService.getUserRoles(userId);

        // Then
        assertEquals(2, roles.size());
        assertTrue(roles.contains(RbacService.ROLE_VIEWER));
        assertTrue(roles.contains(RbacService.ROLE_OPERATOR));
    }

    @Test
    void shouldReturnEmptySetWhenUserHasNoRoles() {
        // Given
        String userId = "newuser";
        when(valueOperations.get("rbac:user:roles:" + userId)).thenReturn(null);

        // When
        Set<String> roles = rbacService.getUserRoles(userId);

        // Then
        assertNotNull(roles);
        assertTrue(roles.isEmpty());
    }

    @Test
    void shouldSetRolePermissions() {
        // Given
        String role = "CUSTOM_ROLE";
        Set<String> permissions = Set.of(
            Permission.MONITOR_DATA_READ.getCode(),
            Permission.ALERT_READ.getCode()
        );

        // When
        rbacService.setRolePermissions(role, permissions);

        // Then
        verify(valueOperations).set(eq("rbac:role:permissions:" + role), eq(permissions));

        // 验证清理了权限缓存（会调用 redisTemplate.keys）
        verify(redisTemplate, atLeastOnce()).keys("rbac:user:permissions:*");
    }

    @Test
    void shouldGetRolePermissions() {
        // Given
        String role = RbacService.ROLE_VIEWER;
        Set<String> expectedPermissions = Set.of(
            Permission.MONITOR_DATA_READ.getCode(),
            Permission.ALERT_READ.getCode()
        );

        when(valueOperations.get("rbac:role:permissions:" + role)).thenReturn(expectedPermissions);

        // When
        Set<String> permissions = rbacService.getRolePermissions(role);

        // Then
        assertEquals(2, permissions.size());
        assertTrue(permissions.contains(Permission.MONITOR_DATA_READ.getCode()));
    }

    @Test
    void shouldGetUserPermissions() {
        // Given
        String userId = "user123";
        Set<String> userRoles = Set.of(RbacService.ROLE_VIEWER);
        Set<String> viewerPermissions = Set.of(
            Permission.MONITOR_DATA_READ.getCode(),
            Permission.ALERT_READ.getCode(),
            Permission.SERVER_READ.getCode()
        );

        when(valueOperations.get("rbac:user:permissions:" + userId)).thenReturn(null); // 缓存未命中
        when(valueOperations.get("rbac:user:roles:" + userId)).thenReturn(userRoles);
        when(valueOperations.get("rbac:role:permissions:" + RbacService.ROLE_VIEWER))
            .thenReturn(viewerPermissions);

        // When
        Set<String> permissions = rbacService.getUserPermissions(userId);

        // Then
        assertEquals(3, permissions.size());
        assertTrue(permissions.contains(Permission.MONITOR_DATA_READ.getCode()));

        // 验证缓存了结果
        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
            eq("rbac:user:permissions:" + userId),
            any(),
            durationCaptor.capture()
        );

        assertEquals(Duration.ofMinutes(30), durationCaptor.getValue());
    }

    @Test
    void shouldGetUserPermissionsFromCache() {
        // Given
        String userId = "user123";
        Set<String> cachedPermissions = Set.of(
            Permission.MONITOR_DATA_READ.getCode(),
            Permission.ALERT_READ.getCode()
        );

        when(valueOperations.get("rbac:user:permissions:" + userId)).thenReturn(cachedPermissions);

        // When
        Set<String> permissions = rbacService.getUserPermissions(userId);

        // Then
        assertEquals(2, permissions.size());
        verify(valueOperations, never()).get(startsWith("rbac:user:roles:"));
    }

    @Test
    void shouldCheckUserHasPermission() {
        // Given
        String userId = "user123";
        Set<String> userPermissions = Set.of(
            Permission.MONITOR_DATA_READ.getCode(),
            Permission.ALERT_READ.getCode()
        );

        when(valueOperations.get("rbac:user:permissions:" + userId)).thenReturn(userPermissions);

        // When & Then
        assertTrue(rbacService.hasPermission(userId, Permission.MONITOR_DATA_READ.getCode()));
        assertTrue(rbacService.hasPermission(userId, Permission.MONITOR_DATA_READ));
        assertFalse(rbacService.hasPermission(userId, Permission.MONITOR_DATA_WRITE.getCode()));
    }

    @Test
    void shouldCheckUserHasAnyPermission() {
        // Given
        String userId = "user123";
        Set<String> userPermissions = Set.of(
            Permission.MONITOR_DATA_READ.getCode()
        );

        when(valueOperations.get("rbac:user:permissions:" + userId)).thenReturn(userPermissions);

        // When & Then
        assertTrue(rbacService.hasAnyPermission(
            userId,
            Permission.MONITOR_DATA_READ.getCode(),
            Permission.MONITOR_DATA_WRITE.getCode()
        ));

        assertFalse(rbacService.hasAnyPermission(
            userId,
            Permission.MONITOR_DATA_WRITE.getCode(),
            Permission.MONITOR_DATA_DELETE.getCode()
        ));
    }

    @Test
    void shouldCheckUserHasAllPermissions() {
        // Given
        String userId = "user123";
        Set<String> userPermissions = Set.of(
            Permission.MONITOR_DATA_READ.getCode(),
            Permission.ALERT_READ.getCode()
        );

        when(valueOperations.get("rbac:user:permissions:" + userId)).thenReturn(userPermissions);

        // When & Then
        assertTrue(rbacService.hasAllPermissions(
            userId,
            Permission.MONITOR_DATA_READ.getCode(),
            Permission.ALERT_READ.getCode()
        ));

        assertFalse(rbacService.hasAllPermissions(
            userId,
            Permission.MONITOR_DATA_READ.getCode(),
            Permission.MONITOR_DATA_WRITE.getCode()
        ));
    }

    @Test
    void shouldCheckUserHasRole() {
        // Given
        String userId = "user123";
        Set<String> userRoles = Set.of(RbacService.ROLE_VIEWER, RbacService.ROLE_OPERATOR);

        when(valueOperations.get("rbac:user:roles:" + userId)).thenReturn(userRoles);

        // When & Then
        assertTrue(rbacService.hasRole(userId, RbacService.ROLE_VIEWER));
        assertTrue(rbacService.hasRole(userId, RbacService.ROLE_OPERATOR));
        assertFalse(rbacService.hasRole(userId, RbacService.ROLE_MONITOR_ADMIN));
    }

    @Test
    void shouldCombinePermissionsFromMultipleRoles() {
        // Given
        String userId = "user123";
        Set<String> userRoles = Set.of(
            RbacService.ROLE_VIEWER,
            RbacService.ROLE_OPERATOR
        );

        Set<String> viewerPermissions = Set.of(
            Permission.MONITOR_DATA_READ.getCode(),
            Permission.ALERT_READ.getCode()
        );

        Set<String> operatorPermissions = Set.of(
            Permission.MONITOR_DATA_READ.getCode(), // 重复
            Permission.ALERT_ACKNOWLEDGE.getCode(),
            Permission.SERVER_CONTROL.getCode()
        );

        when(valueOperations.get("rbac:user:permissions:" + userId)).thenReturn(null);
        when(valueOperations.get("rbac:user:roles:" + userId)).thenReturn(userRoles);
        when(valueOperations.get("rbac:role:permissions:" + RbacService.ROLE_VIEWER))
            .thenReturn(viewerPermissions);
        when(valueOperations.get("rbac:role:permissions:" + RbacService.ROLE_OPERATOR))
            .thenReturn(operatorPermissions);

        // When
        Set<String> permissions = rbacService.getUserPermissions(userId);

        // Then - 应该合并去重
        assertEquals(4, permissions.size());
        assertTrue(permissions.contains(Permission.MONITOR_DATA_READ.getCode()));
        assertTrue(permissions.contains(Permission.ALERT_READ.getCode()));
        assertTrue(permissions.contains(Permission.ALERT_ACKNOWLEDGE.getCode()));
        assertTrue(permissions.contains(Permission.SERVER_CONTROL.getCode()));
    }

    @Test
    void shouldReturnAllAvailableRoles() {
        // When
        Set<String> roles = rbacService.getAllRoles();

        // Then
        assertEquals(6, roles.size());
        assertTrue(roles.contains(RbacService.ROLE_SUPER_ADMIN));
        assertTrue(roles.contains(RbacService.ROLE_SYSTEM_ADMIN));
        assertTrue(roles.contains(RbacService.ROLE_MONITOR_ADMIN));
        assertTrue(roles.contains(RbacService.ROLE_OPERATOR));
        assertTrue(roles.contains(RbacService.ROLE_VIEWER));
        assertTrue(roles.contains(RbacService.ROLE_CLIENT));
    }

    @Test
    void shouldReturnAllAvailablePermissions() {
        // When
        Set<String> permissions = rbacService.getAllPermissions();

        // Then - 应该包含所有枚举定义的权限
        assertTrue(permissions.size() >= 30); // 至少30个权限
        assertTrue(permissions.contains(Permission.MONITOR_DATA_READ.getCode()));
        assertTrue(permissions.contains(Permission.USER_CREATE.getCode()));
        assertTrue(permissions.contains(Permission.ALERT_CREATE.getCode()));
    }

    @Test
    void shouldClearUserPermissionsCacheWhenRoleAssigned() {
        // Given
        String userId = "user123";
        when(valueOperations.get(anyString())).thenReturn(new HashSet<>());

        // When
        rbacService.assignRoleToUser(userId, RbacService.ROLE_VIEWER);

        // Then
        verify(redisTemplate).delete("rbac:user:permissions:" + userId);
    }

    @Test
    void shouldClearUserPermissionsCacheWhenRoleRemoved() {
        // Given
        String userId = "user123";
        Set<String> roles = new HashSet<>();
        roles.add(RbacService.ROLE_VIEWER);

        when(valueOperations.get("rbac:user:roles:" + userId)).thenReturn(roles);

        // When
        rbacService.removeRoleFromUser(userId, RbacService.ROLE_VIEWER);

        // Then
        verify(redisTemplate).delete("rbac:user:permissions:" + userId);
    }

    @Test
    void shouldClearAllUserPermissionsCacheWhenRolePermissionsChanged() {
        // Given
        String role = RbacService.ROLE_VIEWER;
        Set<String> permissions = Set.of(Permission.MONITOR_DATA_READ.getCode());
        Set<String> cacheKeys = Set.of(
            "rbac:user:permissions:user1",
            "rbac:user:permissions:user2"
        );

        when(redisTemplate.keys("rbac:user:permissions:*")).thenReturn(cacheKeys);

        // When
        rbacService.setRolePermissions(role, permissions);

        // Then
        verify(redisTemplate).delete(cacheKeys);
    }

    @Test
    void shouldHandleNullRolePermissions() {
        // Given
        String role = "NON_EXISTENT_ROLE";
        when(valueOperations.get("rbac:role:permissions:" + role)).thenReturn(null);

        // When
        Set<String> permissions = rbacService.getRolePermissions(role);

        // Then
        assertNotNull(permissions);
        assertTrue(permissions.isEmpty());
    }

    @Test
    void shouldInitializeDefaultRolesOnConstruction() {
        // Given - 在 setUp 中已经 mock 了 Redis 操作

        // When - 创建新实例时会初始化默认角色
        RbacService newInstance = new RbacService(redisTemplate);

        // Then - 应该设置了默认角色的权限
        verify(valueOperations, atLeast(6)).set(startsWith("rbac:role:permissions:"), any());
    }

    @Test
    void shouldSuperAdminHaveAllPermissions() {
        // Given
        Set<String> superAdminPermissions = new HashSet<>();
        for (Permission permission : Permission.values()) {
            superAdminPermissions.add(permission.getCode());
        }

        when(valueOperations.get("rbac:role:permissions:" + RbacService.ROLE_SUPER_ADMIN))
            .thenReturn(superAdminPermissions);

        // When
        Set<String> permissions = rbacService.getRolePermissions(RbacService.ROLE_SUPER_ADMIN);

        // Then
        assertEquals(Permission.values().length, permissions.size());
    }

    @Test
    void shouldClientRoleHaveLimitedPermissions() {
        // Given
        Set<String> clientPermissions = Set.of(
            Permission.MONITOR_DATA_WRITE.getCode(),
            Permission.WEBSOCKET_CONNECT.getCode()
        );

        when(valueOperations.get("rbac:role:permissions:" + RbacService.ROLE_CLIENT))
            .thenReturn(clientPermissions);

        // When
        Set<String> permissions = rbacService.getRolePermissions(RbacService.ROLE_CLIENT);

        // Then - 客户端应该只有数据写入和连接权限
        assertEquals(2, permissions.size());
        assertTrue(permissions.contains(Permission.MONITOR_DATA_WRITE.getCode()));
        assertFalse(permissions.contains(Permission.MONITOR_DATA_READ.getCode()));
    }
}
