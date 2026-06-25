package cn.flying.service.impl;

import cn.flying.common.util.CacheUtils;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.TenantKeyUtils;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.entity.SysRolePermission;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PermissionServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
class PermissionServiceImplTest {

    @Mock
    private SysPermissionMapper permissionMapper;

    @Mock
    private SysRolePermissionMapper rolePermissionMapper;

    @Mock
    private CacheUtils cacheUtils;

    @InjectMocks
    private PermissionServiceImpl permissionService;

    private static final Long TENANT_ID = 1L;
    private static final String ROLE_USER = "ROLE_USER";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String PERM_FILE_READ = "file:read";
    private static final String PERM_FILE_WRITE = "file:write";
    private static final String PERM_FILE_DELETE = "file:delete";
    private static final String PERM_ADMIN_ALL = "admin:all";

    /**
     * 初始化 MyBatis-Plus Lambda 缓存，支持纯 Mockito 单测构造 LambdaWrapper。
     */
    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, SysPermission.class);
        TableInfoHelper.initTableInfo(assistant, SysRolePermission.class);
    }

    private String buildExpectedCacheKey(String role, Long tenantId) {
        return TenantKeyUtils.tenantKey(Const.PERMISSION_CACHE_PREFIX, tenantId) + role;
    }

    @Nested
    @DisplayName("getPermissionCodes(role, tenantId)")
    class GetPermissionCodesSingleRole {

        @Test
        @DisplayName("should return cached permissions on cache hit")
        void cacheHit_returnsFromCache() {
            String cacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            List<String> cachedList = List.of(PERM_FILE_READ, PERM_FILE_WRITE);
            when(cacheUtils.takeListFormCache(cacheKey, String.class)).thenReturn(cachedList);

            Set<String> result = permissionService.getPermissionCodes(ROLE_USER, TENANT_ID);

            assertEquals(Set.of(PERM_FILE_READ, PERM_FILE_WRITE), result);
            verify(permissionMapper, never()).selectPermissionCodesByRole(anyString(), anyLong());
            verify(cacheUtils, never()).saveToCache(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("should query database and cache on cache miss")
        void cacheMiss_queriesDatabaseAndCaches() {
            String cacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            when(cacheUtils.takeListFormCache(cacheKey, String.class)).thenReturn(null);
            Set<String> dbPermissions = Set.of(PERM_FILE_READ, PERM_FILE_WRITE);
            when(permissionMapper.selectPermissionCodesByRole(ROLE_USER, TENANT_ID)).thenReturn(dbPermissions);

            Set<String> result = permissionService.getPermissionCodes(ROLE_USER, TENANT_ID);

            assertEquals(dbPermissions, result);
            verify(permissionMapper).selectPermissionCodesByRole(ROLE_USER, TENANT_ID);
            verify(cacheUtils).saveToCache(eq(cacheKey), eq(dbPermissions), eq(Const.PERMISSION_CACHE_TTL));
        }

        @Test
        @DisplayName("should return empty set when database returns null")
        void databaseReturnsNull_returnsEmptySet() {
            String cacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            when(cacheUtils.takeListFormCache(cacheKey, String.class)).thenReturn(null);
            when(permissionMapper.selectPermissionCodesByRole(ROLE_USER, TENANT_ID)).thenReturn(null);

            Set<String> result = permissionService.getPermissionCodes(ROLE_USER, TENANT_ID);

            assertTrue(result.isEmpty());
            verify(cacheUtils).saveToCache(eq(cacheKey), eq(new HashSet<>()), eq(Const.PERMISSION_CACHE_TTL));
        }

        @Test
        @DisplayName("should return empty set when database returns empty")
        void databaseReturnsEmpty_returnsEmptySet() {
            String cacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            when(cacheUtils.takeListFormCache(cacheKey, String.class)).thenReturn(null);
            when(permissionMapper.selectPermissionCodesByRole(ROLE_USER, TENANT_ID)).thenReturn(new HashSet<>());

            Set<String> result = permissionService.getPermissionCodes(ROLE_USER, TENANT_ID);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getPermissionCodes(roles, tenantId)")
    class GetPermissionCodesMultipleRoles {

        @Test
        @DisplayName("should return empty set for null roles list")
        void nullRoles_returnsEmptySet() {
            Set<String> result = permissionService.getPermissionCodes((List<String>) null, TENANT_ID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(cacheUtils);
            verifyNoInteractions(permissionMapper);
        }

        @Test
        @DisplayName("should return empty set for empty roles list")
        void emptyRoles_returnsEmptySet() {
            Set<String> result = permissionService.getPermissionCodes(List.of(), TENANT_ID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(cacheUtils);
            verifyNoInteractions(permissionMapper);
        }

        @Test
        @DisplayName("should merge permissions from all cached roles")
        void allCached_mergesPermissions() {
            String userCacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            String adminCacheKey = buildExpectedCacheKey(ROLE_ADMIN, TENANT_ID);
            when(cacheUtils.takeListFormCache(userCacheKey, String.class))
                    .thenReturn(List.of(PERM_FILE_READ, PERM_FILE_WRITE));
            when(cacheUtils.takeListFormCache(adminCacheKey, String.class))
                    .thenReturn(List.of(PERM_ADMIN_ALL, PERM_FILE_DELETE));

            Set<String> result = permissionService.getPermissionCodes(List.of(ROLE_USER, ROLE_ADMIN), TENANT_ID);

            assertEquals(Set.of(PERM_FILE_READ, PERM_FILE_WRITE, PERM_ADMIN_ALL, PERM_FILE_DELETE), result);
            verify(permissionMapper, never()).selectPermissionCodesByRoles(any(), anyLong());
        }

        @Test
        @DisplayName("should query database only for uncached roles")
        void partiallyCached_queriesOnlyUncached() {
            String userCacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            String adminCacheKey = buildExpectedCacheKey(ROLE_ADMIN, TENANT_ID);
            when(cacheUtils.takeListFormCache(userCacheKey, String.class))
                    .thenReturn(List.of(PERM_FILE_READ));
            when(cacheUtils.takeListFormCache(adminCacheKey, String.class))
                    .thenReturn(null);
            when(permissionMapper.selectPermissionCodesByRoles(List.of(ROLE_ADMIN), TENANT_ID))
                    .thenReturn(Set.of(PERM_ADMIN_ALL));
            when(permissionMapper.selectPermissionCodesByRole(ROLE_ADMIN, TENANT_ID))
                    .thenReturn(Set.of(PERM_ADMIN_ALL));

            Set<String> result = permissionService.getPermissionCodes(List.of(ROLE_USER, ROLE_ADMIN), TENANT_ID);

            assertEquals(Set.of(PERM_FILE_READ, PERM_ADMIN_ALL), result);
            verify(permissionMapper).selectPermissionCodesByRoles(List.of(ROLE_ADMIN), TENANT_ID);
            verify(permissionMapper).selectPermissionCodesByRole(ROLE_ADMIN, TENANT_ID);
            verify(cacheUtils).saveToCache(eq(adminCacheKey), eq(Set.of(PERM_ADMIN_ALL)), eq(Const.PERMISSION_CACHE_TTL));
        }

        @Test
        @DisplayName("should query all roles when none cached")
        void noneCached_queriesAllRoles() {
            String userCacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            String adminCacheKey = buildExpectedCacheKey(ROLE_ADMIN, TENANT_ID);
            when(cacheUtils.takeListFormCache(userCacheKey, String.class)).thenReturn(null);
            when(cacheUtils.takeListFormCache(adminCacheKey, String.class)).thenReturn(null);
            
            List<String> roles = List.of(ROLE_USER, ROLE_ADMIN);
            when(permissionMapper.selectPermissionCodesByRoles(roles, TENANT_ID))
                    .thenReturn(Set.of(PERM_FILE_READ, PERM_ADMIN_ALL));
            when(permissionMapper.selectPermissionCodesByRole(ROLE_USER, TENANT_ID))
                    .thenReturn(Set.of(PERM_FILE_READ));
            when(permissionMapper.selectPermissionCodesByRole(ROLE_ADMIN, TENANT_ID))
                    .thenReturn(Set.of(PERM_ADMIN_ALL));

            Set<String> result = permissionService.getPermissionCodes(roles, TENANT_ID);

            assertEquals(Set.of(PERM_FILE_READ, PERM_ADMIN_ALL), result);
            verify(permissionMapper).selectPermissionCodesByRoles(roles, TENANT_ID);
            verify(cacheUtils).saveToCache(eq(userCacheKey), eq(Set.of(PERM_FILE_READ)), eq(Const.PERMISSION_CACHE_TTL));
            verify(cacheUtils).saveToCache(eq(adminCacheKey), eq(Set.of(PERM_ADMIN_ALL)), eq(Const.PERMISSION_CACHE_TTL));
        }

        @Test
        @DisplayName("should handle database returning null for batch query")
        void databaseReturnsNullForBatch_handlesGracefully() {
            String userCacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            when(cacheUtils.takeListFormCache(userCacheKey, String.class)).thenReturn(null);
            List<String> roles = List.of(ROLE_USER);
            when(permissionMapper.selectPermissionCodesByRoles(roles, TENANT_ID)).thenReturn(null);
            when(permissionMapper.selectPermissionCodesByRole(ROLE_USER, TENANT_ID)).thenReturn(null);

            Set<String> result = permissionService.getPermissionCodes(roles, TENANT_ID);

            assertTrue(result.isEmpty());
            verify(cacheUtils).saveToCache(eq(userCacheKey), eq(new HashSet<>()), eq(Const.PERMISSION_CACHE_TTL));
        }
    }

    @Nested
    @DisplayName("hasPermission(role, permissionCode, tenantId)")
    class HasPermissionWithRole {

        @Test
        @DisplayName("should return true when permission exists")
        void permissionExists_returnsTrue() {
            String cacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            when(cacheUtils.takeListFormCache(cacheKey, String.class))
                    .thenReturn(List.of(PERM_FILE_READ, PERM_FILE_WRITE));

            boolean result = permissionService.hasPermission(ROLE_USER, PERM_FILE_READ, TENANT_ID);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when permission does not exist")
        void permissionNotExists_returnsFalse() {
            String cacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            when(cacheUtils.takeListFormCache(cacheKey, String.class))
                    .thenReturn(List.of(PERM_FILE_READ, PERM_FILE_WRITE));

            boolean result = permissionService.hasPermission(ROLE_USER, PERM_FILE_DELETE, TENANT_ID);

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false when role has no permissions")
        void noPermissions_returnsFalse() {
            String cacheKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);
            when(cacheUtils.takeListFormCache(cacheKey, String.class)).thenReturn(null);
            when(permissionMapper.selectPermissionCodesByRole(ROLE_USER, TENANT_ID)).thenReturn(null);

            boolean result = permissionService.hasPermission(ROLE_USER, PERM_FILE_READ, TENANT_ID);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("getPermissionsByModule")
    class GetPermissionsByModule {

        @Test
        @DisplayName("should return permissions for module")
        void moduleExists_returnsPermissions() {
            SysPermission perm1 = createPermission(1L, "file:read", "file");
            SysPermission perm2 = createPermission(2L, "file:write", "file");
            when(permissionMapper.selectByModule("file", TENANT_ID)).thenReturn(List.of(perm1, perm2));

            List<SysPermission> result = permissionService.getPermissionsByModule("file", TENANT_ID);

            assertEquals(2, result.size());
            assertEquals("file:read", result.get(0).getCode());
            assertEquals("file:write", result.get(1).getCode());
        }

        @Test
        @DisplayName("should return empty list when module has no permissions")
        void moduleEmpty_returnsEmptyList() {
            when(permissionMapper.selectByModule("nonexistent", TENANT_ID)).thenReturn(List.of());

            List<SysPermission> result = permissionService.getPermissionsByModule("nonexistent", TENANT_ID);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("evictCache")
    class EvictCache {

        @Test
        @DisplayName("should delete cache for role")
        void evictCache_deletesRoleCache() {
            String expectedKey = buildExpectedCacheKey(ROLE_USER, TENANT_ID);

            permissionService.evictCache(ROLE_USER, TENANT_ID);

            verify(cacheUtils).deleteCache(expectedKey);
        }
    }

    @Nested
    @DisplayName("evictAllCache")
    class EvictAllCache {

        @Test
        @DisplayName("should delete all caches for tenant with pattern")
        void evictAllCache_deletesWithPattern() {
            String expectedPattern = TenantKeyUtils.tenantKey(Const.PERMISSION_CACHE_PREFIX, TENANT_ID) + "*";

            permissionService.evictAllCache(TENANT_ID);

            verify(cacheUtils).deleteCachePattern(expectedPattern);
        }
    }

    @Nested
    @DisplayName("tenant-scoped permission CRUD")
    class TenantScopedPermissionCrud {

        /**
         * 验证权限更新通过租户作用域 wrapper 执行，避免继承 CRUD 绕过租户隔离。
         */
        @Test
        @DisplayName("should update permission through tenant-scoped wrappers")
        void updatePermission_usesTenantScopedWrappers() {
            SysPermission permission = createPermission(10L, PERM_FILE_READ, "file");
            when(permissionMapper.selectOne(any())).thenReturn(permission);

            SysPermission result;
            try (MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                idUtilsMock.when(() -> IdUtils.fromExternalId("ext_10")).thenReturn(10L);
                result = permissionService.updatePermission(
                        "ext_10",
                        "File Read",
                        "Read files",
                        1,
                        TENANT_ID);
            }

            assertNotNull(result);
            assertEquals("File Read", result.getName());
            assertEquals("Read files", result.getDescription());
            verify(permissionMapper).selectOne(any());
            verify(permissionMapper, never()).selectById(anyLong());
            verify(permissionMapper).update(isNull(), any());
            verify(permissionMapper, never()).updateById(any(SysPermission.class));
            verify(cacheUtils).deleteCachePattern(TenantKeyUtils.tenantKey(Const.PERMISSION_CACHE_PREFIX, TENANT_ID) + "*");
        }

        /**
         * 验证全局权限定义更新会清理所有租户的角色权限缓存。
         */
        @Test
        @DisplayName("should evict all tenant caches when global permission changes")
        void updateGlobalPermission_evictsAllTenantCaches() {
            Long globalTenantId = 0L;
            SysPermission permission = createPermission(10L, PERM_FILE_READ, "file");
            permission.setTenantId(globalTenantId);
            when(permissionMapper.selectOne(any())).thenReturn(permission);

            try (MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                idUtilsMock.when(() -> IdUtils.fromExternalId("ext_10")).thenReturn(10L);
                permissionService.updatePermission(
                        "ext_10",
                        "File Read",
                        null,
                        null,
                        globalTenantId);
            }

            verify(permissionMapper).update(isNull(), any());
            verify(cacheUtils).deleteCachePattern("*" + Const.PERMISSION_CACHE_PREFIX + "*");
            verify(cacheUtils, never()).deleteCachePattern(TenantKeyUtils.tenantKey(Const.PERMISSION_CACHE_PREFIX, globalTenantId) + "*");
        }

        /**
         * 验证权限删除先做租户作用域查询，并使用带租户条件的 delete wrapper。
         */
        @Test
        @DisplayName("should delete permission through tenant-scoped wrappers")
        void deletePermission_usesTenantScopedWrappers() {
            SysPermission permission = createPermission(11L, PERM_FILE_DELETE, "file");
            when(permissionMapper.selectOne(any())).thenReturn(permission);

            try (MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                idUtilsMock.when(() -> IdUtils.fromExternalId("ext_11")).thenReturn(11L);
                permissionService.deletePermission("ext_11", TENANT_ID);
            }

            verify(permissionMapper).selectOne(any());
            verify(rolePermissionMapper).delete(any(Wrapper.class));
            verify(permissionMapper).delete(any(Wrapper.class));
            verify(permissionMapper, never()).deleteById(anyLong());
            verify(cacheUtils).deleteCachePattern(TenantKeyUtils.tenantKey(Const.PERMISSION_CACHE_PREFIX, TENANT_ID) + "*");
        }

        /**
         * 验证角色授权显式写入当前租户 ID。
         */
        @Test
        @DisplayName("should assign permission mapping with current tenant")
        void assignPermissionToRole_setsTenantId() {
            SysPermission permission = createPermission(12L, PERM_FILE_WRITE, "file");
            when(permissionMapper.selectByCode(PERM_FILE_WRITE, TENANT_ID)).thenReturn(permission);
            when(rolePermissionMapper.countByRoleAndPermission(ROLE_USER, PERM_FILE_WRITE, TENANT_ID)).thenReturn(0);

            permissionService.assignPermissionToRole(ROLE_USER, PERM_FILE_WRITE, TENANT_ID);

            ArgumentCaptor<SysRolePermission> captor = ArgumentCaptor.forClass(SysRolePermission.class);
            verify(rolePermissionMapper).insert(captor.capture());
            SysRolePermission rolePermission = captor.getValue();
            assertEquals(TENANT_ID, rolePermission.getTenantId());
            assertEquals(ROLE_USER, rolePermission.getRole());
            assertEquals(permission.getId(), rolePermission.getPermissionId());
        }

        /**
         * 验证全局角色授权只按角色清理所有租户缓存。
         */
        @Test
        @DisplayName("should evict role caches across tenants when assigning global role permission")
        void assignGlobalPermissionToRole_evictsRoleCachesAcrossTenants() {
            Long globalTenantId = 0L;
            SysPermission permission = createPermission(12L, PERM_FILE_WRITE, "file");
            permission.setTenantId(globalTenantId);
            when(permissionMapper.selectByCode(PERM_FILE_WRITE, globalTenantId)).thenReturn(permission);
            when(rolePermissionMapper.countByRoleAndPermission(ROLE_USER, PERM_FILE_WRITE, globalTenantId)).thenReturn(0);

            permissionService.assignPermissionToRole(ROLE_USER, PERM_FILE_WRITE, globalTenantId);

            verify(cacheUtils).deleteCachePattern("*" + Const.PERMISSION_CACHE_PREFIX + ROLE_USER);
            verify(cacheUtils, never()).deleteCache(buildExpectedCacheKey(ROLE_USER, globalTenantId));
        }

        /**
         * 验证撤销角色权限时删除条件包含当前租户，避免误删其他租户映射。
         */
        @Test
        @DisplayName("should revoke permission mapping through tenant-scoped wrapper")
        void revokePermissionFromRole_usesTenantScopedWrapper() {
            SysPermission permission = createPermission(13L, PERM_FILE_WRITE, "file");
            when(permissionMapper.selectByCode(PERM_FILE_WRITE, TENANT_ID)).thenReturn(permission);

            permissionService.revokePermissionFromRole(ROLE_USER, PERM_FILE_WRITE, TENANT_ID);

            ArgumentCaptor<Wrapper<SysRolePermission>> captor = ArgumentCaptor.captor();
            verify(rolePermissionMapper).delete(captor.capture());
            String sqlSegment = captor.getValue().getSqlSegment();
            assertTrue(sqlSegment.contains("role"));
            assertTrue(sqlSegment.contains("permission_id"));
            assertTrue(sqlSegment.contains("tenant_id"));
            verify(cacheUtils).deleteCache(buildExpectedCacheKey(ROLE_USER, TENANT_ID));
        }

        /**
         * 验证全局角色权限撤销会清理所有租户下该角色缓存。
         */
        @Test
        @DisplayName("should evict role caches across tenants when revoking global role permission")
        void revokeGlobalPermissionFromRole_evictsRoleCachesAcrossTenants() {
            Long globalTenantId = 0L;
            SysPermission permission = createPermission(13L, PERM_FILE_WRITE, "file");
            permission.setTenantId(globalTenantId);
            when(permissionMapper.selectByCode(PERM_FILE_WRITE, globalTenantId)).thenReturn(permission);

            permissionService.revokePermissionFromRole(ROLE_USER, PERM_FILE_WRITE, globalTenantId);

            verify(rolePermissionMapper).delete(any(Wrapper.class));
            verify(cacheUtils).deleteCachePattern("*" + Const.PERMISSION_CACHE_PREFIX + ROLE_USER);
            verify(cacheUtils, never()).deleteCache(buildExpectedCacheKey(ROLE_USER, globalTenantId));
        }
    }

    @Nested
    @DisplayName("Multi-tenant isolation")
    class MultiTenantIsolation {

        @Test
        @DisplayName("should use different cache keys for different tenants")
        void differentTenants_differentCacheKeys() {
            Long tenant1 = 1L;
            Long tenant2 = 2L;
            String cacheKey1 = buildExpectedCacheKey(ROLE_USER, tenant1);
            String cacheKey2 = buildExpectedCacheKey(ROLE_USER, tenant2);

            when(cacheUtils.takeListFormCache(cacheKey1, String.class))
                    .thenReturn(List.of(PERM_FILE_READ));
            when(cacheUtils.takeListFormCache(cacheKey2, String.class))
                    .thenReturn(List.of(PERM_FILE_READ, PERM_FILE_WRITE, PERM_FILE_DELETE));

            Set<String> result1 = permissionService.getPermissionCodes(ROLE_USER, tenant1);
            Set<String> result2 = permissionService.getPermissionCodes(ROLE_USER, tenant2);

            assertEquals(1, result1.size());
            assertEquals(3, result2.size());
            assertNotEquals(result1, result2);
        }

        @Test
        @DisplayName("should evict cache only for specified tenant")
        void evictCache_isolatedByTenant() {
            Long tenant1 = 1L;
            Long tenant2 = 2L;
            String expectedKey1 = buildExpectedCacheKey(ROLE_USER, tenant1);

            permissionService.evictCache(ROLE_USER, tenant1);

            verify(cacheUtils).deleteCache(expectedKey1);
            verify(cacheUtils, never()).deleteCache(buildExpectedCacheKey(ROLE_USER, tenant2));
        }
    }

    private SysPermission createPermission(Long id, String code, String module) {
        SysPermission permission = new SysPermission();
        permission.setId(id);
        permission.setCode(code);
        permission.setModule(module);
        permission.setName(code);
        permission.setStatus(1);
        permission.setTenantId(TENANT_ID);
        return permission;
    }
}
