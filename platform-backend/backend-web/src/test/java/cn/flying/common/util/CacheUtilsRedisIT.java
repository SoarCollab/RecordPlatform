package cn.flying.common.util;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CacheUtils 会话生命周期和分片证据 Lua 的真实 Redis 集成测试。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("CacheUtils Redis Lua integration tests")
class CacheUtilsRedisIT {

    private static final String STATE_KEY = "upload:state:session-a";
    private static final String PAUSED_SET_KEY = "upload:paused";
    private static final String PAUSED_AT_KEY = "upload:paused-at:session-a";
    private static final String ACTIVITY_AT_KEY = "upload:activity-at:session-a";
    private static final String AUXILIARY_KEY = "upload:proof:session-a";
    private static final String MEMBER = "session-a";
    private static final long TTL_SECONDS = 300L;
    private static final List<String> TERMINAL_STATUSES =
            List.of("completed", "failed", "cancelled");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private CacheUtils cacheUtils;

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
     * 清空真实 Redis，并把待测工具绑定到真实字符串模板。
     */
    @BeforeEach
    void setUp() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        cacheUtils = new CacheUtils();
        ReflectionTestUtils.setField(cacheUtils, "stringRedisTemplate", redisTemplate);
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
     * 验证 pause、touch、resume 在 Redis 内线性化更新成员、时间证据和 TTL。
     */
    @Test
    @DisplayName("should execute the pause touch resume lifecycle atomically")
    void shouldExecutePauseTouchResumeLifecycleAtomically() {
        String stateJson = "{\"status\":\"uploading\",\"clientId\":\"session-a\"}";
        redisTemplate.opsForValue().set(STATE_KEY, stateJson, 60, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(AUXILIARY_KEY, "proof", 60, TimeUnit.SECONDS);

        long pauseResult = cacheUtils.atomicPauseSessionIfJsonStatusAllowed(
                STATE_KEY,
                PAUSED_SET_KEY,
                PAUSED_AT_KEY,
                ACTIVITY_AT_KEY,
                MEMBER,
                TERMINAL_STATUSES,
                1_700_000_000_000L,
                TTL_SECONDS,
                List.of(AUXILIARY_KEY));

        assertThat(pauseResult).isEqualTo(1L);
        assertThat(redisTemplate.opsForSet().members(PAUSED_SET_KEY)).containsExactly(MEMBER);
        assertThat(redisTemplate.opsForValue().get(PAUSED_AT_KEY)).isEqualTo("1700000000000");
        assertThat(redisTemplate.opsForValue().get(ACTIVITY_AT_KEY)).isEqualTo("1700000000000");
        assertThat(redisTemplate.getExpire(STATE_KEY, TimeUnit.SECONDS)).isBetween(61L, TTL_SECONDS);
        assertThat(redisTemplate.getExpire(AUXILIARY_KEY, TimeUnit.SECONDS)).isBetween(61L, TTL_SECONDS);
        assertThat(redisTemplate.getExpire(PAUSED_AT_KEY, TimeUnit.SECONDS)).isBetween(61L, TTL_SECONDS);
        assertThat(redisTemplate.getExpire(ACTIVITY_AT_KEY, TimeUnit.SECONDS)).isBetween(61L, TTL_SECONDS);

        long touchResult = cacheUtils.atomicTouchSessionIfJsonStatusAllowed(
                STATE_KEY,
                ACTIVITY_AT_KEY,
                MEMBER,
                TERMINAL_STATUSES,
                1_700_000_000_100L,
                TTL_SECONDS,
                List.of(AUXILIARY_KEY));
        assertThat(touchResult).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(ACTIVITY_AT_KEY)).isEqualTo("1700000000100");
        assertThat(redisTemplate.getExpire(ACTIVITY_AT_KEY, TimeUnit.SECONDS)).isBetween(61L, TTL_SECONDS);

        long resumeResult = cacheUtils.atomicResumeSessionIfJsonStatusAllowed(
                STATE_KEY,
                PAUSED_SET_KEY,
                PAUSED_AT_KEY,
                ACTIVITY_AT_KEY,
                MEMBER,
                TERMINAL_STATUSES,
                1_700_000_000_200L,
                TTL_SECONDS,
                List.of(AUXILIARY_KEY));

        assertThat(resumeResult).isEqualTo(2L);
        assertThat(redisTemplate.opsForSet().isMember(PAUSED_SET_KEY, MEMBER)).isFalse();
        assertThat(redisTemplate.hasKey(PAUSED_AT_KEY)).isFalse();
        assertThat(redisTemplate.opsForValue().get(ACTIVITY_AT_KEY)).isEqualTo("1700000000200");
        assertThat(redisTemplate.getExpire(ACTIVITY_AT_KEY, TimeUnit.SECONDS)).isBetween(61L, TTL_SECONDS);
        assertThat(redisTemplate.opsForValue().get(STATE_KEY)).isEqualTo(stateJson);
    }

