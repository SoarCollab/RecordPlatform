package cn.flying.service.platform;

import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JwtUtils;
import cn.flying.controller.SseController;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.platform.PlatformOperationMapper;
import cn.flying.dao.mapper.platform.PlatformQuotaMapper;
import cn.flying.dao.mapper.platform.PlatformTenantMapper;
import cn.flying.dao.vo.admin.ChangeTenantMemberRoleRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantMemberReasonRequest;
import cn.flying.dao.vo.platform.ChangePlatformTenantStatusRequest;
import cn.flying.dao.vo.platform.CreatePlatformTenantRequest;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.UpdatePlatformConfigurationRequest;
import cn.flying.dao.vo.platform.UpdatePlatformQuotaRequest;
import cn.flying.dao.vo.platform.UpdatePlatformTenantRequest;
import cn.flying.service.QuotaService;
import cn.flying.service.admin.TenantInvitationMailSender;
import cn.flying.service.auth.AuthorizationStateService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.test.BaseIntegrationTest;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Exercises production platform transactions, MyBatis isolation, and authorization/SSE state in real MySQL and Redis. */
@Testcontainers(disabledWithoutDocker = false)
@Timeout(60)
class PlatformControlPlaneMySqlIT extends BaseIntegrationTest {

    private static final long TENANT_A = 92301L;
    private static final long TENANT_B = 92302L;
    private static final long ADMIN_A = 9230101L;
    private static final long MEMBER_A = 9230102L;
    private static final long MEMBER_B = 9230201L;
    private static final long ACTOR = 9230001L;
    private static final long CONFIG_ID = 9230801L;
    private static final long UNKNOWN_CONFIG_ID = 9230802L;
    private static final String PREFIX = "platform-it-";
    private static final String REASON = "Approved integration maintenance";
    private static final String HASH_SENTINEL = "synthetic-password-hash-never-project";
    private static final String TOKEN_SENTINEL = "synthetic-platform-token-never-project";
    private static final String OBJECT_SENTINEL = "private-object-path-never-project";
    private static final String CONFIG_KEY = "HIGH_FREQ_THRESHOLD";
    private static final String UNKNOWN_KEY = "PLATFORM_IT_SECRET";
    private static final String PERMISSION_MODULE = "platform-it-permissions";
    private static final String RESERVED_MODULE = "platform-it-reserved";
    private static final String PERMISSION_ROLE = "platform-it-role";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTenantCommandService commands;
    @Autowired private PlatformTenantQueryService tenants;
    @Autowired private PlatformUserService users;
    @Autowired private PlatformConfigurationService configurations;
    @Autowired private PlatformAuditService audits;
    @Autowired private PlatformOperationExecutor operations;
    @Autowired private PlatformOperationMapper operationMapper;
    @Autowired private PlatformTenantMapper tenantMapper;
    @Autowired private PlatformQuotaMapper quotaMapper;
    @Autowired private SysOperationLogMapper auditConfigMapper;
    @Autowired private SysPermissionMapper permissionMapper;
    @Autowired private QuotaService quotaService;
    @Autowired private AuthorizationStateService authorization;
    @Autowired private JwtUtils jwt;
    @Autowired private SseEmitterManager emitters;
    @Autowired private SseController sse;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper json;
    @MockitoBean private TenantInvitationMailSender invitationMail;

    private List<Map<String, Object>> originalConfigurations;

    /** Seeds only suite-owned tenants/accounts and snapshots the one global configuration modified by these tests. */
    @BeforeEach
    void setUpPlatformFixtures() {
        clearFaultTriggers();
        cleanOwnedRows();
        reset(invitationMail);
        originalConfigurations = jdbc.queryForList(
                "SELECT id, config_key, config_value, description, create_time, update_time, version FROM sys_audit_config WHERE config_key = ?",
                CONFIG_KEY);
        jdbc.update("DELETE FROM sys_audit_config WHERE config_key = ?", UNKNOWN_KEY);
        jdbc.update("""
                INSERT INTO sys_audit_config (id, config_key, config_value, description, version)
                VALUES (?, ?, '100', 'Synthetic platform fixture', 0)
                ON DUPLICATE KEY UPDATE config_value = '100', description = 'Synthetic platform fixture', version = 0
                """, CONFIG_ID, CONFIG_KEY);
        insertTenant(TENANT_A, "a");
        insertTenant(TENANT_B, "b");
        insertAccount(ACTOR, 0L, "operator", "platform_admin");
        insertAccount(ADMIN_A, TENANT_A, "admin-a", "admin");
        insertAccount(MEMBER_A, TENANT_A, "member-a", "user");
        insertAccount(MEMBER_B, TENANT_B, "member-b", "admin");
        authorizePlatform();
    }

    /** Restores configuration, removes fault injection and fixtures, and clears thread-bound request authority. */
    @AfterEach
    void tearDownPlatformFixtures() {
        try {
            clearFaultTriggers();
            cleanOwnedRows();
            jdbc.update("DELETE FROM sys_audit_config WHERE config_key = ?", UNKNOWN_KEY);
            if (originalConfigurations != null) {
                jdbc.update("DELETE FROM sys_audit_config WHERE config_key = ?", CONFIG_KEY);
                if (!originalConfigurations.isEmpty()) {
                    Map<String, Object> row = originalConfigurations.getFirst();
                    jdbc.update("""
                            INSERT INTO sys_audit_config (id, config_key, config_value, description, create_time, update_time, version)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """, row.get("id"), row.get("config_key"), row.get("config_value"), row.get("description"),
                            row.get("create_time"), row.get("update_time"), row.get("version"));
                }
            }
        } finally {
            clearIdentity();
        }
    }

