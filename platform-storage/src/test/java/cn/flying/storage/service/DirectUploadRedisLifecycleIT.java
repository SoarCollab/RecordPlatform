package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 Redis 与两个独立客户端验证 direct-upload 生命周期 Lua、fencing 和 watchdog 语义。
 */
@Testcontainers(disabledWithoutDocker = false)
class DirectUploadRedisLifecycleIT {

    private static final int REDIS_PORT = 6379;
    private static final String REDIS_IMAGE = "redis:7.4.9-alpine";
    private static final String EXPIRY_SET_KEY = "storage:direct-upload:staging-expiry";
    private static final String CLAIM_HASH_KEY = "storage:direct-upload:staging-expiry:claims";
    private static final String CLAIM_GATE_KEY = "storage:direct-upload:staging-expiry:claim-gate";
    private static final String DEAD_LETTER_SET_KEY =
            "storage:direct-upload:staging-expiry:dead-letter";
    private static final long WATCHDOG_TIMEOUT_MILLIS = 2_000L;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(REDIS_PORT)
            .waitingFor(Wait.forListeningPort());

    private static LettuceConnectionFactory firstConnectionFactory;
    private static LettuceConnectionFactory secondConnectionFactory;
    private static StringRedisTemplate firstTemplate;
    private static StringRedisTemplate secondTemplate;
    private static RedissonClient firstRedisson;
    private static RedissonClient secondRedisson;

    private StorageProperties storageProperties;
    private DirectUploadStagingTracker firstTracker;
    private DirectUploadStagingTracker secondTracker;
    private DirectUploadLockManager firstLockManager;
    private DirectUploadLockManager secondLockManager;
    private DirectUploadOperationIntentStore firstIntentStore;
    private DirectUploadOperationIntentStore secondIntentStore;

    /**
     * 创建两个独立 Lettuce/Redisson 客户端，共享同一个真实 Redis 容器。
     */
    @BeforeAll
    static void createRedisClients() {
        firstConnectionFactory = connectionFactory();
        secondConnectionFactory = connectionFactory();
        firstTemplate = redisTemplate(firstConnectionFactory);
        secondTemplate = redisTemplate(secondConnectionFactory);
        firstRedisson = redissonClient();
        secondRedisson = redissonClient();
    }

    /**
     * 关闭全部网络客户端，避免集成测试 JVM 遗留连接和 watchdog 线程。
     */
    @AfterAll
    static void closeRedisClients() {
        if (firstRedisson != null) {
            firstRedisson.shutdown();
        }
        if (secondRedisson != null) {
            secondRedisson.shutdown();
        }
        if (firstConnectionFactory != null) {
            firstConnectionFactory.destroy();
        }
        if (secondConnectionFactory != null) {
            secondConnectionFactory.destroy();
        }
    }

    /**
     * 每个场景清空 Redis，并创建共享配置但客户端独立的 tracker/lock manager。
     */
    @BeforeEach
    void setUp() {
        flushDatabase();
        storageProperties = new StorageProperties();
        storageProperties.getDirectUpload().setCleanupBatchSize(2);
        firstLockManager = new DirectUploadLockManager(firstRedisson, storageProperties);
        secondLockManager = new DirectUploadLockManager(secondRedisson, storageProperties);
        firstIntentStore = new DirectUploadOperationIntentStore(firstTemplate);
        secondIntentStore = new DirectUploadOperationIntentStore(secondTemplate);
        firstTracker = new DirectUploadStagingTracker(
                firstTemplate,
                storageProperties,
                firstLockManager,
                firstIntentStore
        );
        secondTracker = new DirectUploadStagingTracker(
                secondTemplate,
                storageProperties,
                secondLockManager,
                secondIntentStore
        );
    }

