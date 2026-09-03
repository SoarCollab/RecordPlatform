package cn.flying.service.auth;

import cn.flying.service.sse.SseEmitterManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

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
}
