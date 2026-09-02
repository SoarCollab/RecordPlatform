package cn.flying.service;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.SysPermission;
import cn.flying.test.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the explicit global-or-current permission boundary against real MySQL and MyBatis interceptors.
 */
@Transactional
@Testcontainers(disabledWithoutDocker = false)
@DisplayName("Permission tenant isolation integration tests")
class PermissionTenantIsolationIT extends BaseIntegrationTest {

    private static final long CURRENT_TENANT = 918001L;
    private static final long OTHER_TENANT = 918002L;
    private static final long GLOBAL_PERMISSION_ID = 91800101L;
    private static final long CURRENT_PERMISSION_ID = 91800102L;
    private static final long DISABLED_PERMISSION_ID = 91800103L;
    private static final long OTHER_PERMISSION_ID = 91800104L;
    private static final String CODE_PREFIX = "f018:";
    private static final String VISIBLE_MODULE = "f018-visible";
    private static final String OTHER_MODULE = "f018-secret";

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Proves that tree, page, and module reads include only global and current-tenant definitions.
     */
    @Test
    @DisplayName("should expose global and current permissions while excluding another tenant")
    void shouldExposeOnlyGlobalAndCurrentPermissionDefinitions() {
        insertPermission(GLOBAL_PERMISSION_ID, 0L, CODE_PREFIX + "global", VISIBLE_MODULE, 1);
        insertPermission(CURRENT_PERMISSION_ID, CURRENT_TENANT, CODE_PREFIX + "current", VISIBLE_MODULE, 1);
        insertPermission(DISABLED_PERMISSION_ID, CURRENT_TENANT, CODE_PREFIX + "disabled", VISIBLE_MODULE, 0);
        insertPermission(OTHER_PERMISSION_ID, OTHER_TENANT, CODE_PREFIX + "other", OTHER_MODULE, 1);

        List<SysPermission> tree = TenantContext.callWithTenantIsolation(
                        CURRENT_TENANT,
                        () -> permissionService.getPermissionTree(CURRENT_TENANT))
                .stream()
                .filter(permission -> permission.getCode().startsWith(CODE_PREFIX))
                .toList();
        IPage<SysPermission> firstPage = TenantContext.callWithTenantIsolation(
                CURRENT_TENANT,
                () -> permissionService.listPermissions(
                        CURRENT_TENANT,
                        VISIBLE_MODULE,
                        new Page<>(1, 2)));
        IPage<SysPermission> secondPage = TenantContext.callWithTenantIsolation(
                CURRENT_TENANT,
                () -> permissionService.listPermissions(
                        CURRENT_TENANT,
                        VISIBLE_MODULE,
                        new Page<>(2, 2)));
        List<String> modules = TenantContext.callWithTenantIsolation(
                CURRENT_TENANT,
                () -> permissionService.listModules(CURRENT_TENANT));

        assertThat(tree)
                .extracting(SysPermission::getCode)
                .containsExactly(CODE_PREFIX + "current", CODE_PREFIX + "global");
        assertThat(firstPage.getTotal()).isEqualTo(3);
        assertThat(firstPage.getPages()).isEqualTo(2);
        assertThat(firstPage.getRecords())
                .extracting(SysPermission::getCode)
                .containsExactly(CODE_PREFIX + "current", CODE_PREFIX + "disabled");
        assertThat(firstPage.getRecords())
                .extracting(SysPermission::getTenantId)
                .containsOnly(0L, CURRENT_TENANT);
        assertThat(secondPage.getRecords())
                .extracting(SysPermission::getCode)
                .containsExactly(CODE_PREFIX + "global");
        assertThat(secondPage.getRecords())
                .extracting(SysPermission::getTenantId)
                .containsOnly(0L);
        assertThat(modules).contains(VISIBLE_MODULE).doesNotContain(OTHER_MODULE);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    /**
     * Proves that grant and revoke operations mutate only current-tenant mappings.
     */
    @Test
    @DisplayName("should keep role permission writes tenant protected")
    void shouldKeepRolePermissionWritesTenantProtected() {
        insertPermission(GLOBAL_PERMISSION_ID, 0L, CODE_PREFIX + "global", VISIBLE_MODULE, 1);
        insertPermission(OTHER_PERMISSION_ID, OTHER_TENANT, CODE_PREFIX + "other", OTHER_MODULE, 1);
        insertRolePermission(91800201L, OTHER_TENANT, "f018role", GLOBAL_PERMISSION_ID);

        TenantContext.runWithTenantIsolation(CURRENT_TENANT, () ->
                permissionService.assignPermissionToRole("f018role", CODE_PREFIX + "global", CURRENT_TENANT));

        assertThat(countRolePermission(CURRENT_TENANT, "f018role", GLOBAL_PERMISSION_ID)).isOne();
        assertThat(countRolePermission(OTHER_TENANT, "f018role", GLOBAL_PERMISSION_ID)).isOne();

        TenantContext.runWithTenantIsolation(CURRENT_TENANT, () ->
                permissionService.revokePermissionFromRole("f018role", CODE_PREFIX + "global", CURRENT_TENANT));

        assertThat(countRolePermission(CURRENT_TENANT, "f018role", GLOBAL_PERMISSION_ID)).isZero();
        assertThat(countRolePermission(OTHER_TENANT, "f018role", GLOBAL_PERMISSION_ID)).isOne();
        assertThat(permissionExists(GLOBAL_PERMISSION_ID)).isTrue();

        assertThatThrownBy(() -> TenantContext.runWithTenantIsolation(CURRENT_TENANT, () ->
                permissionService.assignPermissionToRole("f018role", CODE_PREFIX + "other", CURRENT_TENANT)))
                .isInstanceOf(GeneralException.class);
        assertThat(countRolePermission(CURRENT_TENANT, "f018role", OTHER_PERMISSION_ID)).isZero();
        assertThat(countRolePermission(OTHER_TENANT, "f018role", GLOBAL_PERMISSION_ID)).isOne();
    }

    /**
     * Insert a permission fixture without applying the application tenant interceptor.
     */
    private void insertPermission(long id, long tenantId, String code, String module, int status) {
        jdbcTemplate.update(
                """
                INSERT INTO sys_permission
                    (id, tenant_id, code, name, module, action, description, status, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, 'read', 'F018 integration fixture', ?, NOW(), NOW())
                """,
                id,
                tenantId,
                code,
                code,
                module,
                status);
    }

    /**
     * Insert a role-permission fixture without applying the application tenant interceptor.
     */
    private void insertRolePermission(long id, long tenantId, String role, long permissionId) {
        jdbcTemplate.update(
                """
                INSERT INTO sys_role_permission (id, tenant_id, role, permission_id, create_time)
                VALUES (?, ?, ?, ?, NOW())
                """,
                id,
                tenantId,
                role,
                permissionId);
    }

    /**
     * Count an exact tenant-scoped role mapping directly in the test database.
     */
    private int countRolePermission(long tenantId, String role, long permissionId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sys_role_permission
                WHERE tenant_id = ? AND role = ? AND permission_id = ?
                """,
                Integer.class,
                tenantId,
                role,
                permissionId);
        return count == null ? 0 : count;
    }

    /**
     * Check that a permission definition remains present after mapping changes.
     */
    private boolean permissionExists(long permissionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_permission WHERE id = ?",
                Integer.class,
                permissionId);
        return count != null && count == 1;
    }
}
