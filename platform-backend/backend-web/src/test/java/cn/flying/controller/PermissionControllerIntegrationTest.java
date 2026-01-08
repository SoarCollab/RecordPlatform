package cn.flying.controller;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.entity.SysRolePermission;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import cn.flying.test.support.BaseControllerIntegrationTest;
import cn.flying.test.support.JwtTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PermissionController 集成测试
 *
 * 测试权限管理相关接口（需要admin权限）
 */
@Transactional
@DisplayName("PermissionController Integration Tests")
@TestPropertySource(properties = "test.context=PermissionControllerIntegrationTest")
class PermissionControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/system/permissions";

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private SysPermissionMapper permissionMapper;

    @Autowired
    private SysRolePermissionMapper rolePermissionMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account adminAccount;

    @BeforeEach
    void setUp() {
        // Set up admin user
        setTestAdmin(100L, 1L);
        adminAccount = createAdminAccount(testUserId, testTenantId, "permadmin");

        // Ensure system:admin permission exists for admin role
        setupSystemAdminPermission();
    }

    private Account createAdminAccount(Long userId, Long tenantId, String username) {
        Account account = new Account();
        account.setId(userId);
        account.setUsername(username + "_" + userId);
        account.setEmail(username + "_" + userId + "@test.com");
        account.setPassword(passwordEncoder.encode("password123"));
        account.setRole("admin");
        account.setAvatar("https://example.com/avatar.png");
        account.setNickname("Admin " + username);
        account.setTenantId(tenantId);
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        account.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> accountMapper.insert(account));
        return account;
    }

    private void setupSystemAdminPermission() {
        // Check if system:admin permission exists
        SysPermission existing = permissionMapper.selectByCode("system:admin", testTenantId);
        if (existing == null) {
            // Create global system:admin permission
            SysPermission permission = new SysPermission();
            permission.setTenantId(0L); // global
            permission.setCode("system:admin");
            permission.setName("系统管理");
            permission.setModule("system");
            permission.setAction("admin");
            permission.setDescription("系统管理权限");
            permission.setStatus(1);
            permission.setCreateTime(new Date());
            permission.setUpdateTime(new Date());
            permissionMapper.insert(permission);

            // Map to admin role
            SysRolePermission mapping = new SysRolePermission();
            mapping.setRole("admin");
            mapping.setPermissionId(permission.getId());
            mapping.setTenantId(testTenantId);
            mapping.setCreateTime(new Date());
            rolePermissionMapper.insert(mapping);
        }
    }

    private SysPermission createTestPermission(Long tenantId, String code, String name, String module) {
        SysPermission permission = new SysPermission();
        permission.setTenantId(tenantId);
        permission.setCode(code);
        permission.setName(name);
        permission.setModule(module);
        permission.setAction("test");
        permission.setDescription("Test permission");
        permission.setStatus(1);
        permission.setCreateTime(new Date());
        permission.setUpdateTime(new Date());
        permissionMapper.insert(permission);
        return permission;
    }

    @Nested
    @DisplayName("GET / - Get Permission Tree")
    class GetPermissionTreeTests {

        @Test
        @DisplayName("should return permission tree for admin")
        void shouldReturnPermissionTreeForAdmin() throws Exception {
            createTestPermission(testTenantId, "file:read", "文件读取", "file");

            performGet(BASE_URL)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("should include both global and tenant permissions")
        void shouldIncludeBothGlobalAndTenantPermissions() throws Exception {
            createTestPermission(0L, "global:test", "全局测试", "global");
            createTestPermission(testTenantId, "tenant:test", "租户测试", "tenant");

            MvcResult result = performGet(BASE_URL)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(content).get("data");
            assertThat(data.isArray()).isTrue();
        }

        @Test
        @DisplayName("should return 403 for non-admin user")
        void shouldReturn403ForNonAdminUser() throws Exception {
            // Switch to regular user
            setTestUser(200L, testTenantId);

            performGet(BASE_URL)
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /list - Get Permission List")
    class GetPermissionListTests {

        @Test
        @DisplayName("should return paginated permission list")
        void shouldReturnPaginatedPermissionList() throws Exception {
            createTestPermission(testTenantId, "list:test1", "测试1", "test");
            createTestPermission(testTenantId, "list:test2", "测试2", "test");

            performGet(BASE_URL + "/list?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("should filter by module")
        void shouldFilterByModule() throws Exception {
            createTestPermission(testTenantId, "file:read", "文件读取", "file");
            createTestPermission(testTenantId, "ticket:read", "工单读取", "ticket");

            performGet(BASE_URL + "/list?module=file&pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("GET /modules - Get Module List")
    class GetModuleListTests {

        @Test
        @DisplayName("should return distinct module names")
        void shouldReturnDistinctModuleNames() throws Exception {
            createTestPermission(testTenantId, "mod1:action1", "模块1操作1", "module1");
            createTestPermission(testTenantId, "mod1:action2", "模块1操作2", "module1");
            createTestPermission(testTenantId, "mod2:action1", "模块2操作1", "module2");

            performGet(BASE_URL + "/modules")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("POST / - Create Permission")
    class CreatePermissionTests {

        @Test
        @DisplayName("should create permission successfully")
        void shouldCreatePermissionSuccessfully() throws Exception {
            Map<String, Object> vo = new HashMap<>();
            vo.put("code", "new:permission");
            vo.put("name", "新权限");
            vo.put("module", "new");
            vo.put("action", "create");
            vo.put("description", "测试新权限");

            performPost(BASE_URL, vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.code").value("new:permission"))
                    .andExpect(jsonPath("$.data.name").value("新权限"));
        }

        @Test
        @DisplayName("should fail for missing required fields")
        void shouldFailForMissingRequiredFields() throws Exception {
            Map<String, Object> vo = new HashMap<>();
            vo.put("code", "incomplete:perm");
            // Missing name, module, action

            performPost(BASE_URL, vo)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should fail for empty code")
        void shouldFailForEmptyCode() throws Exception {
            Map<String, Object> vo = new HashMap<>();
            vo.put("code", "");
            vo.put("name", "名称");
            vo.put("module", "模块");
            vo.put("action", "操作");

            performPost(BASE_URL, vo)
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PUT /{id} - Update Permission")
    class UpdatePermissionTests {

        @Test
        @DisplayName("should update permission name successfully")
        void shouldUpdatePermissionNameSuccessfully() throws Exception {
            SysPermission permission = createTestPermission(testTenantId, "update:test", "原名称", "update");

            Map<String, Object> vo = new HashMap<>();
            vo.put("name", "新名称");

            performPut(BASE_URL + "/" + IdUtils.toExternalId(permission.getId()), vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.name").value("新名称"));
        }

        @Test
        @DisplayName("should update permission status successfully")
        void shouldUpdatePermissionStatusSuccessfully() throws Exception {
            SysPermission permission = createTestPermission(testTenantId, "status:test", "状态测试", "status");

            Map<String, Object> vo = new HashMap<>();
            vo.put("status", 0);

            performPut(BASE_URL + "/" + IdUtils.toExternalId(permission.getId()), vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.status").value(0));
        }

        @Test
        @DisplayName("should return 404 for non-existent permission")
        void shouldReturn404ForNonExistentPermission() throws Exception {
            Map<String, Object> vo = new HashMap<>();
            vo.put("name", "新名称");

            performPut(BASE_URL + "/" + IdUtils.toExternalId(999999L), vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.RESULT_DATA_NONE.getCode()));
        }
    }

    @Nested
    @DisplayName("DELETE /{id} - Delete Permission")
    class DeletePermissionTests {

        @Test
        @DisplayName("should delete permission successfully")
        void shouldDeletePermissionSuccessfully() throws Exception {
            SysPermission permission = createTestPermission(testTenantId, "delete:test", "删除测试", "delete");

            performDelete(BASE_URL + "/" + IdUtils.toExternalId(permission.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("删除成功"));
        }

        @Test
        @DisplayName("should also delete associated role permissions")
        void shouldAlsoDeleteAssociatedRolePermissions() throws Exception {
            SysPermission permission = createTestPermission(testTenantId, "cascade:test", "级联删除测试", "cascade");

            // Create role permission mapping
            SysRolePermission mapping = new SysRolePermission();
            mapping.setRole("user");
            mapping.setPermissionId(permission.getId());
            mapping.setTenantId(testTenantId);
            mapping.setCreateTime(new Date());
            rolePermissionMapper.insert(mapping);

            performDelete(BASE_URL + "/" + IdUtils.toExternalId(permission.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("GET /roles/{role} - Get Role Permissions")
    class GetRolePermissionsTests {

        @Test
        @DisplayName("should return role permissions")
        void shouldReturnRolePermissions() throws Exception {
            performGet(BASE_URL + "/roles/admin")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("should return empty set for role with no permissions")
        void shouldReturnEmptySetForRoleWithNoPermissions() throws Exception {
            performGet(BASE_URL + "/roles/newrole")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("POST /roles/{role}/grant - Grant Permission")
    class GrantPermissionTests {

        @Test
        @DisplayName("should grant permission to role successfully")
        void shouldGrantPermissionToRoleSuccessfully() throws Exception {
            SysPermission permission = createTestPermission(testTenantId, "grant:test", "授权测试", "grant");

            Map<String, Object> vo = new HashMap<>();
            vo.put("permissionCode", "grant:test");

            performPost(BASE_URL + "/roles/user/grant", vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("授权成功"));
        }

        @Test
        @DisplayName("should fail for non-existent permission code")
        void shouldFailForNonExistentPermissionCode() throws Exception {
            Map<String, Object> vo = new HashMap<>();
            vo.put("permissionCode", "nonexistent:perm");

            performPost(BASE_URL + "/roles/user/grant", vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500)); // error
        }

        @Test
        @DisplayName("should fail for already granted permission")
        void shouldFailForAlreadyGrantedPermission() throws Exception {
            SysPermission permission = createTestPermission(testTenantId, "duplicate:grant", "重复授权", "duplicate");

            // Grant first time
            SysRolePermission mapping = new SysRolePermission();
            mapping.setRole("user");
            mapping.setPermissionId(permission.getId());
            mapping.setTenantId(testTenantId);
            mapping.setCreateTime(new Date());
            rolePermissionMapper.insert(mapping);

            // Try to grant again
            Map<String, Object> vo = new HashMap<>();
            vo.put("permissionCode", "duplicate:grant");

            performPost(BASE_URL + "/roles/user/grant", vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500));
        }
    }

    @Nested
    @DisplayName("DELETE /roles/{role}/revoke - Revoke Permission")
    class RevokePermissionTests {

        @Test
        @DisplayName("should revoke permission from role successfully")
        void shouldRevokePermissionFromRoleSuccessfully() throws Exception {
            SysPermission permission = createTestPermission(testTenantId, "revoke:test", "撤销测试", "revoke");

            // Grant permission first
            SysRolePermission mapping = new SysRolePermission();
            mapping.setRole("user");
            mapping.setPermissionId(permission.getId());
            mapping.setTenantId(testTenantId);
            mapping.setCreateTime(new Date());
            rolePermissionMapper.insert(mapping);

            performDelete(BASE_URL + "/roles/user/revoke?permissionCode=revoke:test")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("撤销成功"));
        }

        @Test
        @DisplayName("should fail for non-existent permission code")
        void shouldFailForNonExistentPermissionCode() throws Exception {
            performDelete(BASE_URL + "/roles/user/revoke?permissionCode=nonexistent:perm")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500));
        }
    }

    @Nested
    @DisplayName("Authentication Tests")
    class AuthenticationTests {

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(BASE_URL)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 403 for regular user")
        void shouldReturn403ForRegularUser() throws Exception {
            // Use regular user token
            String userToken = JwtTestSupport.generateToken(300L, "regularuser", "user", testTenantId);

            mockMvc.perform(get(BASE_URL)
                            .header("Authorization", "Bearer " + userToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Tenant Isolation Tests")
    class TenantIsolationTests {

        @Test
        @DisplayName("should not see permissions from other tenant")
        void shouldNotSeePermissionsFromOtherTenant() throws Exception {
            // Create permission in different tenant
            createTestPermission(999L, "other:tenant:perm", "其他租户权限", "other");

            MvcResult result = performGet(BASE_URL + "/list?pageNum=1&pageSize=100")
                    .andExpect(status().isOk())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            assertThat(content).doesNotContain("other:tenant:perm");
        }
    }
}
