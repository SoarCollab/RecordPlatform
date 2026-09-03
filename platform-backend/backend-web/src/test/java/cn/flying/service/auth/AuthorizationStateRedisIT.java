package cn.flying.service.auth;

import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Tenant;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TenantMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Proves authVersion and tenant-status cache invalidation against a real Redis server. */
@Testcontainers(disabledWithoutDocker = false)
class AuthorizationStateRedisIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    /** Connects a real StringRedisTemplate to the isolated container. */
    @BeforeAll
    static void connectRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    /** Clears prior test state. */
    @BeforeEach
    void clearRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    /** Releases Lettuce resources. */
    @AfterAll
    static void disconnectRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** Eviction makes account and tenant authorization mutations visible on the next request. */
    @Test
    void observesVersionAndTenantStatusAfterExplicitEviction() {
        AccountMapper accountMapper = mock(AccountMapper.class);
        TenantMapper tenantMapper = mock(TenantMapper.class);
        AtomicReference<Account> account = new AtomicReference<>(account(0L));
        AtomicReference<Tenant> tenant = new AtomicReference<>(tenant(1));
        when(accountMapper.selectAuthorizationState(42L, 7L)).thenAnswer(invocation -> account.get());
        when(tenantMapper.selectAuthorizationState(42L)).thenAnswer(invocation -> tenant.get());

        AuthorizationStateService service = new AuthorizationStateService(accountMapper, tenantMapper, redisTemplate);
        ReflectionTestUtils.setField(service, "platformIdentityEnabled", true);
        ReflectionTestUtils.setField(service, "cacheTtl", Duration.ofMinutes(1));

        assertThat(service.isTokenAuthorized(7L, 42L, "user", "tenant", 0L)).isTrue();
        account.set(account(1L));
        assertThat(service.isTokenAuthorized(7L, 42L, "user", "tenant", 0L)).isTrue();
        service.evictAccount(42L, 7L);
        assertThat(service.isTokenAuthorized(7L, 42L, "user", "tenant", 0L)).isFalse();
        assertThat(service.isTokenAuthorized(7L, 42L, "user", "tenant", 1L)).isTrue();

        tenant.set(tenant(0));
        assertThat(service.isTokenAuthorized(7L, 42L, "user", "tenant", 1L)).isTrue();
        service.evictTenant(42L);
        assertThat(service.isTokenAuthorized(7L, 42L, "user", "tenant", 1L)).isFalse();
    }

    private Account account(Long authVersion) {
        Account account = new Account();
        account.setId(7L);
        account.setTenantId(42L);
        account.setRole("user");
        account.setStatus(1);
        account.setAuthVersion(authVersion);
        account.setDeleted(0);
        return account;
    }

    private Tenant tenant(Integer status) {
        return new Tenant().setId(42L).setStatus(status).setVersion(1L).setDeleted(0);
    }
}
