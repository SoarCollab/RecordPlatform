package cn.flying.service.auth;

import cn.flying.service.sse.SseEmitterManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Tests tenant lifecycle cache and live-connection invalidation. */
class TenantSessionRevocationServiceTest {

    /** Fences authorization state before closing every local tenant connection. */
    @Test
    void invalidatesTenantSessionsAfterLifecycleChange() {
        AuthorizationStateService authorizationStateService = mock(AuthorizationStateService.class);
        SseEmitterManager sseEmitterManager = mock(SseEmitterManager.class);
        TenantSessionRevocationService service = new TenantSessionRevocationService(
                authorizationStateService, sseEmitterManager);

        service.invalidateAfterLifecycleChange(42L);

        var order = inOrder(authorizationStateService, sseEmitterManager);
        order.verify(authorizationStateService).fenceTenant(42L);
        order.verify(sseEmitterManager).closeTenantConnections(42L);
        order.verify(authorizationStateService).evictTenant(42L);
    }

    /** Rejects invalid target tenant identifiers without mutating session state. */
    @Test
    void rejectsInvalidTenantIdentifier() {
        TenantSessionRevocationService service = new TenantSessionRevocationService(
                mock(AuthorizationStateService.class), mock(SseEmitterManager.class));

        assertThatThrownBy(() -> service.invalidateAfterLifecycleChange(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tenant identifier is invalid");
    }

    /** A transaction keeps the tenant authorization fence in place until completion. */
    @Test
    void keepsTenantFencedUntilTransactionCompletion() {
        AuthorizationStateService authorizationStateService = mock(AuthorizationStateService.class);
        SseEmitterManager sseEmitterManager = mock(SseEmitterManager.class);
        TenantSessionRevocationService service = new TenantSessionRevocationService(
                authorizationStateService, sseEmitterManager);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.invalidateAfterLifecycleChange(42L);

            verify(authorizationStateService).fenceTenant(42L);
            verify(authorizationStateService, never()).evictTenant(42L);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verify(authorizationStateService).evictTenant(42L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** Fence cleanup failures remain fail-closed and do not escape transaction completion. */
    @Test
    void containsFenceCleanupFailureAfterTransaction() {
        AuthorizationStateService authorizationStateService = mock(AuthorizationStateService.class);
        SseEmitterManager sseEmitterManager = mock(SseEmitterManager.class);
        TenantSessionRevocationService service = new TenantSessionRevocationService(
                authorizationStateService, sseEmitterManager);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(authorizationStateService).evictTenant(42L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.invalidateAfterLifecycleChange(42L);

            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
