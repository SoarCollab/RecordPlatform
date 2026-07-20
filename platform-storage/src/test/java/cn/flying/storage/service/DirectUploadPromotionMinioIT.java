package cn.flying.storage.service;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3ClientManager.TopologyLease;
import cn.flying.storage.core.S3Monitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用固定版本 MinIO 验证 direct-upload 提升所依赖的真实 S3 provider 合同。
 */
@Testcontainers(disabledWithoutDocker = false)
class DirectUploadPromotionMinioIT {

    private static final int MINIO_PORT = 9000;
    private static final int REDIS_PORT = 6379;
    private static final String ACCESS_KEY = "directupload";
    private static final String SECRET_KEY = "directupload-secret-2026";
    private static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-11-07T00-52-20Z";
    private static final String REDIS_IMAGE = "redis:7.4.9-alpine";
    private static final String ALLOWED_BROWSER_ORIGIN = "http://localhost:5173";
    private static final String DISALLOWED_BROWSER_ORIGIN = "https://evil.example";
    private static final String SAME_SOURCE_BUCKET = "direct-source-same";
    private static final String SAME_TARGET_BUCKET = "direct-target-same";
    private static final String CROSS_SOURCE_BUCKET = "direct-source-cross";
    private static final String CROSS_TARGET_BUCKET = "direct-target-cross";
    private static final String CORS_BUCKET = "direct-browser-cors";
    private static final String DEGRADED_SOURCE_BUCKET = "direct-degraded-source";
    private static final String DEGRADED_TARGET_BUCKET = "direct-degraded-target";
    private static final String DEAD_LETTER_SOURCE_BUCKET = "direct-dead-letter-source";
    private static final String DEAD_LETTER_TARGET_BUCKET = "direct-dead-letter-target";
    private static final String ABORT_RACE_BUCKET = "direct-abort-race";
    private static final String CLEANUP_RACE_BUCKET = "direct-cleanup-race";
    private static final String RESTART_BUCKET = "direct-restart-retry";
    private static final String EXPIRY_SET_KEY = "storage:direct-upload:staging-expiry";
    private static final String STAGING_KEY =
            "tenant/7/staging/direct-upload/provider-session/part-0";

    @Container
    private static final GenericContainer<?> MINIO_A = minioContainer();

    @Container
    private static final GenericContainer<?> MINIO_B = minioContainer();

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(REDIS_PORT)
            .waitingFor(Wait.forListeningPort());

    private static S3Client minioAClient;
    private static S3Client minioBClient;
    private static LettuceConnectionFactory firstRedisConnectionFactory;
    private static LettuceConnectionFactory secondRedisConnectionFactory;
    private static StringRedisTemplate firstRedisTemplate;
    private static StringRedisTemplate secondRedisTemplate;
    private static RedissonClient firstRedisson;
    private static RedissonClient secondRedisson;

    private S3ClientManager clientManager;
    private S3Monitor s3Monitor;
    private ConsistencyRepairService consistencyRepairService;
    private DegradedWriteTracker degradedWriteTracker;
    private DirectUploadLockManager lockManager;
    private DirectUploadStagingTracker stagingTracker;
    private DirectUploadPromotionReceiptStore receiptStore;
    private DirectUploadOperationIntentStore operationIntentStore;
    private StorageProperties storageProperties;
    private ExecutorService executor;
    private SimpleMeterRegistry meterRegistry;
    private DirectUploadLockManager realFirstLockManager;
    private DirectUploadLockManager realSecondLockManager;
    private DirectUploadStagingTracker realFirstTracker;
    private DirectUploadStagingTracker realSecondTracker;
    private DirectUploadPromotionReceiptStore realFirstReceiptStore;
    private DirectUploadPromotionReceiptStore realSecondReceiptStore;
    private DirectUploadOperationIntentStore realFirstIntentStore;
    private DirectUploadOperationIntentStore realSecondIntentStore;

    /**
     * 为两个独立 MinIO endpoint 创建真实同步 S3 客户端。
     */
    @BeforeAll
    static void createClients() {
        minioAClient = s3Client(endpoint(MINIO_A));
        minioBClient = s3Client(endpoint(MINIO_B));
        firstRedisConnectionFactory = redisConnectionFactory();
        secondRedisConnectionFactory = redisConnectionFactory();
        firstRedisTemplate = redisTemplate(firstRedisConnectionFactory);
        secondRedisTemplate = redisTemplate(secondRedisConnectionFactory);
        firstRedisson = redissonClient();
        secondRedisson = redissonClient();
    }

    /**
     * 关闭真实 HTTP 客户端，防止测试 JVM 遗留连接资源。
     */
    @AfterAll
    static void closeClients() {
        if (minioAClient != null) {
            minioAClient.close();
        }
        if (minioBClient != null) {
            minioBClient.close();
        }
        if (firstRedisson != null) {
            firstRedisson.shutdown();
        }
        if (secondRedisson != null) {
            secondRedisson.shutdown();
        }
        if (firstRedisConnectionFactory != null) {
            firstRedisConnectionFactory.destroy();
        }
        if (secondRedisConnectionFactory != null) {
            secondRedisConnectionFactory.destroy();
        }
    }

