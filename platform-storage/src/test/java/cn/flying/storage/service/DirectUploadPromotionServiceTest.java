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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证直传分片晋升的条件复制、有界流、quorum、修复与幂等重试边界。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectUploadPromotionService Unit Tests")
class DirectUploadPromotionServiceTest {

    private static final String SESSION_ID = "session-1";
    private static final String SOURCE_NODE = "node-a";
    private static final String TARGET_NODE = "node-b";
    private static final String STAGING_KEY = "tenant/7/staging/direct-upload/session-1/part-0";
    private static final String SEALED_KEY = STAGING_KEY + ".sealed";

    @Mock
    private S3ClientManager clientManager;

    @Mock
    private TopologyLease topologyLease;

    @Mock
    private S3Monitor s3Monitor;

    @Mock
    private StorageProperties storageProperties;

    @Mock
    private ConsistencyRepairService consistencyRepairService;

    @Mock
    private DegradedWriteTracker degradedWriteTracker;

    @Mock
    private DirectUploadLockManager lockManager;

    @Mock
    private DirectUploadStagingTracker stagingTracker;

    @Mock
    private DirectUploadPromotionReceiptStore receiptStore;

    @Mock
    private DirectUploadOperationIntentStore operationIntentStore;

    @Mock
    private S3Client sourceClient;

    @Mock
    private S3Client targetClient;

    @Mock
    private S3Client thirdClient;

    private StorageProperties.DirectUploadConfig directUploadConfig;
    private StorageProperties.DegradedWriteConfig degradedWriteConfig;
    private ExecutorService executor;
    private SimpleMeterRegistry meterRegistry;
    private DirectUploadPromotionService service;
    private DirectUploadOperationIntentStore.OperationIntent operationIntent;

    /**
     * 创建真实有界执行器和指标注册表，并为锁配置无副作用句柄。
     */
    @BeforeEach
    void setUp() {
        directUploadConfig = new StorageProperties.DirectUploadConfig();
        degradedWriteConfig = new StorageProperties.DegradedWriteConfig();
        degradedWriteConfig.setEnabled(true);
        degradedWriteConfig.setTrackForSync(true);
        executor = Executors.newFixedThreadPool(4);
        meterRegistry = new SimpleMeterRegistry();
        operationIntent = new DirectUploadOperationIntentStore.OperationIntent(
                "test-intent-key",
                "test-intent-fence-key",
                "test-intent-value",
                DirectUploadOperationIntentStore.OperationMode.COMPLETE,
                "a".repeat(64),
                "b".repeat(64),
                1L
        );
        service = new DirectUploadPromotionService(
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
        lenient().when(storageProperties.getDirectUpload()).thenReturn(directUploadConfig);
        lenient().when(storageProperties.getEffectiveReplicationFactor()).thenReturn(1);
        lenient().when(storageProperties.getDegradedWrite()).thenReturn(degradedWriteConfig);
        lenient().when(lockManager.acquire(any())).thenReturn(() -> { });
        lenient().when(operationIntentStore.beginComplete(any())).thenReturn(operationIntent);
        lenient().when(operationIntentStore.beginAbort(any())).thenReturn(operationIntent);
        lenient().when(clientManager.acquireTopologyLease()).thenReturn(topologyLease);
        lenient().when(topologyLease.getClient(anyString())).thenAnswer(invocation ->
                clientManager.getClient(invocation.getArgument(0)));
        lenient().when(topologyLease.getNodeConfig(anyString())).thenAnswer(invocation ->
                clientManager.getNodeConfig(invocation.getArgument(0)));
        lenient().doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(topologyLease).runIfCurrent(any(Runnable.class));
        lenient().when(clientManager.getNodeConfig(anyString())).thenAnswer(invocation -> {
            String nodeName = invocation.getArgument(0);
            return node(nodeName, "http://" + nodeName + ":9000");
        });
    }

    /**
     * 回收测试执行器和指标资源，避免跨测试线程泄漏。
     */
    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        meterRegistry.close();
    }