    /**
     * 验证缺失、终态、损坏 JSON 和身份错配均失败关闭且不制造迟到活动证据。
     */
    @Test
    @DisplayName("should fail closed for missing terminal damaged and mismatched state")
    void shouldFailClosedForMissingTerminalDamagedAndMismatchedState() {
        assertThat(pause(1_700_000_000_000L)).isEqualTo(-1L);
        assertThat(touch(1_700_000_000_010L)).isEqualTo(-1L);
        assertThat(resume(1_700_000_000_020L)).isEqualTo(-1L);

        redisTemplate.opsForValue().set(
                STATE_KEY,
                "{\"status\":\"COMPLETED\",\"clientId\":\"session-a\"}",
                60,
                TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(AUXILIARY_KEY, "proof", 60, TimeUnit.SECONDS);
        assertThat(pause(1_700_000_000_030L)).isZero();
        assertThat(touch(1_700_000_000_040L)).isZero();
        redisTemplate.opsForSet().add(PAUSED_SET_KEY, MEMBER);
        redisTemplate.opsForValue().set(PAUSED_AT_KEY, "1699999999999");
        assertThat(resume(1_700_000_000_050L)).isZero();
        assertThat(redisTemplate.opsForSet().isMember(PAUSED_SET_KEY, MEMBER)).isFalse();
        assertThat(redisTemplate.hasKey(PAUSED_AT_KEY)).isFalse();
        assertThat(redisTemplate.hasKey(ACTIVITY_AT_KEY)).isFalse();
        assertThat(redisTemplate.getExpire(STATE_KEY, TimeUnit.SECONDS)).isBetween(1L, 60L);
        assertThat(redisTemplate.getExpire(AUXILIARY_KEY, TimeUnit.SECONDS)).isBetween(1L, 60L);

        redisTemplate.opsForValue().set(
                STATE_KEY,
                "{\"status\":\"uploading\",\"clientId\":\"session-a\"}",
                60,
                TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(PAUSED_SET_KEY, "wrong-type");
        assertThatThrownBy(() -> pause(1_700_000_000_055L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("证据键类型损坏");
        assertThat(redisTemplate.getExpire(STATE_KEY, TimeUnit.SECONDS)).isBetween(1L, 60L);
        assertThat(redisTemplate.hasKey(PAUSED_AT_KEY)).isFalse();
        assertThat(redisTemplate.hasKey(ACTIVITY_AT_KEY)).isFalse();
        redisTemplate.delete(PAUSED_SET_KEY);

        redisTemplate.opsForHash().put(ACTIVITY_AT_KEY, "field", "wrong-type");
        assertThatThrownBy(() -> touch(1_700_000_000_056L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("证据键类型损坏");
        assertThat(redisTemplate.getExpire(STATE_KEY, TimeUnit.SECONDS)).isBetween(1L, 60L);
        assertThat(redisTemplate.opsForHash().get(ACTIVITY_AT_KEY, "field")).isEqualTo("wrong-type");
        redisTemplate.delete(ACTIVITY_AT_KEY);

        redisTemplate.opsForSet().add(PAUSED_SET_KEY, MEMBER);
        redisTemplate.opsForHash().put(PAUSED_AT_KEY, "field", "wrong-type");
        assertThatThrownBy(() -> resume(1_700_000_000_057L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("证据键类型损坏");
        assertThat(redisTemplate.opsForSet().isMember(PAUSED_SET_KEY, MEMBER)).isTrue();
        assertThat(redisTemplate.opsForHash().get(PAUSED_AT_KEY, "field")).isEqualTo("wrong-type");
        redisTemplate.delete(List.of(PAUSED_SET_KEY, PAUSED_AT_KEY));

        redisTemplate.opsForValue().set(STATE_KEY, "{not-json");
        assertThatThrownBy(() -> pause(1_700_000_000_060L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON 主状态损坏");
        assertThat(redisTemplate.opsForSet().isMember(PAUSED_SET_KEY, MEMBER)).isFalse();

        redisTemplate.opsForValue().set(
                STATE_KEY,
                "{\"status\":\"uploading\",\"clientId\":\"another-session\"}");
        assertThatThrownBy(() -> touch(1_700_000_000_070L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON 主状态损坏");
        assertThat(redisTemplate.hasKey(ACTIVITY_AT_KEY)).isFalse();
    }

    /**
     * 验证 Set/Hash 首次写入、同值幂等与冲突失败在真实 Redis 中保持一致。
     */
    @Test
    @DisplayName("should keep set and hash consistent across idempotence and conflict")
    void shouldKeepSetAndHashConsistentAcrossIdempotenceAndConflict() {
        String setKey = "upload:parts:session-a";
        String hashKey = "upload:part-hashes:session-a";

        assertThat(cacheUtils.atomicAddToSetAndHash(
                setKey, "part-1", hashKey, "1", "sha256:stable")).isTrue();
        assertThat(cacheUtils.atomicAddToSetAndHash(
                setKey, "part-1", hashKey, "1", "sha256:stable")).isTrue();

        assertThatThrownBy(() -> cacheUtils.atomicAddToSetAndHash(
                setKey, "part-2", hashKey, "1", "sha256:conflict"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不同稳定值");
        assertThat(redisTemplate.opsForSet().members(setKey)).isEqualTo(Set.of("part-1"));
        assertThat(redisTemplate.opsForHash().get(hashKey, "1")).isEqualTo("sha256:stable");
    }

    /**
     * 验证 Set/Hash 类型污染在任何写入前失败，不会留下半完成的分片证据。
     */
    @Test
    @DisplayName("should reject corrupted set and hash types before writing either structure")
    void shouldRejectCorruptedSetAndHashTypesBeforeWritingEitherStructure() {
        String setKey = "upload:parts:session-a";
        String hashKey = "upload:part-hashes:session-a";

        redisTemplate.opsForValue().set(setKey, "wrong-type");
        assertThatThrownBy(() -> cacheUtils.atomicAddToSetAndHash(
                setKey, "part-1", hashKey, "1", "sha256:stable"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Set 键类型损坏");
        assertThat(redisTemplate.opsForHash().get(hashKey, "1")).isNull();
        assertThat(redisTemplate.opsForValue().get(setKey)).isEqualTo("wrong-type");

        redisTemplate.delete(List.of(setKey, hashKey));
        redisTemplate.opsForValue().set(hashKey, "wrong-type");
        assertThatThrownBy(() -> cacheUtils.atomicAddToSetAndHash(
                setKey, "part-1", hashKey, "1", "sha256:stable"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Hash 键类型损坏");
        assertThat(redisTemplate.hasKey(setKey)).isFalse();
        assertThat(redisTemplate.opsForValue().get(hashKey)).isEqualTo("wrong-type");
    }

    /**
     * 调用真实 pause Lua，统一复用生产参数。
     */
    private long pause(long timestamp) {
        return cacheUtils.atomicPauseSessionIfJsonStatusAllowed(
                STATE_KEY,
                PAUSED_SET_KEY,
                PAUSED_AT_KEY,
                ACTIVITY_AT_KEY,
                MEMBER,
                TERMINAL_STATUSES,
                timestamp,
                TTL_SECONDS,
                List.of(AUXILIARY_KEY));
    }

    /**
     * 调用真实 touch Lua，统一复用生产参数。
     */
    private long touch(long timestamp) {
        return cacheUtils.atomicTouchSessionIfJsonStatusAllowed(
                STATE_KEY,
                ACTIVITY_AT_KEY,
                MEMBER,
                TERMINAL_STATUSES,
                timestamp,
                TTL_SECONDS,
                List.of(AUXILIARY_KEY));
    }

    /**
     * 调用真实 resume Lua，统一复用生产参数。
     */
    private long resume(long timestamp) {
        return cacheUtils.atomicResumeSessionIfJsonStatusAllowed(
                STATE_KEY,
                PAUSED_SET_KEY,
                PAUSED_AT_KEY,
                ACTIVITY_AT_KEY,
                MEMBER,
                TERMINAL_STATUSES,
                timestamp,
                TTL_SECONDS,
                List.of(AUXILIARY_KEY));
    }
}