    /** A new tenant receives only metadata/quota; its first administrator uses the existing digest-only invitation path. */
    @Test
    void createsTenantQuotaAndFirstAdministratorInvitationWithoutCredentials() throws Exception {
        var defaults = TenantContext.callWithTenantIsolation(TENANT_A,
                () -> quotaService.getCurrentQuotaStatus(TENANT_A, 0L));
        String tenantKey = key();
        var request = new CreatePlatformTenantRequest(PREFIX + "created", "Created Tenant", null, null, REASON);

        PlatformMutationVO created = commands.create(tenantKey, request);
        long tenantId = IdUtils.fromExternalId(created.resourceId());

        assertThat(created.version()).isZero();
        assertThat(commands.create(tenantKey, request)).isEqualTo(created);
        assertThat(count("SELECT COUNT(*) FROM account WHERE tenant_id = ?", tenantId)).isZero();
        assertThat(count("SELECT version FROM tenant WHERE id = ?", tenantId)).isZero();
        Map<String, Object> quota = jdbc.queryForMap("""
                SELECT max_storage_bytes, max_file_count, version FROM quota_policy
                WHERE tenant_id = ? AND scope_type = 'TENANT' AND scope_id = ?
                """, tenantId, tenantId);
        assertThat(((Number) quota.get("max_storage_bytes")).longValue()).isEqualTo(defaults.tenantMaxStorageBytes());
        assertThat(((Number) quota.get("max_file_count")).longValue()).isEqualTo(defaults.tenantMaxFileCount());
        assertThat(((Number) quota.get("version")).longValue()).isZero();

        String invitationKey = key();
        var invite = new CreateTenantInvitationRequest("platform-first-admin@example.test", "admin", 24, REASON);
        PlatformMutationVO invited = users.invite(tenantId, invitationKey, invite);
        assertThat(users.invite(tenantId, invitationKey, invite)).isEqualTo(invited);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(invitationMail, times(1)).sendInvitation(eq(invite.email()), url.capture());
        assertThat(url.getValue()).contains("#token=").doesNotContain("?token=");
        String rawToken = url.getValue().substring(url.getValue().indexOf("#token=") + 7);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> invitation = jdbc.queryForMap(
                "SELECT tenant_id, role, status, token_hash FROM account_invitation WHERE id = ?",
                IdUtils.fromExternalId(invited.resourceId()));
        assertThat(invitation.get("token_hash")).isEqualTo(digest);
        assertThat(invitation.get("role")).isEqualTo("admin");
        assertThat(invitation.get("status")).isEqualTo("PENDING");
        assertThat(count("SELECT COUNT(*) FROM account WHERE tenant_id = ?", tenantId)).isZero();
        assertThat(json.writeValueAsString(audits.list(1, 20, tenantId, null)))
                .doesNotContain(rawToken, digest, invite.email(), HASH_SENTINEL);
        assertSystemContext();
    }