    @Test
    @DisplayName("two real trackers should enforce one bounded cluster claim and owner-only gate release")
    void shouldEnforceClusterGateBatchLimitAndOwnerRelease() throws Exception {
        for (int index = 0; index < 4; index++) {
            recordAsExpired(firstTracker, descriptor(index));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DirectUploadStagingTracker.ClaimBatch> firstClaim = executor.submit(() -> {
                await(start);
                return firstTracker.claimExpired(2);
            });
            Future<DirectUploadStagingTracker.ClaimBatch> secondClaim = executor.submit(() -> {
                await(start);
                return secondTracker.claimExpired(2);
            });
            start.countDown();

            DirectUploadStagingTracker.ClaimBatch firstBatch = firstClaim.get(10, TimeUnit.SECONDS);
            DirectUploadStagingTracker.ClaimBatch secondBatch = secondClaim.get(10, TimeUnit.SECONDS);
            List<DirectUploadStagingTracker.ClaimBatch> ownedBatches =
                    List.of(firstBatch, secondBatch).stream()
                            .filter(batch -> !batch.entries().isEmpty())
                            .toList();

            assertThat(ownedBatches).singleElement()
                    .satisfies(batch -> assertThat(batch.entries()).hasSize(2));
            assertThat(firstBatch.entries().size() + secondBatch.entries().size()).isEqualTo(2);

            DirectUploadStagingTracker.ClaimBatch owner = ownedBatches.getFirst();
            secondTracker.releaseClaimBatch(
                    new DirectUploadStagingTracker.ClaimBatch("forged-token", List.of()));
            assertThat(firstTemplate.opsForValue().get(CLAIM_GATE_KEY))
                    .isEqualTo(owner.batchToken());

            if (owner == firstBatch) {
                firstTracker.releaseClaimBatch(owner);
            } else {
                secondTracker.releaseClaimBatch(owner);
            }
            assertThat(firstTemplate.hasKey(CLAIM_GATE_KEY)).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("refresh and terminal intents should fence stale claims and execution epochs")
    void shouldFenceSupersededClaimsAndPreserveNewScores() {
        DirectUploadStagingDescriptor refreshedDescriptor = descriptor(0);
        DirectUploadStagingDescriptor pendingDescriptor = descriptor(1);
        String refreshedMember = recordAsExpired(firstTracker, refreshedDescriptor);
        String pendingMember = recordAsExpired(firstTracker, pendingDescriptor);

        DirectUploadStagingTracker.ClaimBatch batch = firstTracker.claimExpired(2);
        assertThat(batch.entries()).hasSize(2);
        DirectUploadStagingTracker.TrackedStaging refreshedClaim = findClaim(batch, refreshedMember);
        DirectUploadStagingTracker.TrackedStaging pendingClaim = findClaim(batch, pendingMember);

        secondTracker.record(refreshedDescriptor);
        Double refreshedScore = secondTemplate.opsForZSet().score(EXPIRY_SET_KEY, refreshedMember);
        assertThat(refreshedScore).isNotNull();

        assertThat(firstTracker.isClaimCurrent(refreshedClaim)).isFalse();
        assertThat(firstTracker.completeClaim(refreshedClaim)).isFalse();
        assertThat(firstTracker.rescheduleClaim(refreshedClaim)).isFalse();
        assertThat(firstTracker.rescheduleClaims(List.of(refreshedClaim, pendingClaim))).isEqualTo(1);

        assertThat(secondTemplate.opsForZSet().score(EXPIRY_SET_KEY, refreshedMember))
                .isEqualTo(refreshedScore);
        assertThat(secondTemplate.opsForZSet().score(EXPIRY_SET_KEY, pendingMember))
                .isGreaterThan((double) System.currentTimeMillis());
        assertThat(secondTemplate.opsForHash().hasKey(CLAIM_HASH_KEY, pendingMember)).isFalse();
        firstTracker.releaseClaimBatch(batch);

        DirectUploadPartDescriptor completePart = receiptDescriptor(
                List.of("node-a", "node-b"),
                2
        );
        DirectUploadOperationIntentStore.OperationIntent firstComplete =
                firstIntentStore.beginComplete(completePart);
        DirectUploadOperationIntentStore.OperationIntent retriedComplete =
                secondIntentStore.beginComplete(completePart);
        assertThat(retriedComplete.executionEpoch()).isGreaterThan(firstComplete.executionEpoch());
        assertThatThrownBy(() -> firstIntentStore.verify(firstComplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fence changed");
        secondIntentStore.verify(retriedComplete);

        DirectUploadPartDescriptor conflictingComplete = new DirectUploadPartDescriptor(
                completePart.tenantId(),
                completePart.sessionId(),
                completePart.partIndex(),
                completePart.sourceNode(),
                completePart.stagingObjectName(),
                completePart.finalObjectName(),
                completePart.size(),
                "\"conflicting-etag\"",
                completePart.plainHash(),
                completePart.cipherHash(),
                completePart.checksumAlgorithm(),
                completePart.targetNodes(),
                completePart.requiredQuorum()
        );
        assertThatThrownBy(() -> firstIntentStore.beginComplete(conflictingComplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting direct-upload operation intent");
        assertThatThrownBy(() -> secondIntentStore.verify(retriedComplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fence changed");

        DirectUploadOperationIntentStore.OperationIntent inheritedAbort =
                firstIntentStore.beginAbort(completePart.stagingDescriptor());
        assertThat(inheritedAbort.mode())
                .isEqualTo(DirectUploadOperationIntentStore.OperationMode.COMPLETE);
        firstIntentStore.verify(inheritedAbort);
        firstIntentStore.retire(inheritedAbort, Duration.ofHours(48));
        assertRetiredIntentHasBoundedTtl(firstIntentStore, completePart.stagingDescriptor());

        DirectUploadStagingDescriptor cleanupDescriptor = descriptor(9);
        String cleanupMember = recordAsExpired(firstTracker, cleanupDescriptor);
        DirectUploadStagingTracker.ClaimBatch cleanupBatch = firstTracker.claimExpired(1);
        DirectUploadStagingTracker.TrackedStaging cleanupClaim =
                findClaim(cleanupBatch, cleanupMember);
        DirectUploadOperationIntentStore.OperationIntent firstCleanup = firstIntentStore
                .followOrCreateCleanup(cleanupClaim)
                .orElseThrow();
        assertThatThrownBy(() -> secondTracker.record(cleanupDescriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already finalizing or terminal");
        assertThatThrownBy(() -> firstIntentStore.verify(firstCleanup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fence changed");

        DirectUploadOperationIntentStore.OperationIntent resumedCleanup = secondIntentStore
                .followOrCreateCleanup(cleanupClaim)
                .orElseThrow();
        assertThat(resumedCleanup.mode())
                .isEqualTo(DirectUploadOperationIntentStore.OperationMode.CLEANUP);
        secondIntentStore.verify(resumedCleanup);
        secondTracker.retainAfterDelete(cleanupDescriptor);
        assertThat(firstIntentStore.followOrCreateCleanup(cleanupClaim)).isEmpty();
        secondIntentStore.retire(resumedCleanup, Duration.ofHours(48));
        assertRetiredIntentHasBoundedTtl(secondIntentStore, cleanupDescriptor);
        firstTracker.releaseClaimBatch(cleanupBatch);
    }

    @Test
    @DisplayName("expired gate and member lease should allow another tracker to reclaim with a new token")
    void shouldRecoverClaimAfterFiniteGateAndMemberLease() {
        String member = recordAsExpired(firstTracker, descriptor(0));
        DirectUploadStagingTracker.ClaimBatch firstBatch = firstTracker.claimExpired(1);
        DirectUploadStagingTracker.TrackedStaging oldClaim = firstBatch.entries().getFirst();

        assertThat(firstTemplate.expire(CLAIM_GATE_KEY, Duration.ofMillis(250))).isTrue();
        awaitCondition(() -> Boolean.FALSE.equals(secondTemplate.hasKey(CLAIM_GATE_KEY)), Duration.ofSeconds(5));
        secondTemplate.opsForZSet().add(EXPIRY_SET_KEY, member, System.currentTimeMillis() - 1D);

        DirectUploadStagingTracker.ClaimBatch recoveredBatch = secondTracker.claimExpired(1);
        assertThat(recoveredBatch.entries()).singleElement().satisfies(recovered -> {
            assertThat(recovered.member()).isEqualTo(member);
            assertThat(recovered.claimToken()).isNotEqualTo(oldClaim.claimToken());
        });
        assertThat(firstTracker.isClaimCurrent(oldClaim)).isFalse();

        firstTracker.releaseClaimBatch(firstBatch);
        secondTracker.releaseClaimBatch(recoveredBatch);
    }

    @Test
    @DisplayName("corrupt lifecycle member should move to real Redis dead letter without a delete identity")
    void shouldDeadLetterCorruptLifecycleMember() {
        String corruptMember = "corrupt-member-without-trusted-object-identity";
        firstTemplate.opsForZSet().add(
                EXPIRY_SET_KEY,
                corruptMember,
                System.currentTimeMillis() - 1D
        );

        DirectUploadStagingTracker.ClaimBatch batch = firstTracker.claimExpired(1);

        assertThat(batch.entries()).isEmpty();
        assertThat(firstTemplate.opsForZSet().score(EXPIRY_SET_KEY, corruptMember)).isNull();
        assertThat(firstTemplate.opsForHash().hasKey(CLAIM_HASH_KEY, corruptMember)).isFalse();
        assertThat(firstTemplate.opsForZSet().score(DEAD_LETTER_SET_KEY, corruptMember)).isNotNull();
        firstTracker.releaseClaimBatch(batch);
    }

    @Test
    @DisplayName("two Redisson clients should honor reentrancy and renew a watchdog lock until final close")
    void shouldRenewReentrantWatchdogLockUntilFinalClose() {
        DirectUploadStagingDescriptor descriptor = descriptor(0);
        DirectUploadLockManager.LockHandle outer = firstLockManager.acquire(descriptor);
        DirectUploadLockManager.LockHandle inner = firstLockManager.acquire(descriptor);
        try {
            assertLockUnavailable(secondLockManager, descriptor);
            sleep(Duration.ofMillis(WATCHDOG_TIMEOUT_MILLIS * 2 + 500));
            assertLockUnavailable(secondLockManager, descriptor);

            inner.close();
            assertLockUnavailable(secondLockManager, descriptor);
        } finally {
            outer.close();
            inner.close();
        }

        Optional<DirectUploadLockManager.LockHandle> acquired =
                awaitLock(secondLockManager, descriptor, Duration.ofSeconds(5));
        assertThat(acquired).isPresent();
        acquired.ifPresent(DirectUploadLockManager.LockHandle::close);
    }

    /**
     * 使用两个真实 Redis 客户端并发合并 promotion receipt，并验证首次 quorum 与 TTL 不会降低。
     */
    @Test
    @DisplayName("real Redis receipt should merge concurrent nodes without lowering original quorum or TTL")
    void shouldMergePromotionReceiptAcrossRealRedisClients() throws Exception {
        DirectUploadPromotionReceiptStore firstStore = new DirectUploadPromotionReceiptStore(
                firstTemplate,
                new ObjectMapper(),
                storageProperties
        );
        DirectUploadPromotionReceiptStore secondStore = new DirectUploadPromotionReceiptStore(
                secondTemplate,
                new ObjectMapper(),
                storageProperties
        );
        DirectUploadPartDescriptor firstTopology = receiptDescriptor(
                List.of("node-a", "node-b"),
                2
        );
        DirectUploadPartDescriptor competingTopology = receiptDescriptor(
                List.of("node-b", "node-c"),
                2
        );
        DirectUploadOperationIntentStore.OperationIntent intent =
                firstIntentStore.beginComplete(firstTopology);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> writes = new ArrayList<>();
        try {
            for (int index = 0; index < 32; index++) {
                boolean firstWriter = index % 2 == 0;
                writes.add(executor.submit(() -> {
                    await(start);
                    if (firstWriter) {
                        firstStore.recordSuccess(
                                firstTopology,
                                List.of("node-a", "node-b"),
                                intent
                        );
                    } else {
                        secondStore.recordSuccess(
                                competingTopology,
                                List.of("node-b", "node-c"),
                                intent
                        );
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> write : writes) {
                write.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        DirectUploadPartDescriptor weakerCurrentTopology = receiptDescriptor(
                List.of("node-c", "node-d"),
                1
        );
        DirectUploadPromotionReceiptStore.PromotionReceipt authoritative = firstStore
                .findValidated(firstTopology)
                .orElseThrow();
        secondStore.recordRetrySuccess(
                weakerCurrentTopology,
                authoritative,
                List.of("node-a", "node-b", "node-c"),
                intent
        );

        DirectUploadPromotionReceiptStore.PromotionReceipt receipt = firstStore
                .findValidated(firstTopology)
                .orElseThrow();
        assertThat(receipt.initialTargetNodes())
                .isIn(List.of("node-a", "node-b"), List.of("node-b", "node-c"));
        assertThat(receipt.successfulNodes())
                .containsExactlyInAnyOrder("node-a", "node-b", "node-c")
                .doesNotHaveDuplicates();
        assertThat(receipt.requiredQuorum()).isEqualTo(2);
        Long ttlMillis = secondTemplate.getExpire(
                firstStore.receiptKey(firstTopology),
                TimeUnit.MILLISECONDS
        );
        assertThat(ttlMillis)
                .isNotNull()
                .isGreaterThanOrEqualTo(Duration.ofHours(48).minusMinutes(1).toMillis())
                .isLessThanOrEqualTo(Duration.ofHours(48).plusMinutes(1).toMillis());
    }

    /**
     * 验证真实 Redis tombstone 会 fencing 旧 claim，并让后续实例在完整保留期内无法重新领取。
     */
    @Test
    @DisplayName("real Redis tombstone should fence old generation and remain outside expiry claims")
    void shouldFenceOldClaimWithRealRedisTombstone() {
        DirectUploadStagingDescriptor staging = descriptor(7);
        String member = recordAsExpired(firstTracker, staging);
        DirectUploadStagingTracker.ClaimBatch oldBatch = firstTracker.claimExpired(1);
        DirectUploadStagingTracker.TrackedStaging oldClaim = findClaim(oldBatch, member);
        long beforeRefresh = System.currentTimeMillis();

        secondTracker.retainAfterDelete(staging);

        Double tombstoneScore = secondTemplate.opsForZSet().score(EXPIRY_SET_KEY, member);
        assertThat(tombstoneScore)
                .isNotNull()
                .isGreaterThanOrEqualTo(
                        beforeRefresh + Duration.ofHours(48).minusMinutes(1).toMillis()
                );
        assertThat(firstTracker.isClaimCurrent(oldClaim)).isFalse();
        assertThat(firstTracker.completeClaim(oldClaim)).isFalse();
        assertThat(secondTemplate.opsForHash().hasKey(CLAIM_HASH_KEY, member)).isFalse();

        firstTracker.releaseClaimBatch(oldBatch);
        DirectUploadStagingTracker.ClaimBatch tombstoneBatch = secondTracker.claimExpired(1);
        assertThat(tombstoneBatch.entries()).isEmpty();
        secondTracker.releaseClaimBatch(tombstoneBatch);
    }

    /**
     * 创建已初始化并启动的独立 Lettuce 连接工厂。
     */
    private static LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT))
        );
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    /**
     * 创建使用字符串序列化的 Redis template。
     */
    private static StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 创建启用短 watchdog 周期的独立 Redisson 客户端。
     */
    private static RedissonClient redissonClient() {
        Config config = new Config();
        config.setLockWatchdogTimeout(WATCHDOG_TIMEOUT_MILLIS);
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT))
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2);
        return Redisson.create(config);
    }

    /**
     * 使用真实连接清空当前 Redis database。
     */
    private static void flushDatabase() {
        try (RedisConnection connection = firstConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    /**
     * 创建合法、规范且彼此独立的 staging 身份。
     */
    private DirectUploadStagingDescriptor descriptor(int partIndex) {
        String sessionId = "redis-lifecycle-it-" + partIndex;
        return new DirectUploadStagingDescriptor(
                7L,
                sessionId,
                partIndex,
                "node-a",
                "tenant/7/staging/direct-upload/" + sessionId + "/part-" + partIndex
        );
    }

    /**
     * 构建共享 receipt identity、可变拓扑和 quorum 的合法分片描述。
     */
    private DirectUploadPartDescriptor receiptDescriptor(List<String> targetNodes, int requiredQuorum) {
        String hash = "sha256:" + "a".repeat(64);
        return new DirectUploadPartDescriptor(
                7L,
                "redis-receipt-it",
                0,
                "node-a",
                "tenant/7/staging/direct-upload/redis-receipt-it/part-0",
                "tenant/7/" + hash,
                1024L,
                "\"receipt-etag\"",
                hash,
                hash,
                "SHA-256",
                targetNodes,
                requiredQuorum
        );
    }

    /**
     * 通过生产 record 路径创建成员，再把 score 调整到到期窗口供 claim 测试使用。
     */
    private String recordAsExpired(
            DirectUploadStagingTracker tracker,
            DirectUploadStagingDescriptor descriptor
    ) {
        tracker.record(descriptor);
        String member = encodeMember(descriptor);
        assertThat(firstTemplate.opsForZSet().score(EXPIRY_SET_KEY, member)).isNotNull();
        firstTemplate.opsForZSet().add(EXPIRY_SET_KEY, member, System.currentTimeMillis() - 1D);
        return member;
    }

    /**
     * 复现生产 tracker 的无凭据成员编码，以便精确验证 Redis 状态。
     */
    private String encodeMember(DirectUploadStagingDescriptor descriptor) {
        return String.join(
                ".",
                "v1",
                String.valueOf(descriptor.tenantId()),
                String.valueOf(descriptor.partIndex()),
                encodeToken(descriptor.nodeName()),
                encodeToken(descriptor.sessionId()),
                encodeToken(descriptor.objectName())
        );
    }

    /**
     * 使用与生产一致的 URL-safe Base64 编码一个成员字段。
     */
    private String encodeToken(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 断言分片意图和执行 fence 已按重放防护周期退休，而全局 epoch 仍永久保留。
     */
    private void assertRetiredIntentHasBoundedTtl(
            DirectUploadOperationIntentStore intentStore,
            DirectUploadStagingDescriptor descriptor
    ) {
        DirectUploadOperationIntentStore.LifecycleFenceKeys keys =
                intentStore.lifecycleFenceKeys(descriptor);
        Long intentTtl = firstTemplate.getExpire(keys.intentKey(), TimeUnit.MILLISECONDS);
        Long fenceTtl = firstTemplate.getExpire(keys.executionFenceKey(), TimeUnit.MILLISECONDS);
        Long globalTtl = firstTemplate.getExpire(keys.globalFenceKey(), TimeUnit.MILLISECONDS);
        long lowerBound = Duration.ofHours(48).minusMinutes(1).toMillis();
        assertThat(intentTtl).isNotNull().isGreaterThanOrEqualTo(lowerBound);
        assertThat(fenceTtl).isNotNull().isGreaterThanOrEqualTo(lowerBound);
        assertThat(globalTtl).isEqualTo(-1L);
    }

    /**
     * 从真实领取批次中查找指定 Redis 成员。
     */
    private DirectUploadStagingTracker.TrackedStaging findClaim(
            DirectUploadStagingTracker.ClaimBatch batch,
            String member
    ) {
        return batch.entries().stream()
                .filter(entry -> member.equals(entry.member()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing claimed member: " + member));
    }

    /**
     * 等待并发 claim 起跑信号，中断时使测试失败且恢复线程标志。
     */
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for claim start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for claim start", e);
        }
    }

    /**
     * 在有界时间内等待条件成立。
     */
    private static void awaitCondition(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition did not become true before timeout");
            }
            sleep(Duration.ofMillis(25));
        }
    }

    /**
     * 在有界时间内等待另一个客户端获得已释放的分片锁。
     */
    private Optional<DirectUploadLockManager.LockHandle> awaitLock(
            DirectUploadLockManager lockManager,
            DirectUploadStagingDescriptor descriptor,
            Duration timeout
    ) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Optional<DirectUploadLockManager.LockHandle> acquired;
        do {
            acquired = lockManager.tryAcquireForCleanup(descriptor);
            if (acquired.isPresent() || System.nanoTime() >= deadline) {
                return acquired;
            }
            sleep(Duration.ofMillis(25));
        } while (true);
    }

    /**
     * 断言竞争客户端不能获得锁；若实现缺陷意外获得，也先释放句柄避免污染后续场景。
     */
    private void assertLockUnavailable(
            DirectUploadLockManager lockManager,
            DirectUploadStagingDescriptor descriptor
    ) {
        Optional<DirectUploadLockManager.LockHandle> acquired =
                lockManager.tryAcquireForCleanup(descriptor);
        acquired.ifPresent(DirectUploadLockManager.LockHandle::close);
        assertThat(acquired).isEmpty();
    }

    /**
     * 执行短时测试等待并在中断时恢复线程标志。
     */
    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test wait interrupted", e);
        }
    }
}
