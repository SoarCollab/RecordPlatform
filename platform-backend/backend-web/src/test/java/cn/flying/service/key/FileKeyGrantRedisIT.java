package cn.flying.service.key;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FriendFileShareMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 下载密钥 grant 的真实 Redis Lua 并发与防重放集成测试。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("File key grant Redis integration tests")
class FileKeyGrantRedisIT {

    private static final Long TENANT_ID = 7L;
    private static final Long FILE_ID = 11L;
    private static final Long ACTOR_ID = 13L;
    private static final String FILE_HASH = "hash-redis-it";
    private static final String SESSION_ID = "redis-download-session-12345";
    private static final String INITIAL_KEY = "cmVkaXMtaXQtZmlsZS1rZXk=";
    private static final Instant NOW = Instant.parse("2026-07-27T08:00:00Z");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private FileMapper fileMapper;
    private FileKeyEnvelopeService envelopeService;
    private FileKeyGrantService grantService;
    private File file;
    private FileKeyGrantEnvelopeBinding binding;

    /**
     * 连接 Testcontainers 提供的真实 Redis。
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
     * 清空 Redis 并创建固定 grant 绑定及 mock 信封解封器。
     */
    @BeforeEach
    void setUp() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        file = new File()
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(ACTOR_ID)
                .setVersion(3)
                .setFileHash(FILE_HASH);
        binding = new FileKeyGrantEnvelopeBinding(
                101L, TENANT_ID, FILE_ID, 3, FILE_HASH,
                FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER, ACTOR_ID, 4,
                "RP-AES256-GCM-FRAMED-V2", "UNSIGNED-V1", "NONE-V1",
                "RP-MERKLE-SHA256-V1", "FRAMED_AEAD_V2", "vault-transit",
                1, "9", false);
        fileMapper = mock(FileMapper.class);
        envelopeService = mock(FileKeyEnvelopeService.class);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(envelopeService.unwrapGrantBinding(eq(file), eq(binding), eq(ACTOR_ID), any()))
                .thenReturn(Optional.of(INITIAL_KEY));

        FileKeyDeliveryProperties properties = new FileKeyDeliveryProperties();
        properties.setGrantTtl(Duration.ofSeconds(60));
        properties.setRetryWindow(Duration.ofSeconds(10));
        properties.setMaxSameSessionRetries(1);
        grantService = new FileKeyGrantService(
                redisTemplate, fileMapper, mock(FileShareMapper.class), mock(FriendFileShareMapper.class),
                envelopeService, properties,
                new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        TenantContext.setTenantId(TENANT_ID);
    }

    /**
     * 释放 Lettuce 连接和当前线程租户上下文。
     */
    @AfterAll
    static void disconnectRedis() {
        TenantContext.clear();
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /**
     * 验证并发首次消费只允许一次成功和一次同会话重试，后续及跨会话重放均失败。
     */
    @Test
    @DisplayName("should atomically bound concurrent consume and keep secrets out of Redis")
    void shouldAtomicallyBoundConcurrentConsumeAndKeepSecretsOutOfRedis() throws Exception {
        var grant = grantService.issue(new FileKeyGrantIssueContext(
                file, binding, FileKeyGrantAccessKind.OWNER, ACTOR_ID, null, SESSION_ID));

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Callable<ConsumeOutcome>> consumers = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            consumers.add(() -> consume(grant.reference(), SESSION_ID));
        }
        List<ConsumeOutcome> outcomes;
        try {
            outcomes = executor.invokeAll(consumers).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
        } finally {
            executor.shutdownNow();
        }

        assertThat(outcomes).filteredOn(ConsumeOutcome::success).hasSize(2);
        assertThat(outcomes).filteredOn(outcome -> !outcome.success())
                .extracting(ConsumeOutcome::reason)
                .containsExactly("GRANT_REPLAYED");
        assertThat(consume(grant.reference(), SESSION_ID))
                .isEqualTo(new ConsumeOutcome(false, "GRANT_REPLAYED"));
        assertThat(consume(grant.reference(), "other-download-session-1234"))
                .isEqualTo(new ConsumeOutcome(false, "SESSION_MISMATCH"));

        Set<String> keys = redisTemplate.keys("file:key-grant:v1:*");
        assertThat(keys).hasSize(1);
        String redisKey = keys.iterator().next();
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(redisKey);
        assertThat(redisKey).doesNotContain(grant.reference());
        assertThat(fields.values()).noneMatch(value -> String.valueOf(value).contains(grant.reference()))
                .noneMatch(value -> String.valueOf(value).contains(SESSION_ID))
                .noneMatch(value -> String.valueOf(value).contains(INITIAL_KEY));
        assertThat(fields).containsEntry("state", "CONSUMED")
                .containsEntry("retryCount", "1");
    }

    /**
     * 在独立线程设置租户上下文并把消费结果收敛为稳定枚举。
     */
    private ConsumeOutcome consume(String reference, String sessionId) {
        TenantContext.setTenantId(TENANT_ID);
        try {
            var material = grantService.consumeAuthenticated(
                    reference, sessionId, ACTOR_ID);
            return new ConsumeOutcome(INITIAL_KEY.equals(material.initialKey()), null);
        } catch (GeneralException exception) {
            return new ConsumeOutcome(false, String.valueOf(exception.getData()));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 并发消费结果。
     */
    private record ConsumeOutcome(boolean success, String reason) {
    }
}