    /** A concurrent replay observes PROCESSING promptly, never reruns the action, and later receives the identical result. */
    @Test
    void concurrentDuplicateReturnsInProgressThenReplaysOneCommittedMutation() throws Exception {
        String key = key();
        var request = new UpdatePlatformTenantRequest("Concurrent Single Mutation", 0L, REASON);
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        Supplier<PlatformChange> action = () -> {
            executions.incrementAndGet();
            return TenantContext.callWithTenantIsolation(TENANT_A, () -> {
                assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_A);
                assertThat(TenantContext.isIgnoreIsolation()).isFalse();
                actionEntered.countDown();
                await(releaseAction);
                assertThat(tenantMapper.updateName(TENANT_A, request.name(), 0L)).isOne();
                return new PlatformChange(TENANT_A, TENANT_A, null, 1L, "version=0", "version=1");
            });
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<PlatformMutationVO> original = pool.submit(asPlatform(() -> operations.execute(
                    PlatformOperationType.TENANT_UPDATE, TENANT_A, TENANT_A, null, key, request, REASON, action)));
            try {
                assertThat(actionEntered.await(10, TimeUnit.SECONDS)).isTrue();
                Future<ResultEnum> duplicate = pool.submit(asPlatform(() -> failureOf(() -> operations.execute(
                        PlatformOperationType.TENANT_UPDATE, TENANT_A, TENANT_A, null, key, request, REASON, action))));
                assertThat(duplicate.get(5, TimeUnit.SECONDS)).isEqualTo(ResultEnum.PLATFORM_OPERATION_IN_PROGRESS);
            } finally {
                releaseAction.countDown();
            }
            PlatformMutationVO result = original.get(10, TimeUnit.SECONDS);
            assertThat(operations.execute(PlatformOperationType.TENANT_UPDATE, TENANT_A, TENANT_A, null,
                    key, request, REASON, action)).isEqualTo(result);
        }
        assertThat(executions).hasValue(1);
        assertThat(count("SELECT version FROM tenant WHERE id = ?", TENANT_A)).isOne();
        assertThat(countOperations(key, "SUCCESS")).isOne();
        assertSystemContext();
    }

    /** The fingerprint uses actual payload and reason, including secrets that sanitize to the same audit text. */
    @Test
    void sameKeyWithDifferentActualPayloadOrReasonConflictsWithoutReexecution() {
        String key = key();
        var first = new UpdatePlatformTenantRequest("First Name", 0L, "password=first-secret");
        PlatformMutationVO result = commands.update(TENANT_A, key, first);

        expectFailure(ResultEnum.PLATFORM_IDEMPOTENCY_CONFLICT,
                () -> commands.update(TENANT_A, key, new UpdatePlatformTenantRequest("Second Name", 0L, first.reason())));
        expectFailure(ResultEnum.PLATFORM_IDEMPOTENCY_CONFLICT,
                () -> commands.update(TENANT_A, key, new UpdatePlatformTenantRequest("First Name", 0L, "password=second-secret")));
        expectFailure(ResultEnum.PLATFORM_IDEMPOTENCY_CONFLICT, () -> commands.update(TENANT_B, key, first));

        assertThat(commands.update(TENANT_A, key, first)).isEqualTo(result);
        assertThat(count("SELECT version FROM tenant WHERE id = ?", TENANT_A)).isOne();
        assertThat(count("SELECT version FROM tenant WHERE id = ?", TENANT_B)).isZero();
        assertThat(countOperations(key, "SUCCESS")).isOne();
    }

    /** Two writers of the same tenant version produce one success and one durable optimistic conflict. */
    @Test
    void concurrentTenantVersionsHaveExactlyOneWinner() throws Exception {
        List<ResultEnum> outcomes = race(
                () -> commands.update(TENANT_A, key(), new UpdatePlatformTenantRequest("Name A", 0L, REASON)),
                () -> commands.update(TENANT_A, key(), new UpdatePlatformTenantRequest("Name B", 0L, REASON)));

        assertOneVersionWinner(outcomes);
        assertThat(count("SELECT version FROM tenant WHERE id = ?", TENANT_A)).isOne();
        assertThat(count("SELECT version FROM tenant WHERE id = ?", TENANT_B)).isZero();
        assertTerminalCounts("TENANT_UPDATE");
    }

    /** Missing overrides use version zero and concurrent first writes create exactly one target-owned version-one row. */
    @Test
    void concurrentQuotaVersionsRemainIsolatedFromOtherTenantPolicies() throws Exception {
        insertQuota(9230299L, TENANT_B, 9000L, 90L, 7L);
        var initial = tenants.quota(TENANT_A);
        var defaults = TenantContext.callWithTenantIsolation(TENANT_A,
                () -> quotaService.getCurrentQuotaStatus(TENANT_A, 0L));
        assertThat(initial.version()).isZero();
        assertThat(initial.maxStorageBytes()).isEqualTo(defaults.tenantMaxStorageBytes());

        List<ResultEnum> outcomes = race(
                () -> commands.updateQuota(TENANT_A, key(), new UpdatePlatformQuotaRequest(1000L, 10L, 0L, REASON)),
                () -> commands.updateQuota(TENANT_A, key(), new UpdatePlatformQuotaRequest(2000L, 20L, 0L, REASON)));

        assertOneVersionWinner(outcomes);
        assertThat(count("SELECT COUNT(*) FROM quota_policy WHERE tenant_id = ? AND scope_type = 'TENANT'", TENANT_A)).isOne();
        assertThat(tenants.quota(TENANT_A).version()).isOne();
        assertThat(tenants.quota(TENANT_B).version()).isEqualTo(7L);
        assertThat(tenants.quota(TENANT_B).maxStorageBytes()).isEqualTo(9000L);
        assertThat(TenantContext.callWithTenantIsolation(TENANT_A, () -> quotaMapper.selectOverride(TENANT_B))).isNull();
        assertTerminalCounts("QUOTA_UPDATE");
        assertSystemContext();
    }

    /** The global configuration lock plus version predicate prevents two accepted writes of version zero. */
    @Test
    void concurrentConfigurationVersionsHaveExactlyOneWinner() throws Exception {
        List<ResultEnum> outcomes = race(
                () -> configurations.update(CONFIG_KEY, key(), new UpdatePlatformConfigurationRequest(111L, 0L, REASON)),
                () -> configurations.update(CONFIG_KEY, key(), new UpdatePlatformConfigurationRequest(222L, 0L, REASON)));

        assertOneVersionWinner(outcomes);
        assertThat(configurations.get(CONFIG_KEY).version()).isOne();
        assertThat(configurations.get(CONFIG_KEY).value()).isIn(111L, 222L);
        assertTerminalCounts("CONFIGURATION_UPDATE");
        assertSystemContext();
    }

    /** Success-audit failure rolls back SQL changes, releases authorization fences, and preserves conservative SSE closure. */
    @Test
    void auditPersistenceFailureRollsBackBusinessWritesAndReleasesFences() throws Exception {
        installSuccessAuditFailure();
        long quotaRows = count("SELECT COUNT(*) FROM quota_policy");
        String key = key();
        var request = new CreatePlatformTenantRequest(PREFIX + "rollback", "Rollback Tenant", 1024L, 3L, REASON);

        expectFailure(ResultEnum.SERVICE_UNAVAILABLE, () -> commands.create(key, request));

        assertThat(count("SELECT COUNT(*) FROM tenant WHERE code = ?", request.code())).isZero();
        assertThat(count("SELECT COUNT(*) FROM quota_policy")).isEqualTo(quotaRows);
        assertThat(countOperations(key, "FAILURE")).isOne();
        assertThat(json.writeValueAsString(operation(key))).doesNotContain(TOKEN_SENTINEL);

        assertThat(authorized(MEMBER_A, TENANT_A, "user", 0L)).isTrue();
        assertThat(authorized(MEMBER_B, TENANT_B, "admin", 0L)).isTrue();
        emitters.createConnection(TENANT_A, MEMBER_A, PREFIX + "rollback-connection");
        String lifecycleKey = key();
        expectFailure(ResultEnum.SERVICE_UNAVAILABLE, () -> commands.changeStatus(TENANT_A, lifecycleKey,
                new ChangePlatformTenantStatusRequest(0, 0L, REASON)));

        assertThat(count("SELECT status FROM tenant WHERE id = ?", TENANT_A)).isOne();
        assertThat(count("SELECT version FROM tenant WHERE id = ?", TENANT_A)).isZero();
        assertThat(countOperations(lifecycleKey, "FAILURE")).isOne();
        assertThat(authorized(MEMBER_A, TENANT_A, "user", 0L))
                .as("rollback completion removes the malformed fence before current state is reloaded").isTrue();
        assertThat(authorized(MEMBER_B, TENANT_B, "admin", 0L)).isTrue();
        assertThat(emitters.getUserConnectionCount(TENANT_A, MEMBER_A))
                .as("already closed live connections stay closed after SQL rollback").isZero();
        clearFaultTriggers();
        expectFailure(ResultEnum.SERVICE_UNAVAILABLE, () -> commands.create(key, request));
        assertThat(count("SELECT COUNT(*) FROM tenant WHERE code = ?", request.code())).isZero();
        assertSystemContext();
    }

    /** A failed claim insert does not reach the business mutation at all. */
    @Test
    void unavailableClaimStoragePreventsAnyBusinessMutation() {
        jdbc.execute("""
                CREATE TRIGGER platform_it_claim_failure BEFORE INSERT ON platform_operation_log
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Synthetic claim failure'
                """);
        String key = key();

        assertThatThrownBy(() -> commands.update(TENANT_A, key,
                new UpdatePlatformTenantRequest("Must Not Commit", 0L, REASON))).isInstanceOf(RuntimeException.class);

        assertThat(count("SELECT version FROM tenant WHERE id = ?", TENANT_A)).isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM tenant WHERE id = ?", String.class, TENANT_A))
                .isEqualTo(PREFIX + "a");
        assertThat(count("SELECT COUNT(*) FROM platform_operation_log WHERE actor_id = ?", ACTOR)).isZero();
        assertSystemContext();
    }

    /** Last-administrator failure is durably replayed even if later database state would allow a new command. */
    @Test
    void failedMemberInvariantRemainsTheOriginalIdempotentOutcome() {
        String key = key();
        var request = new ChangeTenantMemberRoleRequest("user", "password=" + TOKEN_SENTINEL);

        expectFailure(ResultEnum.LAST_TENANT_ADMIN_REQUIRED, () -> users.changeRole(TENANT_A, ADMIN_A, key, request));
        assertThat(count("SELECT auth_version FROM account WHERE id = ?", ADMIN_A)).isZero();
        insertAccount(9230103L, TENANT_A, "second-admin", "admin");
        expectFailure(ResultEnum.LAST_TENANT_ADMIN_REQUIRED, () -> users.changeRole(TENANT_A, ADMIN_A, key, request));

        assertThat(jdbc.queryForObject("SELECT role FROM account WHERE id = ?", String.class, ADMIN_A)).isEqualTo("admin");
        assertThat(countOperations(key, "FAILURE")).isOne();
        assertThat(operation(key).getErrorCode()).isEqualTo(ResultEnum.LAST_TENANT_ADMIN_REQUIRED.getCode());
        assertThat(operation(key).getReason()).doesNotContain(TOKEN_SENTINEL);
        assertSystemContext();
    }

    /** Member commands and reads retain the forced target even when another valid user's ID is supplied. */
    @Test
    void targetMemberOperationsRestoreSystemContextAndCannotCrossTenant() {
        expectFailure(ResultEnum.TENANT_MEMBER_NOT_FOUND, () -> users.changeRole(TENANT_A, MEMBER_B, key(),
                new ChangeTenantMemberRoleRequest("monitor", REASON)));
        assertSystemContext();

        PlatformMutationVO changed = users.changeRole(TENANT_A, MEMBER_A, key(),
                new ChangeTenantMemberRoleRequest("monitor", REASON));

        assertThat(changed.resourceId()).isEqualTo(IdUtils.toExternalUserId(MEMBER_A));
        assertThat(changed.version()).isNull();
        assertThat(users.members(TENANT_A, 1, 20, null, null, null).getRecords())
                .extracting(member -> member.id()).containsExactlyInAnyOrder(
                        IdUtils.toExternalUserId(ADMIN_A), IdUtils.toExternalUserId(MEMBER_A));
        assertThat(jdbc.queryForObject("SELECT role FROM account WHERE id = ?", String.class, MEMBER_B)).isEqualTo("admin");
        assertThat(count("SELECT auth_version FROM account WHERE id = ?", MEMBER_B)).isZero();
        assertSystemContext();
    }

    /** Direct service calls reject tenant identities and bypassed system context before creating any operation row. */
    @Test
    void serviceEntryGuardRejectsTenantIdentityAndIsolationBypass() {
        authorize("admin", 0L, false, true);
        expectFailure(ResultEnum.PERMISSION_UNAUTHORIZED, () -> tenants.list(1, 20, null, null));
        expectFailure(ResultEnum.PERMISSION_UNAUTHORIZED, () -> commands.update(TENANT_A, key(),
                new UpdatePlatformTenantRequest("Forbidden", 0L, REASON)));
        authorize("platform_admin", 0L, true, true);
        expectFailure(ResultEnum.PERMISSION_UNAUTHORIZED, () -> commands.update(TENANT_A, key(),
                new UpdatePlatformTenantRequest("Forbidden", 0L, REASON)));
        assertThat(TenantContext.isIgnoreIsolation()).isTrue();
        authorize("platform_admin", TENANT_B, false, true);
        expectFailure(ResultEnum.PERMISSION_UNAUTHORIZED, () -> audits.list(1, 20, null, null));
        assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_B);

        assertThat(count("SELECT COUNT(*) FROM platform_operation_log WHERE actor_id = ?", ACTOR)).isZero();
        assertThat(count("SELECT version FROM tenant WHERE id = ?", TENANT_A)).isZero();
    }

    /** Tenant disable invalidates real cached authorization and only its live SSE connections; restore reloads current state. */
    @Test
    void disablingAndRestoringTenantRefreshesRealAuthorizationAndSseState() {
        assertThat(authorized(MEMBER_A, TENANT_A, "user", 0L)).isTrue();
        assertThat(authorized(MEMBER_B, TENANT_B, "admin", 0L)).isTrue();
        String token = jwt.createSseToken(MEMBER_A, TENANT_A, "user", 0L);
        emitters.createConnection(TENANT_A, MEMBER_A, PREFIX + "a-connection");
        emitters.createConnection(TENANT_B, MEMBER_B, PREFIX + "b-connection");
        assertThat(emitters.getUserConnectionCount(TENANT_A, MEMBER_A)).isOne();

        commands.changeStatus(TENANT_A, key(), new ChangePlatformTenantStatusRequest(0, 0L, REASON));

        assertThat(authorized(MEMBER_A, TENANT_A, "user", 0L)).isFalse();
        assertThat(authorized(MEMBER_B, TENANT_B, "admin", 0L)).isTrue();
        assertThat(emitters.getUserConnectionCount(TENANT_A, MEMBER_A)).isZero();
        assertThat(emitters.getUserConnectionCount(TENANT_B, MEMBER_B)).isOne();
        MockHttpServletRequest handshake = new MockHttpServletRequest("GET", "/api/v1/sse/connect");
        var denied = sse.connect(token, TENANT_A, handshake);
        assertThat(denied.getStatusCode().value()).isEqualTo(401);
        assertThat(denied.getBody()).isNull();
        assertThat(handshake.getAttribute(Const.ATTR_USER_ID)).isNull();
        assertThat(emitters.getUserConnectionCount(TENANT_A, MEMBER_A)).isZero();
        assertThat(TenantContext.callWithTenantIsolation(TENANT_A, () -> jwt.validateAndConsumeSseToken(token))).isNull();

        commands.changeStatus(TENANT_A, key(), new ChangePlatformTenantStatusRequest(1, 1L, REASON));

        assertThat(authorized(MEMBER_A, TENANT_A, "user", 0L)).isTrue();
        assertThat(tenants.get(TENANT_A).version()).isEqualTo(2L);
        assertThat(tenants.get(TENANT_A).disabledReason()).isNull();
        assertSystemContext();
    }

    /** The platform wrapper retains the reviewed member auth-version, outstanding token, and live SSE revocation contract. */
    @Test
    void memberRoleChangeRevokesRealCachedAuthorizationAndSseTokens() {
        assertThat(authorized(MEMBER_A, TENANT_A, "user", 0L)).isTrue();
        String token = jwt.createSseToken(MEMBER_A, TENANT_A, "user", 0L);
        emitters.createConnection(TENANT_A, MEMBER_A, PREFIX + "role-connection");

        users.changeRole(TENANT_A, MEMBER_A, key(), new ChangeTenantMemberRoleRequest("monitor", REASON));

        assertThat(authorized(MEMBER_A, TENANT_A, "user", 0L)).isFalse();
        assertThat(authorized(MEMBER_A, TENANT_A, "monitor", 1L)).isTrue();
        assertThat(TenantContext.callWithTenantIsolation(TENANT_A, () -> jwt.validateAndConsumeSseToken(token))).isNull();
        assertThat(emitters.getUserConnectionCount(TENANT_A, MEMBER_A)).isZero();
        assertThat(count("SELECT auth_version FROM account WHERE id = ?", MEMBER_A)).isOne();
        assertSystemContext();
    }

    /** Platform history stays system-owned and redacted, while tenant/member audit tables receive no duplicate target evidence. */
    @Test
    void platformAuditIsIsolatedRedactedAndUnavailableToLegacyTenantZeroAdmin() throws Exception {
        MDC.put("traceId", "password=" + TOKEN_SENTINEL);
        String key = key();
        PlatformMutationVO mutation = users.revokeSessions(TENANT_A, MEMBER_A, key,
                new TenantMemberReasonRequest("password=" + TOKEN_SENTINEL + " token=" + HASH_SENTINEL));
        long operationId = IdUtils.fromExternalId(mutation.operationId());
        var audit = audits.get(operationId);

        assertThat(audit.actorId()).isEqualTo(IdUtils.toExternalUserId(ACTOR));
        assertThat(audit.resourceId()).isEqualTo(IdUtils.toExternalUserId(MEMBER_A));
        assertThat(audit.targetTenantId()).isEqualTo(IdUtils.toExternalId(TENANT_A));
        assertThat(audit.traceId()).isNull();
        assertThat(audit.status()).isEqualTo("SUCCESS");
        String projected = json.writeValueAsString(audit);
        assertThat(projected).doesNotContain(TOKEN_SENTINEL, HASH_SENTINEL, "requestHash", "idempotencyKey", "authVersion");
        assertThat(operation(key).getReason()).doesNotContain(TOKEN_SENTINEL, HASH_SENTINEL);
        assertThat(operation(key).getTenantId()).isZero();
        assertThat(TenantContext.callWithTenantIsolation(TENANT_A, () -> operationMapper.selectById(operationId))).isNull();
        assertThat(count("SELECT COUNT(*) FROM account_member_audit WHERE actor_id = ?", ACTOR)).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_operation_log WHERE user_id = ?", ACTOR)).isZero();

        authorize("admin", 0L, false, true);
        expectFailure(ResultEnum.PERMISSION_UNAUTHORIZED, () -> audits.get(operationId));
    }

    /** Corrupt entries stay redacted; recreated entries retain version/replay semantics and remain readable by tenant audit consumers. */
    @Test
    void safeConfigurationOmitsCorruptValuesDescriptionsAndUnknownKeys() throws Exception {
        jdbc.update("UPDATE sys_audit_config SET config_value = ?, description = ?, version = 7 WHERE config_key = ?",
                TOKEN_SENTINEL, HASH_SENTINEL, CONFIG_KEY);
        jdbc.update("INSERT INTO sys_audit_config (id, config_key, config_value, description) VALUES (?, ?, ?, ?)",
                UNKNOWN_CONFIG_ID, UNKNOWN_KEY, TOKEN_SENTINEL, HASH_SENTINEL);

        var unavailable = configurations.get(CONFIG_KEY);
        assertThat(unavailable.state()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.value()).isNull();
        assertThat(unavailable.version()).isEqualTo(7L);
        assertThat(json.writeValueAsString(configurations.list())).doesNotContain(TOKEN_SENTINEL, HASH_SENTINEL, UNKNOWN_KEY);
        assertThat(configurations.getSafeTenantAuditConfigs()).extracting(config -> config.getConfigKey())
                .doesNotContain(CONFIG_KEY, UNKNOWN_KEY);
        expectFailure(ResultEnum.PLATFORM_CONFIGURATION_UNSUPPORTED, () -> configurations.get(UNKNOWN_KEY));
        String key = key();

        configurations.update(CONFIG_KEY, key, new UpdatePlatformConfigurationRequest(120L, 7L, REASON));

        assertThat(configurations.get(CONFIG_KEY).value()).isEqualTo(120L);
        assertThat(configurations.get(CONFIG_KEY).version()).isEqualTo(8L);
        assertThat(operation(key).getBeforeSummary()).contains("unavailable").doesNotContain(TOKEN_SENTINEL);

        long previousId = count("SELECT id FROM sys_audit_config WHERE config_key = ?", CONFIG_KEY);
        assertThat(jdbc.update("DELETE FROM sys_audit_config WHERE config_key = ?", CONFIG_KEY)).isOne();
        var missing = configurations.get(CONFIG_KEY);
        assertThat(missing.state()).isEqualTo("UNAVAILABLE");
        assertThat(missing.value()).isNull();
        assertThat(missing.version()).isZero();
        String restoreKey = key();
        var restore = new UpdatePlatformConfigurationRequest(130L, 0L, REASON);

        PlatformMutationVO restored = configurations.update(CONFIG_KEY, restoreKey, restore);

        assertThat(restored.resourceId()).isEqualTo(CONFIG_KEY);
        assertThat(restored.version()).isOne();
        long restoredId = count("SELECT id FROM sys_audit_config WHERE config_key = ?", CONFIG_KEY);
        assertThat(restoredId).isGreaterThan(Integer.MAX_VALUE).isNotEqualTo(previousId);
        assertThat(count("SELECT COUNT(*) FROM sys_audit_config WHERE config_key = ?", CONFIG_KEY)).isOne();
        assertThat(count("SELECT version FROM sys_audit_config WHERE config_key = ?", CONFIG_KEY)).isOne();
        assertThat(configurations.get(CONFIG_KEY).value()).isEqualTo(130L);
        assertThat(configurations.get(CONFIG_KEY).state()).isEqualTo("AVAILABLE");
        var consumed = TenantContext.callWithTenantIsolation(TENANT_A,
                () -> auditConfigMapper.selectAuditConfigByKey(CONFIG_KEY));
        assertThat(consumed).isNotNull();
        assertThat(consumed.getConfigValue()).isEqualTo("130");
        assertThat(configurations.update(CONFIG_KEY, restoreKey, restore)).isEqualTo(restored);
        assertThat(count("SELECT id FROM sys_audit_config WHERE config_key = ?", CONFIG_KEY)).isEqualTo(restoredId);
        assertThat(countOperations(restoreKey, "SUCCESS")).isOne();
        assertThat(operation(restoreKey).getBeforeSummary()).contains("value=unavailable", "version=0");
        assertThat(operation(restoreKey).getAfterSummary()).contains("value=130", "version=1");
        assertSystemContext();
    }

    /** Cross-tenant pages expose only approved metadata with real pagination, filtering, and secret-bearing source rows. */
    @Test
    void metadataPagesExcludePlatformAccountsAndSecretSourceColumns() throws Exception {
        insertFile(9230151L, TENANT_A, MEMBER_A, 1, 123L, 0);
        var page = users.list(1, 1, PREFIX, null, null, null);
        assertThat(page.getTotal()).isEqualTo(3L);
        assertThat(page.getRecords()).hasSize(1);
        var all = users.list(1, 20, PREFIX, null, null, null);
        assertThat(all.getRecords()).extracting(user -> user.id()).doesNotContain(IdUtils.toExternalUserId(ACTOR));
        assertThat(all.getRecords()).allSatisfy(user -> assertThat(user.role()).isIn("user", "admin", "monitor"));
        String projected = json.writeValueAsString(all);
        assertThat(projected).doesNotContain(HASH_SENTINEL, OBJECT_SENTINEL, "email", "password", "authVersion", "fileParam");
        assertThat(users.list(1, 20, "' OR 1=1 --", null, null, null).getRecords()).isEmpty();
        assertThat(users.list(1, 20, PREFIX, "user", 1, TENANT_A).getRecords())
                .extracting(user -> user.id()).containsExactly(IdUtils.toExternalUserId(MEMBER_A));
        assertThat(tenants.list(1, 1, PREFIX, null).getRecords())
                .extracting(tenant -> tenant.id()).containsExactly(IdUtils.toExternalId(TENANT_A));
        assertThat(tenants.list(2, 1, PREFIX, null).getRecords())
                .extracting(tenant -> tenant.id()).containsExactly(IdUtils.toExternalId(TENANT_B));
        assertSystemContext();
    }

    /** Usage counts real eligible file states, ordinary audit, and completed attestations within the explicit tenant only. */
    @Test
    void usageMeasuresOnlyTargetEligibleFilesAndTenantOwnedEvidence() {
        insertFile(9230151L, TENANT_A, MEMBER_A, 0, 100L, 0);
        insertFile(9230152L, TENANT_A, MEMBER_A, 1, 200L, 0);
        insertFile(9230153L, TENANT_A, MEMBER_A, 2, 900L, 0);
        insertFile(9230154L, TENANT_A, MEMBER_A, 1, 900L, 1);
        insertFile(9230251L, TENANT_B, MEMBER_B, 1, 700L, 0);
        insertBusinessAudit(9230161L, TENANT_A, MEMBER_A);
        insertBusinessAudit(9230261L, TENANT_B, MEMBER_B);
        insertAttestation(9230171L, TENANT_A, "COMPLETED");
        insertAttestation(9230172L, TENANT_A, "PENDING");
        insertAttestation(9230271L, TENANT_B, "COMPLETED");
        commands.update(TENANT_A, key(), new UpdatePlatformTenantRequest("Usage Tenant", 0L, REASON));

        var usage = tenants.usage(TENANT_A);

        assertThat(usage.users()).isEqualTo(2L);
        assertThat(usage.files()).isEqualTo(2L);
        assertThat(usage.logicalStorageBytes()).isEqualTo(300L);
        assertThat(usage.auditRecords()).isOne();
        assertThat(usage.completedAttestations()).isOne();
        assertThat(usage.auditScope()).isEqualTo("TENANT_BUSINESS_OPERATION_LOG");
        assertThat(usage.attestationScope()).isEqualTo("TENANT_COMPLETED_ATTESTATION_BATCH");
        assertThat(usage.state()).isEqualTo("AVAILABLE");
        assertThat(tenants.usage(TENANT_B).logicalStorageBytes()).isEqualTo(700L);
        assertSystemContext();
    }

    /** Real SQL excludes reserved names, including case/whitespace variants, before pages, modules, and grants are returned. */
    @Test
    void tenantPermissionSqlExcludesReservedNamespaceAcrossOwnershipAndGrants() {
        insertPermission(9230901L, 0L, "platform-it:global", PERMISSION_MODULE);
        insertPermission(9230902L, TENANT_A, "platform-it:current", PERMISSION_MODULE);
        insertPermission(9230903L, TENANT_B, "platform-it:other", PERMISSION_MODULE);
        insertPermission(9230904L, 0L, "platform:tenant:read", RESERVED_MODULE);
        insertPermission(9230905L, TENANT_A, "  PlAtFoRm:tenant:write", RESERVED_MODULE);
        insertPermission(9230906L, TENANT_B, "\tPLATFORM:user:read", RESERVED_MODULE);
        for (long id = 9230901L; id <= 9230906L; id++) {
            jdbc.update("INSERT INTO sys_role_permission (id, tenant_id, role, permission_id) VALUES (?, ?, ?, ?)",
                    id + 100, id == 9230901L || id == 9230904L ? 0L : TENANT_A, PERMISSION_ROLE, id);
        }

        TenantContext.runWithTenantIsolation(TENANT_A, () -> {
            assertThat(permissionMapper.selectVisibleActivePermissions(TENANT_A))
                    .extracting(SysPermission::getCode).contains("platform-it:global", "platform-it:current")
                    .doesNotContain("platform-it:other", "platform:tenant:read", "  PlAtFoRm:tenant:write", "\tPLATFORM:user:read");
            var page = permissionMapper.selectVisiblePermissionPage(new Page<SysPermission>(1, 1), TENANT_A, PERMISSION_MODULE);
            assertThat(page.getTotal()).isEqualTo(2L);
            assertThat(page.getRecords()).hasSize(1);
            assertThat(permissionMapper.selectVisiblePermissionPage(new Page<SysPermission>(1, 100), TENANT_A, RESERVED_MODULE)
                    .getTotal()).isZero();
            assertThat(permissionMapper.selectVisibleModules(TENANT_A)).contains(PERMISSION_MODULE).doesNotContain(RESERVED_MODULE);
            assertThat(permissionMapper.selectByModule(RESERVED_MODULE, TENANT_A)).isEmpty();
            assertThat(permissionMapper.selectByCode("platform:tenant:read", TENANT_A)).isNull();
            assertThat(permissionMapper.selectByCode("  PlAtFoRm:tenant:write", TENANT_A)).isNull();
            assertThat(permissionMapper.selectPermissionCodesByRole(PERMISSION_ROLE, TENANT_A))
                    .containsExactlyInAnyOrder("platform-it:global", "platform-it:current");
            assertThat(permissionMapper.selectPermissionCodesByRoles(List.of(PERMISSION_ROLE), TENANT_A))
                    .containsExactlyInAnyOrder("platform-it:global", "platform-it:current");
        });
        assertSystemContext();
    }

    /** System tenant protection and platform-account exclusion are enforced by real commands, with durable failure outcomes. */
    @Test
    void systemTenantAndPlatformAccountsCannotBeDisabledThroughTargetCommands() {
        long systemVersion = count("SELECT version FROM tenant WHERE id = 0");
        long systemStatus = count("SELECT status FROM tenant WHERE id = 0");
        String key = key();

        expectFailure(ResultEnum.PLATFORM_SYSTEM_TENANT_PROTECTED, () -> commands.changeStatus(0L, key,
                new ChangePlatformTenantStatusRequest(0, systemVersion, REASON)));
        expectFailure(ResultEnum.TENANT_MEMBER_NOT_FOUND, () -> users.revokeSessions(0L, ACTOR, key(),
                new TenantMemberReasonRequest(REASON)));

        assertThat(count("SELECT version FROM tenant WHERE id = 0")).isEqualTo(systemVersion);
        assertThat(count("SELECT status FROM tenant WHERE id = 0")).isEqualTo(systemStatus);
        assertThat(count("SELECT auth_version FROM account WHERE id = ?", ACTOR)).isZero();
        assertThat(countOperations(key, "FAILURE")).isOne();
        assertSystemContext();
    }

    /** Current locks observe a committed disable despite an older snapshot, and inactive targets never receive capabilities. */
    @Test
    void disabledTenantInvitationFailsWithoutCreatingOrSendingCapability() {
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(20);
        TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        concurrent.setTimeout(10);
        snapshot.executeWithoutResult(state -> {
            assertThat(count("SELECT status FROM tenant WHERE id = ?", TENANT_A)).isOne();
            concurrent.executeWithoutResult(other -> assertThat(jdbc.update(
                    "UPDATE tenant SET status = 0, version = version + 1 WHERE id = ?", TENANT_A)).isOne());
            assertThat(count("SELECT status FROM tenant WHERE id = ?", TENANT_A))
                    .as("ordinary reads retain the pre-disable repeatable-read snapshot").isOne();
            var current = commands.lockTarget(TENANT_A);
            assertThat(current.getStatus()).as("the production target lock reads current lifecycle state").isZero();
            assertThat(current.getVersion()).isOne();
        });
        String key = key();

        expectFailure(ResultEnum.PLATFORM_TENANT_INACTIVE, () -> users.invite(TENANT_A, key,
                new CreateTenantInvitationRequest("disabled-platform-invite@example.test", "admin", 24, REASON)));

        assertThat(count("SELECT COUNT(*) FROM account_invitation WHERE tenant_id = ?", TENANT_A)).isZero();
        assertThat(countOperations(key, "FAILURE")).isOne();
        verifyNoInteractions(invitationMail);
        assertSystemContext();
    }

    /** Runs two real database commands from independently authenticated threads with a simultaneous start. */
    private List<ResultEnum> race(Supplier<PlatformMutationVO> first, Supplier<PlatformMutationVO> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<ResultEnum> a = pool.submit(asPlatform(() -> { await(start); return outcome(first); }));
            Future<ResultEnum> b = pool.submit(asPlatform(() -> { await(start); return outcome(second); }));
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    /** Reports only structured business outcomes, allowing infrastructure exceptions to fail the test. */
    private ResultEnum outcome(Supplier<PlatformMutationVO> action) {
        try {
            action.get();
            return ResultEnum.SUCCESS;
        } catch (GeneralException exception) {
            return exception.getResultEnum();
        }
    }

    /** Requires an expected rejection without masking unexpected success. */
    private ResultEnum failureOf(Supplier<PlatformMutationVO> action) {
        ResultEnum outcome = outcome(action);
        assertThat(outcome).isNotEqualTo(ResultEnum.SUCCESS);
        return outcome;
    }

    /** Asserts exact business failures while allowing unrelated SQL/Redis failures to surface. */
    private void expectFailure(ResultEnum expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(GeneralException.class,
                failure -> assertThat(failure.getResultEnum()).isEqualTo(expected));
    }

    /** Verifies the serialized optimistic lock rather than accepting arbitrary concurrent failures. */
    private void assertOneVersionWinner(List<ResultEnum> outcomes) {
        assertThat(outcomes).containsExactlyInAnyOrder(ResultEnum.SUCCESS, ResultEnum.PLATFORM_VERSION_CONFLICT);
    }

    /** Checks the winner and loser both retain their respective durable terminal evidence. */
    private void assertTerminalCounts(String operation) {
        assertThat(count("SELECT COUNT(*) FROM platform_operation_log WHERE actor_id = ? AND operation = ? AND status = 'SUCCESS'",
                ACTOR, operation)).isOne();
        assertThat(count("SELECT COUNT(*) FROM platform_operation_log WHERE actor_id = ? AND operation = ? AND status = 'FAILURE'",
                ACTOR, operation)).isOne();
    }

    /** Establishes and reliably clears a valid platform principal in worker threads. */
    private <T> Callable<T> asPlatform(Callable<T> action) {
        return () -> {
            authorizePlatform();
            try {
                T result = action.call();
                assertSystemContext();
                return result;
            } finally {
                clearIdentity();
            }
        };
    }

    /** Waits only for test-controlled synchronization and fails within a bounded duration. */
    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted integration synchronization", exception);
        }
    }

    /** Populates the same principal and trusted request state the validated JWT filter supplies. */
    private static void authorizePlatform() {
        authorize("platform_admin", 0L, false, true);
    }

    /** Builds identity boundary variants without manufacturing a different target-tenant session. */
    private static void authorize(String role, long tenantId, boolean ignoreIsolation, boolean capabilities) {
        var authorities = new ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        if (capabilities) PlatformPermissions.allCodes().stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
        User principal = new User(PREFIX + "operator", "unused", authorities);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        TenantContext.setTenantId(tenantId);
        TenantContext.setIgnoreIsolation(ignoreIsolation);
        MDC.put(Const.ATTR_USER_ID, Long.toString(ACTOR));
        MDC.put(Const.ATTR_USER_ROLE, role);
    }

    /** Clears principal, tenant bypass state and MDC on success and exceptional paths. */
    private static void clearIdentity() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    /** Verifies the complete caller context after target-tenant work and its failure paths. */
    private static void assertSystemContext() {
        assertThat(TenantContext.getTenantId()).isZero();
        assertThat(TenantContext.isIgnoreIsolation()).isFalse();
        assertThat(MDC.get(Const.ATTR_USER_ID)).isEqualTo(Long.toString(ACTOR));
    }

    /** Queries real cached authorization inside its tenant-bound security context. */
    private boolean authorized(long account, long tenant, String role, long version) {
        return TenantContext.callWithTenantIsolation(tenant,
                () -> authorization.isTokenAuthorized(account, tenant, role, "tenant", version));
    }

    /** Reads one exact durable claim through the production tenant-protected mapper. */
    private cn.flying.dao.entity.platform.PlatformOperationLog operation(String key) {
        return operationMapper.selectByActorAndKey(ACTOR, key);
    }

    /** Counts one accepted logical command and its expected terminal state directly in MySQL. */
    private long countOperations(String key, String status) {
        return count("SELECT COUNT(*) FROM platform_operation_log WHERE actor_id = ? AND idempotency_key = ? AND status = ?",
                ACTOR, key, status);
    }

    /** Reads a nonnull scalar from the isolated test database. */
    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        assertThat(value).isNotNull();
        return value;
    }

    /** Generates a distinct logical command key without including secrets in test output. */
    private static String key() {
        return UUID.randomUUID().toString();
    }

    /** Seeds tenant metadata with the same initial version used by the production migration. */
    private void insertTenant(long id, String suffix) {
        jdbc.update("""
                INSERT INTO tenant (id, name, code, status, version, deleted, create_time, update_time)
                VALUES (?, ?, ?, 1, 0, 0, NOW(), NOW())
                """, id, PREFIX + suffix, PREFIX + suffix);
        authorization.evictTenant(id);
    }

    /** Stores a secret-bearing account so real projections must discard credential columns. */
    private void insertAccount(long id, long tenantId, String suffix, String role) {
        jdbc.update("""
                INSERT INTO account (id, tenant_id, username, password, email, nickname, role, status, auth_version, deleted, register_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0, 0, NOW())
                """, id, tenantId, PREFIX + suffix, HASH_SENTINEL, PREFIX + suffix + "@example.test", PREFIX + suffix, role);
        authorization.evictAccount(tenantId, id);
    }

    /** Seeds an existing target override to detect accidental cross-tenant changes. */
    private void insertQuota(long id, long tenant, long bytes, long files, long version) {
        jdbc.update("""
                INSERT INTO quota_policy (id, tenant_id, scope_type, scope_id, max_storage_bytes, max_file_count, status, version)
                VALUES (?, ?, 'TENANT', ?, ?, ?, 1, ?)
                """, id, tenant, tenant, bytes, files, version);
    }

    /** Seeds only required file metadata with a private object-path sentinel and explicit quota eligibility. */
    private void insertFile(long id, long tenant, long user, int status, long bytes, int deleted) {
        jdbc.update("""
                INSERT INTO file (id, tenant_id, uid, file_name, file_param, file_hash, status, deleted, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """, id, tenant, Long.toString(user), PREFIX + id,
                "{\"fileSize\":" + bytes + ",\"objectPath\":\"" + OBJECT_SENTINEL + "\"}",
                PREFIX + "hash-" + id, status, deleted);
    }

    /** Seeds a tenant-owned ordinary operation independently of platform evidence. */
    private void insertBusinessAudit(long id, long tenant, long user) {
        jdbc.update("""
                INSERT INTO sys_operation_log (id, tenant_id, user_id, username, module, operation_type, description, status, operation_time)
                VALUES (?, ?, ?, ?, 'platform-it', '查询', 'Synthetic ordinary audit', 0, NOW())
                """, id, tenant, user, PREFIX + user);
    }

    /** Seeds tenant batches with stable fixture-owned issuance keys without invoking remote blockchain providers. */
    private void insertAttestation(long id, long tenant, String status) {
        jdbc.update("""
                INSERT INTO attestation_batch (id, tenant_id, batch_no, idempotency_key, merkle_root, proof_algorithm, leaf_count, status, deleted)
                VALUES (?, ?, ?, ?, ?, 'SHA256', 1, ?, 0)
                """, id, tenant, PREFIX + id, PREFIX + "attestation-" + id, "a".repeat(64), status);
    }

    /** Inserts explicit permission ownership variants using test-only JDBC, bypassing service reservation guards. */
    private void insertPermission(long id, long tenant, String code, String module) {
        jdbc.update("""
                INSERT INTO sys_permission (id, tenant_id, code, name, module, action, description, status)
                VALUES (?, ?, ?, 'Synthetic permission', ?, 'read', 'Synthetic platform namespace test', 1)
                """, id, tenant, code, module);
    }

    /** Forces only SUCCESS persistence to fail, leaving the executor's separate FAILURE transition available. */
    private void installSuccessAuditFailure() {
        jdbc.execute("""
                CREATE TRIGGER platform_it_success_failure BEFORE UPDATE ON platform_operation_log
                FOR EACH ROW
                BEGIN
                    IF NEW.status = 'SUCCESS' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic-platform-token-never-project';
                    END IF;
                END
                """);
    }

    /** Removes only this suite's temporary SQL fault-injection triggers. */
    private void clearFaultTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS platform_it_success_failure");
        jdbc.execute("DROP TRIGGER IF EXISTS platform_it_claim_failure");
    }

    /** Removes suite-owned rows while retaining unrelated tenants, configurations and released migration history. */
    private void cleanOwnedRows() {
        List<Long> tenantIds = jdbc.queryForList("SELECT id FROM tenant WHERE code LIKE ?", Long.class, PREFIX + "%");
        for (long tenant : tenantIds) {
            emitters.closeTenantConnections(tenant);
            List<Long> accounts = jdbc.queryForList("SELECT id FROM account WHERE tenant_id = ?", Long.class, tenant);
            for (long account : accounts) authorization.evictAccount(tenant, account);
            authorization.evictTenant(tenant);
            for (String table : List.of("account_member_audit", "account_invitation", "sys_operation_log",
                    "attestation_batch", "file", "quota_policy", "account")) {
                jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant);
            }
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenant);
        }
        jdbc.update("DELETE FROM platform_operation_log WHERE actor_id = ?", ACTOR);
        jdbc.update("DELETE FROM account WHERE id = ?", ACTOR);
        authorization.evictAccount(0L, ACTOR);
        jdbc.update("DELETE FROM sys_role_permission WHERE id BETWEEN 9231001 AND 9231006");
        jdbc.update("DELETE FROM sys_permission WHERE id BETWEEN 9230901 AND 9230906");
    }
}
