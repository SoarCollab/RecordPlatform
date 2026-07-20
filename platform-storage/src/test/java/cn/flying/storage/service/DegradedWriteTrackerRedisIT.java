package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用两个独立 Redis 客户端验证 degraded-write revision CAS Lua 的真实原子语义。
 */
@Testcontainers(disabledWithoutDocker = false)
class DegradedWriteTrackerRedisIT {

    private static final int REDIS_PORT = 6379;
    private static final String REDIS_IMAGE = "redis:7.4.9-alpine";
    private static final String ACTIVE_KEY = "storage:degraded_writes";
    private static final String DUE_KEY = "storage:degraded_writes:due";
    private static final String FAILURE_KEY = "storage:degraded_writes:repair_failures";
    private static final String DEAD_LETTER_KEY = "storage:degraded_writes:dead_letter";
    private static final String REVISION_KEY = "storage:degraded_writes:revisions";
    private static final String HASH = "sha256:redis-cas";
    private static final String STORAGE_FIELD = "1:" + HASH;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(REDIS_PORT)
            .waitingFor(Wait.forListeningPort());

    private static LettuceConnectionFactory firstConnectionFactory;
    private static LettuceConnectionFactory secondConnectionFactory;
    private static StringRedisTemplate firstTemplate;
    private static StringRedisTemplate secondTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 建立两个连接工厂，确保测试覆盖跨客户端而非单连接串行行为。
     */
    @BeforeAll
    static void createRedisClients() {
        firstConnectionFactory = connectionFactory();
        secondConnectionFactory = connectionFactory();
        firstTemplate = redisTemplate(firstConnectionFactory);
        secondTemplate = redisTemplate(secondConnectionFactory);
    }

    /**
     * 关闭 Lettuce 资源，避免集成测试 JVM 遗留网络线程。
     */
    @AfterAll
    static void closeRedisClients() {
        if (firstConnectionFactory != null) {
            firstConnectionFactory.destroy();
        }
        if (secondConnectionFactory != null) {
            secondConnectionFactory.destroy();
        }
    }

