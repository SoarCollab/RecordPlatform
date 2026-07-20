package cn.flying.storage.service;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3ClientManager.TopologyLease;
import cn.flying.storage.core.S3Monitor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在独立受限堆 fork 中证明 96 MiB direct-upload 对象不会被聚合成等量数组。
 */
class DirectUploadConstrainedHeapIT {

    private static final long MEBIBYTE = 1024L * 1024;
    private static final long OBJECT_SIZE = 96L * MEBIBYTE;
    private static final long MAX_EXPECTED_HEAP = 88L * MEBIBYTE;
    private static final String SOURCE_NODE = "heap-source";
    private static final String TARGET_NODE = "heap-target";
    private static final String STAGING_KEY =
            "tenant/7/staging/direct-upload/heap-session/part-0";
    private static final String STAGING_ETAG = "\"heap-staging-etag\"";
    private static final String SEALED_ETAG = "\"heap-sealed-etag\"";
    private static final String TARGET_ETAG = "\"heap-target-etag\"";

    private S3ClientManager clientManager;
    private S3Monitor s3Monitor;
    private ConsistencyRepairService consistencyRepairService;
    private DegradedWriteTracker degradedWriteTracker;
    private DirectUploadLockManager lockManager;
    private DirectUploadStagingTracker stagingTracker;
    private DirectUploadPromotionReceiptStore receiptStore;
    private DirectUploadOperationIntentStore operationIntentStore;
    private S3Client sourceClient;
    private S3Client targetClient;
    private StorageProperties storageProperties;
    private ExecutorService executor;
    private SimpleMeterRegistry meterRegistry;

    /**
     * 创建只使用固定小缓冲的 mock provider 和单线程传输执行器。
     */
    @BeforeEach
    void setUp() {
        clientManager = mock(S3ClientManager.class);
        s3Monitor = mock(S3Monitor.class);
        consistencyRepairService = mock(ConsistencyRepairService.class);
        degradedWriteTracker = mock(DegradedWriteTracker.class);
        lockManager = mock(DirectUploadLockManager.class);
        stagingTracker = mock(DirectUploadStagingTracker.class);
        receiptStore = mock(DirectUploadPromotionReceiptStore.class);
        operationIntentStore = mock(DirectUploadOperationIntentStore.class);
        TopologyLease topologyLease = mock(TopologyLease.class);
        sourceClient = mock(S3Client.class);
        targetClient = mock(S3Client.class);
        storageProperties = new StorageProperties();
        storageProperties.getDirectUpload().setStreamBufferBytes(8 * 1024);
        executor = Executors.newSingleThreadExecutor();
        meterRegistry = new SimpleMeterRegistry();
        when(lockManager.acquire(any())).thenReturn(() -> { });
        when(operationIntentStore.beginComplete(any())).thenReturn(
                new DirectUploadOperationIntentStore.OperationIntent(
                        "heap-intent-key",
                        "heap-intent-fence-key",
                        "heap-intent-value",
                        DirectUploadOperationIntentStore.OperationMode.COMPLETE,
                        "a".repeat(64),
                        "b".repeat(64),
                        1L
                )
        );
        when(clientManager.acquireTopologyLease()).thenReturn(topologyLease);
        when(topologyLease.getClient(anyString())).thenAnswer(invocation ->
                clientManager.getClient(invocation.getArgument(0)));
        when(topologyLease.getNodeConfig(anyString())).thenAnswer(invocation ->
                clientManager.getNodeConfig(invocation.getArgument(0)));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(topologyLease).runIfCurrent(any(Runnable.class));
    }