    /**
     * 为每个合同场景创建独立编排依赖、执行器和指标注册表。
     */
    @BeforeEach
    void setUp() {
        flushRedis();
        clientManager = mock(S3ClientManager.class);
        TopologyLease topologyLease = mock(TopologyLease.class);
        s3Monitor = mock(S3Monitor.class);
        consistencyRepairService = mock(ConsistencyRepairService.class);
        degradedWriteTracker = mock(DegradedWriteTracker.class);
        lockManager = mock(DirectUploadLockManager.class);
        stagingTracker = mock(DirectUploadStagingTracker.class);
        receiptStore = mock(DirectUploadPromotionReceiptStore.class);
        storageProperties = new StorageProperties();
        storageProperties.getDirectUpload().setStreamBufferBytes(8 * 1024);
        executor = Executors.newFixedThreadPool(2);
        meterRegistry = new SimpleMeterRegistry();
        when(lockManager.acquire(any())).thenReturn(() -> { });
        when(clientManager.acquireTopologyLease()).thenReturn(topologyLease);
        when(topologyLease.getClient(anyString())).thenAnswer(invocation ->
                clientManager.getClient(invocation.getArgument(0)));
        when(topologyLease.getNodeConfig(anyString())).thenAnswer(invocation ->
                clientManager.getNodeConfig(invocation.getArgument(0)));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(topologyLease).runIfCurrent(any(Runnable.class));
        realFirstIntentStore = new DirectUploadOperationIntentStore(firstRedisTemplate);
        realSecondIntentStore = new DirectUploadOperationIntentStore(secondRedisTemplate);
        operationIntentStore = realFirstIntentStore;
        realFirstLockManager = new DirectUploadLockManager(firstRedisson, storageProperties);
        realSecondLockManager = new DirectUploadLockManager(secondRedisson, storageProperties);
        realFirstTracker = new DirectUploadStagingTracker(
                firstRedisTemplate,
                storageProperties,
                realFirstLockManager,
                realFirstIntentStore
        );
        realSecondTracker = new DirectUploadStagingTracker(
                secondRedisTemplate,
                storageProperties,
                realSecondLockManager,
                realSecondIntentStore
        );
        realFirstReceiptStore = new DirectUploadPromotionReceiptStore(
                firstRedisTemplate,
                new ObjectMapper(),
                storageProperties
        );
        realSecondReceiptStore = new DirectUploadPromotionReceiptStore(
                secondRedisTemplate,
                new ObjectMapper(),
                storageProperties
        );
    }

