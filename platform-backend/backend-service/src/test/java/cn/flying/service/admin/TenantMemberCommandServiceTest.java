package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.service.auth.AccountSessionRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies tenant mutation isolation, self-protection and last-admin invariants. */
@ExtendWith(MockitoExtension.class)
class TenantMemberCommandServiceTest {

    @Mock private AccountMapper accountMapper;
    @Mock private TenantMapper tenantMapper;
    @Mock private AccountSessionRevocationService revocationService;
    @Mock private TenantMemberAuditService auditService;
    private TenantMemberCommandService service;

    @BeforeEach
    void setUp() {
        service = new TenantMemberCommandService(accountMapper, tenantMapper, revocationService, auditService);
        lenient().when(auditService.sanitizeReason("approved")).thenReturn("approved");
    }

    @Test
    void rejectsCrossTenantTargetWithoutMutation() {
        when(tenantMapper.lockTenantForMemberMutation(11L)).thenReturn(11L);

        assertThatThrownBy(() -> service.changeStatus(11L, 1L, 99L, 0, "approved"))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.TENANT_MEMBER_NOT_FOUND));

        verify(accountMapper, never()).updateTenantMemberStatus(11L, 99L, 0);
    }

    @Test
    void rejectsSelfDisable() {
        when(tenantMapper.lockTenantForMemberMutation(11L)).thenReturn(11L);
        when(accountMapper.selectTenantMemberForUpdate(11L, 7L)).thenReturn(account(7L, "admin", 1));

        assertThatThrownBy(() -> service.changeStatus(11L, 7L, 7L, 0, "approved"))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum())
                                .isEqualTo(ResultEnum.TENANT_ADMIN_SELF_OPERATION_FORBIDDEN));
    }

    @Test
    void rejectsRemovingLastActiveAdministrator() {
        when(tenantMapper.lockTenantForMemberMutation(11L)).thenReturn(11L);
        when(accountMapper.selectTenantMemberForUpdate(11L, 8L)).thenReturn(account(8L, "admin", 1));
        when(accountMapper.countActiveTenantAdministrators(11L)).thenReturn(1L);

        assertThatThrownBy(() -> service.changeRole(11L, 7L, 8L, "user", "approved"))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.LAST_TENANT_ADMIN_REQUIRED));
    }

    @Test
    void roleChangeRevokesOldJwtAndSseState() {
        when(tenantMapper.lockTenantForMemberMutation(11L)).thenReturn(11L);
        when(accountMapper.selectTenantMemberForUpdate(11L, 8L)).thenReturn(account(8L, "user", 1));
        when(accountMapper.updateTenantMemberRole(11L, 8L, "monitor")).thenReturn(1);

        service.changeRole(11L, 7L, 8L, "monitor", "approved");

        verify(revocationService).invalidateAfterVersionChange(11L, 8L);
        verify(auditService).record(11L, 7L, 8L, null,
                "ROLE_CHANGED", "user", "monitor", "approved");
    }

    @Test
    void explicitSessionRevokeUsesVersionedRevocationService() {
        Account target = account(8L, "user", 1);
        target.setAuthVersion(3L);
        when(tenantMapper.lockTenantForMemberMutation(11L)).thenReturn(11L);
        when(accountMapper.selectTenantMemberForUpdate(11L, 8L)).thenReturn(target);

        service.revokeSessions(11L, 7L, 8L, "approved");

        verify(revocationService).revokeAllSessions(11L, 8L);
        verify(auditService).record(11L, 7L, 8L, null,
                "SESSIONS_REVOKED", "3", "4", "approved");
    }

    private Account account(Long id, String role, Integer status) {
        Account account = new Account();
        account.setId(id);
        account.setTenantId(11L);
        account.setRole(role);
        account.setStatus(status);
        return account;
    }
}
