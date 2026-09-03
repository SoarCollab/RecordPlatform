package cn.flying.service.auth;

import cn.flying.common.util.JwtUtils;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.service.sse.SseEmitterManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests account-wide revocation coordination after authorization version changes. */
@ExtendWith(MockitoExtension.class)
class AccountSessionRevocationServiceTest {

    @Mock
    private AccountMapper accountMapper;
    @Mock
    private AuthorizationStateService authorizationStateService;
    @Mock
    private JwtUtils jwtUtils;
    @Mock
    private SseEmitterManager sseEmitterManager;

    /** Increments the database fence before removing all cached and live session state. */
    @Test
    void revokesPersistentAndEphemeralSessions() {
        when(accountMapper.incrementAuthVersion(42L, 7L)).thenReturn(1);
        AccountSessionRevocationService service = service();

        service.revokeAllSessions(42L, 7L);

        InOrder order = inOrder(accountMapper, authorizationStateService, jwtUtils, sseEmitterManager);
        order.verify(accountMapper).incrementAuthVersion(42L, 7L);
        order.verify(authorizationStateService).fenceAccount(42L, 7L);
        order.verify(jwtUtils).invalidateUserSseTokens(42L, 7L);
        order.verify(sseEmitterManager).closeUserConnections(42L, 7L);
        order.verify(authorizationStateService).evictAccount(42L, 7L);
    }

    /** A missing account cannot be reported as successfully revoked. */
    @Test
    void failsClosedWhenPersistentVersionWasNotChanged() {
        when(accountMapper.incrementAuthVersion(42L, 7L)).thenReturn(0);
        AccountSessionRevocationService service = service();

        assertThatThrownBy(() -> service.revokeAllSessions(42L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Account session revocation failed");
        verify(authorizationStateService, never()).fenceAccount(42L, 7L);
        verify(authorizationStateService, never()).evictAccount(42L, 7L);
        verify(jwtUtils, never()).invalidateUserSseTokens(42L, 7L);
        verify(sseEmitterManager, never()).closeUserConnections(42L, 7L);
    }

    /** A transaction keeps the invalid authorization fence until completion. */
    @Test
    void keepsAuthorizationFencedUntilTransactionCompletion() {
        AccountSessionRevocationService service = service();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.invalidateAfterVersionChange(42L, 7L);

            verify(authorizationStateService).fenceAccount(42L, 7L);
            verify(authorizationStateService, never()).evictAccount(42L, 7L);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verify(authorizationStateService).evictAccount(42L, 7L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private AccountSessionRevocationService service() {
        return new AccountSessionRevocationService(
                accountMapper, authorizationStateService, jwtUtils, sseEmitterManager);
    }
}