    /**
     * 回收每个场景创建的有界执行器和指标注册表。
     */
    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        meterRegistry.close();
    }

    @Test
    @DisplayName("single MinIO endpoint should use server copy and replace staging metadata")
    void shouldUseServerCopyAndReplaceMetadataOnSingleMinioEndpoint() throws Exception {
        createBucket(minioAClient, SAME_SOURCE_BUCKET);
        createBucket(minioAClient, SAME_TARGET_BUCKET);
        byte[] content = "minio-same-endpoint-provider-contract".getBytes(StandardCharsets.UTF_8);
        String stagingEtag = putStaging(minioAClient, SAME_SOURCE_BUCKET, content);
        DirectUploadPartDescriptor descriptor = descriptor(
                SAME_SOURCE_BUCKET,
                SAME_TARGET_BUCKET,
                stagingEtag,
                content
        );
        configureNode(SAME_SOURCE_BUCKET, minioAClient, endpoint(MINIO_A));
        configureNode(SAME_TARGET_BUCKET, minioAClient, endpoint(MINIO_A) + "/");

        DirectUploadDigestAccumulator aggregateDigest = DirectUploadDigestAccumulator.sha256();
        DirectUploadPromotionResult result = service().promote(descriptor, aggregateDigest);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregateDigest.finishHash()).isEqualTo(descriptor.plainHash());
        assertThat(readObject(minioAClient, SAME_TARGET_BUCKET, descriptor.finalObjectName()))
                .containsExactly(content);
        assertFinalMetadata(minioAClient, SAME_TARGET_BUCKET, descriptor);
        assertObjectMissing(minioAClient, SAME_SOURCE_BUCKET, STAGING_KEY);
        assertObjectMissing(minioAClient, SAME_SOURCE_BUCKET, STAGING_KEY + ".sealed");
        assertThat(transferCount("sealed_copy", "success")).isEqualTo(1.0);
        assertThat(transferCount("server_copy", "success")).isEqualTo(1.0);
        assertThat(findTransferCounter("bounded_stream", "success")).isNull();
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
        verify(consistencyRepairService, never())
                .scheduleImmediateRepairByNodesAsync(any(), any(), any());
    }

    @Test
    @DisplayName("two MinIO endpoints should use bounded streaming and preserve trusted metadata")
    void shouldStreamAcrossTwoMinioEndpoints() throws Exception {
        createBucket(minioAClient, CROSS_SOURCE_BUCKET);
        createBucket(minioBClient, CROSS_TARGET_BUCKET);
        byte[] content = "minio-cross-endpoint-provider-contract".getBytes(StandardCharsets.UTF_8);
        String stagingEtag = putStaging(minioAClient, CROSS_SOURCE_BUCKET, content);
        DirectUploadPartDescriptor descriptor = descriptor(
                CROSS_SOURCE_BUCKET,
                CROSS_TARGET_BUCKET,
                stagingEtag,
                content
        );
        configureNode(CROSS_SOURCE_BUCKET, minioAClient, endpoint(MINIO_A));
        configureNode(CROSS_TARGET_BUCKET, minioBClient, endpoint(MINIO_B));

        DirectUploadDigestAccumulator aggregateDigest = DirectUploadDigestAccumulator.sha256();
        DirectUploadPromotionResult result = service().promote(descriptor, aggregateDigest);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregateDigest.finishHash()).isEqualTo(descriptor.plainHash());
        assertThat(readObject(minioBClient, CROSS_TARGET_BUCKET, descriptor.finalObjectName()))
                .containsExactly(content);
        assertFinalMetadata(minioBClient, CROSS_TARGET_BUCKET, descriptor);
        assertObjectMissing(minioAClient, CROSS_SOURCE_BUCKET, STAGING_KEY);
        assertObjectMissing(minioAClient, CROSS_SOURCE_BUCKET, STAGING_KEY + ".sealed");
        assertThat(transferCount("sealed_copy", "success")).isEqualTo(1.0);
        assertThat(transferCount("bounded_stream", "success")).isEqualTo(1.0);
        assertThat(findTransferCounter("server_copy", "success")).isNull();
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
        verify(consistencyRepairService, never())
                .scheduleImmediateRepairByNodesAsync(any(), any(), any());
    }

    /**
     * 验证固定 MinIO 版本只允许精确前端来源，并让浏览器读取预签名 PUT 返回的 ETag。
     */
    @Test
    @DisplayName("MinIO should allow the exact browser origin and expose the PUT ETag")
    void shouldEnforceDirectUploadBrowserCorsContract() throws Exception {
        createBucket(minioAClient, CORS_BUCKET);
        byte[] content = "browser-direct-upload-cors".getBytes(StandardCharsets.UTF_8);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try (S3Presigner presigner = s3Presigner(endpoint(MINIO_A))) {
            URI uploadUri = presigner.presignPutObject(PutObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofMinutes(5))
                            .putObjectRequest(PutObjectRequest.builder()
                                    .bucket(CORS_BUCKET)
                                    .key("tenant/7/staging/direct-upload/cors-session/part-0")
                                    .contentType("application/octet-stream")
                                    .build())
                            .build())
                    .url()
                    .toURI();

            HttpResponse<Void> preflight = httpClient.send(
                    HttpRequest.newBuilder(uploadUri)
                            .header("Origin", ALLOWED_BROWSER_ORIGIN)
                            .header("Access-Control-Request-Method", "PUT")
                            .header("Access-Control-Request-Headers", "content-type")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());

            assertThat(preflight.statusCode()).isIn(200, 204);
            assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin"))
                    .contains(ALLOWED_BROWSER_ORIGIN);
            assertThat(headerContainsToken(preflight, "Access-Control-Allow-Methods", "PUT"))
                    .isTrue();
            assertThat(headerContainsToken(preflight, "Access-Control-Allow-Headers", "Content-Type"))
                    .isTrue();

            HttpResponse<Void> rejectedPreflight = httpClient.send(
                    HttpRequest.newBuilder(uploadUri)
                            .header("Origin", DISALLOWED_BROWSER_ORIGIN)
                            .header("Access-Control-Request-Method", "PUT")
                            .header("Access-Control-Request-Headers", "content-type")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(rejectedPreflight.headers().firstValue("Access-Control-Allow-Origin"))
                    .isEmpty();

            HttpResponse<Void> upload = httpClient.send(
                    HttpRequest.newBuilder(uploadUri)
                            .header("Origin", ALLOWED_BROWSER_ORIGIN)
                            .header("Content-Type", "application/octet-stream")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());

            assertThat(upload.statusCode()).isEqualTo(200);
            assertThat(upload.headers().firstValue("Access-Control-Allow-Origin"))
                    .contains(ALLOWED_BROWSER_ORIGIN);
            assertThat(upload.headers().firstValue("ETag"))
                    .hasValueSatisfying(value -> assertThat(value).isNotBlank());
            assertThat(headerContainsToken(upload, "Access-Control-Expose-Headers", "ETag"))
                    .isTrue();
        }
    }

    /**
     * 验证 direct-upload 降级成功记录可由真实 Redis 恢复监听器驱动，并通过真实 MinIO 补齐副本。
     */
    @Test
    @DisplayName("real Redis recovery should repair a degraded direct-upload replica in MinIO")
    void shouldRecoverDegradedReplicaThroughRealRedisAndMinio() throws Exception {
        createBucket(minioAClient, DEGRADED_SOURCE_BUCKET);
        createBucket(minioBClient, DEGRADED_TARGET_BUCKET);
        byte[] content = "direct-upload-degraded-repair".getBytes(StandardCharsets.UTF_8);
        DirectUploadPartDescriptor descriptor = realDescriptor(
                "degraded-repair-it",
                DEGRADED_SOURCE_BUCKET,
                List.of(DEGRADED_SOURCE_BUCKET, DEGRADED_TARGET_BUCKET),
                1,
                putStaging(
                        minioAClient,
                        DEGRADED_SOURCE_BUCKET,
                        "tenant/7/staging/direct-upload/degraded-repair-it/part-0",
                        content
                ),
                content
        );
        AtomicBoolean targetOnline = new AtomicBoolean(false);
        configureNode(DEGRADED_SOURCE_BUCKET, minioAClient, endpoint(MINIO_A));
        configureNode(DEGRADED_TARGET_BUCKET, minioBClient, endpoint(MINIO_B));
        when(s3Monitor.isNodeOnline(DEGRADED_TARGET_BUCKET))
                .thenAnswer(ignored -> targetOnline.get());
        FaultDomainManager faultDomainManager = faultDomainTopology(
                descriptor.cipherHash(),
                DEGRADED_SOURCE_BUCKET,
                DEGRADED_TARGET_BUCKET
        );
        DegradedWriteTracker realDegradedTracker = realDegradedWriteTracker(faultDomainManager);
        ConsistencyRepairService deferredRepair = mock(ConsistencyRepairService.class);
        when(deferredRepair.scheduleImmediateRepairByNodesAsync(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(false));

        DirectUploadPromotionResult result = realPromotionService(
                deferredRepair,
                realDegradedTracker,
                realFirstLockManager,
                realFirstTracker,
                realFirstReceiptStore
        ).promote(descriptor, DirectUploadDigestAccumulator.sha256());

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(realDegradedTracker.getPendingCount()).isEqualTo(1);
        assertObjectMissing(minioBClient, DEGRADED_TARGET_BUCKET, descriptor.finalObjectName());

        targetOnline.set(true);
        ConsistencyRepairService realRepair = realConsistencyRepairService(faultDomainManager);
        realRecoveryListener(realDegradedTracker, faultDomainManager, realRepair)
                .checkPendingSyncs();

        assertThat(readObject(
                minioBClient,
                DEGRADED_TARGET_BUCKET,
                descriptor.finalObjectName()
        )).containsExactly(content);
        assertFinalMetadata(minioBClient, DEGRADED_TARGET_BUCKET, descriptor);
        assertThat(realDegradedTracker.getPendingCount()).isZero();
        assertThat(realDegradedTracker.getDeadLetterCount()).isZero();
    }

    /**
     * 验证真实 MinIO 中确定性的内容地址不匹配会通过真实 Redis 生命周期收敛到死信。
     */
    @Test
    @DisplayName("deterministic MinIO content mismatch should close in the Redis dead letter lifecycle")
    void shouldDeadLetterDeterministicContentMismatchThroughRealMinioAndRedis() throws Exception {
        createBucket(minioAClient, DEAD_LETTER_SOURCE_BUCKET);
        createBucket(minioBClient, DEAD_LETTER_TARGET_BUCKET);
        byte[] declaredContent = "declared-canonical-content".getBytes(StandardCharsets.UTF_8);
        byte[] corruptedContent = "provider-content-does-not-match-address"
                .getBytes(StandardCharsets.UTF_8);
        String declaredHash = sha256(declaredContent);
        String finalObjectName = "tenant/7/" + declaredHash;
        minioAClient.putObject(
                PutObjectRequest.builder()
                        .bucket(DEAD_LETTER_SOURCE_BUCKET)
                        .key(finalObjectName)
                        .metadata(trustedMetadata(declaredHash))
                        .build(),
                RequestBody.fromBytes(corruptedContent)
        );
        configureNode(DEAD_LETTER_SOURCE_BUCKET, minioAClient, endpoint(MINIO_A));
        configureNode(DEAD_LETTER_TARGET_BUCKET, minioBClient, endpoint(MINIO_B));
        FaultDomainManager faultDomainManager = faultDomainTopology(
                declaredHash,
                DEAD_LETTER_SOURCE_BUCKET,
                DEAD_LETTER_TARGET_BUCKET
        );
        DegradedWriteTracker realDegradedTracker = realDegradedWriteTracker(faultDomainManager);
        storageProperties.getDegradedWrite().setMaxSyncFailures(1);
        storageProperties.getDegradedWrite().setRepairTimeoutSeconds(10);
        realDegradedTracker.recordAuthoritativeDegradedWrite(
                declaredHash,
                List.of(DEAD_LETTER_SOURCE_BUCKET),
                7L
        );

        ConsistencyRepairService realRepair = realConsistencyRepairService(faultDomainManager);
        realRecoveryListener(realDegradedTracker, faultDomainManager, realRepair)
                .checkPendingSyncs();

        assertThat(realDegradedTracker.getPendingCount()).isZero();
        assertThat(realDegradedTracker.getDeadLetterCount()).isEqualTo(1);
        assertObjectMissing(minioBClient, DEAD_LETTER_TARGET_BUCKET, finalObjectName);
    }

    /**
     * 验证两个服务实例共享真实 Redis 锁时，complete 与 abort 只能产生一个一致终态。
     */
    @Test
    @DisplayName("complete and abort should serialize across two real Redis clients")
    void shouldSerializeCompleteAndAbortAcrossRealRedisLock() throws Exception {
        createBucket(minioAClient, ABORT_RACE_BUCKET);
        byte[] content = "complete-abort-race".getBytes(StandardCharsets.UTF_8);
        String stagingKey = "tenant/7/staging/direct-upload/complete-abort-it/part-0";
        DirectUploadPartDescriptor descriptor = realDescriptor(
                "complete-abort-it",
                ABORT_RACE_BUCKET,
                List.of(ABORT_RACE_BUCKET),
                1,
                putStaging(minioAClient, ABORT_RACE_BUCKET, stagingKey, content),
                content
        );
        configureNode(ABORT_RACE_BUCKET, minioAClient, endpoint(MINIO_A));
        DirectUploadPromotionService completeService = realPromotionService(
                consistencyRepairService,
                degradedWriteTracker,
                realFirstLockManager,
                realFirstTracker,
                realFirstReceiptStore
        );
        DirectUploadPromotionService abortService = realPromotionService(
                consistencyRepairService,
                degradedWriteTracker,
                realSecondLockManager,
                realSecondTracker,
                realSecondReceiptStore
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService raceExecutor = Executors.newFixedThreadPool(2);
        DirectUploadPromotionResult completeResult = null;
        Throwable completeFailure = null;
        try {
            Future<DirectUploadPromotionResult> complete = raceExecutor.submit(() -> {
                await(start);
                return completeService.promote(
                        descriptor,
                        DirectUploadDigestAccumulator.sha256()
                );
            });
            Future<?> abort = raceExecutor.submit(() -> {
                await(start);
                abortService.abort(descriptor.stagingDescriptor());
                return null;
            });
            start.countDown();
            abort.get(20, TimeUnit.SECONDS);
            try {
                completeResult = complete.get(20, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                completeFailure = e.getCause();
            }
        } finally {
            raceExecutor.shutdownNow();
        }

        boolean finalExists = objectExists(
                minioAClient,
                ABORT_RACE_BUCKET,
                descriptor.finalObjectName()
        );
        Optional<DirectUploadPromotionReceiptStore.PromotionReceipt> receipt =
                realSecondReceiptStore.findValidated(descriptor);
        assertThat(finalExists).isEqualTo(completeResult != null);
        assertThat(receipt.isPresent()).isEqualTo(completeResult != null);
        assertThat(completeFailure == null).isEqualTo(completeResult != null);
        assertObjectMissing(minioAClient, ABORT_RACE_BUCKET, stagingKey);
        assertObjectMissing(minioAClient, ABORT_RACE_BUCKET, stagingKey + ".sealed");
        assertThat(firstRedisTemplate.opsForZSet().score(
                EXPIRY_SET_KEY,
                encodeLifecycleMember(descriptor.stagingDescriptor())
        )).isNotNull();
    }

    /**
     * 验证真实 Redis 生命周期清理与 complete 竞争时不会留下无 receipt 的混合终态。
     */
    @Test
    @DisplayName("complete and cleanup should serialize across the real Redis part lock")
    void shouldSerializeCompleteAndCleanupAcrossRealRedisLock() throws Exception {
        createBucket(minioAClient, CLEANUP_RACE_BUCKET);
        byte[] content = "complete-cleanup-race".getBytes(StandardCharsets.UTF_8);
        String stagingKey = "tenant/7/staging/direct-upload/complete-cleanup-it/part-0";
        DirectUploadPartDescriptor descriptor = realDescriptor(
                "complete-cleanup-it",
                CLEANUP_RACE_BUCKET,
                List.of(CLEANUP_RACE_BUCKET),
                1,
                putStaging(minioAClient, CLEANUP_RACE_BUCKET, stagingKey, content),
                content
        );
        configureNode(CLEANUP_RACE_BUCKET, minioAClient, endpoint(MINIO_A));
        realFirstTracker.record(descriptor.stagingDescriptor());
        String member = encodeLifecycleMember(descriptor.stagingDescriptor());
        firstRedisTemplate.opsForZSet().add(
                EXPIRY_SET_KEY,
                member,
                System.currentTimeMillis() - 1D
        );
        DirectUploadPromotionService completeService = realPromotionService(
                consistencyRepairService,
                degradedWriteTracker,
                realFirstLockManager,
                realFirstTracker,
                realFirstReceiptStore
        );
        DirectUploadStagingCleanupService cleanupService = new DirectUploadStagingCleanupService(
                clientManager,
                storageProperties,
                realSecondTracker,
                realSecondLockManager,
                realSecondIntentStore,
                meterRegistry
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService raceExecutor = Executors.newFixedThreadPool(2);
        DirectUploadPromotionResult completeResult = null;
        Throwable completeFailure = null;
        try {
            Future<DirectUploadPromotionResult> complete = raceExecutor.submit(() -> {
                await(start);
                return completeService.promote(
                        descriptor,
                        DirectUploadDigestAccumulator.sha256()
                );
            });
            Future<?> cleanup = raceExecutor.submit(() -> {
                await(start);
                cleanupService.cleanupExpiredStagingObjects();
                return null;
            });
            start.countDown();
            cleanup.get(20, TimeUnit.SECONDS);
            try {
                completeResult = complete.get(20, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                completeFailure = e.getCause();
            }
        } finally {
            raceExecutor.shutdownNow();
        }

        boolean finalExists = objectExists(
                minioAClient,
                CLEANUP_RACE_BUCKET,
                descriptor.finalObjectName()
        );
        Optional<DirectUploadPromotionReceiptStore.PromotionReceipt> receipt =
                realSecondReceiptStore.findValidated(descriptor);
        assertThat(finalExists).isEqualTo(completeResult != null);
        assertThat(receipt.isPresent()).isEqualTo(completeResult != null);
        assertThat(completeFailure == null).isEqualTo(completeResult != null);
        assertObjectMissing(minioAClient, CLEANUP_RACE_BUCKET, stagingKey);
        assertObjectMissing(minioAClient, CLEANUP_RACE_BUCKET, stagingKey + ".sealed");
        Double lifecycleScore = firstRedisTemplate.opsForZSet().score(EXPIRY_SET_KEY, member);
        if (completeResult == null) {
            assertThat(lifecycleScore).isNull();
        } else {
            assertThat(lifecycleScore).isNotNull().isGreaterThan(System.currentTimeMillis());
        }
    }

    /**
     * 验证服务重启并切换 Redis 客户端后，可仅凭 receipt 与 final 副本幂等完成重试。
     */
    @Test
    @DisplayName("service restart should retry from Redis receipt without staging")
    void shouldRetryFromReceiptAfterServiceRestart() throws Exception {
        createBucket(minioAClient, RESTART_BUCKET);
        byte[] content = "restart-receipt-retry".getBytes(StandardCharsets.UTF_8);
        String stagingKey = "tenant/7/staging/direct-upload/restart-retry-it/part-0";
        DirectUploadPartDescriptor descriptor = realDescriptor(
                "restart-retry-it",
                RESTART_BUCKET,
                List.of(RESTART_BUCKET),
                1,
                putStaging(minioAClient, RESTART_BUCKET, stagingKey, content),
                content
        );
        configureNode(RESTART_BUCKET, minioAClient, endpoint(MINIO_A));

        DirectUploadPromotionResult first = realPromotionService(
                consistencyRepairService,
                degradedWriteTracker,
                realFirstLockManager,
                realFirstTracker,
                realFirstReceiptStore
        ).promote(descriptor, DirectUploadDigestAccumulator.sha256());
        assertObjectMissing(minioAClient, RESTART_BUCKET, stagingKey);
        assertObjectMissing(minioAClient, RESTART_BUCKET, stagingKey + ".sealed");

        DirectUploadDigestAccumulator restartedDigest = DirectUploadDigestAccumulator.sha256();
        DirectUploadPromotionResult restarted = realPromotionService(
                consistencyRepairService,
                degradedWriteTracker,
                realSecondLockManager,
                realSecondTracker,
                realSecondReceiptStore
        ).promote(descriptor, restartedDigest);

        assertThat(restarted.size()).isEqualTo(first.size()).isEqualTo(content.length);
        assertThat(restarted.eTag()).isEqualTo(first.eTag());
        assertThat(restartedDigest.finishHash()).isEqualTo(descriptor.plainHash());
        assertThat(readObject(minioAClient, RESTART_BUCKET, descriptor.finalObjectName()))
                .containsExactly(content);
        assertThat(realSecondReceiptStore.findValidated(descriptor)).isPresent();
        assertObjectMissing(minioAClient, RESTART_BUCKET, stagingKey);
        assertObjectMissing(minioAClient, RESTART_BUCKET, stagingKey + ".sealed");
    }

    /**
     * 构建单节点 quorum 的真实提升服务。
     */
    private DirectUploadPromotionService service() {
        return new DirectUploadPromotionService(
                clientManager,
                s3Monitor,
                storageProperties,
                consistencyRepairService,
                degradedWriteTracker,
                lockManager,
                stagingTracker,
                receiptStore,
                operationIntentStore,
                executor,
                meterRegistry
        );
    }

    /**
     * 构建使用真实 Redis 生命周期依赖的提升服务，同时允许场景选择真实或受控修复组件。
     */
    private DirectUploadPromotionService realPromotionService(
            ConsistencyRepairService repairService,
            DegradedWriteTracker tracker,
            DirectUploadLockManager realLockManager,
            DirectUploadStagingTracker realTracker,
            DirectUploadPromotionReceiptStore realReceiptStore
    ) {
        return new DirectUploadPromotionService(
                clientManager,
                s3Monitor,
                storageProperties,
                repairService,
                tracker,
                realLockManager,
                realTracker,
                realReceiptStore,
                realLockManager == realSecondLockManager
                        ? realSecondIntentStore
                        : realFirstIntentStore,
                executor,
                meterRegistry
        );
    }

    /**
     * 创建注入真实 Redis、真实配置与确定性 placement 的降级写追踪器。
     */
    private DegradedWriteTracker realDegradedWriteTracker(
            FaultDomainManager faultDomainManager
    ) {
        DegradedWriteTracker tracker = new DegradedWriteTracker();
        ReflectionTestUtils.setField(tracker, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(tracker, "stringRedisTemplate", firstRedisTemplate);
        ReflectionTestUtils.setField(tracker, "redissonClient", firstRedisson);
        ReflectionTestUtils.setField(tracker, "faultDomainManager", faultDomainManager);
        ReflectionTestUtils.setField(tracker, "storageProperties", storageProperties);
        return tracker;
    }

    /**
     * 创建对真实 MinIO 执行条件 GET、流式复制与目标内容地址复核的一致性修复服务。
     */
    private ConsistencyRepairService realConsistencyRepairService(
            FaultDomainManager faultDomainManager
    ) {
        ConsistencyRepairService repairService = new ConsistencyRepairService();
        ReflectionTestUtils.setField(repairService, "clientManager", clientManager);
        ReflectionTestUtils.setField(repairService, "s3Monitor", s3Monitor);
        ReflectionTestUtils.setField(repairService, "faultDomainManager", faultDomainManager);
        ReflectionTestUtils.setField(repairService, "redissonClient", firstRedisson);
        ReflectionTestUtils.setField(repairService, "storageProperties", storageProperties);
        ReflectionTestUtils.setField(repairService, "batchSize", 100);
        ReflectionTestUtils.setField(repairService, "lockTimeoutSeconds", 600L);
        ReflectionTestUtils.setField(repairService, "repairEnabled", true);
        return repairService;
    }

    /**
     * 创建连接真实 Redis tracker 与真实 MinIO repair service 的恢复监听器。
     */
    private DomainRecoveryListener realRecoveryListener(
            DegradedWriteTracker tracker,
            FaultDomainManager faultDomainManager,
            ConsistencyRepairService repairService
    ) {
        DomainRecoveryListener listener = new DomainRecoveryListener();
        ReflectionTestUtils.setField(listener, "degradedWriteTracker", tracker);
        ReflectionTestUtils.setField(listener, "faultDomainManager", faultDomainManager);
        ReflectionTestUtils.setField(listener, "repairService", repairService);
        ReflectionTestUtils.setField(listener, "storageProperties", storageProperties);
        ReflectionTestUtils.setField(listener, "s3Monitor", s3Monitor);
        return listener;
    }

    /**
     * 构建两个故障域各含一个精确 hash 目标的稳定 placement。
     */
    private FaultDomainManager faultDomainTopology(
            String objectHash,
            String sourceNode,
            String targetNode
    ) {
        FaultDomainManager faultDomainManager = mock(FaultDomainManager.class);
        when(faultDomainManager.getPlannedTargetsSnapshot(objectHash))
                .thenReturn(Map.of("domain-a", sourceNode, "domain-b", targetNode));
        when(faultDomainManager.getPlannedTargetDomains(objectHash))
                .thenReturn(List.of("domain-a", "domain-b"));
        when(faultDomainManager.getTargetNodeInDomain(objectHash, "domain-a"))
                .thenReturn(sourceNode);
        when(faultDomainManager.getTargetNodeInDomain(objectHash, "domain-b"))
                .thenReturn(targetNode);
        when(faultDomainManager.getNodeDomain(sourceNode)).thenReturn("domain-a");
        when(faultDomainManager.getNodeDomain(targetNode)).thenReturn("domain-b");
        return faultDomainManager;
    }

    /**
     * 根据真实内容和测试会话构建规范 staging/final identity 的分片描述。
     */
    private DirectUploadPartDescriptor realDescriptor(
            String sessionId,
            String sourceBucket,
            List<String> targetNodes,
            int requiredQuorum,
            String eTag,
            byte[] content
    ) throws Exception {
        String hash = sha256(content);
        return new DirectUploadPartDescriptor(
                7L,
                sessionId,
                0,
                sourceBucket,
                "tenant/7/staging/direct-upload/" + sessionId + "/part-0",
                "tenant/7/" + hash,
                content.length,
                eTag,
                hash,
                hash,
                "SHA-256",
                targetNodes,
                requiredQuorum
        );
    }

    /**
     * 建立节点到真实 endpoint 和客户端的映射，并声明目标节点在线。
     */
    private void configureNode(String nodeName, S3Client client, String nodeEndpoint) {
        when(clientManager.getClient(nodeName)).thenReturn(client);
        NodeConfig nodeConfig = new NodeConfig();
        nodeConfig.setName(nodeName);
        nodeConfig.setEndpoint(nodeEndpoint);
        nodeConfig.setPhysicalStorageId("physical-" + nodeName);
        when(clientManager.getNodeConfig(nodeName)).thenReturn(nodeConfig);
        when(s3Monitor.isNodeOnline(nodeName)).thenReturn(true);
    }

    /**
     * 创建带 staging-only metadata 的源对象，用于证明 final metadata 采用替换语义。
     */
    private String putStaging(S3Client client, String bucket, byte[] content) {
        return putStaging(client, bucket, STAGING_KEY, content);
    }

    /**
     * 在指定规范 key 创建带 staging-only metadata 的源对象。
     */
    private String putStaging(
            S3Client client,
            String bucket,
            String stagingKey,
            byte[] content
    ) {
        return client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(stagingKey)
                        .metadata(Map.of("staging-only", "must-not-survive"))
                        .build(),
                RequestBody.fromBytes(content)
        ).eTag();
    }

    /**
     * 根据测试内容构建包含可信 SHA-256 的分片合同。
     */
    private DirectUploadPartDescriptor descriptor(
            String sourceBucket,
            String targetBucket,
            String eTag,
            byte[] content
    ) throws Exception {
        String hash = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        return new DirectUploadPartDescriptor(
                7L,
                "provider-session",
                0,
                sourceBucket,
                STAGING_KEY,
                "tenant/7/" + hash,
                content.length,
                eTag,
                hash,
                hash,
                "SHA-256",
                List.of(targetBucket),
                1
        );
    }

    /**
     * 读取测试目标对象的小型内容，验证 provider 实际写入结果。
     */
    private byte[] readObject(S3Client client, String bucket, String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()).asByteArray();
    }

    /**
     * 判断真实 provider 中对象是否存在，仅把明确的 404 解释为不存在。
     */
    private boolean objectExists(S3Client client, String bucket, String key) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 计算与生产 canonical object key 相同格式的 SHA-256 内容地址。
     */
    private String sha256(byte[] content) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)
        );
    }

    /**
     * 构建真实修复流程需要原样复制并复核的可信 final metadata。
     */
    private Map<String, String> trustedMetadata(String hash) {
        return Map.of(
                "file-hash", hash,
                "tenant-id", "7",
                "checksum-algorithm", "SHA-256",
                "plain-hash", hash,
                "cipher-hash", hash
        );
    }

    /**
     * 复现 staging tracker 的 URL-safe 成员编码，以便精确检查真实 Redis 终态。
     */
    private String encodeLifecycleMember(DirectUploadStagingDescriptor descriptor) {
        return String.join(
                ".",
                "v1",
                String.valueOf(descriptor.tenantId()),
                String.valueOf(descriptor.partIndex()),
                encodeLifecycleToken(descriptor.nodeName()),
                encodeLifecycleToken(descriptor.sessionId()),
                encodeLifecycleToken(descriptor.objectName())
        );
    }

    /**
     * 使用与生产 tracker 相同的无 padding Base64 URL 编码一个身份字段。
     */
    private String encodeLifecycleToken(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 等待并发场景共同起跑，中断时恢复线程标志并使测试立即失败。
     */
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for race start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for race start", e);
        }
    }

    /**
     * 验证 final 对象只保留 storage 生成的可信元数据。
     */
    private void assertFinalMetadata(
            S3Client client,
            String bucket,
            DirectUploadPartDescriptor descriptor
    ) {
        HeadObjectResponse head = client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(descriptor.finalObjectName())
                .build());
        assertThat(head.contentLength()).isEqualTo(descriptor.size());
        assertThat(head.eTag()).isNotBlank();
        assertThat(head.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "file-hash", descriptor.cipherHash(),
                "tenant-id", String.valueOf(descriptor.tenantId()),
                "checksum-algorithm", "SHA-256",
                "plain-hash", descriptor.plainHash(),
                "cipher-hash", descriptor.cipherHash()
        ));
        assertThat(head.metadata()).doesNotContainKey("staging-only");
    }

    /**
     * 验证 quorum 成功后规范 staging 对象已从 provider 删除。
     */
    private void assertObjectMissing(S3Client client, String bucket, String key) {
        assertThatThrownBy(() -> client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()))
                .isInstanceOf(S3Exception.class)
                .satisfies(error -> assertThat(((S3Exception) error).statusCode()).isEqualTo(404));
    }

    /**
     * 返回指定传输模式的计数器，未创建时返回 null。
     */
    private Counter findTransferCounter(String mode, String result) {
        return meterRegistry.find("storage_direct_upload_transfers_total")
                .tags("mode", mode, "result", result)
                .counter();
    }

    /**
     * 返回已创建传输计数器的当前值。
     */
    private double transferCount(String mode, String result) {
        Counter counter = findTransferCounter(mode, result);
        assertThat(counter).isNotNull();
        return counter.count();
    }

    /**
     * 创建固定镜像和健康门禁的 MinIO 容器。
     */
    private static GenericContainer<?> minioContainer() {
        return new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
                .withExposedPorts(MINIO_PORT)
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withEnv("MINIO_API_CORS_ALLOW_ORIGIN", ALLOWED_BROWSER_ORIGIN)
                .withCommand("server", "/data")
                .waitingFor(Wait.forHttp("/minio/health/ready")
                        .forPort(MINIO_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(90)));
    }

    /**
     * 创建并启动连接同一真实 Redis 容器的独立 Lettuce 工厂。
     */
    private static LettuceConnectionFactory redisConnectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(
                        REDIS.getHost(),
                        REDIS.getMappedPort(REDIS_PORT)
                )
        );
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    /**
     * 创建使用字符串序列化并绑定指定连接工厂的 Redis template。
     */
    private static StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 创建连接同一 Redis 容器但具有独立连接池和锁 watchdog 的 Redisson 客户端。
     */
    private static RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT))
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2);
        return Redisson.create(config);
    }

    /**
     * 使用真实 Lettuce 连接清空测试 database，隔离 receipt、claim 和降级写状态。
     */
    private static void flushRedis() {
        try (RedisConnection connection = firstRedisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    /**
     * 返回容器映射到宿主机的 HTTP endpoint。
     */
    private static String endpoint(GenericContainer<?> container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MINIO_PORT);
    }

    /**
     * 构建强制 path-style 的 MinIO S3 客户端。
     */
    private static S3Client s3Client(String endpoint) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    /**
     * 构建与真实 storage presigner 相同的 path-style 预签名客户端。
     */
    private static S3Presigner s3Presigner(String endpoint) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    /**
     * 按逗号拆分 CORS 响应头并执行大小写不敏感的 token 匹配。
     */
    private static boolean headerContainsToken(
            HttpResponse<?> response,
            String headerName,
            String expectedToken
    ) {
        return response.headers().allValues(headerName).stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(String::trim)
                .anyMatch(expectedToken::equalsIgnoreCase);
    }

    /**
     * 创建测试桶；每个合同场景使用独立且确定的桶名。
     */
    private static void createBucket(S3Client client, String bucket) {
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    }
}
