package cn.flying.service.auth;

import cn.flying.service.sse.SseEmitterManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Clears current authorization and live-session state after a tenant lifecycle change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantSessionRevocationService {

    private final AuthorizationStateService authorizationStateService;
    private final SseEmitterManager sseEmitterManager;

    /**
     * Makes a committed tenant status/version mutation effective for the next request and live connection.
     *
     * @param tenantId changed tenant
     */
    public void invalidateAfterLifecycleChange(Long tenantId) {
        if (tenantId == null || tenantId < 0) {
            throw new IllegalArgumentException("Tenant identifier is invalid");
        }
        authorizationStateService.fenceTenant(tenantId);
        sseEmitterManager.closeTenantConnections(tenantId);
        evictFenceAfterTransaction(tenantId);
    }

    /** Keeps tenant authorization fail-closed until the lifecycle transaction has completed. */
    private void evictFenceAfterTransaction(Long tenantId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            authorizationStateService.evictTenant(tenantId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    authorizationStateService.evictTenant(tenantId);
                } catch (RuntimeException exception) {
                    // The bounded malformed fence intentionally keeps authorization fail-closed until expiry.
                    log.error("Tenant authorization fence cleanup failed: exceptionType={}",
                            exception.getClass().getSimpleName());
                }
            }
        });
    }
}
