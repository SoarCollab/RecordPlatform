package cn.flying.service.auth;

import cn.flying.common.constant.UserRole;
import cn.flying.common.util.Const;
import cn.flying.common.util.TenantKeyUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Tenant;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.Objects;

/**
 * Resolves current account and tenant authorization facts and compares them with token claims.
 */
@Service
@RequiredArgsConstructor
public class AuthorizationStateService {

    private static final int ACTIVE = 1;
    private static final int NOT_DELETED = 0;

    private final AccountMapper accountMapper;
    private final TenantMapper tenantMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${security.platform-identity.enabled:false}")
    private boolean platformIdentityEnabled;

    @Value("${security.authorization-state.cache-ttl:30s}")
    private Duration cacheTtl;

    /** Validates the bounded cache freshness contract at application startup. */
    @PostConstruct
    void validateConfiguration() {
        if (cacheTtl == null || cacheTtl.compareTo(Duration.ofSeconds(1)) < 0
                || cacheTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("security.authorization-state.cache-ttl must be between 1s and 5m");
        }
    }

    /**
     * Validates all current-state claims needed by a protected JWT request.
     * Infrastructure failures intentionally propagate so callers fail closed.
     */
    public boolean isTokenAuthorized(
            Long accountId,
            Long tenantId,
            String tokenRole,
            String tokenScope,
            Long tokenAuthVersion) {
        if (accountId == null || accountId <= 0 || tenantId == null || tenantId < 0
                || tokenAuthVersion == null || tokenAuthVersion < 0) {
            return false;
        }
        UserRole role = UserRole.getRole(tokenRole);
        if (role == UserRole.ROLE_NOOP || !Objects.equals(role.scope(), tokenScope)) {
            return false;
        }
        if (role == UserRole.ROLE_PLATFORM_ADMIN) {
            if (!platformIdentityEnabled || tenantId != 0L) {
                return false;
            }
        } else if (!role.isTenantRole()) {
            return false;
        }

        AccountState account = loadAccountState(tenantId, accountId);
        if (account == null
                || !Objects.equals(account.status(), ACTIVE)
                || !Objects.equals(account.deleted(), NOT_DELETED)
                || account.authVersion() == null
                || account.authVersion() < 0
                || !Objects.equals(account.role(), tokenRole)
                || !Objects.equals(account.authVersion(), tokenAuthVersion)) {
            return false;
        }

        if (role == UserRole.ROLE_PLATFORM_ADMIN) {
            return true;
        }
        TenantState tenant = loadTenantState(tenantId);
        return tenant != null
                && Objects.equals(tenant.status(), ACTIVE)
                && Objects.equals(tenant.deleted(), NOT_DELETED)
                && tenant.version() != null
                && tenant.version() >= 0;
    }

    /**
     * Validates an account returned by password authentication before token issuance.
     */
    public boolean isLoginAuthorized(Account account) {
        if (account == null || account.getAuthVersion() == null || account.getAuthVersion() < 0) {
            return false;
        }
        UserRole role = UserRole.getRole(account.getRole());
        return isTokenAuthorized(
                account.getId(),
                account.getTenantId(),
                account.getRole(),
                role.scope(),
                account.getAuthVersion());
    }

    /**
     * Revalidates an SSE identity after its one-time Redis token is consumed.
     */
    public boolean isSseIdentityAuthorized(Long accountId, Long tenantId, String role, Long tokenAuthVersion) {
        return isTokenAuthorized(accountId, tenantId, role, "tenant", tokenAuthVersion);
    }

    /** Evicts one account authorization entry after a committed identity mutation. */
    public void evictAccount(Long tenantId, Long accountId) {
        redisTemplate.delete(accountKey(tenantId, accountId));
    }

    /** Replaces cached account state with a bounded fail-closed fence until the mutation completes. */
    public void fenceAccount(Long tenantId, Long accountId) {
        redisTemplate.opsForValue().set(accountKey(tenantId, accountId), "invalidating", cacheTtl);
    }

    /** Evicts one tenant authorization entry after a committed lifecycle mutation. */
    public void evictTenant(Long tenantId) {
        redisTemplate.delete(tenantKey(tenantId));
    }

    /** Replaces cached tenant state with a bounded fail-closed fence until the mutation completes. */
    public void fenceTenant(Long tenantId) {
        redisTemplate.opsForValue().set(tenantKey(tenantId), "invalidating", cacheTtl);
    }

    private AccountState loadAccountState(Long tenantId, Long accountId) {
        String key = accountKey(tenantId, accountId);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return parseAccountState(cached);
        }
        Account account = accountMapper.selectAuthorizationState(tenantId, accountId);
        if (account == null) {
            return null;
        }
        AccountState state = new AccountState(
                account.getRole(),
                account.getStatus(),
                account.getAuthVersion(),
                account.getDeleted());
        redisTemplate.opsForValue().set(key, formatAccountState(state), cacheTtl);
        return state;
    }

    private TenantState loadTenantState(Long tenantId) {
        String key = tenantKey(tenantId);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return parseTenantState(cached);
        }
        Tenant tenant = tenantMapper.selectAuthorizationState(tenantId);
        if (tenant == null) {
            return null;
        }
        TenantState state = new TenantState(tenant.getStatus(), tenant.getVersion(), tenant.getDeleted());
        redisTemplate.opsForValue().set(key, formatTenantState(state), cacheTtl);
        return state;
    }

    private String accountKey(Long tenantId, Long accountId) {
        return TenantKeyUtils.tenantKey(Const.AUTH_ACCOUNT_STATE_PREFIX + accountId, tenantId);
    }

    private String tenantKey(Long tenantId) {
        return TenantKeyUtils.tenantKey(Const.AUTH_TENANT_STATE_PREFIX + "current", tenantId);
    }

    private String formatAccountState(AccountState state) {
        return state.role() + "|" + state.status() + "|" + state.authVersion() + "|" + state.deleted();
    }

    private AccountState parseAccountState(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 4) {
            throw new IllegalStateException("Invalid cached account authorization state");
        }
        try {
            return new AccountState(parts[0], Integer.valueOf(parts[1]), Long.valueOf(parts[2]), Integer.valueOf(parts[3]));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid cached account authorization state", exception);
        }
    }

    private String formatTenantState(TenantState state) {
        return state.status() + "|" + state.version() + "|" + state.deleted();
    }

    private TenantState parseTenantState(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3) {
            throw new IllegalStateException("Invalid cached tenant authorization state");
        }
        try {
            return new TenantState(Integer.valueOf(parts[0]), Long.valueOf(parts[1]), Integer.valueOf(parts[2]));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid cached tenant authorization state", exception);
        }
    }

    private record AccountState(String role, Integer status, Long authVersion, Integer deleted) {
    }

    private record TenantState(Integer status, Long version, Integer deleted) {
    }
}
