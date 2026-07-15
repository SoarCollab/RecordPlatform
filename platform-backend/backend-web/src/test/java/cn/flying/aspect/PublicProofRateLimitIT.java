package cn.flying.aspect;

import cn.flying.common.annotation.RateLimit;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.config.RateLimitClientIpProperties;
import cn.flying.controller.PublicProofController;
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

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 公共 proof 共享限流桶的真实 Redis 集成测试。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Public proof Redis rate-limit integration tests")
class PublicProofRateLimitIT {

    private static final String PEER_A = "198.51.100.24";
    private static final String PEER_B = "198.51.100.25";
    private static final String PEER_A_KEY =
            "rate:limit:public:proof-verification:v2:ip:" + PEER_A;

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
     * 清空真实 Redis，并构造仅使用 direct peer 的生产切面。
     */
    @BeforeEach
    void setUp() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        aspect = new RateLimitAspect(
                redisTemplate,
                new TrustedClientIpResolver(
                        new RateLimitClientIpProperties(), "none", "", ""));
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
     * 验证两个入口累计共享 120 次、121 次拒绝、TTL 生效且不同 peer 独立。
     */
    @Test
    @DisplayName("should enforce shared 120-per-minute bucket across both endpoints")
    void shouldEnforceSharedBoundaryAcrossBothEndpoints() throws Throwable {
        RateLimit statusLimit = PublicProofController.class
                .getMethod("getProofStatus", String.class)
                .getAnnotation(RateLimit.class);
        RateLimit signingKeyLimit = PublicProofController.class
                .getMethod("getProofSigningKey", String.class, Integer.class)
                .getAnnotation(RateLimit.class);
        ProceedingJoinPoint statusJoinPoint = mock(ProceedingJoinPoint.class);
        ProceedingJoinPoint signingKeyJoinPoint = mock(ProceedingJoinPoint.class);
        bindPeer(PEER_A);

        for (int index = 0; index < 60; index++) {
            aspect.around(statusJoinPoint, statusLimit);
            aspect.around(signingKeyJoinPoint, signingKeyLimit);
        }

        GeneralException denied = assertThrows(
                GeneralException.class,
                () -> aspect.around(statusJoinPoint, statusLimit));
        assertSame(ResultEnum.PERMISSION_LIMIT, denied.getResultEnum());
        verify(statusJoinPoint, times(60)).proceed();
        verify(signingKeyJoinPoint, times(60)).proceed();
        assertThat(redisTemplate.opsForValue().get(PEER_A_KEY)).isEqualTo("120");
        assertThat(redisTemplate.getExpire(PEER_A_KEY, TimeUnit.SECONDS)).isBetween(1L, 60L);

        bindPeer(PEER_B);
        aspect.around(statusJoinPoint, statusLimit);
        assertThat(redisTemplate.opsForValue().get(
                "rate:limit:public:proof-verification:v2:ip:" + PEER_B)).isEqualTo("1");
    }

    /**
     * 绑定指定 direct peer，并加入不会被默认模式信任的伪造转发头。
     */
    private void bindPeer(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader("X-Real-IP", "203.0.113.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
