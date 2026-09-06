package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.JwtUtils;
import cn.flying.dao.mapper.AccountInvitationMapper;
import cn.flying.dao.vo.admin.AcceptTenantInvitationRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import cn.flying.service.auth.AuthorizationStateService;
import cn.flying.test.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves last-admin and invitation single-use contracts against real MySQL transactions. */
class TenantUserManagementMySqlIT extends BaseIntegrationTest {

    private static final long ADMIN_TENANT = 92201L;
    private static final long INVITE_TENANT = 92202L;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantMemberCommandService commandService;
    @Autowired private TenantInvitationService invitationService;
    @Autowired private TenantMemberQueryService queryService;
    @Autowired private AuthorizationStateService authorizationStateService;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private AccountInvitationMapper invitationMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUpData() {
        jdbcTemplate.update("DELETE FROM account_member_audit WHERE tenant_id IN (?, ?)", ADMIN_TENANT, INVITE_TENANT);
        jdbcTemplate.update("DELETE FROM account_invitation WHERE tenant_id IN (?, ?)", ADMIN_TENANT, INVITE_TENANT);
        jdbcTemplate.update("DELETE FROM account WHERE tenant_id IN (?, ?)", ADMIN_TENANT, INVITE_TENANT);
        jdbcTemplate.update("DELETE FROM account WHERE id IN (?, ?)", 9220001L, 9220002L);
        jdbcTemplate.update("DELETE FROM tenant WHERE id IN (?, ?)", ADMIN_TENANT, INVITE_TENANT);
        insertTenant(ADMIN_TENANT, "member-admin-race");
        insertTenant(INVITE_TENANT, "member-invite-race");
        insertAccount(9220101L, ADMIN_TENANT, "race-admin-a", "race-a@example.test", "admin");
        insertAccount(9220102L, ADMIN_TENANT, "race-admin-b", "race-b@example.test", "admin");
    }

