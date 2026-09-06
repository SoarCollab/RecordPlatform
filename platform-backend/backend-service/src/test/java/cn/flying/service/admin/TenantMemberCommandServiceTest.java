package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.AccountMemberAudit;
import cn.flying.dao.mapper.AccountMemberAuditMapper;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.service.auth.AccountSessionRevocationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Verifies service decisions and audit evidence; real SQL concurrency is covered by the MySQL IT. */
@ExtendWith(MockitoExtension.class)
class TenantMemberCommandServiceTest {

    @Mock private AccountMapper accountMapper;
    @Mock private TenantMapper tenantMapper;
    @Mock private AccountSessionRevocationService revocationService;
    @Mock private AccountMemberAuditMapper auditMapper;
    private MockedStatic<IdUtils> ids;
    private TenantMemberCommandService service;

    /** Uses the real audit sanitizer while keeping persistence and session effects observable. */
    @BeforeEach
    void setUp() {
        ids = mockStatic(IdUtils.class);
        ids.when(IdUtils::nextEntityId).thenReturn(901L);
        service = new TenantMemberCommandService(accountMapper, tenantMapper, revocationService,
                new TenantMemberAuditService(auditMapper));
    }

    /** Releases thread-local ID stubbing so unrelated tests retain their own ID configuration. */
    @AfterEach
    void tearDown() {
        ids.close();
    }

    /** A missing tenant-scoped target must not trigger a global lookup or mutation. */
    @Test
    void rejectsCrossTenantTargetWithoutMutation() {
        when(tenantMapper.lockTenantForMemberMutation(11L)).thenReturn(11L);

        assertRejected(() -> service.changeStatus(11L, 1L, 99L, 0, "approved"),
                ResultEnum.TENANT_MEMBER_NOT_FOUND);

        verify(accountMapper).selectTenantMemberForUpdate(11L, 99L);
        verifyNoMutation();
    }

    /** Disabling the acting administrator is forbidden before counting or changing administrators. */
    @Test
    void rejectsSelfDisable() {
        givenMember(account(7L, "admin", 1));

        assertRejected(() -> service.changeStatus(11L, 7L, 7L, 0, "approved"),
                ResultEnum.TENANT_ADMIN_SELF_OPERATION_FORBIDDEN);

        verify(accountMapper, never()).countActiveTenantAdministrators(any());
        verifyNoMutation();
    }

    /** Both demotion and disable fail closed when at most one active administrator remains. */
    @ParameterizedTest
    @MethodSource("lastAdministratorMutations")
    void rejectsRemovingLastActiveAdministrator(String operation, long remaining) {
        givenMember(account(8L, "admin", 1));
        when(accountMapper.countActiveTenantAdministrators(11L)).thenReturn(remaining);

        assertRejected(() -> mutate(operation, 11L, 8L, "approved"),
                ResultEnum.LAST_TENANT_ADMIN_REQUIRED);

        InOrder order = inOrder(tenantMapper, accountMapper);
        order.verify(tenantMapper).lockTenantForMemberMutation(11L);
        order.verify(accountMapper).selectTenantMemberForUpdate(11L, 8L);
        order.verify(accountMapper).countActiveTenantAdministrators(11L);
        verifyNoMutation();
    }

    /** Successful role changes persist sanitized evidence before invalidating old JWT and SSE state. */
    @Test
    void roleChangeRevokesOldJwtAndSseState() {
        givenMember(account(8L, "user", 1));
        when(accountMapper.updateTenantMemberRole(11L, 8L, "monitor")).thenReturn(1);
        allowAuditInsert();

        service.changeRole(11L, 7L, 8L, "monitor", "approved token=secret-marker\n");

        assertAudit("ROLE_CHANGED", "user", "monitor", "approved token=******");
        InOrder order = inOrder(accountMapper, auditMapper, revocationService);
        order.verify(accountMapper).updateTenantMemberRole(11L, 8L, "monitor");
        order.verify(auditMapper).insert(any(AccountMemberAudit.class));
        order.verify(revocationService).invalidateAfterVersionChange(11L, 8L);
        verify(accountMapper, never()).countActiveTenantAdministrators(any());
    }

    /** Explicit revocation preserves the role and status while auditing the authorization-version advance. */
    @Test
    void explicitSessionRevokeUsesVersionedRevocationService() {
        Account target = account(8L, "user", 1);
        target.setAuthVersion(3L);
        givenMember(target);
        allowAuditInsert();

        service.revokeSessions(11L, 7L, 8L, "approved");

        assertAudit("SESSIONS_REVOKED", "3", "4", "approved");
        InOrder order = inOrder(revocationService, auditMapper);
        order.verify(revocationService).revokeAllSessions(11L, 8L);
        order.verify(auditMapper).insert(any(AccountMemberAudit.class));
        verify(accountMapper, never()).updateTenantMemberRole(any(), any(), anyString());
        verify(accountMapper, never()).updateTenantMemberStatus(any(), any(), anyInt());
        assertThat(target.getRole()).isEqualTo("user");
        assertThat(target.getStatus()).isEqualTo(1);
    }

