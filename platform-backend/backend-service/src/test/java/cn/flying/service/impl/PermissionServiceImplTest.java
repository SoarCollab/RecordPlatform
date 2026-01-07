package cn.flying.service.impl;

import cn.flying.common.util.CacheUtils;
import cn.flying.common.util.Const;
import cn.flying.common.util.TenantKeyUtils;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
