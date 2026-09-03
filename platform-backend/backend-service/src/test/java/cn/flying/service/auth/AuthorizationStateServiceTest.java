package cn.flying.service.auth;

import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Tenant;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TenantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Tests fail-closed role, scope, version and tenant lifecycle authorization decisions. */
@ExtendWith(MockitoExtension.class)
class AuthorizationStateServiceTest {

    @Mock
    private AccountMapper accountMapper;
    @Mock
    private TenantMapper tenantMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AuthorizationStateService service;

    /** Creates the service with an explicit bounded cache and enabled platform identity. */
    @BeforeEach
    void setUp() {
        service = new AuthorizationStateService(accountMapper, tenantMapper, redisTemplate);
        ReflectionTestUtils.setField(service, "platformIdentityEnabled", true);
        ReflectionTestUtils.setField(service, "cacheTtl", Duration.ofSeconds(30));
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /** Accepts only an active current tenant account and writes both bounded cache records. */
    @Test
    void acceptsActiveTenantIdentity() {
        when(accountMapper.selectAuthorizationState(42L, 7L)).thenReturn(account(42L, "admin", 1, 5L, 0));
        when(tenantMapper.selectAuthorizationState(42L)).thenReturn(tenant(42L, 1, 9L, 0));

        assertThat(service.isTokenAuthorized(7L, 42L, "admin", "tenant", 5L)).isTrue();

        verify(valueOperations).set("tenant:42:auth:account:7", "admin|1|5|0", Duration.ofSeconds(30));
        verify(valueOperations).set("tenant:42:auth:tenant:current", "1|9|0", Duration.ofSeconds(30));
    }

    /** Platform recovery remains available even when the tenant-zero business row is disabled. */
    @Test
    void platformIdentitySkipsTenantBusinessStatus() {
        when(accountMapper.selectAuthorizationState(0L, 7L))
                .thenReturn(account(0L, "platform_admin", 1, 2L, 0));

        assertThat(service.isTokenAuthorized(7L, 0L, "platform_admin", "platform", 2L)).isTrue();

        verify(tenantMapper, never()).selectAuthorizationState(any());
    }

    /** A legacy tenant role in tenant zero remains tenant-scoped and observes tenant status. */
    @Test
    void legacyTenantZeroRoleStillRequiresActiveTenantState() {
        when(accountMapper.selectAuthorizationState(0L, 8L)).thenReturn(account(0L, "user", 1, 0L, 0));
        when(tenantMapper.selectAuthorizationState(0L)).thenReturn(tenant(0L, 0, 3L, 0));

        assertThat(service.isTokenAuthorized(8L, 0L, "user", "tenant", 0L)).isFalse();
    }

    /** Rejects stale authVersion and role/scope confusion without consulting tenant state. */
    @Test
    void rejectsStaleOrConfusedClaims() {
        when(accountMapper.selectAuthorizationState(42L, 7L)).thenReturn(account(42L, "admin", 1, 6L, 0));

        assertThat(service.isTokenAuthorized(7L, 42L, "admin", "tenant", 5L)).isFalse();
        assertThat(service.isTokenAuthorized(7L, 42L, "admin", "platform", 6L)).isFalse();
        assertThat(service.isTokenAuthorized(7L, 42L, "platform_admin", "platform", 6L)).isFalse();
        verify(tenantMapper, never()).selectAuthorizationState(any());
    }

    /** Redis failures never downgrade to a database-only allow decision. */
    @Test
    void failsClosedWhenRedisIsUnavailable() {
        when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> service.isTokenAuthorized(7L, 42L, "user", "tenant", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");
        verify(accountMapper, never()).selectAuthorizationState(any(), any());
    }

    /** Corrupt cache content is rejected rather than accepted or silently bypassed. */
    @Test
    void failsClosedForCorruptCacheState() {
        when(valueOperations.get("tenant:42:auth:account:7")).thenReturn("corrupt");

        assertThatThrownBy(() -> service.isTokenAuthorized(7L, 42L, "user", "tenant", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid cached account authorization state");
    }

    /** An in-flight version mutation fence never permits a stale token. */
    @Test
    void failsClosedWhileAccountVersionMutationIsInFlight() {
        when(valueOperations.get("tenant:42:auth:account:7")).thenReturn("invalidating");

        assertThatThrownBy(() -> service.isTokenAuthorized(7L, 42L, "user", "tenant", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid cached account authorization state");
        verify(accountMapper, never()).selectAuthorizationState(any(), any());
    }

    /** Mutation fences use the same bounded cache TTL and cannot become permanent locks. */
    @Test
    void writesBoundedFailClosedMutationFences() {
        service.fenceAccount(42L, 7L);
        service.fenceTenant(42L);

        verify(valueOperations).set("tenant:42:auth:account:7", "invalidating", Duration.ofSeconds(30));
        verify(valueOperations).set("tenant:42:auth:tenant:current", "invalidating", Duration.ofSeconds(30));
    }

    /** Disabled platform feature rejects platform claims before any state lookup. */
    @Test
    void rejectsPlatformIdentityWhenFeatureIsDisabled() {
        ReflectionTestUtils.setField(service, "platformIdentityEnabled", false);

        assertThat(service.isTokenAuthorized(7L, 0L, "platform_admin", "platform", 0L)).isFalse();
        verify(accountMapper, never()).selectAuthorizationState(any(), any());
    }

    /** Login rejects accounts that predate the mandatory authorization-version contract. */
    @Test
    void rejectsLoginWithoutAuthorizationVersion() {
        Account account = account(42L, "user", 1, null, 0);

        assertThat(service.isLoginAuthorized(account)).isFalse();

        verify(accountMapper, never()).selectAuthorizationState(any(), any());
        verify(tenantMapper, never()).selectAuthorizationState(any());
    }

    /** Invalid cache TTLs fail application startup rather than weakening freshness. */
    @Test
    void rejectsUnboundedCacheConfiguration() {
        ReflectionTestUtils.setField(service, "cacheTtl", Duration.ofMinutes(10));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateConfiguration"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("security.authorization-state.cache-ttl must be between 1s and 5m");
    }

    /** Rejects malformed immutable identity claims before touching external state. */
    @Test
    void rejectsMalformedIdentityClaimsBeforeStateLookup() {
        assertThat(service.isTokenAuthorized(null, 42L, "user", "tenant", 0L)).isFalse();
        assertThat(service.isTokenAuthorized(0L, 42L, "user", "tenant", 0L)).isFalse();
        assertThat(service.isTokenAuthorized(7L, null, "user", "tenant", 0L)).isFalse();
        assertThat(service.isTokenAuthorized(7L, -1L, "user", "tenant", 0L)).isFalse();
        assertThat(service.isTokenAuthorized(7L, 42L, "user", "tenant", -1L)).isFalse();
        assertThat(service.isTokenAuthorized(7L, 42L, "unknown", null, 0L)).isFalse();

        verifyNoInteractions(accountMapper, tenantMapper);
    }

    /** Reads valid account and tenant facts from the bounded cache without database access. */
    @Test
    void acceptsCachedTenantIdentity() {
        when(valueOperations.get("tenant:42:auth:account:7")).thenReturn("admin|1|5|0");
        when(valueOperations.get("tenant:42:auth:tenant:current")).thenReturn("1|9|0");

        assertThat(service.isTokenAuthorized(7L, 42L, "admin", "tenant", 5L)).isTrue();

        verifyNoInteractions(accountMapper, tenantMapper);
    }

    /** Rejects every inactive, deleted, stale or missing current account representation. */
    @Test
    void rejectsInvalidCurrentAccountStates() {
        when(accountMapper.selectAuthorizationState(42L, 7L)).thenReturn(
                null,
                account(42L, "admin", 0, 5L, 0),
                account(42L, "admin", 1, 5L, 1),
                account(42L, "admin", 1, null, 0),
                account(42L, "admin", 1, -1L, 0),
                account(42L, "user", 1, 5L, 0),
                account(42L, "admin", 1, 6L, 0));

        for (int index = 0; index < 7; index++) {
            assertThat(service.isTokenAuthorized(7L, 42L, "admin", "tenant", 5L)).isFalse();
        }
        verify(tenantMapper, never()).selectAuthorizationState(any());
    }

    /** Rejects every inactive, deleted, invalid-version or missing tenant representation. */
    @Test
    void rejectsInvalidCurrentTenantStates() {
        when(accountMapper.selectAuthorizationState(42L, 7L))
                .thenReturn(account(42L, "admin", 1, 5L, 0));
        when(tenantMapper.selectAuthorizationState(42L)).thenReturn(
                null,
                tenant(42L, 0, 9L, 0),
                tenant(42L, 1, 9L, 1),
                tenant(42L, 1, null, 0),
                tenant(42L, 1, -1L, 0));

        for (int index = 0; index < 5; index++) {
            assertThat(service.isTokenAuthorized(7L, 42L, "admin", "tenant", 5L)).isFalse();
        }
    }

    /** Login and SSE checks reuse the same current-state authorization contract. */
    @Test
    void authorizesLoginAndSseThroughCurrentState() {
        when(accountMapper.selectAuthorizationState(42L, 7L))
                .thenReturn(account(42L, "user", 1, 5L, 0));
        when(tenantMapper.selectAuthorizationState(42L)).thenReturn(tenant(42L, 1, 9L, 0));

        assertThat(service.isLoginAuthorized(account(42L, "user", 1, 5L, 0))).isTrue();
        assertThat(service.isSseIdentityAuthorized(7L, 42L, "user", 5L)).isTrue();
    }

    /** Cache eviction uses tenant-prefixed keys for both account and tenant state. */
    @Test
    void evictsTenantPrefixedAuthorizationState() {
        service.evictAccount(42L, 7L);
        service.evictTenant(42L);

        verify(redisTemplate).delete("tenant:42:auth:account:7");
        verify(redisTemplate).delete("tenant:42:auth:tenant:current");
    }

    /** Numeric corruption in either cache record fails closed instead of querying the database. */
    @Test
    void rejectsNumericCacheCorruption() {
        when(valueOperations.get("tenant:42:auth:account:7")).thenReturn("user|active|0|0");
        assertThatThrownBy(() -> service.isTokenAuthorized(7L, 42L, "user", "tenant", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid cached account authorization state");

        when(valueOperations.get("tenant:42:auth:account:7")).thenReturn("user|1|0|0");
        when(valueOperations.get("tenant:42:auth:tenant:current")).thenReturn("active|1|0");
        assertThatThrownBy(() -> service.isTokenAuthorized(7L, 42L, "user", "tenant", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid cached tenant authorization state");
    }

    /** A tenant cache record with the wrong field count is rejected without fallback. */
    @Test
    void rejectsMalformedTenantCacheShape() {
        when(valueOperations.get("tenant:42:auth:account:7")).thenReturn("user|1|0|0");
        when(valueOperations.get("tenant:42:auth:tenant:current")).thenReturn("1|0");

        assertThatThrownBy(() -> service.isTokenAuthorized(7L, 42L, "user", "tenant", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid cached tenant authorization state");
        verifyNoInteractions(accountMapper, tenantMapper);
    }

    /** Null, too-short and valid cache TTLs enforce the documented startup bounds. */
    @Test
    void validatesAllCacheConfigurationBoundaries() {
        ReflectionTestUtils.setField(service, "cacheTtl", null);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateConfiguration"))
                .isInstanceOf(IllegalStateException.class);

        ReflectionTestUtils.setField(service, "cacheTtl", Duration.ofMillis(999));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateConfiguration"))
                .isInstanceOf(IllegalStateException.class);

        ReflectionTestUtils.setField(service, "cacheTtl", Duration.ofSeconds(1));
        ReflectionTestUtils.invokeMethod(service, "validateConfiguration");
    }

    private Account account(Long tenantId, String role, Integer status, Long authVersion, Integer deleted) {
        Account account = new Account();
        account.setId(7L);
        account.setTenantId(tenantId);
        account.setRole(role);
        account.setStatus(status);
        account.setAuthVersion(authVersion);
        account.setDeleted(deleted);
        return account;
    }

    private Tenant tenant(Long tenantId, Integer status, Long version, Integer deleted) {
        return new Tenant()
                .setId(tenantId)
                .setStatus(status)
                .setVersion(version)
                .setDeleted(deleted);
    }
}