    /** Removes synthetic caller authority even when a lifecycle assertion fails. */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void concurrentDemotionsKeepOneActiveAdministrator() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> demoteAfter(start, 9220101L, 9220102L));
            Future<Boolean> second = executor.submit(() -> demoteAfter(start, 9220102L, 9220101L));
            start.countDown();

            assertThat(first.get() ^ second.get()).isTrue();
        }
        Integer activeAdmins = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account WHERE tenant_id = ? AND role = 'admin' AND status = 1 AND deleted = 0",
                Integer.class, ADMIN_TENANT);
        assertThat(activeAdmins).isEqualTo(1);
    }

    @Test
    void concurrentInvitationAcceptanceCreatesExactlyOneAccount() throws Exception {
        String token = "integration-invitation-token-" + "x".repeat(24);
        insertInvitation(9220201L, token, "accepted-once@example.test");

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> acceptAfter(start, token, "accepted-once-a"));
            Future<Boolean> second = executor.submit(() -> acceptAfter(start, token, "accepted-once-b"));
            start.countDown();
            assertThat(first.get() ^ second.get()).isTrue();
        }

        Integer accounts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account WHERE tenant_id = ? AND email = ?",
                Integer.class, INVITE_TENANT, "accepted-once@example.test");
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM account_invitation WHERE id = ?", String.class, 9220201L);
        assertThat(accounts).isEqualTo(1);
        assertThat(status).isEqualTo("ACCEPTED");
    }

    /** A valid capability cannot create accounts for an inactive, deleted, absent or corrupt owner tenant. */
    @ParameterizedTest
    @ValueSource(strings = {"disabled", "deleted", "missing", "invalid-version"})
    void rejectsInvitationForUnavailableTenantAndRestoresCaller(String lifecycle) throws Exception {
        String token = "integration-unavailable-tenant-" + "y".repeat(24);
        String email = "unavailable-tenant@example.test";
        insertInvitation(9220202L, token, email);
        switch (lifecycle) {
            case "disabled" -> jdbcTemplate.update("UPDATE tenant SET status = 0 WHERE id = ?", INVITE_TENANT);
            case "deleted" -> jdbcTemplate.update("UPDATE tenant SET deleted = 1 WHERE id = ?", INVITE_TENANT);
            case "missing" -> jdbcTemplate.update("DELETE FROM tenant WHERE id = ?", INVITE_TENANT);
            case "invalid-version" -> jdbcTemplate.update("UPDATE tenant SET version = -1 WHERE id = ?", INVITE_TENANT);
            default -> throw new AssertionError("Unknown tenant lifecycle fixture");
        }
        TenantContext.setTenantId(ADMIN_TENANT);
        TenantContext.setIgnoreIsolation(true);

        assertInvitationRejected(token);

        assertThat(TenantContext.getTenantId()).isEqualTo(ADMIN_TENANT);
        assertThat(TenantContext.isIgnoreIsolation()).isTrue();
        assertInvitationUnconsumed(9220202L, email);
    }

    /** Tenant validation must see a committed disable even after owner lookup established an older RR snapshot. */
    @Test
    void rejectsTenantDisabledAfterOwnerLookupSnapshot() throws Exception {
        String token = "integration-tenant-snapshot-" + "z".repeat(24);
        String tokenHash = sha256(token);
        String email = "tenant-snapshot@example.test";
        insertInvitation(9220203L, token, email);
        TenantContext.setTenantId(ADMIN_TENANT);
        TenantContext.setIgnoreIsolation(true);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(20);

        transaction.executeWithoutResult(state -> {
            assertThat(invitationMapper.selectOwnerTenantIdByTokenHash(tokenHash)).isEqualTo(INVITE_TENANT);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?",
                    Integer.class, INVITE_TENANT)).isEqualTo(1);
            disableInvitationTenantOnAnotherConnection();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?",
                    Integer.class, INVITE_TENANT)).as("ordinary reads still use the older snapshot").isEqualTo(1);

            assertInvitationRejected(token);

            assertThat(TenantContext.getTenantId()).isEqualTo(ADMIN_TENANT);
            assertThat(TenantContext.isIgnoreIsolation()).isTrue();
            state.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?",
                Integer.class, INVITE_TENANT)).isZero();
        assertInvitationUnconsumed(9220203L, email);
    }

    @Test
    void concurrentInvitationCreationKeepsOnePendingToken() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> createAfter(start, "duplicate@example.test"));
            Future<Boolean> second = executor.submit(() -> createAfter(start, "duplicate@example.test"));
            start.countDown();
            assertThat(first.get() ^ second.get()).isTrue();
        }

        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_invitation WHERE tenant_id = ? AND email = ? AND status = 'PENDING'",
                Integer.class, INVITE_TENANT, "duplicate@example.test");
        assertThat(pending).isEqualTo(1);
    }

    @Test
    void tenantZeroMemberQueriesHidePlatformAccounts() {
        insertAccount(9220001L, 0L, "member-query-platform", "member-query-platform@example.test", "platform_admin");
        insertAccount(9220002L, 0L, "member-query-user", "member-query-user@example.test", "user");

        var page = TenantContext.callWithTenantIsolation(0L,
                () -> queryService.list(0L, 1, 20, "member-query-", null, null));

        assertThat(page.getRecords()).extracting(member -> member.username())
                .containsExactly("member-query-user");
    }

    @Test
    void roleChangeInvalidatesCachedAuthorizationAndOutstandingSseToken() {
        long accountId = 9220103L;
        insertAccount(accountId, ADMIN_TENANT, "revoked-member", "revoked-member@example.test", "user");
        boolean initiallyAuthorized = TenantContext.callWithTenantIsolation(ADMIN_TENANT,
                () -> authorizationStateService.isTokenAuthorized(
                        accountId, ADMIN_TENANT, "user", "tenant", 0L));
        String sseToken = jwtUtils.createSseToken(accountId, ADMIN_TENANT, "user", 0L);

        TenantContext.runWithTenantIsolation(ADMIN_TENANT,
                () -> commandService.changeRole(
                        ADMIN_TENANT, 9220101L, accountId, "monitor", "integration revocation"));

        boolean oldTokenAuthorized = TenantContext.callWithTenantIsolation(ADMIN_TENANT,
                () -> authorizationStateService.isTokenAuthorized(
                        accountId, ADMIN_TENANT, "user", "tenant", 0L));
        boolean currentTokenAuthorized = TenantContext.callWithTenantIsolation(ADMIN_TENANT,
                () -> authorizationStateService.isTokenAuthorized(
                        accountId, ADMIN_TENANT, "monitor", "tenant", 1L));
        String[] consumed = TenantContext.callWithTenantIsolation(ADMIN_TENANT,
                () -> jwtUtils.validateAndConsumeSseToken(sseToken));

        assertThat(initiallyAuthorized).isTrue();
        assertThat(oldTokenAuthorized).isFalse();
        assertThat(currentTokenAuthorized).isTrue();
        assertThat(consumed).isNull();
    }

    /** Waits for the shared start and reports only the expected last-admin rejection as false. */
    private boolean demoteAfter(CountDownLatch start, long actor, long target) throws Exception {
        start.await();
        try {
            TenantContext.runWithTenantIsolation(ADMIN_TENANT,
                    () -> commandService.changeRole(ADMIN_TENANT, actor, target, "user", "concurrency test"));
            return true;
        } catch (GeneralException exception) {
            if (exception.getResultEnum() == ResultEnum.LAST_TENANT_ADMIN_REQUIRED) return false;
            throw exception;
        }
    }

    /** Waits for the shared start and reports only expected single-use rejection as false. */
    private boolean acceptAfter(CountDownLatch start, String token, String username) throws Exception {
        start.await();
        try {
            invitationService.accept(new AcceptTenantInvitationRequest(token, username, null, "password123"));
            return true;
        } catch (GeneralException exception) {
            if (exception.getResultEnum() == ResultEnum.INVITATION_INVALID) return false;
            throw exception;
        }
    }

    /** Races invitation creation and recognizes only the deterministic pending-guard conflict. */
    private boolean createAfter(CountDownLatch start, String email) throws Exception {
        start.await();
        try {
            TenantContext.runWithTenantIsolation(INVITE_TENANT,
                    () -> invitationService.create(INVITE_TENANT, 9220101L,
                            new CreateTenantInvitationRequest(email, "user", 24, "concurrency test")));
            return true;
        } catch (GeneralException exception) {
            if (exception.getResultEnum() == ResultEnum.INVITATION_ALREADY_EXISTS) return false;
            throw exception;
        }
    }

    /** Inserts a dedicated active test tenant. */
    private void insertTenant(long id, String code) {
        jdbcTemplate.update("""
                INSERT INTO tenant (id, name, code, status, version, deleted, create_time, update_time)
                VALUES (?, ?, ?, 1, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, code, code);
    }

    /** Inserts an active test account using globally unique identity values. */
    private void insertAccount(long id, long tenantId, String username, String email, String role) {
        jdbcTemplate.update("""
                INSERT INTO account
                    (id, tenant_id, username, password, email, role, status, auth_version,
                     register_time, update_time, deleted)
                VALUES (?, ?, ?, '$2a$10$integration.placeholder.hash.value', ?, ?, 1, 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, id, tenantId, username, email, role);
    }

    /** Inserts an unexpired pending capability bound to the dedicated invitation tenant. */
    private void insertInvitation(long id, String token, String email) throws Exception {
        jdbcTemplate.update("""
                INSERT INTO account_invitation
                    (id, tenant_id, token_hash, email, role, status, invited_by, expires_at, create_time, update_time)
                VALUES (?, ?, ?, ?, 'user', 'PENDING', ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 HOUR),
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, id, INVITE_TENANT, sha256(token), email, 9220101L);
    }

    /** Checks the non-disclosing public rejection without relying on localized or secret-bearing detail. */
    private void assertInvitationRejected(String token) {
        assertThatThrownBy(() -> invitationService.accept(
                new AcceptTenantInvitationRequest(token, "unavailable-tenant-member", null, "password123")))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.INVITATION_INVALID));
    }

    /** Proves a denied acceptance preserves the capability and produces no account or success audit. */
    private void assertInvitationUnconsumed(long invitationId, String email) {
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, accepted_by FROM account_invitation WHERE id = ?", invitationId))
                .containsEntry("status", "PENDING").containsEntry("accepted_by", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account WHERE tenant_id = ? AND email = ?",
                Integer.class, INVITE_TENANT, email)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_member_audit WHERE tenant_id = ?",
                Integer.class, INVITE_TENANT)).isZero();
    }

    /** Commits a lifecycle change on an independent connection with a bounded wait and no timing sleeps. */
    private void disableInvitationTenantOnAnotherConnection() {
        var executor = Executors.newSingleThreadExecutor();
        Future<?> disable = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(state -> assertThat(jdbcTemplate.update(
                        "UPDATE tenant SET status = 0, version = version + 1 WHERE id = ?", INVITE_TENANT))
                        .isEqualTo(1)));
        try {
            disable.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while disabling the invitation tenant", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AssertionError("Independent tenant disable did not commit", exception);
        } finally {
            disable.cancel(true);
            executor.shutdownNow();
        }
    }

    /** Produces the database lookup digest used by invitation acceptance. */
    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