    /**
     * 每个场景清空共享 Redis，隔离 revision 与 due 状态。
     */
    @BeforeEach
    void flushRedis() {
        try (RedisConnection connection = firstConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    /**
     * 验证 A 读取旧 raw/revision 后，B 的新 placement 提交会让 A 失败关闭且不改写任何结构。
     */
    @Test
    @DisplayName("two clients should reject a stale placement transition without changing B state")
    void shouldRejectStaleClientAfterConcurrentPlacementCommit() throws Exception {
        Map<String, String> oldTargets = targets("domain-a", "node-a", "domain-b", "node-b",
                "domain-c", "node-c");
        Map<String, String> newTargets = targets("domain-a", "node-a", "domain-b", "node-b",
                "domain-d", "node-d");
        DegradedWriteTracker seedTracker = tracker(firstTemplate, fixedManager(oldTargets));
        DegradedWriteTracker secondTracker = tracker(secondTemplate, fixedManager(newTargets));
        seedTracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a"), 1L);

        CountDownLatch staleSnapshotRead = new CountDownLatch(1);
        CountDownLatch continueStalePlanner = new CountDownLatch(1);
        FaultDomainManager blockingManager = mock(FaultDomainManager.class);
        when(blockingManager.getPlannedTargetsSnapshot(HASH)).thenAnswer(invocation -> {
            staleSnapshotRead.countDown();
            await(continueStalePlanner);
            return oldTargets;
        });
        DegradedWriteTracker firstTracker = tracker(firstTemplate, blockingManager);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> staleUpdate = executor.submit(() ->
                    firstTracker.updateMissingDomains(HASH, 1L, "domain-b", "node-b"));
            assertThat(staleSnapshotRead.await(10, TimeUnit.SECONDS)).isTrue();

            secondTracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a"), 1L);
            String committedRaw = secondTemplate.<Object, Object>opsForHash().get(ACTIVE_KEY, STORAGE_FIELD)
                    .toString();
            Double committedDue = secondTemplate.opsForZSet().score(DUE_KEY, STORAGE_FIELD);
            String committedRevision = secondTemplate.<Object, Object>opsForHash()
                    .get(REVISION_KEY, STORAGE_FIELD).toString();

            continueStalePlanner.countDown();
            Throwable failure = catchThrowable(() -> staleUpdate.get(10, TimeUnit.SECONDS));
            assertThat(failure).isInstanceOf(ExecutionException.class);
            assertThat(failure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("revision CAS conflict");
            assertThat(firstTemplate.<Object, Object>opsForHash().get(ACTIVE_KEY, STORAGE_FIELD))
                    .isEqualTo(committedRaw);
            assertThat(firstTemplate.opsForZSet().score(DUE_KEY, STORAGE_FIELD))
                    .isEqualTo(committedDue);
            assertThat(firstTemplate.<Object, Object>opsForHash().get(REVISION_KEY, STORAGE_FIELD))
                    .isEqualTo(committedRevision);
        } finally {
            continueStalePlanner.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 验证删除后以完全相同 JSON 重建仍由持久 revision 拦截，避免 raw-only ABA。
     */
    @Test
    @DisplayName("same raw JSON delete and recreate should still fence the stale client")
    void shouldFenceSameJsonDeleteRecreateAba() throws Exception {
        Map<String, String> plannedTargets = targets(
                "domain-a", "node-a", "domain-b", "node-b", "domain-c", "node-c");
        DegradedWriteTracker seedTracker = tracker(firstTemplate, fixedManager(plannedTargets));
        DegradedWriteTracker secondTracker = tracker(secondTemplate, fixedManager(plannedTargets));
        seedTracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a"), 1L);
        String originalRaw = firstTemplate.<Object, Object>opsForHash().get(ACTIVE_KEY, STORAGE_FIELD).toString();

        CountDownLatch staleSnapshotRead = new CountDownLatch(1);
        CountDownLatch continueStalePlanner = new CountDownLatch(1);
        FaultDomainManager blockingManager = mock(FaultDomainManager.class);
        when(blockingManager.getPlannedTargetsSnapshot(HASH)).thenAnswer(invocation -> {
            staleSnapshotRead.countDown();
            await(continueStalePlanner);
            return plannedTargets;
        });
        DegradedWriteTracker firstTracker = tracker(firstTemplate, blockingManager);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> staleUpdate = executor.submit(() ->
                    firstTracker.updateMissingDomains(HASH, 1L, "domain-b", "node-b"));
            assertThat(staleSnapshotRead.await(10, TimeUnit.SECONDS)).isTrue();

            secondTracker.markSynced(HASH, 1L);
            assertThat(secondTemplate.<Object, Object>opsForHash().get(REVISION_KEY, STORAGE_FIELD))
                    .isEqualTo("2");
            assertThat(executeRawSetTransition(secondTemplate, 2L, originalRaw, 777D)).isEqualTo(1L);

            continueStalePlanner.countDown();
            Throwable failure = catchThrowable(() -> staleUpdate.get(10, TimeUnit.SECONDS));
            assertThat(failure).isInstanceOf(ExecutionException.class);
            assertThat(failure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("revision CAS conflict");
            assertThat(firstTemplate.<Object, Object>opsForHash().get(ACTIVE_KEY, STORAGE_FIELD))
                    .isEqualTo(originalRaw);
            assertThat(firstTemplate.opsForZSet().score(DUE_KEY, STORAGE_FIELD)).isEqualTo(777D);
            assertThat(firstTemplate.<Object, Object>opsForHash().get(REVISION_KEY, STORAGE_FIELD))
                    .isEqualTo("3");
        } finally {
            continueStalePlanner.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 验证达到阈值时 active、due、failure、DLQ 与 revision 在生产 Lua 中一次性完成。
     */
    @Test
    @DisplayName("real Redis should atomically commit the complete dead-letter transition")
    void shouldAtomicallyCommitDeadLetterTransition() throws Exception {
        Map<String, String> plannedTargets = targets(
                "domain-a", "node-a", "domain-b", "node-b", "domain-c", "node-c");
        DegradedWriteTracker tracker = tracker(firstTemplate, fixedManager(plannedTargets));
        tracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a"), 1L);
        tracker.recordRepairFailure(HASH, 1L, "domain-b", "node-b", "first", 3);

        DegradedWriteTracker.RepairFailureDisposition disposition = tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "node-b", "threshold", 2);

        assertThat(disposition).isEqualTo(DegradedWriteTracker.RepairFailureDisposition.DEAD_LETTERED);
        String repairField = repairField("domain-b", "node-b");
        String activeRaw = firstTemplate.<Object, Object>opsForHash().get(ACTIVE_KEY, STORAGE_FIELD).toString();
        DegradedWriteTracker.DegradedWriteRecord active = objectMapper.readValue(
                activeRaw,
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(active.getMissingDomains()).containsExactly("domain-c");
        assertThat(firstTemplate.opsForZSet().score(DUE_KEY, STORAGE_FIELD)).isNotNull();
        assertThat(firstTemplate.opsForHash().hasKey(FAILURE_KEY, repairField)).isFalse();
        assertThat(firstTemplate.opsForHash().hasKey(DEAD_LETTER_KEY, repairField)).isTrue();
        assertThat(firstTemplate.<Object, Object>opsForHash().get(REVISION_KEY, STORAGE_FIELD))
                .isEqualTo("3");
    }

    /**
     * 验证错误 Redis key 类型在任何写入前返回失败，active 与 revision 均保持原值。
     */
    @Test
    @DisplayName("wrong Redis key type should fail before any partial transition")
    void shouldFailBeforeMutationWhenRedisKeyTypeIsWrong() throws Exception {
        Map<String, String> plannedTargets = targets(
                "domain-a", "node-a", "domain-b", "node-b", "domain-c", "node-c");
        DegradedWriteTracker tracker = tracker(firstTemplate, fixedManager(plannedTargets));
        tracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a"), 1L);
        String originalRaw = firstTemplate.<Object, Object>opsForHash().get(ACTIVE_KEY, STORAGE_FIELD).toString();
        String originalRevision = firstTemplate.<Object, Object>opsForHash()
                .get(REVISION_KEY, STORAGE_FIELD).toString();
        firstTemplate.delete(DUE_KEY);
        firstTemplate.opsForValue().set(DUE_KEY, "wrong-type");

        Throwable failure = catchThrowable(() ->
                tracker.updateMissingDomains(HASH, 1L, "domain-b", "node-b"));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid result");
        assertThat(firstTemplate.<Object, Object>opsForHash().get(ACTIVE_KEY, STORAGE_FIELD))
                .isEqualTo(originalRaw);
        assertThat(firstTemplate.<Object, Object>opsForHash().get(REVISION_KEY, STORAGE_FIELD))
                .isEqualTo(originalRevision);
        assertThat(firstTemplate.opsForValue().get(DUE_KEY)).isEqualTo("wrong-type");
        assertThat(firstTemplate.opsForHash().size(FAILURE_KEY)).isZero();
        assertThat(firstTemplate.opsForHash().size(DEAD_LETTER_KEY)).isZero();
    }

    /**
     * 创建注入真实 RedisTemplate、固定拓扑与无阻塞锁替身的 tracker。
     */
    private DegradedWriteTracker tracker(
            StringRedisTemplate template,
            FaultDomainManager faultDomainManager
    ) throws Exception {
        RLock updateLock = mock(RLock.class);
        when(updateLock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getLock(anyString())).thenReturn(updateLock);
        DegradedWriteTracker tracker = new DegradedWriteTracker();
        ReflectionTestUtils.setField(tracker, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(tracker, "stringRedisTemplate", template);
        ReflectionTestUtils.setField(tracker, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(tracker, "faultDomainManager", faultDomainManager);
        ReflectionTestUtils.setField(tracker, "storageProperties", new StorageProperties());
        return tracker;
    }

    /**
     * 创建始终返回同一不可变 placement 快照的故障域管理器。
     */
    private FaultDomainManager fixedManager(Map<String, String> plannedTargets) {
        FaultDomainManager manager = mock(FaultDomainManager.class);
        when(manager.getPlannedTargetsSnapshot(HASH)).thenReturn(Map.copyOf(plannedTargets));
        return manager;
    }

    /**
     * 使用生产 CAS Lua 在删除后按相同 raw JSON 重建 active 与 due。
     */
    private Long executeRawSetTransition(
            StringRedisTemplate template,
            long expectedRevision,
            String activeJson,
            double dueScore
    ) {
        return template.execute(
                DegradedWriteTracker.revisionedTransitionScriptForTesting(),
                List.of(ACTIVE_KEY, DUE_KEY, FAILURE_KEY, DEAD_LETTER_KEY, REVISION_KEY),
                STORAGE_FIELD,
                STORAGE_FIELD,
                String.valueOf(expectedRevision),
                String.valueOf(expectedRevision + 1L),
                STORAGE_FIELD,
                "0",
                "",
                HASH,
                "0",
                "",
                "S",
                activeJson,
                "S",
                String.valueOf(dueScore),
                "0",
                "0",
                "0",
                "0"
        );
    }

    /**
     * 创建最多三组 domain/node 的有序 placement 快照。
     */
    private Map<String, String> targets(String... domainNodePairs) {
        Map<String, String> targets = new LinkedHashMap<>();
        for (int index = 0; index < domainNodePairs.length; index += 2) {
            targets.put(domainNodePairs[index], domainNodePairs[index + 1]);
        }
        return Map.copyOf(targets);
    }

    /**
     * 复现生产 tenant/hash/domain/target 修复 field 编码。
     */
    private String repairField(String domain, String targetNode) {
        String encodedDomain = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(domain.getBytes(StandardCharsets.UTF_8));
        String encodedTarget = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(targetNode.getBytes(StandardCharsets.UTF_8));
        return STORAGE_FIELD + ":" + encodedDomain + ":" + encodedTarget;
    }

    /**
     * 等待并发测试闸门，超时或中断均转为明确测试失败。
     */
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for degraded-write CAS gate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for degraded-write CAS gate", e);
        }
    }

    /**
     * 创建连接本测试 Redis 容器的 Lettuce 工厂。
     */
    private static LettuceConnectionFactory connectionFactory() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT)
        );
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    /**
     * 创建使用字符串序列化器的 RedisTemplate。
     */
    private static StringRedisTemplate redisTemplate(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }
}
