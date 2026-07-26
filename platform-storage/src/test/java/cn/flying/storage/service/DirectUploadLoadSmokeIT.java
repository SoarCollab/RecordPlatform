package cn.flying.storage.service;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3ClientManager.TopologyLease;
import cn.flying.storage.core.S3Monitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用固定 MinIO、真实 Redis 和小型并发负载验证 direct-upload 提升资源与清理边界。
 */
@Testcontainers(disabledWithoutDocker = false)
@Timeout(value = 75, unit = TimeUnit.SECONDS)
class DirectUploadLoadSmokeIT {

    private static final int MINIO_PORT = 9000;
    private static final int REDIS_PORT = 6379;
    private static final int ITERATIONS = 8;
    private static final int CONCURRENCY = 4;
    private static final int PART_BYTES = 256 * 1024;
    private static final String ACCESS_KEY = "directupload";
    private static final String SECRET_KEY = "directupload-secret-2026";
    private static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-11-07T00-52-20Z";
    private static final String REDIS_IMAGE = "redis:7.4.9-alpine";
    private static final String SOURCE_BUCKET = "direct-load-source";
    private static final String TARGET_BUCKET = "direct-load-target";
    private static final String RECEIPT_KEY_PATTERN = "storage:direct-upload:promotion-receipt:v1:*";
    private static final String EXPIRY_SET_KEY = "storage:direct-upload:staging-expiry";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse(MINIO_IMAGE))
            .withExposedPorts(MINIO_PORT)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready")
                    .forPort(MINIO_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(90)));

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(REDIS_PORT)
            .waitingFor(Wait.forListeningPort());

    private static S3Client minioClient;
    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedissonClient redissonClient;

    private StorageProperties storageProperties;
    private S3ClientManager clientManager;
    private S3Monitor s3Monitor;
    private ConsistencyRepairService repairService;
    private DegradedWriteTracker degradedWriteTracker;
    private DirectUploadPromotionService promotionService;
    private ExecutorService promotionExecutor;
    private SimpleMeterRegistry meterRegistry;

    /**
     * 创建真实 MinIO 与 Redis 客户端。
     */
    @BeforeAll
    static void createClients() {
        minioClient = s3Client(endpoint(MINIO));
        redisConnectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT)));
        redisConnectionFactory.afterPropertiesSet();
        redisConnectionFactory.start();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT))
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2);
        redissonClient = Redisson.create(config);
    }

    /**
     * 关闭共享 provider 和 Redis 客户端。
     */
    @AfterAll
    static void closeClients() {
        if (minioClient != null) {
            minioClient.close();
        }
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    /**
     * 创建真实 lifecycle 依赖和单副本 direct-upload 提升服务。
     */
    @BeforeEach
    void setUp() {
        flushRedis();
        createBucket(SOURCE_BUCKET);
        createBucket(TARGET_BUCKET);
        storageProperties = new StorageProperties();
        storageProperties.getReplication().setFactor(1);
        storageProperties.getReplication().setQuorum("1");
        storageProperties.getDirectUpload().setStreamBufferBytes(8 * 1024);
        storageProperties.getDirectUpload().setTransferTimeoutSeconds(15);

        clientManager = mock(S3ClientManager.class);
        TopologyLease topologyLease = mock(TopologyLease.class);
        s3Monitor = mock(S3Monitor.class);
        repairService = mock(ConsistencyRepairService.class);
        degradedWriteTracker = mock(DegradedWriteTracker.class);
        when(clientManager.acquireTopologyLease()).thenReturn(topologyLease);
        when(topologyLease.getClient(anyString())).thenAnswer(invocation ->
                clientManager.getClient(invocation.getArgument(0)));
        when(topologyLease.getNodeConfig(anyString())).thenAnswer(invocation ->
                clientManager.getNodeConfig(invocation.getArgument(0)));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(topologyLease).runIfCurrent(any(Runnable.class));
        configureNode(SOURCE_BUCKET, "physical-load-source");
        configureNode(TARGET_BUCKET, "physical-load-target");

        DirectUploadOperationIntentStore operationIntentStore =
                new DirectUploadOperationIntentStore(redisTemplate);
        DirectUploadLockManager lockManager =
                new DirectUploadLockManager(redissonClient, storageProperties);
        DirectUploadStagingTracker stagingTracker = new DirectUploadStagingTracker(
                redisTemplate, storageProperties, lockManager, operationIntentStore);
        DirectUploadPromotionReceiptStore receiptStore = new DirectUploadPromotionReceiptStore(
                redisTemplate, new ObjectMapper(), storageProperties);
        promotionExecutor = Executors.newFixedThreadPool(CONCURRENCY);
        meterRegistry = new SimpleMeterRegistry();
        promotionService = new DirectUploadPromotionService(
                clientManager,
                s3Monitor,
                storageProperties,
                repairService,
                degradedWriteTracker,
                lockManager,
                stagingTracker,
                receiptStore,
                operationIntentStore,
                promotionExecutor,
                meterRegistry
        );
    }

    /**
     * 回收当前 smoke 的对象、桶、Redis 数据和执行器。
     */
    @AfterEach
    void tearDown() {
        deleteBucket(SOURCE_BUCKET);
        deleteBucket(TARGET_BUCKET);
        flushRedis();
        promotionExecutor.shutdownNow();
        meterRegistry.close();
    }

    /**
     * 并发提升固定小分片，记录延迟、吞吐、JVM 资源、lifecycle 和清理证据。
     */
    @Test
    @DisplayName("concurrent direct-upload load smoke should remain bounded and leave no run residual")
    void shouldProduceBoundedLoadSmokeReport() throws Exception {
        List<LoadInput> inputs = prepareInputs();
        ResourceSampler sampler = new ResourceSampler();
        ExecutorService callers = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LoadResult>> futures = new ArrayList<>();
        for (LoadInput input : inputs) {
            futures.add(callers.submit(promotionTask(input, start)));
        }

        sampler.start();
        long startedAt = System.nanoTime();
        start.countDown();
        List<LoadResult> results = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try {
            for (Future<LoadResult> future : futures) {
                long remainingNanos = Math.max(1L, deadlineNanos - System.nanoTime());
                results.add(future.get(remainingNanos, TimeUnit.NANOSECONDS));
            }
        } finally {
            callers.shutdownNow();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            sampler.close();
        }
        long wallNanos = System.nanoTime() - startedAt;

        assertSuccessfulResults(inputs, results);
        long receiptCountBeforeCleanup = countReceiptKeys();
        Long tombstoneCountBeforeCleanup = redisTemplate.opsForZSet().zCard(EXPIRY_SET_KEY);
        assertThat(receiptCountBeforeCleanup).isEqualTo(ITERATIONS);
        assertThat(tombstoneCountBeforeCleanup).isEqualTo(ITERATIONS);
        verify(degradedWriteTracker, times(ITERATIONS)).recordAuthoritativeDegradedWrite(
                any(),
                argThat(nodes -> nodes.size() == 1 && nodes.contains(TARGET_BUCKET)),
                eq(7L)
        );
        verify(repairService, never()).scheduleImmediateRepairByNodesAsync(any(), any(), any());

        cleanupRun(inputs);
        long receiptCountAfterCleanup = countReceiptKeys();
        Long tombstoneCountAfterCleanup = redisTemplate.opsForZSet().zCard(EXPIRY_SET_KEY);
        ResourceSnapshot resource = sampler.snapshot();
        LoadSmokeReport report = buildReport(
                results,
                wallNanos,
                resource,
                receiptCountBeforeCleanup,
                tombstoneCountBeforeCleanup,
                receiptCountAfterCleanup,
                tombstoneCountAfterCleanup
        );
        writeReport(report);

        assertThat(report.failures()).isZero();
        assertThat(report.cleanupSuccess()).isTrue();
        assertThat(report.p99Millis()).isLessThan(30_000D);
        assertThat(report.resource().heapPeakDeltaBytes()).isLessThan(96L * 1024 * 1024);
        assertThat(report.resource().directBufferAvailable()).isTrue();
        assertThat(report.resource().directBufferPeakDeltaBytes()).isLessThan(64L * 1024 * 1024);
        assertThat(report.resource().threadPeakDelta()).isLessThan(64);
    }

    /**
     * 创建每次迭代唯一的 staging 内容、hash 和描述。
     */
    private List<LoadInput> prepareInputs() throws Exception {
        List<LoadInput> inputs = new ArrayList<>();
        for (int index = 0; index < ITERATIONS; index++) {
            byte[] content = deterministicBytes(index);
            String hash = hash(content);
            String sessionId = "load-smoke-" + index;
            String stagingKey = stagingKey(sessionId);
            String etag = minioClient.putObject(
                    PutObjectRequest.builder().bucket(SOURCE_BUCKET).key(stagingKey).build(),
                    RequestBody.fromBytes(content)
            ).eTag();
            DirectUploadPartDescriptor part = new DirectUploadPartDescriptor(
                    7L,
                    sessionId,
                    0,
                    SOURCE_BUCKET,
                    stagingKey,
                    "tenant/7/" + hash,
                    content.length,
                    etag,
                    hash,
                    hash,
                    "SHA-256",
                    List.of(TARGET_BUCKET),
                    1
            );
            inputs.add(new LoadInput(part, content));
        }
        return inputs;
    }

    /**
     * 构建一个等待统一起跑信号的提升任务。
     */
    private Callable<LoadResult> promotionTask(LoadInput input, CountDownLatch start) {
        return () -> {
            start.await(5, TimeUnit.SECONDS);
            long startedAt = System.nanoTime();
            try {
                DirectUploadDigestAccumulator digest = DirectUploadDigestAccumulator.sha256();
                DirectUploadPromotionResult result = promotionService.promote(input.part(), digest);
                return new LoadResult(
                        true,
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                        result.size(),
                        digest.finishHash(),
                        null
                );
            } catch (RuntimeException e) {
                return new LoadResult(
                        false,
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                        0L,
                        null,
                        e.getClass().getSimpleName()
                );
            }
        };
    }

    /**
     * 断言每次迭代完成真实对象提升、hash 校验和 staging 删除。
     */
    private void assertSuccessfulResults(List<LoadInput> inputs, List<LoadResult> results) {
        assertThat(results).hasSize(ITERATIONS).allMatch(LoadResult::success);
        for (int index = 0; index < ITERATIONS; index++) {
            LoadInput input = inputs.get(index);
            LoadResult result = results.get(index);
            assertThat(result.bytes()).isEqualTo(PART_BYTES);
            assertThat(result.hash()).isEqualTo(input.part().plainHash());
            assertThat(readObject(TARGET_BUCKET, input.part().finalObjectName()))
                    .containsExactly(input.content());
            assertThat(objectExists(SOURCE_BUCKET, input.part().stagingObjectName())).isFalse();
            assertThat(objectExists(
                    SOURCE_BUCKET,
                    input.part().stagingDescriptor().sealedObjectName()
            )).isFalse();
        }
    }

    /**
     * 删除本次 smoke 创建的 final 对象和 run 级 Redis lifecycle 数据。
     */
    private void cleanupRun(List<LoadInput> inputs) {
        for (LoadInput input : inputs) {
            minioClient.deleteObject(DeleteObjectRequest.builder()
                    .bucket(TARGET_BUCKET)
                    .key(input.part().finalObjectName())
                    .build());
        }
        flushRedis();
        assertThat(listObjectCount(SOURCE_BUCKET)).isZero();
        assertThat(listObjectCount(TARGET_BUCKET)).isZero();
        assertThat(redisDatabaseSize()).isZero();
    }

    /**
     * 汇总固定 PR smoke 的分位数、吞吐、资源和 lifecycle 证据。
     */
    private LoadSmokeReport buildReport(
            List<LoadResult> results,
            long wallNanos,
            ResourceSnapshot resource,
            long receiptCount,
            long tombstoneCount,
            long receiptCountAfterCleanup,
            Long tombstoneCountAfterCleanup
    ) {
        List<Long> latencies = results.stream()
                .map(LoadResult::latencyMillis)
                .sorted()
                .toList();
        long totalBytes = results.stream().mapToLong(LoadResult::bytes).sum();
        long wallMillis = Duration.ofNanos(wallNanos).toMillis();
        double seconds = Math.max(0.001D, wallNanos / 1_000_000_000D);
        return new LoadSmokeReport(
                "direct-upload-load-smoke-v1",
                Instant.now().toString(),
                environmentSnapshot(),
                ITERATIONS,
                CONCURRENCY,
                PART_BYTES,
                results.stream().filter(LoadResult::success).count(),
                results.stream().filter(result -> !result.success()).count(),
                wallMillis,
                percentile(latencies, 0.50D),
                percentile(latencies, 0.95D),
                percentile(latencies, 0.99D),
                totalBytes / seconds,
                resource,
                new LifecycleSnapshot(
                        receiptCount,
                        tombstoneCount,
                        receiptCountAfterCleanup,
                        requireAvailableCount(tombstoneCountAfterCleanup, "staging tombstone after cleanup"),
                        "unavailable:not-exercised",
                        null,
                        "unavailable:not-exercised",
                        null
                ),
                true
        );
    }

    /**
     * 将机器可读 JSON 与人读 Markdown 报告写入 Failsafe target。
     */
    private void writeReport(LoadSmokeReport report) throws Exception {
        Path reportDirectory = Path.of("target", "direct-upload-load-smoke");
        Files.createDirectories(reportDirectory);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(reportDirectory.resolve("report.json").toFile(), report);
        String markdown = """
                # Direct Upload Load Smoke

                - environment fingerprint: %s
                - OS/arch/Java: %s / %s / %s
                - processors/max heap bytes: %d / %d
                - iterations: %d
                - concurrency: %d
                - part bytes: %d
                - success/failure: %d/%d
                - latency p50/p95/p99 ms: %.2f / %.2f / %.2f
                - throughput bytes/sec: %.2f
                - heap peak delta bytes: %d
                - direct-buffer available/peak delta bytes: %s / %d
                - GC count/time delta: %d / %d ms
                - thread peak delta: %d
                - receipt/tombstone before cleanup: %d / %d
                - receipt/tombstone after cleanup: %d / %d
                - degraded backlog: %s
                - repair backlog: %s
                - object and Redis residual after cleanup: 0
                """.formatted(
                report.environment().fingerprint(),
                report.environment().osName(),
                report.environment().osArchitecture(),
                report.environment().javaVersion(),
                report.environment().availableProcessors(),
                report.environment().maxHeapBytes(),
                report.iterations(),
                report.concurrency(),
                report.partBytes(),
                report.successes(),
                report.failures(),
                report.p50Millis(),
                report.p95Millis(),
                report.p99Millis(),
                report.bytesPerSecond(),
                report.resource().heapPeakDeltaBytes(),
                report.resource().directBufferAvailable(),
                report.resource().directBufferPeakDeltaBytes(),
                report.resource().gcCollectionCountDelta(),
                report.resource().gcCollectionTimeMillisDelta(),
                report.resource().threadPeakDelta(),
                report.lifecycle().receiptCountBeforeCleanup(),
                report.lifecycle().stagingTombstoneCountBeforeCleanup(),
                report.lifecycle().receiptCountAfterCleanup(),
                report.lifecycle().stagingTombstoneCountAfterCleanup(),
                report.lifecycle().degradedBacklogStatus(),
                report.lifecycle().repairBacklogStatus()
        );
        Files.writeString(reportDirectory.resolve("report.md"), markdown, StandardCharsets.UTF_8);
    }

    /**
     * 生成不含凭据的 JVM/主机环境指纹，防止跨环境误比较报告。
     */
    private EnvironmentSnapshot environmentSnapshot() {
        String osName = System.getProperty("os.name", "unavailable");
        String osArchitecture = System.getProperty("os.arch", "unavailable");
        String javaVersion = System.getProperty("java.version", "unavailable");
        int processors = Runtime.getRuntime().availableProcessors();
        long maxHeapBytes = Runtime.getRuntime().maxMemory();
        String source = String.join("|",
                osName,
                osArchitecture,
                javaVersion,
                Integer.toString(processors),
                Long.toString(maxHeapBytes));
        try {
            String fingerprint = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            return new EnvironmentSnapshot(
                    fingerprint, osName, osArchitecture, javaVersion, processors, maxHeapBytes);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 load smoke 环境指纹", e);
        }
    }

    /**
     * 计算已排序样本的 nearest-rank 分位数。
     */
    private double percentile(List<Long> sortedValues, double percentile) {
        int rank = (int) Math.ceil(percentile * sortedValues.size());
        return sortedValues.get(Math.max(0, rank - 1));
    }

    /**
     * 将 Redis 计数解包为真实值；驱动未返回数据时失败而不是伪造零。
     */
    private long requireAvailableCount(Long value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " unavailable");
        }
        return value;
    }

    /**
     * 建立逻辑节点到真实 MinIO endpoint 和物理身份的映射。
     */
    private void configureNode(String node, String physicalStorageId) {
        when(clientManager.getClient(node)).thenReturn(minioClient);
        NodeConfig nodeConfig = new NodeConfig();
        nodeConfig.setName(node);
        nodeConfig.setEndpoint(endpoint(MINIO));
        nodeConfig.setPhysicalStorageId(physicalStorageId);
        when(clientManager.getNodeConfig(node)).thenReturn(nodeConfig);
        when(s3Monitor.isNodeOnline(node)).thenReturn(true);
    }

    /**
     * 创建固定名称的 smoke 测试桶。
     */
    private void createBucket(String bucket) {
        minioClient.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    }

    /**
     * 删除测试桶中的全部对象并删除桶。
     */
    private void deleteBucket(String bucket) {
        minioClient.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents()
                .forEach(object -> minioClient.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(object.key()).build()));
        minioClient.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
    }

    /**
     * 返回测试桶中的对象数量。
     */
    private long listObjectCount(String bucket) {
        return minioClient.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .keyCount();
    }

    /**
     * 读取小型 final 对象用于内容断言。
     */
    private byte[] readObject(String bucket, String key) {
        return minioClient.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    /**
     * 判断真实 MinIO 中对象是否存在。
     */
    private boolean objectExists(String bucket, String key) {
        try {
            minioClient.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 清空隔离 Redis database 中的 run 级测试状态。
     */
    private static void flushRedis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    /**
     * 统计真实 Redis 中的 promotion receipt 数量。
     */
    private long countReceiptKeys() {
        var keys = redisTemplate.keys(RECEIPT_KEY_PATTERN);
        return keys == null ? 0L : keys.size();
    }

    /**
     * 返回隔离 Redis database 的 key 总数。
     */
    private long redisDatabaseSize() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            Long size = connection.serverCommands().dbSize();
            return size == null ? 0L : size;
        }
    }

    /**
     * 生成不会跨迭代重复的固定大小内容。
     */
    private byte[] deterministicBytes(int iteration) {
        byte[] seed = ("direct-load-smoke-" + iteration + "-").getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[PART_BYTES];
        for (int index = 0; index < content.length; index++) {
            content[index] = seed[index % seed.length];
        }
        return content;
    }

    /**
     * 返回规范 staging object key。
     */
    private String stagingKey(String sessionId) {
        return "tenant/7/staging/direct-upload/" + sessionId + "/part-0";
    }

    /**
     * 计算 direct-upload 合同使用的 SHA-256 内容地址。
     */
    private String hash(byte[] content) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
    }

    /**
     * 返回容器映射到宿主机的 endpoint。
     */
    private static String endpoint(GenericContainer<?> container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MINIO_PORT);
    }

    /**
     * 创建 path-style MinIO 客户端。
     */
    private static S3Client s3Client(String endpoint) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(15)))
                .build();
    }

    /**
     * 周期采集 JVM heap、GC、线程和 direct buffer 峰值。
     */
    private static final class ResourceSampler implements AutoCloseable {
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        private final BufferPoolMXBean directBuffer = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)
                .stream()
                .filter(pool -> "direct".equals(pool.getName()))
                .findFirst()
                .orElse(null);
        private final long heapStart = memory.getHeapMemoryUsage().getUsed();
        private final long gcCountStart = gcCount();
        private final long gcTimeStart = gcTime();
        private final int threadStart = threads.getThreadCount();
        private final long directStart = directMemory();
        private final AtomicLong heapPeak = new AtomicLong(heapStart);
        private final AtomicLong directPeak = new AtomicLong(directStart);
        private final AtomicLong threadPeak = new AtomicLong(threadStart);
        private final AtomicBoolean running = new AtomicBoolean();
        private ExecutorService samplerExecutor;

        /**
         * 启动单线程有界资源采样循环。
         */
        void start() {
            running.set(true);
            samplerExecutor = Executors.newSingleThreadExecutor();
            samplerExecutor.submit(() -> {
                while (running.get()) {
                    sample();
                    try {
                        Thread.sleep(20L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        /**
         * 更新当前资源峰值。
         */
        private void sample() {
            heapPeak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
            directPeak.accumulateAndGet(directMemory(), Math::max);
            threadPeak.accumulateAndGet(threads.getThreadCount(), Math::max);
        }

        /**
         * 生成包含峰值与增量的不可变资源快照。
         */
        ResourceSnapshot snapshot() {
            sample();
            return new ResourceSnapshot(
                    heapStart,
                    heapPeak.get(),
                    Math.max(0L, heapPeak.get() - heapStart),
                    gcCountStart,
                    gcCount(),
                    Math.max(0L, gcCount() - gcCountStart),
                    gcTimeStart,
                    gcTime(),
                    Math.max(0L, gcTime() - gcTimeStart),
                    threadStart,
                    (int) threadPeak.get(),
                    (int) Math.max(0L, threadPeak.get() - threadStart),
                    directBuffer != null,
                    directStart,
                    directPeak.get(),
                    directBuffer == null ? -1L : Math.max(0L, directPeak.get() - directStart)
            );
        }

        /**
         * 停止采样线程并等待其退出。
         */
        @Override
        public void close() {
            running.set(false);
            if (samplerExecutor != null) {
                samplerExecutor.shutdownNow();
                try {
                    if (!samplerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("资源采样线程未在期限内退出");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待资源采样线程退出时被中断", e);
                }
            }
        }

        /**
         * 汇总所有 GC bean 的 collection count。
         */
        private long gcCount() {
            return collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount)
                    .filter(value -> value >= 0L).sum();
        }

        /**
         * 汇总所有 GC bean 的 collection time。
         */
        private long gcTime() {
            return collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime)
                    .filter(value -> value >= 0L).sum();
        }

        /**
         * 返回当前 direct buffer 占用，平台不可用时显式返回 -1。
         */
        private long directMemory() {
            return directBuffer == null ? -1L : directBuffer.getMemoryUsed();
        }
    }

    /**
     * 保存单次 smoke 输入。
     */
    private record LoadInput(DirectUploadPartDescriptor part, byte[] content) {
    }

    /**
     * 保存单次 smoke 结果。
     */
    private record LoadResult(boolean success, long latencyMillis, long bytes, String hash, String error) {
    }

    /**
     * 保存 JVM 资源起点、峰值和增量。
     */
    private record ResourceSnapshot(
            long heapStartBytes,
            long heapPeakBytes,
            long heapPeakDeltaBytes,
            long gcCollectionCountStart,
            long gcCollectionCountEnd,
            long gcCollectionCountDelta,
            long gcCollectionTimeMillisStart,
            long gcCollectionTimeMillisEnd,
            long gcCollectionTimeMillisDelta,
            int threadStart,
            int threadPeak,
            int threadPeakDelta,
            boolean directBufferAvailable,
            long directBufferStartBytes,
            long directBufferPeakBytes,
            long directBufferPeakDeltaBytes
    ) {
    }

    /**
     * 保存清理前后 lifecycle 与 backlog 计数。
     */
    private record LifecycleSnapshot(
            long receiptCountBeforeCleanup,
            long stagingTombstoneCountBeforeCleanup,
            long receiptCountAfterCleanup,
            long stagingTombstoneCountAfterCleanup,
            String degradedBacklogStatus,
            Long degradedBacklogAfterCleanup,
            String repairBacklogStatus,
            Long repairBacklogAfterCleanup
    ) {
    }

    /**
     * 保存不含凭据的 JVM/主机环境事实。
     */
    private record EnvironmentSnapshot(
            String fingerprint,
            String osName,
            String osArchitecture,
            String javaVersion,
            int availableProcessors,
            long maxHeapBytes
    ) {
    }

    /**
     * 保存机器可读的 direct-upload PR smoke 报告。
     */
    private record LoadSmokeReport(
            String schema,
            String generatedAt,
            EnvironmentSnapshot environment,
            int iterations,
            int concurrency,
            int partBytes,
            long successes,
            long failures,
            long wallMillis,
            double p50Millis,
            double p95Millis,
            double p99Millis,
            double bytesPerSecond,
            ResourceSnapshot resource,
            LifecycleSnapshot lifecycle,
            boolean cleanupSuccess
    ) {
    }
}
