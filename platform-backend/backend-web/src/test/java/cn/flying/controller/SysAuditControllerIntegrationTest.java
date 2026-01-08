package cn.flying.controller;

import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.entity.SysRolePermission;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import cn.flying.dao.vo.audit.AuditConfigVO;
import cn.flying.dao.vo.audit.AuditLogQueryVO;
import cn.flying.test.support.BaseControllerIntegrationTest;
import cn.flying.test.support.JwtTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SysAuditController 集成测试
 *
 * 测试系统审计相关接口（需要admin或monitor权限）
 */
@Transactional
@DisplayName("SysAuditController Integration Tests")
@TestPropertySource(properties = "test.context=SysAuditControllerIntegrationTest")
class SysAuditControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/system/audit";

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private SysOperationLogMapper operationLogMapper;

    @Autowired
    private SysPermissionMapper permissionMapper;

    @Autowired
    private SysRolePermissionMapper rolePermissionMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account adminAccount;
    private Account monitorAccount;

    @BeforeEach
    void setUp() {
        // Set up admin user
        setTestAdmin(100L, 1L);
        adminAccount = createAdminAccount(testUserId, testTenantId, "auditadmin");
        monitorAccount = createMonitorAccount(200L, testTenantId, "auditmonitor");

        // Ensure system:admin permission exists
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

    private Account createMonitorAccount(Long userId, Long tenantId, String username) {
        Account account = new Account();
        account.setId(userId);
        account.setUsername(username + "_" + userId);
        account.setEmail(username + "_" + userId + "@test.com");
        account.setPassword(passwordEncoder.encode("password123"));
        account.setRole("monitor");
        account.setAvatar("https://example.com/avatar.png");
        account.setNickname("Monitor " + username);
        account.setTenantId(tenantId);
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        account.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> accountMapper.insert(account));
        return account;
    }

    private void setupSystemAdminPermission() {
        SysPermission existing = permissionMapper.selectByCode("system:admin", testTenantId);
        if (existing == null) {
            SysPermission permission = new SysPermission();
            permission.setTenantId(0L);
            permission.setCode("system:admin");
            permission.setName("系统管理");
            permission.setModule("system");
            permission.setAction("admin");
            permission.setDescription("系统管理权限");
            permission.setStatus(1);
            permission.setCreateTime(new Date());
            permission.setUpdateTime(new Date());
            permissionMapper.insert(permission);

            SysRolePermission mapping = new SysRolePermission();
            mapping.setRole("admin");
            mapping.setPermissionId(permission.getId());
            mapping.setTenantId(testTenantId);
            mapping.setCreateTime(new Date());
            rolePermissionMapper.insert(mapping);
        }
    }

    private SysOperationLog createTestLog(Long userId, String username, String module,
                                          String operationType, Integer status) {
        SysOperationLog log = new SysOperationLog();
        log.setUserId(String.valueOf(userId));
        log.setUsername(username);
        log.setModule(module);
        log.setOperationType(operationType);
        log.setDescription("Test operation");
        log.setMethod("GET");
        log.setRequestUrl("/api/test");
        log.setRequestIp("127.0.0.1");
        log.setStatus(status);
        log.setExecutionTime(100L);
        log.setOperationTime(LocalDateTime.now());
        log.setTenantId(testTenantId);
        TenantContext.runWithTenant(testTenantId, () -> operationLogMapper.insert(log));
        return log;
    }

    @Nested
    @DisplayName("GET /overview - Get Audit Overview")
    class GetAuditOverviewTests {

        @Test
        @DisplayName("should return audit overview for admin")
        void shouldReturnAuditOverviewForAdmin() throws Exception {
            performGet(BASE_URL + "/overview")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isMap());
        }

        @Test
        @DisplayName("should return audit overview for monitor")
        void shouldReturnAuditOverviewForMonitor() throws Exception {
            String monitorToken = JwtTestSupport.generateMonitorToken(monitorAccount.getId(), testTenantId);

            mockMvc.perform(get(BASE_URL + "/overview")
                            .header("Authorization", "Bearer " + monitorToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should return 403 for regular user")
        void shouldReturn403ForRegularUser() throws Exception {
            String userToken = JwtTestSupport.generateToken(300L, "regularuser", "user", testTenantId);

            mockMvc.perform(get(BASE_URL + "/overview")
                            .header("Authorization", "Bearer " + userToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET / - Get Audit Logs")
    class GetAuditLogsTests {

        @Test
        @DisplayName("should return paginated audit logs")
        void shouldReturnPaginatedAuditLogs() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);
            createTestLog(testUserId, "testuser", "file", "上传", 0);

            performGet(BASE_URL + "?current=1&size=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("should filter by username")
        void shouldFilterByUsername() throws Exception {
            createTestLog(testUserId, "targetuser", "file", "查询", 0);
            createTestLog(200L, "otheruser", "file", "查询", 0);

            performGet(BASE_URL + "?current=1&size=20&username=targetuser")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should filter by module")
        void shouldFilterByModule() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);
            createTestLog(testUserId, "testuser", "ticket", "查询", 0);

            performGet(BASE_URL + "?current=1&size=20&module=file")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should filter by status")
        void shouldFilterByStatus() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);
            createTestLog(testUserId, "testuser", "file", "上传", 1);

            performGet(BASE_URL + "?current=1&size=20&status=0")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should filter by action")
        void shouldFilterByAction() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);
            createTestLog(testUserId, "testuser", "file", "上传", 0);

            performGet(BASE_URL + "?current=1&size=20&action=查询")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should handle time range filter")
        void shouldHandleTimeRangeFilter() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);

            performGet(BASE_URL + "?current=1&size=20&startTime=2020-01-01 00:00:00&endTime=2030-12-31 23:59:59")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("GET /logs/{id} - Get Log Detail")
    class GetLogDetailTests {

        @Test
        @DisplayName("should return log detail")
        void shouldReturnLogDetail() throws Exception {
            SysOperationLog log = createTestLog(testUserId, "testuser", "file", "查询", 0);

            performGet(BASE_URL + "/logs/" + log.getId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should return null for non-existent log")
        void shouldReturnNullForNonExistentLog() throws Exception {
            performGet(BASE_URL + "/logs/999999")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /export - Export Audit Logs")
    class ExportAuditLogsTests {

        @Test
        @DisplayName("should export audit logs as Excel")
        void shouldExportAuditLogsAsExcel() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);

            MvcResult result = mockMvc.perform(get(BASE_URL + "/export")
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/vnd.ms-excel"))
                    .andReturn();

            assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
        }

        @Test
        @DisplayName("should export with filters")
        void shouldExportWithFilters() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);

            mockMvc.perform(get(BASE_URL + "/export?module=file&status=0")
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /high-frequency - Get High Frequency Operations")
    class GetHighFrequencyOperationsTests {

        @Test
        @DisplayName("should return high frequency operations")
        void shouldReturnHighFrequencyOperations() throws Exception {
            for (int i = 0; i < 10; i++) {
                createTestLog(testUserId, "testuser", "file", "查询", 0);
            }

            performGet(BASE_URL + "/high-frequency")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("POST /sensitive/page - Get Sensitive Operations")
    class GetSensitiveOperationsTests {

        @Test
        @DisplayName("should return sensitive operations")
        void shouldReturnSensitiveOperations() throws Exception {
            AuditLogQueryVO queryVO = new AuditLogQueryVO();
            queryVO.setPageNum(1);
            queryVO.setPageSize(20);

            performPost(BASE_URL + "/sensitive/page", queryVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }
    }

    @Nested
    @DisplayName("GET /error-stats - Get Error Operation Stats")
    class GetErrorOperationStatsTests {

        @Test
        @DisplayName("should return error operation stats")
        void shouldReturnErrorOperationStats() throws Exception {
            createTestLog(testUserId, "testuser", "file", "上传", 1);

            performGet(BASE_URL + "/error-stats")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("GET /time-distribution - Get User Time Distribution")
    class GetUserTimeDistributionTests {

        @Test
        @DisplayName("should return user time distribution")
        void shouldReturnUserTimeDistribution() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);

            performGet(BASE_URL + "/time-distribution")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("GET /configs - Get Audit Configs")
    class GetAuditConfigsTests {

        @Test
        @DisplayName("should return audit configs")
        void shouldReturnAuditConfigs() throws Exception {
            performGet(BASE_URL + "/configs")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("PUT /configs - Update Audit Config")
    class UpdateAuditConfigTests {

        @Test
        @DisplayName("should update audit config")
        void shouldUpdateAuditConfig() throws Exception {
            AuditConfigVO configVO = new AuditConfigVO();
            configVO.setConfigKey("audit.retention.days");
            configVO.setConfigValue("90");
            configVO.setDescription("审计日志保留天数");

            performPut(BASE_URL + "/configs", configVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("GET /check-anomalies - Check Anomalies")
    class CheckAnomaliesTests {

        @Test
        @DisplayName("should check anomalies")
        void shouldCheckAnomalies() throws Exception {
            performGet(BASE_URL + "/check-anomalies")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isMap());
        }
    }

    @Nested
    @DisplayName("POST /backup-logs - Backup Logs")
    class BackupLogsTests {

        @Test
        @DisplayName("should backup logs with default parameters")
        void shouldBackupLogsWithDefaultParameters() throws Exception {
            performPost(BASE_URL + "/backup-logs?days=180&deleteAfterBackup=false", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should backup logs with custom days")
        void shouldBackupLogsWithCustomDays() throws Exception {
            performPost(BASE_URL + "/backup-logs?days=30&deleteAfterBackup=false", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("Authentication Tests")
    class AuthenticationTests {

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(BASE_URL + "/overview")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 403 for regular user")
        void shouldReturn403ForRegularUser() throws Exception {
            String userToken = JwtTestSupport.generateToken(300L, "regularuser", "user", testTenantId);

            mockMvc.perform(get(BASE_URL + "/overview")
                            .header("Authorization", "Bearer " + userToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isForbidden());
        }
    }
}
