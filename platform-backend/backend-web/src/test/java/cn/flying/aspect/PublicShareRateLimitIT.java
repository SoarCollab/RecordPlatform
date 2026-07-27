package cn.flying.aspect;

import cn.flying.common.annotation.RateLimit;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.config.RateLimitClientIpProperties;
import cn.flying.controller.ShareRestController;
import cn.flying.security.TrustedClientIpResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 匿名公开分享端点共享限流桶的真实 Redis 集成测试。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Public share Redis rate-limit integration tests")
class PublicShareRateLimitIT {

    private static final String PEER_A = "198.51.100.44";
    private static final String PEER_B = "198.51.100.45";
    private static final String RATE_KEY_PREFIX = "rate:limit:public:share-access:v2:ip:";
    private static final String PEER_A_KEY = RATE_KEY_PREFIX + PEER_A;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RateLimitAspect aspect;

    /**
     * 连接 Testcontainers 提供的真实 Redis，并初始化字符串模板。
     */
    @BeforeAll
    static void connectRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    /**
     * 清空真实 Redis，并构造只信任 direct peer 的生产切面。
     */
    @BeforeEach
    void setUp() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        aspect = new RateLimitAspect(
                redisTemplate,
                new TrustedClientIpResolver(new RateLimitClientIpProperties(), "none", "", ""));
    }

    /**
     * 清理当前线程的 servlet 请求上下文。
     */
    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 释放 Lettuce 连接资源。
     */
    @AfterAll
    static void disconnectRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /**
     * 验证两个公开文件入口跨任意租户头共享 30 次配额，第 31 次拒绝且不同 peer 独立。
     */
    @Test
    @DisplayName("should enforce one tenantless 30-per-minute bucket across both public share endpoints")
    void shouldEnforceOneTenantlessBucketAcrossBothEndpoints() throws Throwable {
        RateLimit downloadLimit = ShareRestController.class
                .getMethod("publicDownload", String.class, String.class, jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(RateLimit.class);
        RateLimit decryptLimit = ShareRestController.class
                .getMethod("publicDecryptInfo", String.class, String.class, String.class, String.class,
                        jakarta.servlet.http.HttpServletRequest.class,
                        jakarta.servlet.http.HttpServletResponse.class)
                .getAnnotation(RateLimit.class);
        ProceedingJoinPoint downloadJoinPoint = mock(ProceedingJoinPoint.class);
        ProceedingJoinPoint decryptJoinPoint = mock(ProceedingJoinPoint.class);

        for (int index = 0; index < 15; index++) {
            bindPeer(PEER_A, index % 2 == 0 ? "42" : "43");
            aspect.around(downloadJoinPoint, downloadLimit);
            bindPeer(PEER_A, index % 3 == 0 ? "invalid" : "0");
            aspect.around(decryptJoinPoint, decryptLimit);
        }

        bindPeer(PEER_A, "999");
        GeneralException denied = assertThrows(
                GeneralException.class,
                () -> aspect.around(downloadJoinPoint, downloadLimit));

        assertSame(ResultEnum.PERMISSION_LIMIT, denied.getResultEnum());
        verify(downloadJoinPoint, times(15)).proceed();
        verify(decryptJoinPoint, times(15)).proceed();
        assertThat(redisTemplate.opsForValue().get(PEER_A_KEY)).isEqualTo("30");
        assertThat(redisTemplate.getExpire(PEER_A_KEY, TimeUnit.SECONDS)).isBetween(1L, 60L);
        assertThat(redisTemplate.keys("rate:limit:public:share-access:*")).containsExactly(PEER_A_KEY);

        bindPeer(PEER_B, "42");
        aspect.around(downloadJoinPoint, downloadLimit);
        assertThat(redisTemplate.opsForValue().get(RATE_KEY_PREFIX + PEER_B)).isEqualTo("1");
        assertThat(redisTemplate.keys("rate:limit:public:share-access:*")).isEqualTo(Set.of(
                PEER_A_KEY,
                RATE_KEY_PREFIX + PEER_B));
    }

    /**
     * 绑定 direct peer、伪造转发头及任意租户头，模拟修复前可拆桶的恶意请求。
     *
     * @param peer socket 对端地址
     * @param tenantHeader 调用者提供的租户头
     */
    private void bindPeer(String peer, String tenantHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Tenant-ID", tenantHeader);
        request.addHeader("X-Forwarded-For", "203.0.113.91");
        request.addHeader("X-Real-IP", "203.0.113.92");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
