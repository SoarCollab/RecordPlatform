package cn.flying.service.auth;

import cn.flying.common.util.JwtUtils;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.service.sse.SseEmitterManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Coordinates account-wide token, cache and live SSE revocation after identity mutations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountSessionRevocationService {

    private final AccountMapper accountMapper;
    private final AuthorizationStateService authorizationStateService;
    private final JwtUtils jwtUtils;
    private final SseEmitterManager sseEmitterManager;

    /**
     * Increments the persistent account authorization version and invalidates all local session state.
     *
     * @param tenantId account tenant
     * @param accountId account identifier
     */
    @Transactional
    public void revokeAllSessions(Long tenantId, Long accountId) {
        if (accountMapper.incrementAuthVersion(tenantId, accountId) != 1) {
            throw new IllegalStateException("Account session revocation failed");
        }
        invalidateAfterVersionChange(tenantId, accountId);
    }

    /**
     * Invalidates cached and ephemeral session state after a caller atomically changed auth_version.
     */
    public void invalidateAfterVersionChange(Long tenantId, Long accountId) {
        authorizationStateService.fenceAccount(tenantId, accountId);
        jwtUtils.invalidateUserSseTokens(tenantId, accountId);
        sseEmitterManager.closeUserConnections(tenantId, accountId);
        evictFenceAfterTransaction(tenantId, accountId);
    }

    /** Keeps the fail-closed fence until the surrounding database transaction has completed. */
    private void evictFenceAfterTransaction(Long tenantId, Long accountId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            authorizationStateService.evictAccount(tenantId, accountId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    authorizationStateService.evictAccount(tenantId, accountId);
                } catch (RuntimeException exception) {
                    // The bounded malformed fence intentionally keeps authorization fail-closed until expiry.
                    log.error("Account authorization fence cleanup failed: exceptionType={}",
                            exception.getClass().getSimpleName());
                }
            }
        });
    }
}