    /**
     * 回收受限堆测试创建的线程和指标资源。
     */
    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        meterRegistry.close();
    }

    @Test
    @DisplayName("96 MiB generated object should complete under an 80 MiB heap")
    void shouldPromoteGeneratedObjectWithoutObjectSizedHeapAllocation() throws Exception {
        long maxHeap = Runtime.getRuntime().maxMemory();
        assertThat(OBJECT_SIZE).isGreaterThanOrEqualTo(80L * MEBIBYTE);
        assertThat(maxHeap).isLessThanOrEqualTo(MAX_EXPECTED_HEAP);
        assertThat(maxHeap).isLessThan(OBJECT_SIZE);

        String expectedHash = generatedHash(OBJECT_SIZE);
        Map<String, String> finalMetadata = finalMetadata(expectedHash);
        DirectUploadPartDescriptor descriptor = descriptor(expectedHash);
        configureProviderMocks(finalMetadata);
        AtomicLong transferredBytes = new AtomicLong();
        AtomicReference<String> transferredHash = new AtomicReference<>();
        AtomicReference<PutObjectRequest> targetRequest = new AtomicReference<>();
        when(targetClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> consumeRequestBody(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        targetRequest,
                        transferredBytes,
                        transferredHash
                ));

        DirectUploadDigestAccumulator aggregateDigest = DirectUploadDigestAccumulator.sha256();
        DirectUploadPromotionResult result = service().promote(descriptor, aggregateDigest);

        assertThat(result.size()).isEqualTo(OBJECT_SIZE);
        assertThat(transferredBytes.get()).isEqualTo(OBJECT_SIZE);
        assertThat(transferredHash.get()).isEqualTo(expectedHash);
        assertThat(aggregateDigest.finishHash()).isEqualTo(expectedHash);
        assertThat(targetRequest.get()).isNotNull();
        assertThat(targetRequest.get().contentLength()).isEqualTo(OBJECT_SIZE);
        assertThat(targetRequest.get().metadata()).containsExactlyInAnyOrderEntriesOf(finalMetadata);
        ArgumentCaptor<GetObjectRequest> sourceRequests = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(sourceClient, times(2)).getObject(sourceRequests.capture());
        assertThat(sourceRequests.getAllValues()).allSatisfy(request -> {
            assertThat(request.bucket()).isEqualTo(SOURCE_NODE);
            assertThat(request.key()).isEqualTo(STAGING_KEY + ".sealed");
            assertThat(request.ifMatch()).isEqualTo(SEALED_ETAG);
        });
        verify(targetClient).getObject(any(GetObjectRequest.class));
        verify(sourceClient, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
        assertThat(meterRegistry.counter(
                "storage_direct_upload_transfers_total",
                "mode", "bounded_stream",
                "result", "success").count()).isEqualTo(1.0);
    }

    /**
     * 配置源 HEAD/GET、目标 PUT 后 HEAD 和跨 endpoint 节点信息。
     */
    private void configureProviderMocks(Map<String, String> finalMetadata) {
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE))
                .thenReturn(node(SOURCE_NODE, "http://heap-source:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE))
                .thenReturn(node(TARGET_NODE, "http://heap-target:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(sourceClient.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());
        AtomicInteger sealedHeadCalls = new AtomicInteger();
        when(sourceClient.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            if ((STAGING_KEY + ".sealed").equals(request.key())) {
                if (sealedHeadCalls.incrementAndGet() == 1) {
                    throw NoSuchKeyException.builder().message("sealed missing").build();
                }
                return HeadObjectResponse.builder()
                        .contentLength(OBJECT_SIZE)
                        .eTag(SEALED_ETAG)
                        .build();
            }
            return HeadObjectResponse.builder()
                    .contentLength(OBJECT_SIZE)
                    .eTag(STAGING_ETAG)
                    .build();
        });
        when(sourceClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(ignored -> generatedResponseStream());
        when(targetClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(OBJECT_SIZE)
                        .eTag(TARGET_ETAG)
                        .metadata(finalMetadata)
                        .build());
        when(targetClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(ignored -> generatedResponseStream(TARGET_ETAG));
        when(sourceClient.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());
    }

    /**
     * 实际消费 SDK RequestBody 的可重开流，并用固定 16 KiB 缓冲计算目标 hash。
     */
    private PutObjectResponse consumeRequestBody(
            PutObjectRequest request,
            RequestBody body,
            AtomicReference<PutObjectRequest> targetRequest,
            AtomicLong transferredBytes,
            AtomicReference<String> transferredHash
    ) throws Exception {
        assertThat(body.contentLength()).isEqualTo(OBJECT_SIZE);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = body.contentStreamProvider().newStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                assertThat(total).isLessThanOrEqualTo(OBJECT_SIZE);
                digest.update(buffer, 0, read);
            }
        }
        targetRequest.set(request);
        transferredBytes.set(total);
        transferredHash.set("sha256:" + HexFormat.of().formatHex(digest.digest()));
        return PutObjectResponse.builder().eTag(TARGET_ETAG).build();
    }

    /**
     * 构建不预分配对象内容的可关闭 SDK 响应流。
     */
    private ResponseInputStream<GetObjectResponse> generatedResponseStream() {
        return generatedResponseStream(SEALED_ETAG);
    }

    /**
     * 构建带指定 ETag 的生成式响应流，供 sealed 与 final 两阶段复核复用。
     */
    private ResponseInputStream<GetObjectResponse> generatedResponseStream(String eTag) {
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength(OBJECT_SIZE)
                .eTag(eTag)
                .build();
        return new ResponseInputStream<>(
                response,
                AbortableInputStream.create(new GeneratedInputStream(OBJECT_SIZE))
        );
    }

    /**
     * 以固定 32 KiB 缓冲预计算生成式对象 SHA-256，不创建等量数组。
     */
    private String generatedHash(long size) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024];
        long position = 0;
        while (position < size) {
            int length = (int) Math.min(buffer.length, size - position);
            fillGeneratedBytes(buffer, 0, length, position);
            digest.update(buffer, 0, length);
            position += length;
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 构建单目标、单 quorum 的 96 MiB 分片描述。
     */
    private DirectUploadPartDescriptor descriptor(String hash) {
        return new DirectUploadPartDescriptor(
                7L,
                "heap-session",
                0,
                SOURCE_NODE,
                STAGING_KEY,
                "tenant/7/" + hash,
                OBJECT_SIZE,
                STAGING_ETAG,
                hash,
                hash,
                "SHA-256",
                List.of(TARGET_NODE),
                1
        );
    }

    /**
     * 返回 service 写入并复核的完整 final metadata。
     */
    private Map<String, String> finalMetadata(String hash) {
        return Map.of(
                "file-hash", hash,
                "tenant-id", "7",
                "checksum-algorithm", "SHA-256",
                "plain-hash", hash,
                "cipher-hash", hash
        );
    }

    /**
     * 构建只包含名称和 endpoint 的测试节点配置。
     */
    private NodeConfig node(String name, String endpoint) {
        NodeConfig node = new NodeConfig();
        node.setName(name);
        node.setEndpoint(endpoint);
        node.setPhysicalStorageId("physical-" + name);
        return node;
    }

    /**
     * 构建受测提升服务。
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
     * 按绝对偏移生成稳定字节模式。
     */
    private static void fillGeneratedBytes(
            byte[] destination,
            int offset,
            int length,
            long absolutePosition
    ) {
        for (int index = 0; index < length; index++) {
            destination[offset + index] = (byte) (((absolutePosition + index) * 31 + 17) & 0xff);
        }
    }

    /**
     * 仅保存长度和当前位置的生成式输入流，任何时刻都不持有完整对象。
     */
    private static final class GeneratedInputStream extends InputStream {
        private final long size;
        private long position;

        private GeneratedInputStream(long size) {
            this.size = size;
        }

        /**
         * 读取单个确定性字节。
         */
        @Override
        public int read() {
            if (position >= size) {
                return -1;
            }
            int value = (int) ((position * 31 + 17) & 0xff);
            position++;
            return value;
        }

        /**
         * 直接填充调用方的小缓冲，避免任何对象尺寸相关分配。
         */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (position >= size) {
                return -1;
            }
            int actualLength = (int) Math.min(length, size - position);
            fillGeneratedBytes(bytes, offset, actualLength, position);
            position += actualLength;
            return actualLength;
        }
    }
}
