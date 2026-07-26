package cn.flying.storage.service;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3ClientManager.TopologyLease;
import cn.flying.storage.core.S3Monitor;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用固定 MinIO 与 Toxiproxy 验证 direct-upload 故障矩阵中的真实 provider 和网络边界。
 *
 * <p>矩阵追踪关系如下：</p>
 * <ul>
 *     <li>F01-F06、F12-F14：本类同编号测试。</li>
 *     <li>F07：{@link DirectUploadPromotionMinioIT#shouldRecoverDegradedReplicaThroughRealRedisAndMinio()}。</li>
 *     <li>F08：本类 F14 内的真实 repair callback timeout，及
 *         {@link DirectUploadPromotionServiceTest#shouldTrackPartialQuorumAndKeepFailedRepairDurable()}。</li>
 *     <li>F09：{@link DirectUploadPromotionMinioIT#shouldDeadLetterDeterministicContentMismatchThroughRealMinioAndRedis()}。</li>
 *     <li>F10：{@link DirectUploadRedisLifecycleIT#shouldMergePromotionReceiptAcrossRealRedisClients()}
 *         与 {@link DirectUploadPromotionMinioIT#shouldRetryFromReceiptAfterServiceRestart()}。</li>
 *     <li>F11：{@link DirectUploadPromotionMinioIT#shouldSerializeCompleteAndAbortAcrossRealRedisLock()}
 *         与 {@link DirectUploadPromotionMinioIT#shouldSerializeCompleteAndCleanupAcrossRealRedisLock()}。</li>
 * </ul>
 *
 * <p>本类只补此前缺失的真实网络和 provider 证据，避免复制已有慢测。</p>
 */
@Testcontainers(disabledWithoutDocker = false)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DirectUploadFaultMatrixMinioIT {

    private static final int MINIO_PORT = 9000;
    private static final String ACCESS_KEY = "directupload";
    private static final String SECRET_KEY = "directupload-secret-2026";
    private static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-11-07T00-52-20Z";
    private static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.5.0";
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final GenericContainer<?> MINIO_A = minioContainer("fault-minio-a");

    @Container
    private static final GenericContainer<?> MINIO_B = minioContainer("fault-minio-b");

    @Container
    private static final GenericContainer<?> MINIO_C = minioContainer("fault-minio-c");

    /**
     * 创建具有独立数据卷和网络身份的固定版本 MinIO 容器。
     */
    private static GenericContainer<?> minioContainer(String networkAlias) {
        return new GenericContainer<>(
            DockerImageName.parse(MINIO_IMAGE))
            .withNetwork(NETWORK)
            .withNetworkAliases(networkAlias)
            .withExposedPorts(MINIO_PORT)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready")
                    .forPort(MINIO_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(90)));
    }

    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse(TOXIPROXY_IMAGE)
                    .asCompatibleSubstituteFor("shopify/toxiproxy"))
            .withNetwork(NETWORK);

    private static S3Client directClient;
    private static S3Client directBClient;
    private static S3Client directCClient;
    private static ToxiproxyContainer.ContainerProxy firstProxy;
    private static ToxiproxyContainer.ContainerProxy secondProxy;

    private final Set<BucketResource> buckets = new LinkedHashSet<>();
    private final List<S3Client> caseClients = new ArrayList<>();
    private S3Client firstProxyClient;
    private S3Client secondProxyClient;
    private S3ClientManager clientManager;
    private S3Monitor s3Monitor;
    private ConsistencyRepairService repairService;
    private DegradedWriteTracker degradedWriteTracker;
    private DirectUploadLockManager lockManager;
    private DirectUploadStagingTracker stagingTracker;
    private DirectUploadPromotionReceiptStore receiptStore;
    private DirectUploadOperationIntentStore operationIntentStore;
    private DirectUploadOperationIntentStore.OperationIntent operationIntent;
    private StorageProperties storageProperties;
    private ExecutorService executor;
    private SimpleMeterRegistry meterRegistry;

    /**
     * 创建直连和两个彼此独立的 Toxiproxy S3 客户端。
     */
    @BeforeAll
    static void createProviderClients() {
        directClient = s3Client(endpoint(MINIO_A));
        directBClient = s3Client(endpoint(MINIO_B));
        directCClient = s3Client(endpoint(MINIO_C));
        firstProxy = TOXIPROXY.getProxy("fault-minio-b", MINIO_PORT);
        secondProxy = TOXIPROXY.getProxy("fault-minio-c", MINIO_PORT);
    }

    /**
     * 关闭共享 provider 客户端。
     */
    @AfterAll
    static void closeProviderClients() {
        if (directClient != null) {
            directClient.close();
        }
        if (directBClient != null) {
            directBClient.close();
        }
        if (directCClient != null) {
            directCClient.close();
        }
    }

    /**
     * 为每个故障场景创建独立客户端、编排依赖和资源边界。
     */
    @BeforeEach
    void setUp() {
        firstProxy.setConnectionCut(false);
        secondProxy.setConnectionCut(false);
        firstProxyClient = s3Client(proxyEndpoint(firstProxy));
        secondProxyClient = s3Client(proxyEndpoint(secondProxy));
        caseClients.add(firstProxyClient);
        caseClients.add(secondProxyClient);

        clientManager = mock(S3ClientManager.class);
        TopologyLease topologyLease = mock(TopologyLease.class);
        s3Monitor = mock(S3Monitor.class);
        repairService = mock(ConsistencyRepairService.class);
        degradedWriteTracker = mock(DegradedWriteTracker.class);
        lockManager = mock(DirectUploadLockManager.class);
        stagingTracker = mock(DirectUploadStagingTracker.class);
        receiptStore = mock(DirectUploadPromotionReceiptStore.class);
        operationIntentStore = mock(DirectUploadOperationIntentStore.class);
        operationIntent = new DirectUploadOperationIntentStore.OperationIntent(
                "storage:direct-upload:operation-intent:v1:test",
                "storage:direct-upload:operation-intent:v1:test:fence",
                "v1|COMPLETE|" + "a".repeat(64) + "|" + "b".repeat(64),
                DirectUploadOperationIntentStore.OperationMode.COMPLETE,
                "a".repeat(64),
                "b".repeat(64),
                1L
        );
        storageProperties = new StorageProperties();
        storageProperties.getReplication().setFactor(3);
        storageProperties.getReplication().setQuorum("2");
        storageProperties.getDirectUpload().setStreamBufferBytes(8 * 1024);
        storageProperties.getDirectUpload().setTransferTimeoutSeconds(3);
        storageProperties.getDegradedWrite().setEnabled(true);
        storageProperties.getDegradedWrite().setTrackForSync(true);
        executor = Executors.newFixedThreadPool(4);
        meterRegistry = new SimpleMeterRegistry();

        when(clientManager.acquireTopologyLease()).thenReturn(topologyLease);
        when(topologyLease.getClient(anyString())).thenAnswer(invocation ->
                clientManager.getClient(invocation.getArgument(0)));
        when(topologyLease.getNodeConfig(anyString())).thenAnswer(invocation ->
                clientManager.getNodeConfig(invocation.getArgument(0)));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(topologyLease).runIfCurrent(any(Runnable.class));
        when(lockManager.acquire(any())).thenReturn(() -> { });
        when(operationIntentStore.beginComplete(any())).thenReturn(operationIntent);
        when(receiptStore.findValidated(any())).thenReturn(Optional.empty());
        when(repairService.scheduleImmediateRepairByNodesAsync(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(false));
    }

    /**
     * 清除 toxic、对象、测试桶和每个场景创建的客户端。
     */
    @AfterEach
    void tearDown() throws IOException {
        firstProxy.setConnectionCut(false);
        secondProxy.setConnectionCut(false);
        try {
            removeAllToxics(firstProxy);
        } finally {
            try {
                removeAllToxics(secondProxy);
            } finally {
                for (BucketResource bucket : buckets) {
                    deleteBucket(bucket);
                }
                for (S3Client client : caseClients) {
                    client.close();
                }
                if (executor != null) {
                    executor.shutdownNow();
                }
                if (meterRegistry != null) {
                    meterRegistry.close();
                }
            }
        }
    }

    /**
     * F01：public/sealed staging 与 receipt 均缺失时不得从残余状态推断成功。
     */
    @Test
    @DisplayName("F01 missing staging and receipt should fail without final evidence")
    void shouldFailWhenStagingAndReceiptAreMissing() throws Exception {
        String source = createBucket("f01-source");
        String target = createBucket("f01-target");
        byte[] content = bytes("missing-staging");
        DirectUploadPartDescriptor part = descriptor(
                "fault-f01", source, List.of(target), 1, "missing-etag", content.length, hash(content));
        configureNode(source, directClient, endpoint(MINIO_A), "physical-source");
        configureNode(target, directClient, endpoint(MINIO_A), "physical-target");

        assertThatThrownBy(() -> service().promote(part, DirectUploadDigestAccumulator.sha256()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion receipt is required when trustworthy staging is unavailable");

        assertThat(objectExists(target, part.finalObjectName())).isFalse();
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    /**
     * F02：真实 HEAD 大小与声明不一致时不得写入任何 final 副本。
     */
    @Test
    @DisplayName("F02 staging size mismatch should retain source and publish no final object")
    void shouldRejectStagingSizeMismatch() throws Exception {
        byte[] content = bytes("size-mismatch");
        FaultCase faultCase = singleTargetCase("f02", content, content.length + 1L, hash(content), null);

        assertThatThrownBy(() -> service().promote(faultCase.part(), DirectUploadDigestAccumulator.sha256()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size mismatch");

        assertThat(objectExists(faultCase.source(), faultCase.part().stagingObjectName())).isTrue();
        assertThat(objectExists(faultCase.target(), faultCase.part().finalObjectName())).isFalse();
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
    }

    /**
     * F03：真实 GET 内容的 SHA-256 与声明不同不得计入 quorum 或发布 receipt。
     */
    @Test
    @DisplayName("F03 cipher hash mismatch should retain recovery evidence")
    void shouldRejectCipherHashMismatch() throws Exception {
        byte[] content = bytes("cipher-hash-real");
        String wrongHash = hash(bytes("cipher-hash-fake"));
        FaultCase faultCase = singleTargetCase("f03", content, content.length, wrongHash, null);

        assertThatThrownBy(() -> service().promote(faultCase.part(), DirectUploadDigestAccumulator.sha256()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum mismatch");

        assertThat(objectExists(faultCase.source(), faultCase.part().stagingObjectName())).isTrue();
        assertThat(objectExists(faultCase.target(), faultCase.part().finalObjectName())).isFalse();
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
    }

    /**
     * F04：客户端提交的 ETag 与真实 HEAD 不一致时必须在 seal/final 前失败。
     */
    @Test
    @DisplayName("F04 client ETag mismatch should fail before final publication")
    void shouldRejectClientEtagMismatch() throws Exception {
        byte[] content = bytes("etag-real");
        FaultCase faultCase = singleTargetCase("f04", content, content.length, hash(content), "wrong-etag");

        assertThatThrownBy(() -> service().promote(faultCase.part(), DirectUploadDigestAccumulator.sha256()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ETag mismatch");

        assertThat(objectExists(faultCase.source(), faultCase.part().stagingObjectName())).isTrue();
        assertThat(objectExists(faultCase.target(), faultCase.part().finalObjectName())).isFalse();
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
    }

    /**
     * F05：三个独立目标中一个真实 TCP reset 时，两个成功副本应满足原始 quorum 并留下降级证据。
     */
    @Test
    @DisplayName("F05 one reset target should keep exact quorum success and degraded evidence")
    void shouldSucceedAtExactQuorumWithOneDisconnectedTarget() throws Exception {
        byte[] content = bytes("exact-quorum");
        QuorumCase quorumCase = quorumCase("f05", content);
        secondProxy.toxics().resetPeer("f05-reset-upstream", ToxicDirection.UPSTREAM, 0L);
        secondProxy.toxics().resetPeer("f05-reset-downstream", ToxicDirection.DOWNSTREAM, 0L);

        DirectUploadPromotionResult result = service().promote(
                quorumCase.part(), DirectUploadDigestAccumulator.sha256());

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(objectExists(quorumCase.source(), quorumCase.part().finalObjectName())).isTrue();
        assertThat(readObject(quorumCase.firstTarget(), quorumCase.part().finalObjectName()))
                .containsExactly(content);
        assertThat(objectExists(quorumCase.secondTarget(), quorumCase.part().finalObjectName())).isFalse();
        verify(receiptStore).recordSuccess(
                eq(quorumCase.part()),
                argThat(nodes -> nodes.size() == 2
                        && nodes.contains(quorumCase.source())
                        && nodes.contains(quorumCase.firstTarget())),
                eq(operationIntent));
        verify(degradedWriteTracker).recordAuthoritativeDegradedWrite(
                eq(quorumCase.part().cipherHash()), any(), eq(7L));
        verify(stagingTracker).retainAfterDelete(quorumCase.part().stagingDescriptor());
    }

    /**
     * F06：三个目标仅一个可达时必须失败并保留唯一 staging 恢复源。
     */
    @Test
    @DisplayName("F06 two disconnected targets should fail below quorum and retain staging")
    void shouldFailBelowQuorumWithTwoDisconnectedTargets() throws Exception {
        byte[] content = bytes("below-quorum");
        QuorumCase quorumCase = quorumCase("f06", content);
        firstProxy.setConnectionCut(true);
        secondProxy.setConnectionCut(true);

        assertThatThrownBy(() -> service().promote(
                quorumCase.part(), DirectUploadDigestAccumulator.sha256()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");

        assertThat(objectExists(quorumCase.source(), quorumCase.part().stagingObjectName())).isTrue();
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    /**
     * F12：过期预签名 PUT URL 必须由真实 MinIO 拒绝且不产生可完成对象。
     */
    @Test
    @DisplayName("F12 expired presigned PUT should be rejected by MinIO")
    void shouldRejectExpiredPresignedPut() throws Exception {
        String bucket = createBucket("f12-expired");
        String key = "tenant/7/staging/direct-upload/fault-f12/part-0";
        URI uploadUri;
        try (S3Presigner presigner = s3Presigner(endpoint(MINIO_A))) {
            uploadUri = presigner.presignPutObject(PutObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofSeconds(1))
                            .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                            .build())
                    .url().toURI();
        }
        Thread.sleep(2_500L);

        HttpResponse<Void> response = boundedHttpClient().send(
                HttpRequest.newBuilder(uploadUri)
                        .timeout(Duration.ofSeconds(5))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes("expired")))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isIn(400, 403);
        assertThat(objectExists(bucket, key)).isFalse();
    }

    /**
     * F13：客户端上传中途断连不得留下可被 HEAD 当作完整分片的对象。
     */
    @Test
    @DisplayName("F13 interrupted PUT should not publish a partial staging object")
    void shouldDiscardInterruptedPresignedPut() throws Exception {
        String bucket = createBucket(directBClient, "f13-interrupt");
        String key = "tenant/7/staging/direct-upload/fault-f13/part-0";
        firstProxy.toxics().limitData("f13-limit", ToxicDirection.UPSTREAM, 64 * 1024L);
        URI uploadUri;
        try (S3Presigner presigner = s3Presigner(proxyEndpoint(firstProxy))) {
            uploadUri = presigner.presignPutObject(PutObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofMinutes(2))
                            .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                            .build())
                    .url().toURI();
        }
        byte[] body = new byte[1024 * 1024];

        boolean interrupted;
        try {
            HttpResponse<Void> response = boundedHttpClient().send(
                    HttpRequest.newBuilder(uploadUri)
                            .timeout(Duration.ofSeconds(5))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            interrupted = response.statusCode() < 200 || response.statusCode() >= 300;
        } catch (IOException expectedDisconnect) {
            interrupted = true;
        }

        removeAllToxics(firstProxy);
        assertThat(interrupted).isTrue();
        awaitObjectMissing(directBClient, bucket, key, Duration.ofSeconds(5));
    }

    /**
     * F14：provider HEAD/GET/PUT 延迟超过传输预算时必须有界失败并保留 staging。
     */
    @Test
    @DisplayName("F14 storage timeout should fail within deadline and retain staging")
    void shouldFailStorageTimeoutWithinDeadline() throws Exception {
        byte[] content = bytes("storage-timeout");
        String source = createBucket("f14-source");
        String target = createBucket(directBClient, "f14-target");
        String key = stagingKey("fault-f14");
        String etag = putObject(source, key, content);
        DirectUploadPartDescriptor part = descriptor(
                "fault-f14", source, List.of(target), 1, etag, content.length, hash(content));
        configureNode(source, directClient, endpoint(MINIO_A), "physical-source");
        configureNode(target, firstProxyClient, proxyEndpoint(firstProxy), "physical-target");
        storageProperties.getDirectUpload().setTransferTimeoutSeconds(1);
        firstProxy.toxics().latency("f14-upstream", ToxicDirection.UPSTREAM, 1_500L);
        firstProxy.toxics().latency("f14-downstream", ToxicDirection.DOWNSTREAM, 1_500L);
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> service().promote(part, DirectUploadDigestAccumulator.sha256()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(6));
        assertThat(objectExists(source, key)).isTrue();
        verify(receiptStore, never()).recordSuccess(any(), any(), any());

        removeAllToxics(firstProxy);
        shouldDeferRealRepairAfterNetworkTimeout();
    }

    /**
     * F08：真实一致性修复经过 Toxiproxy 超时后必须返回可重试延后语义且不发布目标副本。
     */
    private void shouldDeferRealRepairAfterNetworkTimeout() throws Exception {
        byte[] content = bytes("repair-timeout");
        String source = createBucket("f08-source");
        String target = createBucket(directBClient, "f08-target");
        String objectName = "tenant/7/" + hash(content);
        putObject(source, objectName, content);
        configureNode(source, directClient, endpoint(MINIO_A), "physical-f08-source");
        configureNode(target, firstProxyClient, proxyEndpoint(firstProxy), "physical-f08-target");
        storageProperties.getDegradedWrite().setRepairTimeoutSeconds(1);
        FaultDomainManager faultDomainManager = mock(FaultDomainManager.class);
        when(faultDomainManager.areNodesOnIndependentPhysicalStorage(source, target)).thenReturn(true);
        ConsistencyRepairService realRepair = new ConsistencyRepairService();
        ReflectionTestUtils.setField(realRepair, "clientManager", clientManager);
        ReflectionTestUtils.setField(realRepair, "s3Monitor", s3Monitor);
        ReflectionTestUtils.setField(realRepair, "faultDomainManager", faultDomainManager);
        ReflectionTestUtils.setField(realRepair, "redissonClient", mock(RedissonClient.class));
        ReflectionTestUtils.setField(realRepair, "storageProperties", storageProperties);
        firstProxy.toxics().latency("f08-upstream", ToxicDirection.UPSTREAM, 1_500L);
        firstProxy.toxics().latency("f08-downstream", ToxicDirection.DOWNSTREAM, 1_500L);
        long startedAt = System.nanoTime();

        ConsistencyRepairService.ImmediateRepairResult result = realRepair
                .scheduleImmediateRepairByNodesDetailedAsync(objectName, source, target)
                .get(5, TimeUnit.SECONDS);

        assertThat(result.status())
                .isEqualTo(ConsistencyRepairService.ImmediateRepairStatus.RETRYABLE_DEFERRED);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
        removeAllToxics(firstProxy);
        assertThat(objectExists(directBClient, target, objectName)).isFalse();
    }

    /**
     * 构建单目标真实 MinIO 校验场景。
     */
    private FaultCase singleTargetCase(
            String caseId,
            byte[] content,
            long declaredSize,
            String declaredHash,
            String declaredEtag
    ) throws Exception {
        String source = createBucket(caseId + "-source");
        String target = createBucket(caseId + "-target");
        String key = stagingKey("fault-" + caseId);
        String providerEtag = putObject(source, key, content);
        DirectUploadPartDescriptor part = descriptor(
                "fault-" + caseId,
                source,
                List.of(target),
                1,
                declaredEtag == null ? providerEtag : declaredEtag,
                declaredSize,
                declaredHash
        );
        configureNode(source, directClient, endpoint(MINIO_A), "physical-" + caseId + "-source");
        configureNode(target, directClient, endpoint(MINIO_A), "physical-" + caseId + "-target");
        return new FaultCase(source, target, part);
    }

    /**
     * 构建三个逻辑独立目标、其中两个经过独立网络代理的 quorum 场景。
     */
    private QuorumCase quorumCase(String caseId, byte[] content) throws Exception {
        String source = createBucket(caseId + "-source");
        String firstTarget = createBucket(directBClient, caseId + "-target-b");
        String secondTarget = createBucket(directCClient, caseId + "-target-c");
        String sessionId = "fault-" + caseId;
        String etag = putObject(source, stagingKey(sessionId), content);
        DirectUploadPartDescriptor part = descriptor(
                sessionId,
                source,
                List.of(source, firstTarget, secondTarget),
                2,
                etag,
                content.length,
                hash(content)
        );
        configureNode(source, directClient, endpoint(MINIO_A), "physical-" + caseId + "-a");
        configureNode(firstTarget, firstProxyClient, proxyEndpoint(firstProxy), "physical-" + caseId + "-b");
        configureNode(secondTarget, secondProxyClient, proxyEndpoint(secondProxy), "physical-" + caseId + "-c");
        return new QuorumCase(source, firstTarget, secondTarget, part);
    }

    /**
     * 构建使用真实 S3 客户端和受控 lifecycle 依赖的生产提升服务。
     */
    private DirectUploadPromotionService service() {
        return new DirectUploadPromotionService(
                clientManager,
                s3Monitor,
                storageProperties,
                repairService,
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
     * 建立节点到客户端、endpoint 和独立物理存储身份的映射。
     */
    private void configureNode(String node, S3Client client, String nodeEndpoint, String physicalStorageId) {
        when(clientManager.getClient(node)).thenReturn(client);
        NodeConfig nodeConfig = new NodeConfig();
        nodeConfig.setName(node);
        nodeConfig.setEndpoint(nodeEndpoint);
        nodeConfig.setPhysicalStorageId(physicalStorageId);
        when(clientManager.getNodeConfig(node)).thenReturn(nodeConfig);
        when(s3Monitor.isNodeOnline(node)).thenReturn(true);
    }

    /**
     * 构建合法的 direct-upload 分片描述。
     */
    private DirectUploadPartDescriptor descriptor(
            String sessionId,
            String source,
            List<String> targets,
            int quorum,
            String etag,
            long declaredSize,
            String declaredHash
    ) {
        return new DirectUploadPartDescriptor(
                7L,
                sessionId,
                0,
                source,
                stagingKey(sessionId),
                "tenant/7/" + declaredHash,
                declaredSize,
                etag,
                declaredHash,
                declaredHash,
                "SHA-256",
                targets,
                quorum
        );
    }

    /**
     * 创建当前场景独占的测试桶。
     */
    private String createBucket(String bucket) {
        return createBucket(directClient, bucket);
    }

    /**
     * 在指定独立 provider 上创建场景专属桶并登记精确清理身份。
     */
    private String createBucket(S3Client client, String bucket) {
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        buckets.add(new BucketResource(client, bucket));
        return bucket;
    }

    /**
     * 写入一个真实 staging 对象并返回 provider ETag。
     */
    private String putObject(String bucket, String key, byte[] content) {
        return directClient.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(content)
        ).eTag();
    }

    /**
     * 读取一个测试对象的小型内容。
     */
    private byte[] readObject(String bucket, String key) {
        S3Client client = buckets.stream()
                .filter(resource -> resource.bucket().equals(bucket))
                .map(BucketResource::client)
                .findFirst()
                .orElseThrow();
        return client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    /**
     * 判断真实 provider 中指定对象是否存在。
     */
    private boolean objectExists(String bucket, String key) {
        S3Client client = buckets.stream()
                .filter(resource -> resource.bucket().equals(bucket))
                .map(BucketResource::client)
                .findFirst()
                .orElseThrow();
        return objectExists(client, bucket, key);
    }

    /**
     * 判断指定独立 provider 中的对象是否存在。
     */
    private boolean objectExists(S3Client client, String bucket, String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 删除当前场景在测试桶中创建的全部对象并删除桶。
     */
    private void deleteBucket(BucketResource resource) {
        S3Client client = resource.client();
        String bucket = resource.bucket();
        client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents()
                .forEach(object -> client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(object.key()).build()));
        client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
    }

    /**
     * 在有限时间内等待中断上传的 provider 临时状态完成回滚。
     */
    private void awaitObjectMissing(S3Client client, String bucket, String key, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (objectExists(client, bucket, key) && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertThat(objectExists(client, bucket, key)).isFalse();
    }

    /**
     * 删除代理上的全部 toxic，保证场景间网络状态隔离。
     */
    private static void removeAllToxics(ToxiproxyContainer.ContainerProxy proxy) throws IOException {
        for (var toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    /**
     * 返回规范 staging object key。
     */
    private static String stagingKey(String sessionId) {
        return "tenant/7/staging/direct-upload/" + sessionId + "/part-0";
    }

    /**
     * 计算 direct-upload 合同使用的 SHA-256 内容地址。
     */
    private static String hash(byte[] content) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
    }

    /**
     * 创建确定性的 UTF-8 测试字节。
     */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 返回容器映射到宿主机的 endpoint。
     */
    private static String endpoint(GenericContainer<?> container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MINIO_PORT);
    }

    /**
     * 返回 Toxiproxy 映射到宿主机的 endpoint。
     */
    private static String proxyEndpoint(ToxiproxyContainer.ContainerProxy proxy) {
        return "http://" + proxy.getContainerIpAddress() + ":" + proxy.getProxyPort();
    }

    /**
     * 创建带短连接和 socket 上界的 path-style MinIO 客户端。
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
                        .socketTimeout(Duration.ofSeconds(3)))
                .build();
    }

    /**
     * 创建与真实 direct-upload 一致的 path-style 预签名客户端。
     */
    private static S3Presigner s3Presigner(String endpoint) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /**
     * 创建具有连接上界的 JDK HTTP 客户端，避免预签名故障场景无限等待连接。
     */
    private static HttpClient boundedHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /**
     * 保存单目标故障场景的 provider 身份。
     */
    private record FaultCase(String source, String target, DirectUploadPartDescriptor part) {
    }

    /**
     * 保存三目标 quorum 故障场景的 provider 身份。
     */
    private record QuorumCase(
            String source,
            String firstTarget,
            String secondTarget,
            DirectUploadPartDescriptor part
    ) {
    }

    /**
     * 保存测试桶所属的精确 provider 身份，避免跨 provider 或全局清理。
     */
    private record BucketResource(S3Client client, String bucket) {
    }
}