    @Test
    @DisplayName("same endpoint should use conditional CopyObject and replace metadata")
    void shouldUseConditionalServerCopyOnSameEndpoint() throws Exception {
        byte[] content = "same-endpoint-content".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000/"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());

        DirectUploadDigestAccumulator aggregate = digestAccumulator();
        DirectUploadPromotionResult result = service.promote(descriptor, aggregate);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregate.finishHash()).isEqualTo(descriptor.cipherHash());
        ArgumentCaptor<CopyObjectRequest> copyCaptor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(targetClient).copyObject(copyCaptor.capture());
        assertThat(copyCaptor.getValue().sourceBucket()).isEqualTo(SOURCE_NODE);
        assertThat(copyCaptor.getValue().sourceKey()).isEqualTo(SEALED_KEY);
        assertThat(copyCaptor.getValue().destinationBucket()).isEqualTo(TARGET_NODE);
        assertThat(copyCaptor.getValue().destinationKey()).isEqualTo(descriptor.finalObjectName());
        assertThat(copyCaptor.getValue().copySourceIfMatch()).isEqualTo("\"sealed-etag\"");
        assertThat(copyCaptor.getValue().metadataDirective()).isEqualTo(MetadataDirective.REPLACE);
        assertThat(copyCaptor.getValue().metadata()).containsAllEntriesOf(finalMetadata(descriptor));
        assertRequestTimeouts(copyCaptor.getValue());
        ArgumentCaptor<HeadObjectRequest> sourceHeadCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(sourceClient, times(3)).headObject(sourceHeadCaptor.capture());
        assertThat(sourceHeadCaptor.getAllValues()).allSatisfy(this::assertRequestTimeouts);
        ArgumentCaptor<GetObjectRequest> sourceGetCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(sourceClient).getObject(sourceGetCaptor.capture());
        assertRequestTimeouts(sourceGetCaptor.getValue());
        ArgumentCaptor<HeadObjectRequest> finalHeadCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(targetClient).headObject(finalHeadCaptor.capture());
        assertRequestTimeouts(finalHeadCaptor.getValue());
        verify(targetClient, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(sourceClient, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
        assertThat(meterRegistry.counter(
                "storage_direct_upload_transfers_total",
                "mode", "server_copy",
                "result", "success").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("different endpoints should use a known-length reopenable stream")
    void shouldStreamWithReopenableProviderAcrossEndpoints() throws Exception {
        byte[] content = "cross-endpoint-content".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio-a:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio-b:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    assertThat(body.contentLength()).isEqualTo(content.length);
                    try (InputStream first = body.contentStreamProvider().newStream()) {
                        assertThat(first.readAllBytes()).containsExactly(content);
                    }
                    try (InputStream retry = body.contentStreamProvider().newStream()) {
                        assertThat(retry.readAllBytes()).containsExactly(content);
                    }
                    return PutObjectResponse.builder().eTag("\"target-etag\"").build();
                });

        DirectUploadPromotionResult result = service.promote(
                descriptor,
                digestAccumulator()
        );

        assertThat(result.eTag()).isEqualTo("\"target-etag\"");
        ArgumentCaptor<GetObjectRequest> getCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(sourceClient, times(3)).getObject(getCaptor.capture());
        assertThat(getCaptor.getAllValues()).allSatisfy(request ->
                {
                    assertThat(request.ifMatch()).isEqualTo("\"sealed-etag\"");
                    assertRequestTimeouts(request);
                });
        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(targetClient).putObject(putCaptor.capture(), any(RequestBody.class));
        assertThat(putCaptor.getValue().contentLength()).isEqualTo(content.length);
        assertThat(putCaptor.getValue().metadata()).containsAllEntriesOf(finalMetadata(descriptor));
        assertRequestTimeouts(putCaptor.getValue());
        verify(targetClient, never()).copyObject(any(CopyObjectRequest.class));
        assertThat(meterRegistry.counter(
                "storage_direct_upload_transfers_total",
                "mode", "bounded_stream",
                "result", "success").count()).isEqualTo(1.0);
        assertThat(pendingDeadlineAbortTaskCount()).isZero();
    }

    @Test
    @DisplayName("copy precondition failure should never fall back to PUT")
    void shouldNotFallbackWhenCopyPreconditionFails() {
        byte[] content = "copy-precondition".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(412).message("PreconditionFailed").build());

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");

        verify(targetClient, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("ordinary provider copy failure should fall back to bounded streaming")
    void shouldFallbackToBoundedStreamWhenProviderCopyIsUnavailable() {
        byte[] content = "copy-fallback".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(501).message("copy unsupported").build());
        when(targetClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    try (InputStream input = body.contentStreamProvider().newStream()) {
                        assertThat(input.readAllBytes()).containsExactly(content);
                    }
                    return PutObjectResponse.builder().eTag("\"target-etag\"").build();
                });

        DirectUploadPromotionResult result = service.promote(descriptor, digestAccumulator());

        assertThat(result.eTag()).isEqualTo("\"target-etag\"");
        verify(targetClient).copyObject(any(CopyObjectRequest.class));
        verify(targetClient).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(meterRegistry.counter(
                "storage_direct_upload_transfers_total",
                "mode", "server_copy",
                "result", "fallback").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("staging ETag mismatch should fail before any final write")
    void shouldRejectStagingEtagMismatchBeforePromotion() {
        byte[] content = "etag-mismatch".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        when(sourceClient.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            if (SEALED_KEY.equals(request.key())) {
                throw NoSuchKeyException.builder().message("sealed missing").build();
            }
            return HeadObjectResponse.builder()
                    .contentLength((long) content.length)
                    .eTag("\"different-etag\"")
                    .build();
        });

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ETag mismatch");

        verifyNoInteractions(targetClient);
        verify(sourceClient, never()).getObject(any(GetObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("provider wrapping quotes should not change an otherwise identical ETag")
    void shouldAcceptOnlyWrappingQuoteDifferenceInStagingEtag() {
        byte[] content = "etag-wrapping-quotes".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(
                content,
                List.of(TARGET_NODE),
                1,
                "staging-etag"
        );
        stubSourceStaging(content, "\"staging-etag\"");
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());

        DirectUploadPromotionResult result = service.promote(descriptor, digestAccumulator());

        assertThat(result.size()).isEqualTo(content.length);
        verify(targetClient).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    @DisplayName("checksum mismatch should have no final or cleanup side effects")
    void shouldRejectChecksumMismatchBeforePromotion() {
        byte[] declared = "declared-content".getBytes();
        byte[] tampered = declared.clone();
        tampered[0] ^= 1;
        DirectUploadPartDescriptor descriptor = descriptor(declared, List.of(TARGET_NODE), 1);
        stubSourceStaging(tampered, "\"staging-etag\"");

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");

        verifyNoInteractions(targetClient);
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("same ETag content switch should be rejected from sealed before any final call")
    void shouldRejectSameEtagContentSwitchFromSealedBeforeFinalCalls() {
        byte[] declared = "same-etag-original".getBytes();
        byte[] switched = declared.clone();
        switched[0] ^= 0x01;
        DirectUploadPartDescriptor descriptor = descriptor(declared, List.of(TARGET_NODE), 1);
        AtomicInteger sealedHeadCalls = new AtomicInteger();
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        when(sourceClient.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            if (SEALED_KEY.equals(request.key()) && sealedHeadCalls.incrementAndGet() == 1) {
                throw NoSuchKeyException.builder().message("sealed missing").build();
            }
            return HeadObjectResponse.builder()
                    .contentLength((long) declared.length)
                    .eTag(SEALED_KEY.equals(request.key()) ? "\"sealed-etag\"" : "\"staging-etag\"")
                    .build();
        });
        when(sourceClient.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().build());
        when(sourceClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(invocation -> responseStream(switched));

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");

        ArgumentCaptor<CopyObjectRequest> sealCaptor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(sourceClient).copyObject(sealCaptor.capture());
        assertThat(sealCaptor.getValue().sourceKey()).isEqualTo(STAGING_KEY);
        assertThat(sealCaptor.getValue().destinationKey()).isEqualTo(SEALED_KEY);
        verify(clientManager, never()).getClient(TARGET_NODE);
        verifyNoInteractions(targetClient);
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("existing valid sealed should resume without reading public staging")
    void shouldResumeFromExistingSealedWithoutReadingPublicStaging() {
        byte[] content = "existing-sealed-restart".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        when(sourceClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength((long) content.length)
                        .eTag("\"sealed-etag\"")
                        .build());
        when(sourceClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(invocation -> responseStream(content));
        when(sourceClient.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());

        DirectUploadDigestAccumulator aggregate = digestAccumulator();
        DirectUploadPromotionResult result = service.promote(descriptor, aggregate);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregate.finishHash()).isEqualTo(descriptor.cipherHash());
        ArgumentCaptor<HeadObjectRequest> headCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(sourceClient).headObject(headCaptor.capture());
        assertThat(headCaptor.getValue().key()).isEqualTo(SEALED_KEY);
        ArgumentCaptor<GetObjectRequest> getCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(sourceClient).getObject(getCaptor.capture());
        assertThat(getCaptor.getValue().key()).isEqualTo(SEALED_KEY);
        verify(sourceClient, never()).copyObject(any(CopyObjectRequest.class));
        verify(sourceClient, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("final content hash mismatch should not count toward quorum")
    void shouldRejectFinalContentHashMismatchFromQuorum() {
        byte[] content = "final-content-original".getBytes();
        byte[] corruptFinal = content.clone();
        corruptFinal[0] ^= 0x01;
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        when(targetClient.headObject(any(HeadObjectRequest.class))).thenReturn(finalHead(descriptor));
        when(targetClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(invocation -> responseStream(corruptFinal));

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");

        verify(targetClient).getObject(any(GetObjectRequest.class));
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    /**
     * 验证 final HEAD 返回的 255 字符可见 ASCII ETag 可在持久化证据后正常返回。
     */
    @Test
    @DisplayName("255-character visible final ETag should be accepted")
    void shouldAcceptMaximumLengthFinalEtag() {
        byte[] content = "maximum-final-etag".getBytes();
        String maximumEtag = "e".repeat(255);
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        when(targetClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(finalHead(descriptor, maximumEtag));
        when(targetClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(invocation -> responseStream(content));

        DirectUploadPromotionResult result = service.promote(descriptor, digestAccumulator());

        assertThat(result.eTag()).isEqualTo(maximumEtag);
        verify(receiptStore).recordSuccess(descriptor, List.of(TARGET_NODE), operationIntent);
        verify(degradedWriteTracker).recordAuthoritativeDegradedWrite(
                descriptor.cipherHash(), List.of(TARGET_NODE), descriptor.tenantId());
        verify(sourceClient, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
    }

    /**
     * 验证同一物理集群即使使用不同 DNS/端口别名，也不能作为两个独立副本凑 quorum。
     */
    @Test
    @DisplayName("duplicate physical storage IDs should fail before provider side effects")
    void shouldRejectDuplicatePhysicalStorageIdsBeforeProviderAccess() {
        byte[] content = "duplicate-physical-target".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(
                content,
                List.of(TARGET_NODE, "node-c"),
                2
        );
        when(clientManager.getNodeConfig(TARGET_NODE))
                .thenReturn(node(TARGET_NODE, "https://minio-primary:9443/storage", "cluster-one"));
        when(clientManager.getNodeConfig("node-c"))
                .thenReturn(node("node-c", "https://minio-alias:10443/storage", "cluster-one"));

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate physical storage identity")
                .hasMessageContaining("current topology");

        verify(lockManager, never()).acquire(any());
        verify(receiptStore, never()).findValidated(any());
        verifyNoInteractions(sourceClient, targetClient, thirdClient);
    }

    /**
     * 验证 endpoint 相同但显式物理身份不同的目标按配置事实计为独立副本。
     */
    @Test
    @DisplayName("different physical storage IDs should be accepted even with the same endpoint text")
    void shouldAcceptDifferentPhysicalStorageIdsWithSameEndpointText() {
        byte[] content = "explicit-independent-targets".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(
                content,
                List.of(TARGET_NODE, "node-c"),
                2
        );
        stubSourceStaging(content);
        stubTargetFinal(targetClient, descriptor, content);
        stubTargetFinal(thirdClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getClient("node-c")).thenReturn(thirdClient);
        when(clientManager.getNodeConfig(SOURCE_NODE))
                .thenReturn(node(SOURCE_NODE, "http://shared-gateway:9000", "source-cluster"));
        when(clientManager.getNodeConfig(TARGET_NODE))
                .thenReturn(node(TARGET_NODE, "http://shared-gateway:9000", "cluster-one"));
        when(clientManager.getNodeConfig("node-c"))
                .thenReturn(node("node-c", "http://shared-gateway:9000", "cluster-two"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(s3Monitor.isNodeOnline("node-c")).thenReturn(true);

        DirectUploadPromotionResult result = service.promote(descriptor, digestAccumulator());

        assertThat(result.size()).isEqualTo(content.length);
        verify(receiptStore).recordSuccess(
                eq(descriptor),
                org.mockito.ArgumentMatchers.argThat(nodes ->
                        nodes.contains(TARGET_NODE) && nodes.contains("node-c")),
                eq(operationIntent)
        );
    }

    /**
     * 验证首次提升的所有 final worker 已结束后若 topology revision 漂移，最终 receipt 提交失败关闭。
     */
    @Test
    @DisplayName("initial receipt should fail closed when topology changes after final workers")
    void shouldFenceInitialReceiptWhenTopologyChangesAfterFinalWorkers() throws Exception {
        byte[] content = "initial-topology-revision-drift".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        CountDownLatch commitBoundaryEntered = new CountDownLatch(1);
        CountDownLatch allowCommitCheck = new CountDownLatch(1);
        AtomicBoolean topologyCurrent = new AtomicBoolean(true);
        doAnswer(invocation -> {
            commitBoundaryEntered.countDown();
            if (!allowCommitCheck.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test topology fence timed out");
            }
            if (!topologyCurrent.get()) {
                throw new IllegalStateException("S3 topology changed during operation");
            }
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(topologyLease).runIfCurrent(any(Runnable.class));

        CompletableFuture<DirectUploadPromotionResult> promotion = CompletableFuture.supplyAsync(
                () -> service.promote(descriptor, digestAccumulator())
        );
        assertThat(commitBoundaryEntered.await(5, TimeUnit.SECONDS)).isTrue();
        topologyCurrent.set(false);
        allowCommitCheck.countDown();

        assertThatThrownBy(promotion::join)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("S3 topology changed during operation");
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
        verifyNoInteractions(degradedWriteTracker, consistencyRepairService);
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    /**
     * 验证历史 receipt 的别名节点不能在重启重试时再次被当作两个独立 final 副本。
     */
    @Test
    @DisplayName("receipt retry should deduplicate historical candidates by physical storage ID")
    void shouldDeduplicatePhysicalCandidatesFromReceipt() {
        byte[] content = "duplicate-receipt-physical-target".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(
                content,
                List.of("node-c", "node-d"),
                1
        );
        DirectUploadPromotionReceiptStore.PromotionReceipt receipt = receipt(
                descriptor,
                List.of(SOURCE_NODE, TARGET_NODE),
                List.of(SOURCE_NODE, TARGET_NODE),
                2
        );
        when(receiptStore.findValidated(descriptor)).thenReturn(Optional.of(receipt));
        when(clientManager.getNodeConfig(SOURCE_NODE))
                .thenReturn(node(SOURCE_NODE, "http://minio-primary:9000", "shared-cluster"));
        when(clientManager.getNodeConfig(TARGET_NODE))
                .thenReturn(node(TARGET_NODE, "http://minio-alias:19000", "shared-cluster"));
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        stubFinalOnly(sourceClient, descriptor, content);

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("final replica quorum is unavailable");

        verify(receiptStore).findValidated(descriptor);
        verify(sourceClient).getObject(any(GetObjectRequest.class));
        verify(targetClient, never()).headObject(any(HeadObjectRequest.class));
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    /**
     * 验证超过 manifest 列上限的 final ETag 不能进入 receipt、降级跟踪或 staging 清理。
     */
    @Test
    @DisplayName("256-character final ETag should fail before durable side effects")
    void shouldRejectOversizedFinalEtagBeforeDurableSideEffects() {
        byte[] content = "oversized-final-etag".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        when(targetClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(finalHead(descriptor, "e".repeat(256)));

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");

        verify(targetClient, never()).getObject(any(GetObjectRequest.class));
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
        verifyNoInteractions(degradedWriteTracker, consistencyRepairService);
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    /**
     * 验证包含控制字符的 final ETag 在任何持久化或删除副作用前失败关闭。
     */
    @Test
    @DisplayName("control-character final ETag should fail before durable side effects")
    void shouldRejectControlCharacterFinalEtagBeforeDurableSideEffects() {
        byte[] content = "control-final-etag".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        when(targetClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(finalHead(descriptor, "etag\nunsafe"));

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");

        verify(targetClient, never()).getObject(any(GetObjectRequest.class));
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
        verifyNoInteractions(degradedWriteTracker, consistencyRepairService);
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("partial quorum should persist degraded state before repair and cleanup")
    void shouldTrackPartialQuorumAndKeepFailedRepairDurable() {
        byte[] content = "partial-quorum-content".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(
                content,
                List.of(SOURCE_NODE, TARGET_NODE, "node-c"),
                2
        );
        stubSourceStagingAndFinal(content, descriptor);
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(s3Monitor.isNodeOnline(SOURCE_NODE)).thenReturn(true);
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(s3Monitor.isNodeOnline("node-c")).thenReturn(false);
        when(sourceClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio-a:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio-b:9000"));
        when(consistencyRepairService.scheduleImmediateRepairByNodesAsync(
                descriptor.finalObjectName(), SOURCE_NODE, "node-c"))
                .thenReturn(CompletableFuture.completedFuture(false));

        DirectUploadPromotionResult result = service.promote(descriptor, digestAccumulator());

        assertThat(result.size()).isEqualTo(content.length);
        var ordered = inOrder(degradedWriteTracker, consistencyRepairService, sourceClient, stagingTracker);
        ordered.verify(degradedWriteTracker).recordAuthoritativeDegradedWrite(
                eq(descriptor.cipherHash()),
                org.mockito.ArgumentMatchers.argThat(nodes ->
                        nodes.contains(SOURCE_NODE) && nodes.contains(TARGET_NODE)),
                eq(7L)
        );
        ordered.verify(consistencyRepairService).scheduleImmediateRepairByNodesAsync(
                descriptor.finalObjectName(), SOURCE_NODE, "node-c");
        ordered.verify(sourceClient, times(2)).deleteObject(any(DeleteObjectRequest.class));
        ordered.verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
        verify(degradedWriteTracker, never()).markNodeRepaired(anyString(), eq(7L), eq("node-c"));
    }

    /**
     * 验证配置副本数不足但本轮目标均成功时，仍在清理 staging 前持久化权威降级证据。
     */
    @Test
    @DisplayName("configured replica deficit should persist evidence even without failed promotion tasks")
    void shouldPersistReplicaDeficitEvidenceWithoutFailedPromotionTasks() {
        byte[] content = "configured-replica-deficit".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
        stubSourceStaging(content);
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);

        DirectUploadPromotionResult result = service.promote(descriptor, digestAccumulator());

        assertThat(result.size()).isEqualTo(content.length);
        var ordered = inOrder(receiptStore, degradedWriteTracker, sourceClient, stagingTracker);
        ordered.verify(receiptStore).recordSuccess(
                descriptor,
                List.of(TARGET_NODE),
                operationIntent
        );
        ordered.verify(degradedWriteTracker).recordAuthoritativeDegradedWrite(
                descriptor.cipherHash(), List.of(TARGET_NODE), descriptor.tenantId());
        ordered.verify(sourceClient, times(2)).deleteObject(any(DeleteObjectRequest.class));
        ordered.verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
        verifyNoInteractions(consistencyRepairService);
    }

    @Test
    @DisplayName("retry should fail before cleanup when verified replicas miss current placement and tracking is disabled")
    void shouldFailClosedWhenVerifiedReplicasMissCurrentPlacementWithoutTracking() {
        byte[] content = "retry-current-placement-deficit".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(
                content,
                List.of(TARGET_NODE, "node-c"),
                2
        );
        DirectUploadPromotionReceiptStore.PromotionReceipt authoritative = receipt(
                descriptor,
                List.of("node-d", "node-e"),
                List.of("node-d", "node-e"),
                2
        );
        S3Client fourthClient = mock(S3Client.class);
        S3Client fifthClient = mock(S3Client.class);
        degradedWriteConfig.setEnabled(false);
        when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
        when(receiptStore.findValidated(descriptor)).thenReturn(Optional.of(authoritative));
        when(clientManager.getClient("node-d")).thenReturn(fourthClient);
        when(clientManager.getClient("node-e")).thenReturn(fifthClient);
        stubFinalOnly(fourthClient, descriptor, content);
        stubFinalOnly(fifthClient, descriptor, content);

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence tracking is unavailable");

        verify(receiptStore).recordRetrySuccess(
                descriptor,
                authoritative,
                List.of("node-d", "node-e"),
                operationIntent
        );
        verifyNoInteractions(consistencyRepairService);
        verify(stagingTracker, never()).retainAfterDelete(any());
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("successful immediate repair should clear only the repaired node")
    void shouldClearDegradedNodeOnlyAfterRepairSucceeds() {
        byte[] content = "partial-repair-success".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(
                content,
                List.of(SOURCE_NODE, TARGET_NODE),
                1
        );
        stubSourceStagingAndFinal(content, descriptor);
        when(s3Monitor.isNodeOnline(SOURCE_NODE)).thenReturn(true);
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(false);
        when(sourceClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        when(consistencyRepairService.scheduleImmediateRepairByNodesAsync(
                descriptor.finalObjectName(), SOURCE_NODE, TARGET_NODE))
                .thenReturn(CompletableFuture.completedFuture(true));

        service.promote(descriptor, digestAccumulator());

        verify(degradedWriteTracker).recordAuthoritativeDegradedWrite(
                descriptor.cipherHash(), List.of(SOURCE_NODE), 7L);
        verify(degradedWriteTracker).markNodeRepaired(
                descriptor.cipherHash(), 7L, TARGET_NODE);
    }

    @Test
    @DisplayName("quorum failure should preserve staging and lifecycle record")
    void shouldPreserveStagingWhenFinalQuorumFails() {
        byte[] content = "quorum-failure".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(
                content,
                List.of(TARGET_NODE, "node-c"),
                2
        );
        stubSourceStaging(content);
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(false);
        when(s3Monitor.isNodeOnline("node-c")).thenReturn(false);

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");

        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
        verifyNoInteractions(degradedWriteTracker, consistencyRepairService);
    }

    @Test
    @DisplayName("receipt write failure should fail completion before deleting staging or tracker")
    void shouldPreserveStagingAndTrackerWhenReceiptWriteFails() {
        byte[] content = "receipt-write-failure".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(receiptStore).recordSuccess(
                        descriptor,
                        List.of(TARGET_NODE),
                        operationIntent
                );

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis unavailable");

        verify(receiptStore).recordSuccess(descriptor, List.of(TARGET_NODE), operationIntent);
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
        verifyNoInteractions(degradedWriteTracker, consistencyRepairService);
    }

    @Test
    @DisplayName("transfer timeout should drain the running worker before returning and releasing the lock")
    void shouldPreserveStagingWhenPromotionTimesOut() throws Exception {
        byte[] content = "timeout-content".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        CountDownLatch copyStarted = new CountDownLatch(1);
        CountDownLatch releaseCopy = new CountDownLatch(1);
        CountDownLatch workerTerminated = new CountDownLatch(1);
        AtomicBoolean lockReleased = new AtomicBoolean(false);
        AtomicBoolean lockReleasedAfterWorkerTerminal = new AtomicBoolean(false);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        executor.shutdownNow();
        executor = new AfterExecuteTrackingExecutor(workerTerminated);
        service = new DirectUploadPromotionService(
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
        directUploadConfig.setTransferTimeoutSeconds(1);
        stubSourceStaging(content);
        when(lockManager.acquire(descriptor.stagingDescriptor())).thenReturn(() -> {
            lockReleased.set(true);
            lockReleasedAfterWorkerTerminal.set(workerTerminated.getCount() == 0);
        });
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenAnswer(invocation -> {
            copyStarted.countDown();
            releaseCopy.await();
            return CopyObjectResponse.builder().build();
        });

        try {
            CompletableFuture<Throwable> completion = CompletableFuture.supplyAsync(() -> {
                try {
                    service.promote(descriptor, digestAccumulator());
                    return null;
                } catch (Throwable throwable) {
                    return throwable;
                }
            }, caller);
            assertThat(copyStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> completion.get(1300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(workerTerminated.getCount()).isEqualTo(1);
            assertThat(lockReleased).isFalse();

            releaseCopy.countDown();
            Throwable failure = completion.get(2, TimeUnit.SECONDS);
            assertThat(failure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("timed out");
            assertThat(workerTerminated.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(lockReleased).isTrue();
            assertThat(lockReleasedAfterWorkerTerminal).isTrue();

            verify(targetClient, never()).headObject(any(HeadObjectRequest.class));
            verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
            verify(stagingTracker, never()).retainAfterDelete(any());
            verify(consistencyRepairService, never()).scheduleImmediateRepairByNodesAsync(
                    anyString(), anyString(), anyString());
        } finally {
            releaseCopy.countDown();
            caller.shutdownNow();
        }
    }

    /**
     * 验证源 GET 在共享截止点后才返回 headers 时，尚未交给目标 PUT 的响应流会立即 abort。
     */
    @Test
    @DisplayName("expired source GET should abort the response before request body ownership")
    void shouldAbortSourceResponseReturnedAfterPromotionDeadline() throws Exception {
        byte[] content = "late-source-response".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        CountDownLatch promotionSourceGetStarted = new CountDownLatch(1);
        CountDownLatch releasePromotionSourceGet = new CountDownLatch(1);
        AtomicBoolean responseAborted = new AtomicBoolean(false);
        AtomicInteger sourceGetCount = new AtomicInteger();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        directUploadConfig.setTransferTimeoutSeconds(1);
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        when(sourceClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength((long) content.length)
                        .eTag("\"staging-etag\"")
                        .build());
        when(sourceClient.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
            if (sourceGetCount.incrementAndGet() == 1) {
                return responseStream(content);
            }
            promotionSourceGetStarted.countDown();
            releasePromotionSourceGet.await();
            return responseStream(
                    new ByteArrayInputStream(content),
                    content.length,
                    () -> responseAborted.set(true)
            );
        });
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio-a:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio-b:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    try (InputStream input = body.contentStreamProvider().newStream()) {
                        input.readAllBytes();
                    }
                    return PutObjectResponse.builder().build();
                });

        try {
            CompletableFuture<Throwable> completion = CompletableFuture.supplyAsync(() -> {
                try {
                    service.promote(descriptor, digestAccumulator());
                    return null;
                } catch (Throwable throwable) {
                    return throwable;
                }
            }, caller);
            assertThat(promotionSourceGetStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> completion.get(1300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releasePromotionSourceGet.countDown();

            assertThat(completion.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("timed out");
            assertThat(responseAborted).isTrue();
            verify(targetClient, never()).headObject(any(HeadObjectRequest.class));
            verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
            verify(stagingTracker, never()).retainAfterDelete(any());
        } finally {
            releasePromotionSourceGet.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    @DisplayName("verification deadline should abort a GET stream blocked after response headers")
    void shouldAbortBlockedVerificationStreamAtDeadline() throws Exception {
        byte[] content = "blocked-verification-stream".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        AbortUnblockingInputStream blockedInput = new AbortUnblockingInputStream();
        directUploadConfig.setTransferTimeoutSeconds(1);
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        when(sourceClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength((long) content.length)
                        .eTag("\"staging-etag\"")
                        .build());
        when(sourceClient.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(blockedInput, content.length, blockedInput::abort));

        CompletableFuture<Throwable> completion = CompletableFuture.supplyAsync(() -> {
            try {
                service.promote(descriptor, digestAccumulator());
                return null;
            } catch (Throwable throwable) {
                return throwable;
            }
        });

        assertThat(blockedInput.awaitReadStarted(1, TimeUnit.SECONDS)).isTrue();
        Throwable failure = completion.get(2, TimeUnit.SECONDS);

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verification timed out");
        assertThat(blockedInput.wasAborted()).isTrue();
        ArgumentCaptor<GetObjectRequest> getCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(sourceClient).getObject(getCaptor.capture());
        assertRequestTimeouts(getCaptor.getValue());
        verifyNoInteractions(targetClient);
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("executor saturation should fail closed before cleanup")
    void shouldPreserveStagingWhenExecutorRejectsPromotion() {
        byte[] content = "executor-saturated".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        executor.shutdownNow();

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("executor is saturated");

        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("retry should require final quorum and revalidate content")
    void shouldCompleteRetryOnlyWithVerifiedFinalQuorum() {
        byte[] content = "retry-content".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE, "node-c"), 2);
        when(receiptStore.findValidated(descriptor)).thenReturn(Optional.of(receipt(
                descriptor,
                List.of(TARGET_NODE, "node-c"),
                List.of(TARGET_NODE, "node-c"),
                2
        )));
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getClient("node-c")).thenReturn(thirdClient);
        stubFinalOnly(targetClient, descriptor, content);
        stubFinalOnly(thirdClient, descriptor, content);

        DirectUploadDigestAccumulator aggregate = digestAccumulator();
        DirectUploadPromotionResult result = service.promote(descriptor, aggregate);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregate.finishHash()).isEqualTo(descriptor.cipherHash());
        verify(targetClient).getObject(any(GetObjectRequest.class));
        verify(targetClient, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
    }

    @Test
    @DisplayName("single-replica receipt retry should not require a physical storage identity")
    void shouldRetrySingleReplicaWithoutPhysicalStorageIdentity() {
        byte[] content = "single-replica-retry".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        DirectUploadPromotionReceiptStore.PromotionReceipt authoritative = receipt(
                descriptor,
                List.of(TARGET_NODE),
                List.of(TARGET_NODE),
                1
        );
        when(receiptStore.findValidated(descriptor)).thenReturn(Optional.of(authoritative));
        when(clientManager.getNodeConfig(TARGET_NODE))
                .thenReturn(node(TARGET_NODE, "http://node-b:9000", null));
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        stubFinalOnly(targetClient, descriptor, content);

        DirectUploadDigestAccumulator aggregate = digestAccumulator();
        DirectUploadPromotionResult result = service.promote(descriptor, aggregate);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregate.finishHash()).isEqualTo(descriptor.cipherHash());
        verify(receiptStore).recordRetrySuccess(
                descriptor,
                authoritative,
                List.of(TARGET_NODE),
                operationIntent
        );
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
    }

    /**
     * 验证 receipt 已过期且 staging 已删除时，不能按后来降低的一副本拓扑重新授权 final 成功。
     */
    @Test
    @DisplayName("expired receipt and missing staging should fail closed after topology quorum is lowered")
    void shouldFailClosedWhenReceiptExpiredAndStagingMissingAfterQuorumLowered() {
        byte[] content = "expired-receipt-lowered-quorum".getBytes();
        DirectUploadPartDescriptor loweredTopology = descriptor(
                content,
                List.of(TARGET_NODE),
                1
        );
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        when(sourceClient.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("staging missing").build());

        assertThatThrownBy(() -> service.promote(loweredTopology, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion receipt is required");

        verify(receiptStore).findValidated(loweredTopology);
        verify(targetClient, never()).headObject(any(HeadObjectRequest.class));
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("receipt should recover an original quorum after the current topology completely changes")
    void shouldRecoverRetryFromReceiptAfterCompleteTopologyChange() {
        byte[] content = "retry-receipt-topology-change".getBytes();
        String hash = sha256Prefixed(content);
        DirectUploadPartDescriptor descriptor = new DirectUploadPartDescriptor(
                7L,
                SESSION_ID,
                0,
                "staging-node",
                STAGING_KEY,
                "tenant/7/" + hash,
                content.length,
                "\"staging-etag\"",
                hash,
                hash,
                "SHA-256",
                List.of("node-c", "node-d"),
                1
        );
        DirectUploadPromotionReceiptStore.PromotionReceipt receipt = receipt(
                descriptor,
                List.of(SOURCE_NODE, TARGET_NODE),
                List.of(SOURCE_NODE, TARGET_NODE),
                2
        );
        when(receiptStore.findValidated(descriptor)).thenReturn(Optional.of(receipt));
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(null);
        when(clientManager.getClient("node-c")).thenReturn(sourceClient);
        when(clientManager.getClient("node-d")).thenReturn(targetClient);
        stubFinalOnly(sourceClient, descriptor, content);
        stubFinalOnly(targetClient, descriptor, content);

        DirectUploadDigestAccumulator aggregate = digestAccumulator();
        DirectUploadPromotionResult result = service.promote(descriptor, aggregate);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregate.finishHash()).isEqualTo(hash);
        verify(sourceClient).getObject(any(GetObjectRequest.class));
        verify(targetClient).getObject(any(GetObjectRequest.class));
        var ordered = inOrder(receiptStore, degradedWriteTracker);
        ordered.verify(receiptStore).recordRetrySuccess(
                eq(descriptor),
                any(DirectUploadPromotionReceiptStore.PromotionReceipt.class),
                eq(List.of("node-c", "node-d")),
                eq(operationIntent)
        );
        ordered.verify(degradedWriteTracker).recordAuthoritativeDegradedWrite(
                descriptor.cipherHash(),
                List.of("node-c", "node-d"),
                7L
        );
        verifyNoInteractions(consistencyRepairService);
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
    }

    /**
     * 验证 retry 完成 final 全量 hash 后若 topology revision 漂移，不能把旧快照结果写入 receipt。
     */
    @Test
    @DisplayName("retry receipt should fail closed when topology changes after final hash")
    void shouldFenceRetryReceiptWhenTopologyChangesAfterFinalHash() throws Exception {
        byte[] content = "retry-topology-revision-drift".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        DirectUploadPromotionReceiptStore.PromotionReceipt authoritative = receipt(
                descriptor,
                List.of(TARGET_NODE),
                List.of(TARGET_NODE),
                1
        );
        when(receiptStore.findValidated(descriptor)).thenReturn(Optional.of(authoritative));
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        stubFinalOnly(targetClient, descriptor, content);
        CountDownLatch commitBoundaryEntered = new CountDownLatch(1);
        CountDownLatch allowCommitCheck = new CountDownLatch(1);
        AtomicBoolean topologyCurrent = new AtomicBoolean(true);
        doAnswer(invocation -> {
            commitBoundaryEntered.countDown();
            if (!allowCommitCheck.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test topology fence timed out");
            }
            if (!topologyCurrent.get()) {
                throw new IllegalStateException("S3 topology changed during operation");
            }
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(topologyLease).runIfCurrent(any(Runnable.class));

        CompletableFuture<DirectUploadPromotionResult> retry = CompletableFuture.supplyAsync(
                () -> service.promote(descriptor, digestAccumulator())
        );
        assertThat(commitBoundaryEntered.await(5, TimeUnit.SECONDS)).isTrue();
        topologyCurrent.set(false);
        allowCommitCheck.countDown();

        assertThatThrownBy(retry::join)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("S3 topology changed during operation");
        verify(receiptStore, never()).recordRetrySuccess(any(), any(), any(), any());
        verifyNoInteractions(degradedWriteTracker, consistencyRepairService);
        verify(sourceClient, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("authoritative receipt should ignore replayed staging and complete from final replicas")
    void shouldIgnoreReplayedStagingWhenAuthoritativeReceiptExists() {
        byte[] content = "authoritative-final-content".getBytes();
        byte[] replayedContent = content.clone();
        replayedContent[0] ^= 0x01;
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        DirectUploadPromotionReceiptStore.PromotionReceipt receipt = receipt(
                descriptor,
                List.of(TARGET_NODE),
                List.of(TARGET_NODE),
                1
        );
        when(receiptStore.findValidated(descriptor)).thenReturn(Optional.of(receipt));
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        stubFinalOnly(targetClient, descriptor, content);
        lenient().when(sourceClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength((long) replayedContent.length)
                        .eTag("\"replayed-etag\"")
                        .build());
        lenient().when(sourceClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(invocation -> responseStream(replayedContent));
        when(sourceClient.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        DirectUploadDigestAccumulator aggregate = digestAccumulator();
        DirectUploadPromotionResult result = service.promote(descriptor, aggregate);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregate.finishHash()).isEqualTo(descriptor.cipherHash());
        var ordered = inOrder(targetClient, sourceClient);
        ordered.verify(targetClient).headObject(any(HeadObjectRequest.class));
        ordered.verify(targetClient).getObject(any(GetObjectRequest.class));
        ordered.verify(sourceClient, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(sourceClient, never()).headObject(any(HeadObjectRequest.class));
        verify(sourceClient, never()).getObject(any(GetObjectRequest.class));
        verify(sourceClient, never()).copyObject(any(CopyObjectRequest.class));
        verify(sourceClient, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(targetClient, never()).copyObject(any(CopyObjectRequest.class));
        verify(targetClient, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(receiptStore).recordRetrySuccess(
                eq(descriptor),
                any(DirectUploadPromotionReceiptStore.PromotionReceipt.class),
                eq(List.of(TARGET_NODE)),
                eq(operationIntent)
        );
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
    }

    @Test
    @DisplayName("corrupt or identity-mismatched receipt should fail closed before legacy fallback")
    void shouldFailClosedWhenReceiptValidationFails() {
        byte[] content = "retry-corrupt-receipt".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        when(receiptStore.findValidated(descriptor))
                .thenThrow(new IllegalStateException("direct-upload promotion receipt is corrupt"));

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt is corrupt");

        verifyNoInteractions(targetClient, thirdClient);
        verify(receiptStore, never()).recordSuccess(any(), any(), any());
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("retry with fewer than quorum final replicas should fail closed")
    void shouldRejectRetryWithoutFinalQuorum() {
        byte[] content = "retry-insufficient".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE, "node-c"), 2);
        stubReceiptForCurrentTopology(descriptor);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getClient("node-c")).thenReturn(thirdClient);
        stubFinalOnly(targetClient, descriptor, content);
        when(thirdClient.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");

        verify(targetClient).getObject(any(GetObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("receipt retry should count only listed physical candidates toward quorum")
    void shouldCountOnlyListedPhysicalCandidatesTowardRetryQuorum() {
        byte[] content = "retry-current-targets".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE, "node-c"), 2);
        stubReceiptForCurrentTopology(descriptor);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getClient("node-c")).thenReturn(thirdClient);
        stubFinalOnly(targetClient, descriptor, content);
        when(thirdClient.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("final missing").build());

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");

        verify(targetClient).getObject(any(GetObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
    }

    @Test
    @DisplayName("unavailable source client should complete from current final quorum and retain lifecycle tracking")
    void shouldRetainTrackerWhenSourceClientIsUnavailableButFinalQuorumIsValid() {
        byte[] content = "retry-source-client-unavailable".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE, "node-c"), 2);
        stubReceiptForCurrentTopology(descriptor);
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(null);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getClient("node-c")).thenReturn(thirdClient);
        stubFinalOnly(targetClient, descriptor, content);
        stubFinalOnly(thirdClient, descriptor, content);

        DirectUploadDigestAccumulator aggregate = digestAccumulator();
        DirectUploadPromotionResult result = service.promote(descriptor, aggregate);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregate.finishHash()).isEqualTo(descriptor.cipherHash());
        verify(targetClient).getObject(any(GetObjectRequest.class));
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
        verifyNoInteractions(sourceClient);
    }

    @Test
    @DisplayName("one retry HEAD transport failure should not hide quorum on other current targets")
    void shouldIgnoreSingleRetryHeadTransportFailureWhenOtherTargetsReachQuorum() {
        byte[] content = "retry-head-sdk-failure".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(
                content,
                List.of(TARGET_NODE, "node-c", "node-d"),
                2
        );
        S3Client fourthClient = mock(S3Client.class);
        stubReceiptForCurrentTopology(descriptor);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getClient("node-c")).thenReturn(thirdClient);
        when(clientManager.getClient("node-d")).thenReturn(fourthClient);
        when(targetClient.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkClientException.builder().message("target temporarily unavailable").build());
        stubFinalOnly(thirdClient, descriptor, content);
        stubFinalOnly(fourthClient, descriptor, content);
        when(consistencyRepairService.scheduleImmediateRepairByNodesAsync(
                descriptor.finalObjectName(), "node-c", TARGET_NODE))
                .thenReturn(CompletableFuture.completedFuture(false));

        DirectUploadDigestAccumulator aggregate = digestAccumulator();
        DirectUploadPromotionResult result = service.promote(descriptor, aggregate);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregate.finishHash()).isEqualTo(descriptor.cipherHash());
        verify(thirdClient).getObject(any(GetObjectRequest.class));
        verify(consistencyRepairService).scheduleImmediateRepairByNodesAsync(
                descriptor.finalObjectName(), "node-c", TARGET_NODE);
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
    }

    @Test
    @DisplayName("retry should discard a corrupt first candidate and verify the next without digest pollution")
    void shouldTryNextRetryCandidateWithoutPollutingAggregate() {
        byte[] content = "retry-valid-candidate".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(
                content,
                List.of(TARGET_NODE, "node-c", "node-d"),
                2
        );
        S3Client fourthClient = mock(S3Client.class);
        stubReceiptForCurrentTopology(descriptor);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getClient("node-c")).thenReturn(thirdClient);
        when(clientManager.getClient("node-d")).thenReturn(fourthClient);
        when(targetClient.headObject(any(HeadObjectRequest.class))).thenReturn(finalHead(descriptor));
        when(targetClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(invocation -> responseStream(
                        new PartialFailureInputStream(content, Math.max(1, content.length / 2)),
                        content.length
                ));
        stubFinalOnly(thirdClient, descriptor, content);
        stubFinalOnly(fourthClient, descriptor, content);
        when(consistencyRepairService.scheduleImmediateRepairByNodesAsync(
                descriptor.finalObjectName(), "node-c", TARGET_NODE))
                .thenReturn(CompletableFuture.completedFuture(false));

        DirectUploadDigestAccumulator aggregate = digestAccumulator();
        DirectUploadPromotionResult result = service.promote(descriptor, aggregate);

        assertThat(result.size()).isEqualTo(content.length);
        assertThat(aggregate.finishHash()).isEqualTo(descriptor.cipherHash());
        verify(targetClient).getObject(any(GetObjectRequest.class));
        verify(thirdClient).getObject(any(GetObjectRequest.class));
        verify(fourthClient).getObject(any(GetObjectRequest.class));
        verify(consistencyRepairService).scheduleImmediateRepairByNodesAsync(
                descriptor.finalObjectName(), "node-c", TARGET_NODE);
    }

    @Test
    @DisplayName("retry should fail when removing a corrupt candidate drops below quorum")
    void shouldFailRetryWhenRejectedCandidateDropsBelowQuorum() {
        byte[] content = "retry-quorum-lost".getBytes();
        byte[] corrupt = content.clone();
        corrupt[0] ^= 1;
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE, "node-c"), 2);
        stubReceiptForCurrentTopology(descriptor);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getClient("node-c")).thenReturn(thirdClient);
        when(targetClient.headObject(any(HeadObjectRequest.class))).thenReturn(finalHead(descriptor));
        when(targetClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(invocation -> responseStream(corrupt));
        stubFinalOnly(thirdClient, descriptor, content);
        DirectUploadDigestAccumulator aggregate = digestAccumulator();

        assertThatThrownBy(() -> service.promote(descriptor, aggregate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");

        assertThat(aggregate.finishHash()).isEqualTo(sha256Prefixed(new byte[0]));
        verify(thirdClient).getObject(any(GetObjectRequest.class));
        verify(stagingTracker, never()).retainAfterDelete(any());
        verifyNoInteractions(consistencyRepairService);
    }

    @Test
    @DisplayName("tombstone persistence failure after quorum should fail complete")
    void shouldFailCompleteWhenTombstonePersistenceFails() {
        byte[] content = "cleanup-deferred".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());

        assertThatThrownBy(() -> service.promote(descriptor, digestAccumulator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tombstone")
                .hasRootCauseMessage("redis unavailable");

        verify(sourceClient, times(2)).deleteObject(any(DeleteObjectRequest.class));
        assertThat(meterRegistry.counter(
                "storage_direct_upload_operations_total",
                "operation", "staging_cleanup",
                "result", "tombstone_failure").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("provider delete failure should defer after tombstone is retained")
    void shouldDeferProviderDeleteFailureAfterRetainingTombstone() {
        byte[] content = "cleanup-provider-deferred".getBytes();
        DirectUploadPartDescriptor descriptor = descriptor(content, List.of(TARGET_NODE), 1);
        stubSourceStaging(content);
        stubTargetFinal(targetClient, descriptor, content);
        when(clientManager.getClient(TARGET_NODE)).thenReturn(targetClient);
        when(clientManager.getNodeConfig(SOURCE_NODE)).thenReturn(node(SOURCE_NODE, "http://minio:9000"));
        when(clientManager.getNodeConfig(TARGET_NODE)).thenReturn(node(TARGET_NODE, "http://minio:9000"));
        when(s3Monitor.isNodeOnline(TARGET_NODE)).thenReturn(true);
        when(targetClient.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(targetClient.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        when(sourceClient.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(SdkClientException.builder().message("provider unavailable").build());

        DirectUploadPromotionResult result = service.promote(descriptor, digestAccumulator());

        assertThat(result.size()).isEqualTo(content.length);
        verify(sourceClient, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(stagingTracker).retainAfterDelete(descriptor.stagingDescriptor());
        assertThat(meterRegistry.counter(
                "storage_direct_upload_operations_total",
                "operation", "staging_cleanup",
                "result", "deferred").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("abort should delete public and sealed before retaining tombstone")
    void shouldAbortCanonicalStagingUnderThePartLock() {
        DirectUploadStagingDescriptor descriptor = new DirectUploadStagingDescriptor(
                7L, SESSION_ID, 0, SOURCE_NODE, STAGING_KEY);
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        when(sourceClient.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        service.abort(descriptor);

        var ordered = inOrder(sourceClient, stagingTracker);
        ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        ordered.verify(sourceClient, times(2)).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getAllValues()).extracting(DeleteObjectRequest::bucket)
                .containsOnly(SOURCE_NODE);
        assertThat(deleteCaptor.getAllValues()).extracting(DeleteObjectRequest::key)
                .containsExactly(STAGING_KEY, SEALED_KEY);
        ordered.verify(stagingTracker).retainAfterDelete(descriptor);
        verifyNoInteractions(receiptStore);
    }

    @Test
    @DisplayName("abort should retain tombstone when the staging bucket is already absent")
    void shouldTreatMissingStagingBucketAsIdempotentAbortSuccess() {
        DirectUploadStagingDescriptor descriptor = new DirectUploadStagingDescriptor(
                7L, SESSION_ID, 0, SOURCE_NODE, STAGING_KEY);
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        when(sourceClient.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("NoSuchBucket").build());

        service.abort(descriptor);

        verify(stagingTracker).retainAfterDelete(descriptor);
        verifyNoInteractions(receiptStore);
        assertThat(meterRegistry.counter(
                "storage_direct_upload_operations_total",
                "operation", "abort",
                "result", "success").count()).isEqualTo(1.0);
    }

    /**
     * 构建包含规范租户路径、hash 和目标快照的分片描述。
     */
    private DirectUploadPartDescriptor descriptor(byte[] content, List<String> targets, int quorum) {
        return descriptor(content, targets, quorum, "\"staging-etag\"");
    }

    /**
     * 构建使用指定调用方 ETag 的分片描述。
     */
    private DirectUploadPartDescriptor descriptor(
            byte[] content,
            List<String> targets,
            int quorum,
            String eTag
    ) {
        String hash = sha256Prefixed(content);
        return new DirectUploadPartDescriptor(
                7L,
                SESSION_ID,
                0,
                SOURCE_NODE,
                STAGING_KEY,
                "tenant/7/" + hash,
                content.length,
                eTag,
                hash,
                hash,
                "SHA-256",
                targets,
                quorum
        );
    }

    /**
     * 构建与当前 final identity 一致、但保留指定首次拓扑和原 quorum 的测试 receipt。
     */
    private DirectUploadPromotionReceiptStore.PromotionReceipt receipt(
            DirectUploadPartDescriptor descriptor,
            List<String> initialTargets,
            List<String> successfulNodes,
            int originalQuorum
    ) {
        return new DirectUploadPromotionReceiptStore.PromotionReceipt(
                1,
                descriptor.tenantId(),
                descriptor.sessionId(),
                descriptor.partIndex(),
                descriptor.finalObjectName(),
                descriptor.size(),
                descriptor.plainHash(),
                descriptor.cipherHash(),
                descriptor.checksumAlgorithm(),
                initialTargets,
                successfulNodes,
                originalQuorum
        );
    }

    /**
     * 为 final-only retry 固化当前拓扑和原仲裁，避免测试依赖已禁止的无回执降级授权。
     */
    private void stubReceiptForCurrentTopology(DirectUploadPartDescriptor descriptor) {
        when(receiptStore.findValidated(descriptor)).thenReturn(Optional.of(receipt(
                descriptor,
                descriptor.targetNodes(),
                descriptor.targetNodes(),
                descriptor.requiredQuorum()
        )));
    }

    /**
     * 配置源 staging 的 HEAD、条件 GET、桶检查和成功删除响应。
     */
    private void stubSourceStaging(byte[] content) {
        stubSourceStaging(content, "\"staging-etag\"");
    }

    /**
     * 配置指定 staging ETag 的源对象响应。
     */
    private void stubSourceStaging(byte[] content, String eTag) {
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        AtomicInteger sealedHeadCalls = new AtomicInteger();
        when(sourceClient.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            if (SEALED_KEY.equals(request.key())) {
                if (sealedHeadCalls.incrementAndGet() == 1) {
                    throw NoSuchKeyException.builder().message("sealed missing").build();
                }
                return HeadObjectResponse.builder()
                        .contentLength((long) content.length)
                        .eTag("\"sealed-etag\"")
                        .build();
            }
            return HeadObjectResponse.builder()
                    .contentLength((long) content.length)
                    .eTag(eTag)
                    .build();
        });
        when(sourceClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(invocation -> responseStream(content));
        lenient().when(sourceClient.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());
    }

    /**
     * 同时按请求 key 返回源 staging 和源 final HEAD。
     */
    private void stubSourceStagingAndFinal(byte[] content, DirectUploadPartDescriptor descriptor) {
        when(clientManager.getClient(SOURCE_NODE)).thenReturn(sourceClient);
        AtomicInteger sealedHeadCalls = new AtomicInteger();
        when(sourceClient.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            if (SEALED_KEY.equals(request.key())) {
                if (sealedHeadCalls.incrementAndGet() == 1) {
                    throw NoSuchKeyException.builder().message("sealed missing").build();
                }
                return HeadObjectResponse.builder()
                        .contentLength((long) content.length)
                        .eTag("\"sealed-etag\"")
                        .build();
            }
            return request.key().equals(STAGING_KEY)
                    ? HeadObjectResponse.builder()
                        .contentLength((long) content.length)
                        .eTag("\"staging-etag\"")
                        .build()
                    : finalHead(descriptor);
        });
        when(sourceClient.getObject(any(GetObjectRequest.class)))
                .thenAnswer(invocation -> responseStream(content));
        when(sourceClient.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());
    }

    /**
     * 配置目标 final HEAD 与 provider 响应。
     */
    private void stubTargetFinal(
            S3Client client,
            DirectUploadPartDescriptor descriptor,
            byte[] content
    ) {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(finalHead(descriptor));
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> responseStream(content));
    }

    /**
     * 配置 retry 候选 final 的 HEAD 和有界读取。
     */
    private void stubFinalOnly(S3Client client, DirectUploadPartDescriptor descriptor, byte[] content) {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(finalHead(descriptor));
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> responseStream(content));
    }

    /**
     * 构建可信 final HEAD。
     */
    private HeadObjectResponse finalHead(DirectUploadPartDescriptor descriptor) {
        return finalHead(descriptor, "\"target-etag\"");
    }

    /**
     * 构建使用指定 provider ETag 的 final HEAD，用于验证持久化边界。
     */
    private HeadObjectResponse finalHead(DirectUploadPartDescriptor descriptor, String eTag) {
        return HeadObjectResponse.builder()
                .contentLength(descriptor.size())
                .eTag(eTag)
                .metadata(finalMetadata(descriptor))
                .build();
    }

    /**
     * 构建 storage 写入的完整 final 用户元数据。
     */
    private Map<String, String> finalMetadata(DirectUploadPartDescriptor descriptor) {
        return Map.of(
                "file-hash", descriptor.cipherHash(),
                "tenant-id", String.valueOf(descriptor.tenantId()),
                "checksum-algorithm", "SHA-256",
                "plain-hash", descriptor.plainHash(),
                "cipher-hash", descriptor.cipherHash()
        );
    }

    /**
     * 构建每次调用都拥有独立底层流的 AWS 响应流。
     */
    private ResponseInputStream<GetObjectResponse> responseStream(byte[] content) {
        return responseStream(new ByteArrayInputStream(content), content.length);
    }

    /**
     * 用指定底层流构建响应，便于模拟部分读取失败。
     */
    private ResponseInputStream<GetObjectResponse> responseStream(InputStream input, long contentLength) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(contentLength).build(),
                AbortableInputStream.create(input)
        );
    }

    /**
     * 用可观测 abort 行为构建响应，模拟 SDK 已返回 headers 后阻塞在 body read。
     */
    private ResponseInputStream<GetObjectResponse> responseStream(
            InputStream input,
            long contentLength,
            Runnable abortAction
    ) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(contentLength).build(),
                AbortableInputStream.create(input, abortAction::run)
        );
    }

    /**
     * 创建仅包含 endpoint 事实的节点配置。
     */
    private NodeConfig node(String name, String endpoint) {
        return node(name, endpoint, "physical-" + name);
    }

    /**
     * 创建带显式物理存储身份的测试节点配置。
     */
    private NodeConfig node(String name, String endpoint, String physicalStorageId) {
        NodeConfig config = new NodeConfig();
        config.setName(name);
        config.setEndpoint(endpoint);
        config.setPhysicalStorageId(physicalStorageId);
        return config;
    }

    /**
     * 断言 AWS 请求同时具备整次调用和单次尝试的正数超时，且尝试超时不超过总超时。
     */
    private void assertRequestTimeouts(SdkRequest request) {
        var override = request.overrideConfiguration().orElseThrow();
        var callTimeout = override.apiCallTimeout().orElseThrow();
        var attemptTimeout = override.apiCallAttemptTimeout().orElseThrow();
        assertThat(callTimeout.toNanos()).isPositive();
        assertThat(attemptTimeout.toNanos()).isPositive();
        assertThat(attemptTimeout).isLessThanOrEqualTo(callTimeout);
    }

    /**
     * 读取共享截止调度器的待处理任务数，验证正常闭流会从延迟队列真实移除 abort 任务。
     */
    private int pendingDeadlineAbortTaskCount() throws ReflectiveOperationException {
        Field schedulerField = DirectUploadPromotionService.class
                .getDeclaredField("DEADLINE_ABORT_SCHEDULER");
        schedulerField.setAccessible(true);
        ScheduledThreadPoolExecutor scheduler =
                (ScheduledThreadPoolExecutor) schedulerField.get(null);
        return scheduler.getQueue().size();
    }

    /**
     * 创建支持候选回滚的整文件 SHA-256 累加器。
     */
    private DirectUploadDigestAccumulator digestAccumulator() {
        return DirectUploadDigestAccumulator.sha256();
    }

    /**
     * 计算规范 SHA-256 内容标识。
     */
    private String sha256Prefixed(byte[] content) {
        MessageDigest digest = sha256Digest();
        return "sha256:" + HexFormat.of().formatHex(digest.digest(content));
    }

    /**
     * 创建测试用 SHA-256 digest。
     */
    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 通过 afterExecute 发布真实 executor worker 已退出 Runnable 的证据。
     */
    private static final class AfterExecuteTrackingExecutor extends ThreadPoolExecutor {
        private final CountDownLatch workerTerminated;

        private AfterExecuteTrackingExecutor(CountDownLatch workerTerminated) {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
            this.workerTerminated = workerTerminated;
        }

        /**
         * 在节点提升 Runnable 完整返回后发布终止信号。
         */
        @Override
        protected void afterExecute(Runnable runnable, Throwable throwable) {
            try {
                super.afterExecute(runnable, throwable);
            } finally {
                workerTerminated.countDown();
            }
        }
    }

    /**
     * 首次返回部分内容，后续读取抛错，用于验证失败候选的 digest 不会提交。
     */
    private static final class PartialFailureInputStream extends InputStream {
        private final byte[] content;
        private final int failureAfter;
        private int position;

        private PartialFailureInputStream(byte[] content, int failureAfter) {
            this.content = content.clone();
            this.failureAfter = failureAfter;
        }

        /**
         * 返回一个字节，达到故障边界后抛出读取异常。
         */
        @Override
        public int read() throws IOException {
            if (position >= failureAfter) {
                throw new IOException("simulated partial stream failure");
            }
            return content[position++] & 0xff;
        }

        /**
         * 最多返回到故障边界，确保 digest 已吸收部分字节后才失败。
         */
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= failureAfter) {
                throw new IOException("simulated partial stream failure");
            }
            int readable = Math.min(length, failureAfter - position);
            System.arraycopy(content, position, buffer, offset, readable);
            position += readable;
            return readable;
        }
    }

    /**
     * 在 abort 前永久阻塞读取，并在 abort 后以 IOException 退出。
     */
    private static final class AbortUnblockingInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch abortSignal = new CountDownLatch(1);
        private final AtomicBoolean aborted = new AtomicBoolean(false);

        /**
         * 阻塞单字节读取直到收到 abort。
         */
        @Override
        public int read() throws IOException {
            awaitAbort();
            throw new IOException("stream aborted at deadline");
        }

        /**
         * 阻塞批量读取直到收到 abort。
         */
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            awaitAbort();
            throw new IOException("stream aborted at deadline");
        }

        /**
         * 发布 provider abort 并唤醒阻塞读取。
         */
        private void abort() {
            aborted.set(true);
            abortSignal.countDown();
        }

        /**
         * 等待并传播测试线程中断，避免隐藏异步取消。
         */
        private void awaitAbort() throws IOException {
            readStarted.countDown();
            try {
                abortSignal.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("blocked read interrupted", e);
            }
        }

        /**
         * 等待生产读取真正进入阻塞点。
         */
        private boolean awaitReadStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return readStarted.await(timeout, unit);
        }

        /**
         * 返回 provider abort 是否被触发。
         */
        private boolean wasAborted() {
            return aborted.get();
        }
    }
}
