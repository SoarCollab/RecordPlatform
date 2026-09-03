package cn.flying.controller;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private static final long MAPPER_TEST_TENANT = 929101L;
    private static final long MAPPER_OTHER_TENANT = 929102L;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        return createTestLog(
                testTenantId,
                userId,
                username,
                module,
                operationType,
                status,
                LocalDateTime.now()
        );
    }

    /**
     * Creates one operation-log fixture for the specified tenant and timestamp.
     */
    private SysOperationLog createTestLog(Long tenantId, Long userId, String username, String module,
                                          String operationType, Integer status, LocalDateTime operationTime) {
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
        log.setOperationTime(operationTime);
        log.setTenantId(tenantId);
        TenantContext.runWithTenantIsolation(tenantId, () -> operationLogMapper.insert(log));
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
    @DisplayName("GET /logs - Get Audit Logs")
    class GetAuditLogsTests {

        @Test
        @DisplayName("should return paginated audit logs")
        void shouldReturnPaginatedAuditLogs() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);
            createTestLog(testUserId, "testuser", "file", "上传", 0);

            performGet(BASE_URL + "/logs?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("should filter by username")
        void shouldFilterByUsername() throws Exception {
            createTestLog(testUserId, "targetuser", "file", "查询", 0);
            createTestLog(200L, "otheruser", "file", "查询", 0);

            performGet(BASE_URL + "/logs?pageNum=1&pageSize=20&username=targetuser")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should filter by module")
        void shouldFilterByModule() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);
            createTestLog(testUserId, "testuser", "ticket", "查询", 0);

            performGet(BASE_URL + "/logs?pageNum=1&pageSize=20&module=file")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should filter by status")
        void shouldFilterByStatus() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);
            createTestLog(testUserId, "testuser", "file", "上传", 1);

            performGet(BASE_URL + "/logs?pageNum=1&pageSize=20&status=0")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should filter by action")
        void shouldFilterByAction() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);
            createTestLog(testUserId, "testuser", "file", "上传", 0);

            performGet(BASE_URL + "/logs?pageNum=1&pageSize=20&operationType=查询")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should handle time range filter")
        void shouldHandleTimeRangeFilter() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);

            performGet(BASE_URL + "/logs?pageNum=1&pageSize=20&startTime=2020-01-01 00:00:00&endTime=2030-12-31 23:59:59")
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

            performGet(BASE_URL + "/logs/" + IdUtils.toExternalId(log.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should return null for non-existent log")
        void shouldReturnNullForNonExistentLog() throws Exception {
            performGet(BASE_URL + "/logs/" + IdUtils.toExternalId(999999L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }
    
    @Test
    @DisplayName("should format date fields correctly")
    void shouldFormatDateFieldsCorrectly() throws Exception {
        SysOperationLog log = createTestLog(testUserId, "testuser", "file", "查询", 0);

        mockMvc.perform(get(BASE_URL + "/logs/" + IdUtils.toExternalId(log.getId()))
                        .header("Authorization", "Bearer " + testToken)
                        .header(HEADER_TENANT_ID, testTenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.operationTime").isString())
                .andExpect(jsonPath("$.data.operationTime").value(org.hamcrest.Matchers.matchesPattern("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$")));
    }

    @Nested
    @DisplayName("POST /logs/export - Export Audit Logs")
    class ExportAuditLogsTests {

        @Test
        @DisplayName("should export audit logs as Excel")
        void shouldExportAuditLogsAsExcel() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);

            MvcResult result = mockMvc.perform(post(BASE_URL + "/logs/export")
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .andReturn();

            assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
        }

        @Test
        @DisplayName("should export with filters")
        void shouldExportWithFilters() throws Exception {
            createTestLog(testUserId, "testuser", "file", "查询", 0);

            mockMvc.perform(post(BASE_URL + "/logs/export")
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"module\":\"file\",\"status\":0}"))
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
            String username = "sensitive-filter-user";
            createTestLog(testUserId, username, "file", "查询", 0);
            createTestLog(testUserId, username, "file", "删除", 0);
            createTestLog(2L, 200L, username, "file", "删除", 0, LocalDateTime.now());

            AuditLogQueryVO queryVO = new AuditLogQueryVO();
            queryVO.setPageNum(1);
            queryVO.setPageSize(20);
            queryVO.setUsername(username);
            queryVO.setStatus(0);
            queryVO.setRequestIp("127.0.0.1");

            performPost(BASE_URL + "/sensitive/page", queryVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.records.length()").value(1))
                    .andExpect(jsonPath("$.data.records[0].operationType").value("删除"))
                    .andExpect(jsonPath("$.data.records[0].executionTime").value(100));
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
    @DisplayName("POST /anomalies/check - Check Anomalies")
    class CheckAnomaliesTests {

        @Test
        @DisplayName("should check anomalies")
        void shouldCheckAnomalies() throws Exception {
            performPost(BASE_URL + "/anomalies/check", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isMap());
        }
    }

    @Nested
    @DisplayName("POST /logs/backups - Backup Logs")
    class BackupLogsTests {

        @Test
        @DisplayName("should bypass tenant rewriting while keeping backup and cleanup tenant scoped")
        void shouldKeepBackupAndCleanupTenantScoped() {
            LocalDateTime oldOperationTime = LocalDateTime.now().minusDays(2);
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(1);
            SysOperationLog currentTenantLog = createTestLog(
                    MAPPER_TEST_TENANT, testUserId, "backup-current", "audit", "备份", 0, oldOperationTime);
            SysOperationLog otherTenantLog = createTestLog(
                    MAPPER_OTHER_TENANT, 200L, "backup-other", "audit", "备份", 0, oldOperationTime);

            int backedUp = operationLogMapper.insertOperationLogBackup(MAPPER_TEST_TENANT, cutoffTime);
            int deleted = operationLogMapper.deleteOperationLogsBefore(MAPPER_TEST_TENANT, cutoffTime);

            assertThat(backedUp).isEqualTo(1);
            assertThat(deleted).isEqualTo(1);
            assertThat(countBackupRowsById(currentTenantLog.getId())).isEqualTo(1);
            assertThat(countBackupRowsById(otherTenantLog.getId())).isZero();
            assertThat(countOperationRowsById(currentTenantLog.getId())).isZero();
            assertThat(countOperationRowsById(otherTenantLog.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("should count only current-tenant downloads in the half-open window")
        void shouldCountOnlyCurrentTenantDownloads() {
            LocalDateTime startTime = LocalDateTime.now().minusMinutes(5);
            LocalDateTime endTime = LocalDateTime.now().plusMinutes(5);
            createTestLog(MAPPER_TEST_TENANT, testUserId, "download-query", "file", "查询", 0, LocalDateTime.now());
            createTestLog(MAPPER_TEST_TENANT, testUserId, "download-update", "file", "修改", 0, LocalDateTime.now());
            createTestLog(MAPPER_TEST_TENANT, testUserId, "download-current-1", "file", "下载", 0, LocalDateTime.now());
            createTestLog(MAPPER_TEST_TENANT, testUserId, "download-current-2", "file", "下载", 0, LocalDateTime.now());
            createTestLog(MAPPER_OTHER_TENANT, 200L, "download-other", "file", "下载", 0, LocalDateTime.now());

            Long downloads = TenantContext.callWithTenantIsolation(
                    MAPPER_TEST_TENANT,
                    () -> operationLogMapper.countOperationsByTypeBetween("下载", startTime, endTime)
            );

            assertThat(downloads).isEqualTo(2L);
        }

        @Test
        @DisplayName("should backup logs with default parameters")
        void shouldBackupLogsWithDefaultParameters() throws Exception {
            performPost(BASE_URL + "/logs/backups?days=180&deleteAfterBackup=false", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should backup logs with custom days")
        void shouldBackupLogsWithCustomDays() throws Exception {
            performPost(BASE_URL + "/logs/backups?days=30&deleteAfterBackup=false", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    /**
     * Counts one source-log fixture by ID without applying the application tenant interceptor.
     */
    private int countOperationRowsById(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log WHERE id = ?",
                Integer.class,
                id
        );
        return count == null ? 0 : count;
    }

    /**
     * Counts one backup-log fixture by ID without applying the application tenant interceptor.
     */
    private int countBackupRowsById(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log_backup WHERE id = ?",
                Integer.class,
                id
        );
        return count == null ? 0 : count;
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