    /** Tenant mutations cannot grant platform, unknown, blank, or differently cased roles. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"platform_admin", "noop", "owner", "ADMIN", " admin "})
    void rejectsNonTenantRolesBeforeLocking(String role) {
        assertRejected(() -> service.changeRole(11L, 7L, 8L, role, "approved"),
                ResultEnum.PARAM_IS_INVALID);

        verifyNoInteractions(tenantMapper, accountMapper, auditMapper, revocationService);
    }

    /** Invalid lifecycle states are rejected before tenant or account access. */
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 2, Integer.MAX_VALUE})
    void rejectsInvalidStatusBeforeLocking(Integer status) {
        assertRejected(() -> service.changeStatus(11L, 7L, 8L, status, "approved"),
                ResultEnum.PARAM_IS_INVALID);

        verifyNoInteractions(tenantMapper, accountMapper, auditMapper, revocationService);
    }

    /** Missing and negative tenant identifiers never reach a tenant mapper. */
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1L})
    void rejectsInvalidTenantWithoutDataAccess(Long tenantId) {
        assertRejected(() -> service.revokeSessions(tenantId, 7L, 8L, "approved"),
                ResultEnum.TENANT_MEMBER_NOT_FOUND);

        verifyNoInteractions(tenantMapper, accountMapper, auditMapper, revocationService);
    }

    /** A missing or mismatched tenant lock cannot authorize member access. */
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {99L})
    void rejectsUnavailableTenantFence(Long lockedTenant) {
        when(tenantMapper.lockTenantForMemberMutation(11L)).thenReturn(lockedTenant);

        assertRejected(() -> service.changeRole(11L, 7L, 8L, "user", "approved"),
                ResultEnum.TENANT_MEMBER_NOT_FOUND);

        verifyNoInteractions(accountMapper, auditMapper, revocationService);
    }

    /** Null target IDs fail before attempting a member lookup. */
    @Test
    void rejectsNullMemberId() {
        when(tenantMapper.lockTenantForMemberMutation(11L)).thenReturn(11L);

        assertRejected(() -> service.revokeSessions(11L, 7L, null, "approved"),
                ResultEnum.TENANT_MEMBER_NOT_FOUND);

        verifyNoInteractions(accountMapper, auditMapper, revocationService);
    }

    /** Every mutation requires a reason before making persistent or session changes. */
    @ParameterizedTest
    @ValueSource(strings = {"role", "status", "sessions"})
    void rejectsBlankReasonWithoutSideEffects(String operation) {
        givenMember(account(8L, "user", 1));

        assertRejected(() -> mutate(operation, 11L, 8L, " \n\t"), ResultEnum.PARAM_IS_INVALID);

        verifyNoMutation();
    }

    /** Repeating a role or state does not advance authorization state or create a false audit event. */
    @ParameterizedTest
    @ValueSource(strings = {"role", "status"})
    void sameValueIsAnIdempotentNoOpEvenForSelf(String operation) {
        givenMember(account(7L, "user", 0));

        if (operation.equals("role")) {
            service.changeRole(11L, 7L, 7L, "user", "approved");
        } else {
            service.changeStatus(11L, 7L, 7L, 0, "approved");
        }

        verify(accountMapper, never()).countActiveTenantAdministrators(any());
        verifyNoMutation();
    }

    /** Even no-op requests must satisfy the mandatory-reason contract. */
    @Test
    void sameValueStillRequiresReason() {
        givenMember(account(8L, "user", 1));

        assertRejected(() -> service.changeRole(11L, 7L, 8L, "user", null),
                ResultEnum.PARAM_IS_INVALID);

        verifyNoMutation();
    }

    /** Self-demotion is forbidden even when another administrator might exist. */
    @Test
    void rejectsSelfDemotionWithoutCountingAdministrators() {
        givenMember(account(7L, "admin", 1));

        assertRejected(() -> service.changeRole(11L, 7L, 7L, "monitor", "approved"),
                ResultEnum.TENANT_ADMIN_SELF_OPERATION_FORBIDDEN);

        verify(accountMapper, never()).countActiveTenantAdministrators(any());
        verifyNoMutation();
    }

    /** Demotion succeeds only after the locked count proves another administrator remains. */
    @Test
    void demotesAdministratorWhenAnotherActiveAdministratorRemains() {
        givenMember(account(8L, "admin", 1));
        when(accountMapper.countActiveTenantAdministrators(11L)).thenReturn(2L);
        when(accountMapper.updateTenantMemberRole(11L, 8L, "user")).thenReturn(1);
        allowAuditInsert();

        service.changeRole(11L, 7L, 8L, "user", "approved");

        assertAudit("ROLE_CHANGED", "admin", "user", "approved");
        InOrder order = inOrder(tenantMapper, accountMapper, revocationService);
        order.verify(tenantMapper).lockTenantForMemberMutation(11L);
        order.verify(accountMapper).selectTenantMemberForUpdate(11L, 8L);
        order.verify(accountMapper).countActiveTenantAdministrators(11L);
        order.verify(accountMapper).updateTenantMemberRole(11L, 8L, "user");
        order.verify(revocationService).invalidateAfterVersionChange(11L, 8L);
    }

    /** Promoting an ordinary member adds an administrator and does not consult the removal guard. */
    @Test
    void promotesMemberToAdministratorWithoutRemovalCheck() {
        givenMember(account(8L, "monitor", 1));
        when(accountMapper.updateTenantMemberRole(11L, 8L, "admin")).thenReturn(1);
        allowAuditInsert();

        service.changeRole(11L, 7L, 8L, "admin", "approved");

        assertAudit("ROLE_CHANGED", "monitor", "admin", "approved");
        verify(accountMapper, never()).countActiveTenantAdministrators(any());
        verify(revocationService).invalidateAfterVersionChange(11L, 8L);
    }

    /** A disabled administrator is not part of the active-administrator count. */
    @Test
    void demotesDisabledAdministratorWithoutActiveAdminCheck() {
        givenMember(account(8L, "admin", 0));
        when(accountMapper.updateTenantMemberRole(11L, 8L, "monitor")).thenReturn(1);
        allowAuditInsert();

        service.changeRole(11L, 7L, 8L, "monitor", "approved");

        assertAudit("ROLE_CHANGED", "admin", "monitor", "approved");
        verify(accountMapper, never()).countActiveTenantAdministrators(any());
        verify(revocationService).invalidateAfterVersionChange(11L, 8L);
    }

    /** Disabling an administrator succeeds when another active administrator is protected by the lock. */
    @Test
    void disablesAdministratorWhenAnotherRemains() {
        givenMember(account(8L, "admin", 1));
        when(accountMapper.countActiveTenantAdministrators(11L)).thenReturn(2L);
        when(accountMapper.updateTenantMemberStatus(11L, 8L, 0)).thenReturn(1);
        allowAuditInsert();

        service.changeStatus(11L, 7L, 8L, 0, "approved");

        assertAudit("STATUS_CHANGED", "1", "0", "approved");
        verify(revocationService).invalidateAfterVersionChange(11L, 8L);
    }

    /** Restoring a disabled administrator never invokes the last-admin removal check. */
    @Test
    void restoresDisabledAdministratorAndRevokesStaleSessions() {
        givenMember(account(8L, "admin", 0));
        when(accountMapper.updateTenantMemberStatus(11L, 8L, 1)).thenReturn(1);
        allowAuditInsert();

        service.changeStatus(11L, 7L, 8L, 1, "approved");

        assertAudit("STATUS_CHANGED", "0", "1", "approved");
        verify(accountMapper, never()).countActiveTenantAdministrators(any());
        verify(revocationService).invalidateAfterVersionChange(11L, 8L);
    }

    /** Legacy tenant zero remains a valid tenant for session administration. */
    @Test
    void revokesSessionsForLegacyTenantZero() {
        Account target = account(8L, "user", 1);
        target.setTenantId(0L);
        when(tenantMapper.lockTenantForMemberMutation(0L)).thenReturn(0L);
        when(accountMapper.selectTenantMemberForUpdate(0L, 8L)).thenReturn(target);
        allowAuditInsert();

        service.revokeSessions(0L, 7L, 8L, "approved");

        verify(revocationService).revokeAllSessions(0L, 8L);
        ArgumentCaptor<AccountMemberAudit> audit = ArgumentCaptor.forClass(AccountMemberAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertThat(audit.getValue().getTenantId()).isZero();
    }

    /** A lost or over-broad update cannot be reported as success or invalidate an unrelated session. */
    @ParameterizedTest
    @MethodSource("unsuccessfulUpdates")
    void rejectsUnexpectedAffectedRowCount(String operation, int affectedRows) {
        givenMember(account(8L, "monitor", 1));
        if (operation.equals("role")) {
            when(accountMapper.updateTenantMemberRole(11L, 8L, "user")).thenReturn(affectedRows);
        } else {
            when(accountMapper.updateTenantMemberStatus(11L, 8L, 0)).thenReturn(affectedRows);
        }

        assertRejected(() -> mutate(operation, 11L, 8L, "approved"), ResultEnum.TENANT_MEMBER_NOT_FOUND);

        verifyNoInteractions(auditMapper, revocationService);
    }

    /** Audit failure prevents successful role-change completion and session invalidation. */
    @Test
    void failsRoleChangeWhenAuditCannotBePersisted() {
        givenMember(account(8L, "user", 1));
        when(accountMapper.updateTenantMemberRole(11L, 8L, "monitor")).thenReturn(1);

        assertThatThrownBy(() -> service.changeRole(11L, 7L, 8L, "monitor", "approved"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tenant member audit persistence failed");

        verifyNoInteractions(revocationService);
    }

    /** A failed session revocation must not leave a success audit event. */
    @Test
    void doesNotAuditFailedSessionRevocation() {
        givenMember(account(8L, "user", 1));
        IllegalStateException failure = new IllegalStateException("revocation unavailable");
        doThrow(failure).when(revocationService).revokeAllSessions(11L, 8L);

        assertThatThrownBy(() -> service.revokeSessions(11L, 7L, 8L, "approved"))
                .isSameAs(failure);

        verifyNoInteractions(auditMapper);
    }

    /** Supplies both last-admin-affecting operations and fail-closed count boundaries. */
    private static Stream<Arguments> lastAdministratorMutations() {
        return Stream.of(Arguments.of("role", 0L), Arguments.of("role", 1L),
                Arguments.of("status", 0L), Arguments.of("status", 1L));
    }

    /** Supplies lost-update and unexpected-multiple-update cases for role and state commands. */
    private static Stream<Arguments> unsuccessfulUpdates() {
        return Stream.of(Arguments.of("role", 0), Arguments.of("role", 2),
                Arguments.of("status", 0), Arguments.of("status", 2));
    }

    /** Dispatches valid mutation requests for common boundary assertions. */
    private void mutate(String operation, Long tenantId, Long targetId, String reason) {
        switch (operation) {
            case "role" -> service.changeRole(tenantId, 7L, targetId, "user", reason);
            case "status" -> service.changeStatus(tenantId, 7L, targetId, 0, reason);
            case "sessions" -> service.revokeSessions(tenantId, 7L, targetId, reason);
            default -> throw new IllegalArgumentException("Unknown test operation: " + operation);
        }
    }

    /** Sets up one visible tenant member behind the shared tenant mutation fence. */
    private void givenMember(Account target) {
        when(tenantMapper.lockTenantForMemberMutation(11L)).thenReturn(11L);
        when(accountMapper.selectTenantMemberForUpdate(11L, target.getId())).thenReturn(target);
    }

    /** Allows exactly one audit row to be persisted for successful commands. */
    private void allowAuditInsert() {
        when(auditMapper.insert(any(AccountMemberAudit.class))).thenReturn(1);
    }

    /** Checks persisted event identity and values using the real audit service output. */
    private void assertAudit(String action, String oldValue, String newValue, String reason) {
        ArgumentCaptor<AccountMemberAudit> audit = ArgumentCaptor.forClass(AccountMemberAudit.class);
        verify(auditMapper).insert(audit.capture());
        AccountMemberAudit event = audit.getValue();
        assertThat(event.getTenantId()).isEqualTo(11L);
        assertThat(event.getActorId()).isEqualTo(7L);
        assertThat(event.getTargetAccountId()).isEqualTo(8L);
        assertThat(event.getInvitationId()).isNull();
        assertThat(event.getAction()).isEqualTo(action);
        assertThat(event.getOldValue()).isEqualTo(oldValue);
        assertThat(event.getNewValue()).isEqualTo(newValue);
        assertThat(event.getReason()).isEqualTo(reason);
    }

    /** Ensures rejection never changes member state, creates an audit event, or revokes sessions. */
    private void verifyNoMutation() {
        verify(accountMapper, never()).updateTenantMemberRole(any(), any(), anyString());
        verify(accountMapper, never()).updateTenantMemberStatus(any(), any(), anyInt());
        verifyNoInteractions(auditMapper, revocationService);
    }

    /** Asserts the stable non-disclosing business error for rejected commands. */
    private void assertRejected(Runnable mutation, ResultEnum expected) {
        assertThatThrownBy(mutation::run).isInstanceOfSatisfying(GeneralException.class,
                error -> assertThat(error.getResultEnum()).isEqualTo(expected));
    }

    /** Builds a minimal persisted tenant member with a valid authorization version. */
    private Account account(Long id, String role, Integer status) {
        Account account = new Account();
        account.setId(id);
        account.setTenantId(11L);
        account.setRole(role);
        account.setStatus(status);
        account.setAuthVersion(0L);
        return account;
    }
}
