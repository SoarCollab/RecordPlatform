package cn.flying.storage.service;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.tenant.TenantContextUtil;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.request.AbortDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CompleteDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CreateDirectMultipartUploadRequest;
import cn.flying.platformapi.request.DirectMultipartCompletedPart;
import cn.flying.platformapi.request.DirectMultipartUploadPartRequest;
import cn.flying.platformapi.response.CompleteDirectMultipartUploadResponse;
import cn.flying.platformapi.response.CreateDirectMultipartUploadResponse;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DistributedStorageServiceImpl Unit Tests")
class DistributedStorageServiceImplTest {

    @Mock
    private S3ClientManager clientManager;

    @Mock
    private S3Monitor s3Monitor;

    @Mock
    private StorageProperties storageProperties;

    @Mock
    private ConsistencyRepairService consistencyRepairService;

    @Mock
    private FaultDomainManager faultDomainManager;

    @Mock
    private RebalanceService rebalanceService;

    @Mock
    private DegradedWriteTracker degradedWriteTracker;

    @Mock
    private DirectUploadPromotionService directUploadPromotionService;

    @Mock
    private DirectUploadStagingTracker directUploadStagingTracker;

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private S3ClientManager.TopologyLease topologyLease;

    @InjectMocks
    private DistributedStorageServiceImpl storageService;

    private static final String TEST_FILE_HASH = "sha256_test_hash_1234";
    private static final byte[] TEST_FILE_DATA = "test file content".getBytes();

    private ExecutorService uploadExecutor;

    /**
     * 初始化上传线程池并注入到待测服务，保证异步分支可被稳定执行。
     */
    @BeforeEach
    void setUp() {
        uploadExecutor = Executors.newFixedThreadPool(4);
        ReflectionTestUtils.setField(storageService, "uploadExecutor", uploadExecutor);
        lenient().when(storageProperties.getDirectUpload())
                .thenReturn(new StorageProperties.DirectUploadConfig());
        lenient().when(clientManager.acquireTopologyLease()).thenReturn(topologyLease);
        lenient().when(topologyLease.revision()).thenReturn(1L);
        lenient().when(topologyLease.getClient(anyString()))
                .thenAnswer(invocation -> clientManager.getClient(invocation.getArgument(0)));
        lenient().when(topologyLease.getPresigner(anyString()))
                .thenAnswer(invocation -> clientManager.getPresigner(invocation.getArgument(0)));
    }

    /**
     * 回收测试线程池，避免测试间线程泄漏。
     */
    @AfterEach
    void tearDown() {
        if (uploadExecutor != null) {
            uploadExecutor.shutdownNow();
        }
    }

    /**
     * Calculates the direct-upload hash format used by the browser and storage verifier.
     */
    private String sha256Prefixed(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds the tenant-scoped direct-upload staging key expected by the storage service.
     */
    private String directStagingObjectName(String sessionId, int partIndex) {
        return "tenant/0/staging/direct-upload/" + sessionId + "/part-" + partIndex;
    }

    /**
     * Builds the tenant-scoped direct-upload final key expected by the storage service.
     */
    private String directFinalObjectName(String chunkHash) {
        return "tenant/0/" + chunkHash;
    }

    /**
     * Builds the logical storage path returned for a completed direct-upload part.
     */
    private String directStoragePath(String chunkHash) {
        return "storage/tenant/0/chunk/" + chunkHash;
    }

    /**
     * 构建单分片直传完成请求，便于覆盖副本拓扑与 ETag 边界。
     */
    private CompleteDirectMultipartUploadRequest directCompleteRequest(byte[] chunkBytes, String eTag) {
        String chunkHash = sha256Prefixed(chunkBytes);
        return new CompleteDirectMultipartUploadRequest(
                "direct-session",
                List.of(new DirectMultipartCompletedPart(
                        0,
                        directStagingObjectName("direct-session", 0),
                        directFinalObjectName(chunkHash),
                        "node1",
                        directStoragePath(chunkHash),
                        chunkBytes.length,
                        eTag,
                        chunkHash,
                        chunkHash,
                        "SHA-256"
                ))
        );
    }

    @Nested
    @DisplayName("Store File Chunk Tests")
    class StoreFileChunkTests {

        /**
         * 配置三副本两仲裁写入，并让第三个副本确定失败。
         */
        private void stubThreeReplicaQuorumWithThirdNodeFailure() {
            when(faultDomainManager.getTargetNodes(TEST_FILE_HASH))
                    .thenReturn(List.of("node1", "node2", "node3"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(3);
            when(storageProperties.getEffectiveQuorum()).thenReturn(2);

            StorageProperties.DegradedWriteConfig degradedConfig = new StorageProperties.DegradedWriteConfig();
            degradedConfig.setEnabled(true);
            degradedConfig.setMinReplicas(2);
            degradedConfig.setTrackForSync(true);
            when(storageProperties.getDegradedWrite()).thenReturn(degradedConfig);

            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            when(clientManager.getClient(anyString())).thenReturn(s3Client);
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenReturn(HeadBucketResponse.builder().build());
            when(s3Client.putObject(
                    any(PutObjectRequest.class),
                    any(software.amazon.awssdk.core.sync.RequestBody.class)
            )).thenAnswer(invocation -> {
                PutObjectRequest request = invocation.getArgument(0);
                if ("node3".equals(request.bucket())) {
                    throw new IllegalStateException("node3 write failed");
                }
                return PutObjectResponse.builder().eTag("ok-" + request.bucket()).build();
            });
        }

        @Test
        @DisplayName("Should return error for null file data")
        void shouldReturnErrorForNullFileData() {
            Result<String> result = storageService.storeFileChunk(null, TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
        }

        @Test
        @DisplayName("Should return error for empty file data")
        void shouldReturnErrorForEmptyFileData() {
            Result<String> result = storageService.storeFileChunk(new byte[0], TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
        }

        @Test
        @DisplayName("Should return error for null file hash")
        void shouldReturnErrorForNullFileHash() {
            Result<String> result = storageService.storeFileChunk(TEST_FILE_DATA, null);

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
        }

        @Test
        @DisplayName("Should return error for empty file hash")
        void shouldReturnErrorForEmptyFileHash() {
            Result<String> result = storageService.storeFileChunk(TEST_FILE_DATA, "");

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
        }

        @Test
        @DisplayName("Should return error when insufficient nodes available")
        void shouldReturnErrorWhenInsufficientNodesAvailable() {
            when(faultDomainManager.getTargetNodes(anyString())).thenReturn(Collections.singletonList("node1"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            StorageProperties.DegradedWriteConfig degradedConfig = new StorageProperties.DegradedWriteConfig();
            degradedConfig.setEnabled(false);
            when(storageProperties.getDegradedWrite()).thenReturn(degradedConfig);

            Result<String> result = storageService.storeFileChunk(TEST_FILE_DATA, TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(ResultEnum.STORAGE_INSUFFICIENT_REPLICAS.getCode());
        }

        /**
         * 验证降级写入允许时可返回成功并记录待同步信息。
         */
        @Test
        @DisplayName("Should allow degraded write and track sync")
        void shouldAllowDegradedWriteAndTrackSync() {
            when(faultDomainManager.getTargetNodes(TEST_FILE_HASH)).thenReturn(Collections.singletonList("node1"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            when(storageProperties.getEffectiveQuorum()).thenReturn(2);

            StorageProperties.DegradedWriteConfig degradedConfig = new StorageProperties.DegradedWriteConfig();
            degradedConfig.setEnabled(true);
            degradedConfig.setMinReplicas(1);
            degradedConfig.setTrackForSync(true);
            when(storageProperties.getDegradedWrite()).thenReturn(degradedConfig);

            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(clientManager.getClient("node1")).thenReturn(s3Client);
            when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
            when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().eTag("ok").build());

            Result<String> result = storageService.storeFileChunk(TEST_FILE_DATA, TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).contains(TEST_FILE_HASH);
            verify(degradedWriteTracker).recordAuthoritativeDegradedWrite(
                    eq(TEST_FILE_HASH),
                    anyList(),
                    anyLong()
            );

            ArgumentCaptor<PutObjectRequest> putRequestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(putRequestCaptor.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
            assertThat(putRequestCaptor.getValue().metadata())
                    .containsEntry("file-hash", TEST_FILE_HASH)
                    .containsEntry("tenant-id", "0");
        }

        /**
         * 验证完整三副本拓扑只达到两仲裁时也会先持久化权威成功节点，修复失败不会丢失入口。
         */
        @Test
        @DisplayName("Should retain durable degraded evidence when immediate repair fails after quorum")
        void shouldRetainDurableDegradedEvidenceWhenImmediateRepairFailsAfterQuorum() {
            stubThreeReplicaQuorumWithThirdNodeFailure();
            when(consistencyRepairService.scheduleImmediateRepairByNodesAsync(
                    anyString(), anyString(), anyString()
            )).thenReturn(CompletableFuture.completedFuture(false));

            Result<String> result = storageService.storeFileChunk(TEST_FILE_DATA, TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).contains(TEST_FILE_HASH);
            var evidenceOrder = inOrder(degradedWriteTracker, consistencyRepairService);
            evidenceOrder.verify(degradedWriteTracker).recordAuthoritativeDegradedWrite(
                    eq(TEST_FILE_HASH),
                    argThat(nodes -> new HashSet<>(nodes).equals(Set.of("node1", "node2"))),
                    eq(0L)
            );
            evidenceOrder.verify(consistencyRepairService).scheduleImmediateRepairByNodesAsync(
                    eq("tenant/0/" + TEST_FILE_HASH),
                    eq("node1"),
                    eq("node3")
            );
            verify(degradedWriteTracker, never()).markNodeRepaired(anyString(), anyLong(), anyString());
        }

        /**
         * 验证即时修复只有在真实成功后才清除对应缺失节点。
         */
        @Test
        @DisplayName("Should mark the missing node repaired only after immediate repair succeeds")
        void shouldMarkMissingNodeRepairedOnlyAfterImmediateRepairSucceeds() {
            stubThreeReplicaQuorumWithThirdNodeFailure();
            when(consistencyRepairService.scheduleImmediateRepairByNodesAsync(
                    anyString(), anyString(), anyString()
            )).thenReturn(CompletableFuture.completedFuture(true));

            Result<String> result = storageService.storeFileChunk(TEST_FILE_DATA, TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(200);
            var repairOrder = inOrder(degradedWriteTracker, consistencyRepairService);
            repairOrder.verify(degradedWriteTracker).recordAuthoritativeDegradedWrite(
                    eq(TEST_FILE_HASH),
                    argThat(nodes -> new HashSet<>(nodes).equals(Set.of("node1", "node2"))),
                    eq(0L)
            );
            repairOrder.verify(consistencyRepairService).scheduleImmediateRepairByNodesAsync(
                    eq("tenant/0/" + TEST_FILE_HASH),
                    eq("node1"),
                    eq("node3")
            );
            repairOrder.verify(degradedWriteTracker).markNodeRepaired(TEST_FILE_HASH, 0L, "node3");
        }

        /**
         * 验证达到副本数但无法满足仲裁时返回仲裁失败错误。
         */
        @Test
        @DisplayName("Should return quorum error when all uploads fail")
        void shouldReturnQuorumErrorWhenAllUploadsFail() {
            when(faultDomainManager.getTargetNodes(TEST_FILE_HASH)).thenReturn(Arrays.asList("node1", "node2"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            when(storageProperties.getEffectiveQuorum()).thenReturn(2);

            StorageProperties.DegradedWriteConfig degradedConfig = new StorageProperties.DegradedWriteConfig();
            degradedConfig.setEnabled(false);
            when(storageProperties.getDegradedWrite()).thenReturn(degradedConfig);

            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            when(clientManager.getClient(anyString())).thenReturn(null);

            Result<String> result = storageService.storeFileChunk(TEST_FILE_DATA, TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(ResultEnum.STORAGE_QUORUM_NOT_REACHED.getCode());
        }

        /**
         * 验证存储流程在 try 块内出现异常时会统一返回文件服务错误。
         */
        @Test
        @DisplayName("Should return file service error on exception")
        void shouldReturnFileServiceErrorOnException() {
            when(faultDomainManager.getTargetNodes(TEST_FILE_HASH)).thenReturn(Collections.singletonList("node1"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(1);
            when(storageProperties.getEffectiveQuorum()).thenReturn(1);

            StorageProperties.DegradedWriteConfig degradedConfig = new StorageProperties.DegradedWriteConfig();
            degradedConfig.setEnabled(false);
            when(storageProperties.getDegradedWrite()).thenReturn(degradedConfig);

            ReflectionTestUtils.setField(storageService, "uploadExecutor", null);

            Result<String> result = storageService.storeFileChunk(TEST_FILE_DATA, TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
            ReflectionTestUtils.setField(storageService, "uploadExecutor", uploadExecutor);
        }

        /**
         * 验证达到写入仲裁后会取消剩余未完成上传，避免后台任务继续消耗存储资源。
         */
        @Test
        @DisplayName("Should cancel pending uploads after quorum succeeds")
        void shouldCancelPendingUploadsAfterQuorumSucceeds() throws Exception {
            CompletableFuture<String> completed = CompletableFuture.completedFuture("node1");
            CompletableFuture<String> pending = new CompletableFuture<>();
            List<CompletableFuture<String>> futures = Arrays.asList(completed, pending);
            List<String> nodes = Arrays.asList("node1", "node2");

            Object result = ReflectionTestUtils.invokeMethod(
                    storageService,
                    "storeWithQuorum",
                    futures,
                    nodes,
                    1,
                    TEST_FILE_HASH
            );

            assertThat(result).isNotNull();
            assertThat(pending).isCancelled();
        }
    }

    @Nested
    @DisplayName("Get File List By Hash Tests")
    class GetFileListByHashTests {

        @Test
        @DisplayName("Should return success with null for empty file path list")
        void shouldReturnSuccessWithNullForEmptyFilePathList() {
            Result<List<byte[]>> result = storageService.getFileListByHash(
                    Collections.emptyList(), Collections.singletonList("hash1"));

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isNull();
        }

        @Test
        @DisplayName("Should return success with null for empty file hash list")
        void shouldReturnSuccessWithNullForEmptyFileHashList() {
            Result<List<byte[]>> result = storageService.getFileListByHash(
                    Collections.singletonList("path1"), Collections.emptyList());

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isNull();
        }

        @Test
        @DisplayName("Should return error for mismatched list sizes")
        void shouldReturnErrorForMismatchedListSizes() {
            Result<List<byte[]>> result = storageService.getFileListByHash(
                    Arrays.asList("path1", "path2"),
                    Collections.singletonList("hash1"));

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
        }
    }

    @Nested
    @DisplayName("Get File URL List By Hash Tests")
    class GetFileUrlListByHashTests {

        @Test
        @DisplayName("Should return error for empty file path list")
        void shouldReturnErrorForEmptyFilePathList() {
            Result<List<String>> result = storageService.getFileUrlListByHash(
                    Collections.emptyList(), Collections.singletonList("hash1"));

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
        }

        @Test
        @DisplayName("Should return error for mismatched list sizes")
        void shouldReturnErrorForMismatchedListSizes() {
            Result<List<String>> result = storageService.getFileUrlListByHash(
                    Arrays.asList("path1", "path2"),
                    Collections.singletonList("hash1"));

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
        }

        /**
         * 验证 URL 生成成功分支。
         */
        @Test
        @DisplayName("Should generate file url successfully")
        void shouldGenerateFileUrlSuccessfully() throws Exception {
            String hash = "hash-url-1";
            String chunkPath = TenantContextUtil.buildChunkPath(hash);

            when(faultDomainManager.getCandidateNodes(hash)).thenReturn(Collections.singletonList("node1"));
            when(faultDomainManager.selectBestNodeForRead(anyList())).thenReturn("node1");
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(clientManager.getClient("node1")).thenReturn(s3Client);
            when(clientManager.getPresigner("node1")).thenReturn(s3Presigner);
            when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

            PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
            when(presigned.url()).thenReturn(new URL("http://example.com/test-file"));
            when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

            Result<List<String>> result = storageService.getFileUrlListByHash(
                    Collections.singletonList(chunkPath),
                    Collections.singletonList(hash)
            );

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).containsExactly("http://example.com/test-file");
        }

        /**
         * 验证部分 URL 生成失败时返回容错错误和部分结果。
         */
        @Test
        @DisplayName("Should return partial result when one node fails")
        void shouldReturnPartialResultWhenOneNodeFails() throws Exception {
            String successHash = "hash-url-success";
            String failedHash = "hash-url-failed";
            String successPath = TenantContextUtil.buildChunkPath(successHash);
            String failedPath = TenantContextUtil.buildChunkPath(failedHash);

            when(faultDomainManager.getCandidateNodes(successHash)).thenReturn(Collections.singletonList("node1"));
            when(faultDomainManager.getCandidateNodes(failedHash)).thenReturn(Collections.emptyList());
            when(faultDomainManager.selectBestNodeForRead(anyList())).thenReturn("node1");
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(clientManager.getClient("node1")).thenReturn(s3Client);
            when(clientManager.getPresigner("node1")).thenReturn(s3Presigner);
            when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

            PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
            when(presigned.url()).thenReturn(new URL("http://example.com/success"));
            when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

            Result<List<String>> result = storageService.getFileUrlListByHash(
                    Arrays.asList(successPath, failedPath),
                    Arrays.asList(successHash, failedHash)
            );

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
            assertThat(result.getData()).containsExactly("http://example.com/success");
        }

        /**
         * 验证旧版无租户 logical path 会优先使用路径中的逻辑节点和历史对象 key。
         */
        @Test
        @DisplayName("Should generate file url for legacy node path")
        void shouldGenerateFileUrlForLegacyNodePath() throws Exception {
            String hash = "hash-url-legacy";
            String legacyPath = "minio/node/legacy-node/" + hash;

            when(faultDomainManager.getCandidateNodes(hash)).thenReturn(Collections.emptyList());
            when(faultDomainManager.selectBestNodeForRead(Collections.singletonList("legacy-node")))
                    .thenReturn("legacy-node");
            when(s3Monitor.isNodeOnline("legacy-node")).thenReturn(true);
            when(clientManager.getClient("legacy-node")).thenReturn(s3Client);
            when(clientManager.getPresigner("legacy-node")).thenReturn(s3Presigner);
            when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

            PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
            when(presigned.url()).thenReturn(new URL("http://example.com/legacy"));
            when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

            Result<List<String>> result = storageService.getFileUrlListByHash(
                    Collections.singletonList(legacyPath),
                    Collections.singletonList(hash)
            );

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).containsExactly("http://example.com/legacy");
            ArgumentCaptor<HeadObjectRequest> headRequestCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
            verify(s3Client).headObject(headRequestCaptor.capture());
            assertThat(headRequestCaptor.getValue().bucket()).isEqualTo("legacy-node");
            assertThat(headRequestCaptor.getValue().key()).isEqualTo(hash);
        }
    }

    @Nested
    @DisplayName("Head Object Tests")
    class HeadObjectTests {

        @Test
        @DisplayName("Should return object metadata without downloading content")
        void shouldReturnObjectMetadataWithoutDownloadingContent() {
            String hash = "hash-head-success";
            String chunkPath = TenantContextUtil.buildChunkPath(hash);

            when(faultDomainManager.getCandidateNodes(hash)).thenReturn(Collections.singletonList("node1"));
            when(faultDomainManager.selectBestNodeForRead(anyList())).thenReturn("node1");
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(clientManager.getClient("node1")).thenReturn(s3Client);
            when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(
                    HeadObjectResponse.builder()
                            .contentLength(2048L)
                            .eTag("\"etag-1\"")
                            .metadata(Map.of("file-hash", hash, "tenant-id", "0"))
                            .build()
            );

            Result<StorageObjectHeadVO> result = storageService.headObject(chunkPath, hash);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().exists()).isTrue();
            assertThat(result.getData().fileHash()).isEqualTo(hash);
            assertThat(result.getData().tenantId()).isEqualTo(0L);
            assertThat(result.getData().metadataTenantId()).isEqualTo(0L);
            assertThat(result.getData().nodeName()).isEqualTo("node1");
            assertThat(result.getData().contentLength()).isEqualTo(2048L);
            assertThat(result.getData().eTag()).isEqualTo("\"etag-1\"");
            assertThat(result.getData().metadataHash()).isEqualTo(hash);

            ArgumentCaptor<HeadObjectRequest> headRequestCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
            verify(s3Client).headObject(headRequestCaptor.capture());
            assertThat(headRequestCaptor.getValue().bucket()).isEqualTo("node1");
            assertThat(headRequestCaptor.getValue().key()).isEqualTo("tenant/0/" + hash);
            verify(s3Client, never()).getObject(any(GetObjectRequest.class));
        }

        @Test
        @DisplayName("Should return missing result when object is absent")
        void shouldReturnMissingResultWhenObjectIsAbsent() {
            String hash = "hash-head-missing";
            String chunkPath = TenantContextUtil.buildChunkPath(hash);

            when(faultDomainManager.getCandidateNodes(hash)).thenReturn(Collections.singletonList("node1"));
            when(faultDomainManager.selectBestNodeForRead(anyList())).thenReturn("node1");
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(clientManager.getClient("node1")).thenReturn(s3Client);
            when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(
                    NoSuchKeyException.builder().message("missing").build()
            );

            Result<StorageObjectHeadVO> result = storageService.headObject(chunkPath, hash);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().exists()).isFalse();
            assertThat(result.getData().filePath()).isEqualTo(chunkPath);
            assertThat(result.getData().fileHash()).isEqualTo(hash);
            assertThat(result.getData().tenantId()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should return missing result for generic S3 404 responses")
        void shouldReturnMissingResultForGenericS3NotFoundResponses() {
            String hash = "hash-head-s3-missing";
            String chunkPath = TenantContextUtil.buildChunkPath(hash);

            when(faultDomainManager.getCandidateNodes(hash)).thenReturn(Collections.singletonList("node1"));
            when(faultDomainManager.selectBestNodeForRead(anyList())).thenReturn("node1");
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(clientManager.getClient("node1")).thenReturn(s3Client);
            when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(
                    S3Exception.builder().statusCode(404).message("missing").build()
            );

            Result<StorageObjectHeadVO> result = storageService.headObject(chunkPath, hash);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().exists()).isFalse();
            assertThat(result.getData().fileHash()).isEqualTo(hash);
            assertThat(result.getData().tenantId()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should return service error when object head cannot be confirmed")
        void shouldReturnServiceErrorWhenObjectHeadCannotBeConfirmed() {
            String hash = "hash-head-unavailable";
            String chunkPath = TenantContextUtil.buildChunkPath(hash);

            when(faultDomainManager.getCandidateNodes(hash)).thenReturn(Collections.singletonList("node1"));
            when(faultDomainManager.selectBestNodeForRead(anyList())).thenReturn("node1");
            when(s3Monitor.isNodeOnline("node1")).thenReturn(false);

            Result<StorageObjectHeadVO> result = storageService.headObject(chunkPath, hash);

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
            assertThat(result.getData().exists()).isFalse();
            assertThat(result.getData().fileHash()).isEqualTo(hash);
            verify(clientManager, never()).getClient("node1");
        }

        @Test
        @DisplayName("Should reject hash mismatch between logical path and request hash")
        void shouldRejectHashMismatchBetweenLogicalPathAndRequestHash() {
            String chunkPath = TenantContextUtil.buildChunkPath("actual-hash");

            Result<StorageObjectHeadVO> result = storageService.headObject(chunkPath, "request-hash");

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            verifyNoInteractions(s3Client);
        }
    }

    @Nested
    @DisplayName("Delete File Tests")
    class DeleteFileTests {

        @Test
        @DisplayName("Should return success for empty file content")
        void shouldReturnSuccessForEmptyFileContent() {
            Result<Boolean> result = storageService.deleteFile(Collections.emptyMap());

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isTrue();
        }

        @Test
        @DisplayName("Should return success for null file content")
        void shouldReturnSuccessForNullFileContent() {
            Result<Boolean> result = storageService.deleteFile(null);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isTrue();
        }

        /**
         * 验证路径格式非法时删除接口返回失败。
         */
        @Test
        @DisplayName("Should return error for invalid chunk path")
        void shouldReturnErrorForInvalidChunkPath() {
            Result<Boolean> result = storageService.deleteFile(Map.of(TEST_FILE_HASH, "invalid-path"));

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
            assertThat(result.getData()).isFalse();
        }

        /**
         * 验证路径与 hash 不匹配时删除接口返回失败。
         */
        @Test
        @DisplayName("Should return error for hash mismatch")
        void shouldReturnErrorForHashMismatch() {
            String mismatchedPath = TenantContextUtil.buildChunkPath("another-hash");

            Result<Boolean> result = storageService.deleteFile(Map.of(TEST_FILE_HASH, mismatchedPath));

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
            assertThat(result.getData()).isFalse();
        }

        /**
         * 验证候选节点为空时删除接口返回失败。
         */
        @Test
        @DisplayName("Should return error when no candidate nodes")
        void shouldReturnErrorWhenNoCandidateNodes() {
            String chunkPath = TenantContextUtil.buildChunkPath(TEST_FILE_HASH);
            when(faultDomainManager.getCandidateNodes(TEST_FILE_HASH)).thenReturn(Collections.emptyList());

            Result<Boolean> result = storageService.deleteFile(Map.of(TEST_FILE_HASH, chunkPath));

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
            assertThat(result.getData()).isFalse();
        }

        /**
         * 验证部分文件删除失败时返回失败并保留部分执行结果。
         */
        @Test
        @DisplayName("Should return error when partial delete fails")
        void shouldReturnErrorWhenPartialDeleteFails() {
            String successHash = "hash-delete-success";
            String successPath = TenantContextUtil.buildChunkPath(successHash);
            String invalidPath = "invalid-path";

            when(faultDomainManager.getCandidateNodes(successHash)).thenReturn(Collections.singletonList("node1"));
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(clientManager.getClient("node1")).thenReturn(s3Client);

            Result<Boolean> result = storageService.deleteFile(Map.of(
                    successHash, successPath,
                    TEST_FILE_HASH, invalidPath
            ));

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
            assertThat(result.getData()).isFalse();
        }

        /**
         * 验证删除会覆盖活跃域内的 fallback/rebalance 残留节点，而不仅是当前 hash ring 候选节点。
         */
        @Test
        @DisplayName("Should delete from active domain nodes in addition to current candidates")
        void shouldDeleteFromActiveDomainNodesInAdditionToCurrentCandidates() {
            String chunkPath = TenantContextUtil.buildChunkPath(TEST_FILE_HASH);

            when(faultDomainManager.getCandidateNodes(TEST_FILE_HASH)).thenReturn(List.of("node1"));
            when(faultDomainManager.getActiveDomains()).thenReturn(List.of("domain-a", "domain-b"));
            when(faultDomainManager.getNodesInDomain("domain-a")).thenReturn(Set.of("node1", "node-fallback"));
            when(faultDomainManager.getNodesInDomain("domain-b")).thenReturn(Set.of("node2"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            when(clientManager.getClient(anyString())).thenReturn(s3Client);

            Result<Boolean> result = storageService.deleteFile(Map.of(TEST_FILE_HASH, chunkPath));

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isTrue();
            verify(clientManager).getClient("node1");
            verify(clientManager).getClient("node-fallback");
            verify(clientManager).getClient("node2");
            verify(s3Client, times(3)).deleteObject(any(DeleteObjectRequest.class));
        }

        /**
         * 验证旧版带租户 logical path 删除时会包含路径中的历史逻辑节点。
         */
        @Test
        @DisplayName("Should delete legacy tenant path from recorded node")
        void shouldDeleteLegacyTenantPathFromRecordedNode() {
            String legacyPath = "minio/tenant/42/node/legacy-node/" + TEST_FILE_HASH;

            when(faultDomainManager.getCandidateNodes(TEST_FILE_HASH)).thenReturn(Collections.emptyList());
            when(faultDomainManager.getActiveDomains()).thenReturn(Collections.emptyList());
            when(s3Monitor.isNodeOnline("legacy-node")).thenReturn(true);
            when(clientManager.getClient("legacy-node")).thenReturn(s3Client);

            Result<Boolean> result = storageService.deleteFile(Map.of(TEST_FILE_HASH, legacyPath));

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isTrue();
            ArgumentCaptor<DeleteObjectRequest> deleteRequestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(deleteRequestCaptor.capture());
            assertThat(deleteRequestCaptor.getValue().bucket()).isEqualTo("legacy-node");
            assertThat(deleteRequestCaptor.getValue().key()).isEqualTo("tenant/42/" + TEST_FILE_HASH);
        }
    }

    @Nested
    @DisplayName("Cluster Health Tests")
    class ClusterHealthTests {

        @Test
        @DisplayName("Should return health status for all enabled nodes")
        void shouldReturnHealthStatusForAllEnabledNodes() {
            List<NodeConfig> nodes = Arrays.asList(
                    createNodeConfig("node1", true),
                    createNodeConfig("node2", true),
                    createNodeConfig("node3", false)
            );
            when(storageProperties.getNodes()).thenReturn(nodes);
            when(s3Monitor.getOnlineNodes()).thenReturn(Set.of("node1"));

            Result<Map<String, Boolean>> result = storageService.getClusterHealth();

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).containsEntry("node1", true);
            assertThat(result.getData()).containsEntry("node2", false);
            assertThat(result.getData()).doesNotContainKey("node3");
        }

        @Test
        @DisplayName("Should return empty map for no nodes")
        void shouldReturnEmptyMapForNoNodes() {
            when(storageProperties.getNodes()).thenReturn(Collections.emptyList());
            when(s3Monitor.getOnlineNodes()).thenReturn(Collections.emptySet());

            Result<Map<String, Boolean>> result = storageService.getClusterHealth();

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Storage Capacity Tests")
    class StorageCapacityTests {

        /**
         * 验证所有节点都有容量指标时，返回完整聚合结果且不降级。
         */
        @Test
        @DisplayName("Should aggregate capacity when all nodes have metrics")
        void shouldAggregateCapacityWhenAllNodesHaveMetrics() {
            List<NodeConfig> nodes = Arrays.asList(
                    createNodeConfig("node1", true, "A"),
                    createNodeConfig("node2", true, "B")
            );
            when(storageProperties.getNodes()).thenReturn(nodes);
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(s3Monitor.isNodeOnline("node2")).thenReturn(false);

            S3Monitor.NodeMetrics node1Metrics = new S3Monitor.NodeMetrics();
            node1Metrics.setDiskTotalBytes(1_000L);
            node1Metrics.setDiskUsedBytes(400L);
            S3Monitor.NodeMetrics node2Metrics = new S3Monitor.NodeMetrics();
            node2Metrics.setDiskTotalBytes(2_000L);
            node2Metrics.setDiskUsedBytes(1_500L);

            when(s3Monitor.getNodeMetrics("node1")).thenReturn(node1Metrics);
            when(s3Monitor.getNodeMetrics("node2")).thenReturn(node2Metrics);

            Result<cn.flying.platformapi.response.StorageCapacityVO> result = storageService.getStorageCapacity();

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().degraded()).isFalse();
            assertThat(result.getData().source()).isEqualTo("prometheus");
            assertThat(result.getData().totalCapacityBytes()).isEqualTo(3_000L);
            assertThat(result.getData().usedCapacityBytes()).isEqualTo(1_900L);
            assertThat(result.getData().availableCapacityBytes()).isEqualTo(1_100L);
            assertThat(result.getData().nodes()).hasSize(2);
            assertThat(result.getData().domains()).hasSize(2);
        }

        /**
         * 验证部分节点缺失指标时，返回降级结果并标记 source=prometheus-partial。
         */
        @Test
        @DisplayName("Should mark degraded when partial node metrics missing")
        void shouldMarkDegradedWhenPartialNodeMetricsMissing() {
            List<NodeConfig> nodes = Arrays.asList(
                    createNodeConfig("node1", true, "A"),
                    createNodeConfig("node2", true, "A")
            );
            when(storageProperties.getNodes()).thenReturn(nodes);
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);

            S3Monitor.NodeMetrics node1Metrics = new S3Monitor.NodeMetrics();
            node1Metrics.setDiskTotalBytes(1_000L);
            node1Metrics.setDiskUsedBytes(500L);
            when(s3Monitor.getNodeMetrics("node1")).thenReturn(node1Metrics);
            when(s3Monitor.getNodeMetrics("node2")).thenReturn(null);

            Result<cn.flying.platformapi.response.StorageCapacityVO> result = storageService.getStorageCapacity();

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().degraded()).isTrue();
            assertThat(result.getData().source()).isEqualTo("prometheus-partial");
            assertThat(result.getData().totalCapacityBytes()).isEqualTo(1_000L);
            assertThat(result.getData().usedCapacityBytes()).isEqualTo(500L);
        }

        /**
         * 验证所有节点都被禁用时，返回无受管节点快照供 backend-web 清理桥接指标。
         */
        @Test
        @DisplayName("Should return no-node snapshot when all configured nodes are disabled")
        void shouldReturnNoNodeSnapshotWhenAllConfiguredNodesAreDisabled() {
            List<NodeConfig> nodes = Arrays.asList(
                    createNodeConfig("node1", false, "A"),
                    createNodeConfig("node2", false, "B")
            );
            when(storageProperties.getNodes()).thenReturn(nodes);

            Result<cn.flying.platformapi.response.StorageCapacityVO> result = storageService.getStorageCapacity();

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().degraded()).isTrue();
            assertThat(result.getData().source()).isEqualTo("prometheus-no-nodes");
            assertThat(result.getData().nodes()).isEmpty();
            assertThat(result.getData().domains()).isEmpty();
        }

        /**
         * 验证容量聚合出现异常时返回 FILE_SERVICE_ERROR。
         */
        @Test
        @DisplayName("Should return error when storage capacity aggregation throws")
        void shouldReturnErrorWhenStorageCapacityAggregationThrows() {
            when(storageProperties.getNodes()).thenThrow(new RuntimeException("boom"));

            Result<cn.flying.platformapi.response.StorageCapacityVO> result = storageService.getStorageCapacity();

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
            assertThat(result.getData()).isNull();
        }
    }

    @Nested
    @DisplayName("Domain Health Tests")
    class DomainHealthTests {

        @Test
        @DisplayName("Should return health status for all domains")
        void shouldReturnHealthStatusForAllDomains() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(storageProperties.isStandbyEnabled()).thenReturn(true);
            when(storageProperties.getStandbyDomain()).thenReturn("STANDBY");
            
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1", "node-a2"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(faultDomainManager.getNodesInDomain("STANDBY")).thenReturn(Set.of("standby-1"));
            
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(2);
            when(faultDomainManager.countHealthyNodesInDomain("domain-B")).thenReturn(0);
            when(faultDomainManager.countHealthyNodesInDomain("STANDBY")).thenReturn(1);
            
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);

            Result<Map<String, Map<String, Object>>> result = storageService.getDomainHealth();

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).containsKeys("domain-A", "domain-B", "STANDBY");
            
            Map<String, Object> domainAHealth = result.getData().get("domain-A");
            assertThat(domainAHealth.get("totalNodes")).isEqualTo(2);
            assertThat(domainAHealth.get("healthyNodes")).isEqualTo(2);
            assertThat(domainAHealth.get("status")).isEqualTo("healthy");
            
            Map<String, Object> domainBHealth = result.getData().get("domain-B");
            assertThat(domainBHealth.get("status")).isEqualTo("down");
        }

        @Test
        @DisplayName("Should return degraded status for partially healthy domain")
        void shouldReturnDegradedStatusForPartiallyHealthyDomain() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Collections.singletonList("domain-A"));
            when(storageProperties.isStandbyEnabled()).thenReturn(false);
            
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node1", "node2"));
            when(faultDomainManager.countHealthyNodesInDomain("domain-A")).thenReturn(1);
            
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(s3Monitor.isNodeOnline("node2")).thenReturn(false);

            Result<Map<String, Map<String, Object>>> result = storageService.getDomainHealth();

            Map<String, Object> domainAHealth = result.getData().get("domain-A");
            assertThat(domainAHealth.get("status")).isEqualTo("degraded");
        }

        @Test
        @DisplayName("Should return empty status for domain with no nodes")
        void shouldReturnEmptyStatusForDomainWithNoNodes() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Collections.singletonList("empty-domain"));
            when(storageProperties.isStandbyEnabled()).thenReturn(false);
            
            when(faultDomainManager.getNodesInDomain("empty-domain")).thenReturn(Collections.emptySet());
            when(faultDomainManager.countHealthyNodesInDomain("empty-domain")).thenReturn(0);

            Result<Map<String, Map<String, Object>>> result = storageService.getDomainHealth();

            Map<String, Object> domainHealth = result.getData().get("empty-domain");
            assertThat(domainHealth.get("status")).isEqualTo("empty");
        }
    }

    @Nested
    @DisplayName("Chunk Locations Tests")
    class ChunkLocationsTests {

        @Test
        @DisplayName("Should return error for null chunk hash")
        void shouldReturnErrorForNullChunkHash() {
            Result<List<String>> result = storageService.getChunkLocations(null);

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
        }

        @Test
        @DisplayName("Should return error for empty chunk hash")
        void shouldReturnErrorForEmptyChunkHash() {
            Result<List<String>> result = storageService.getChunkLocations("");

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
        }

        @Test
        @DisplayName("Should return empty list when no candidate nodes")
        void shouldReturnEmptyListWhenNoCandidateNodes() {
            when(faultDomainManager.getCandidateNodes(anyString())).thenReturn(Collections.emptyList());

            Result<List<String>> result = storageService.getChunkLocations(TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isEmpty();
        }

        /**
         * 验证候选节点全部离线时返回空位置列表。
         */
        @Test
        @DisplayName("Should return empty locations when all nodes offline")
        void shouldReturnEmptyLocationsWhenAllNodesOffline() {
            when(faultDomainManager.getCandidateNodes(TEST_FILE_HASH)).thenReturn(Arrays.asList("node1", "node2"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(false);

            Result<List<String>> result = storageService.getChunkLocations(TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isEmpty();
        }

        /**
         * 验证仅在线且对象存在的节点会被过滤返回。
         */
        @Test
        @DisplayName("Should filter and return only online nodes with object")
        void shouldFilterAndReturnOnlyOnlineNodesWithObject() {
            when(faultDomainManager.getCandidateNodes(TEST_FILE_HASH)).thenReturn(Arrays.asList("node1", "node2", "node3"));
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(s3Monitor.isNodeOnline("node2")).thenReturn(false);
            when(s3Monitor.isNodeOnline("node3")).thenReturn(true);

            when(clientManager.getClient("node1")).thenReturn(s3Client);
            when(clientManager.getClient("node3")).thenReturn(null);
            when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

            Result<List<String>> result = storageService.getChunkLocations(TEST_FILE_HASH);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).containsExactly("node1");
        }
    }

    @Nested
    @DisplayName("Trigger Rebalance Tests")
    class TriggerRebalanceTests {

        @Test
        @DisplayName("Should return task ID on successful trigger")
        void shouldReturnTaskIdOnSuccessfulTrigger() {
            when(rebalanceService.triggerManualRebalance("domain-A")).thenReturn("task-123");

            Result<String> result = storageService.triggerRebalance("domain-A");

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isEqualTo("task-123");
        }

        @Test
        @DisplayName("Should return error when rebalance is disabled")
        void shouldReturnErrorWhenRebalanceIsDisabled() {
            when(rebalanceService.triggerManualRebalance(anyString())).thenReturn(null);

            Result<String> result = storageService.triggerRebalance("domain-A");

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
        }

        @Test
        @DisplayName("Should return error on exception")
        void shouldReturnErrorOnException() {
            when(rebalanceService.triggerManualRebalance(anyString()))
                    .thenThrow(new RuntimeException("Rebalance failed"));

            Result<String> result = storageService.triggerRebalance("domain-A");

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
        }
    }

    @Nested
    @DisplayName("Rebalance Status Tests")
    class RebalanceStatusTests {

        @Test
        @DisplayName("Should return rebalance status")
        void shouldReturnRebalanceStatus() {
            RebalanceService.RebalanceStatus status = mock(RebalanceService.RebalanceStatus.class);
            when(status.isRunning()).thenReturn(true);
            when(status.isSuccess()).thenReturn(false);
            when(status.getType()).thenReturn(RebalanceService.RebalanceType.REBALANCE_TO_NEW);
            when(status.getMigratedCount()).thenReturn(new java.util.concurrent.atomic.AtomicInteger(10));
            when(status.getFailedCount()).thenReturn(new java.util.concurrent.atomic.AtomicInteger(2));
            
            when(rebalanceService.getStatus()).thenReturn(status);

            Result<Map<String, Object>> result = storageService.getRebalanceStatus();

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().get("running")).isEqualTo(true);
            assertThat(result.getData().get("migratedCount")).isEqualTo(10);
            assertThat(result.getData().get("failedCount")).isEqualTo(2);
        }

        @Test
        @DisplayName("Should return error on exception")
        void shouldReturnErrorOnException() {
            when(rebalanceService.getStatus()).thenThrow(new RuntimeException("Status error"));

            Result<Map<String, Object>> result = storageService.getRebalanceStatus();

            assertThat(result.getCode()).isEqualTo(ResultEnum.FILE_SERVICE_ERROR.getCode());
        }

        /**
         * 验证再平衡状态中可空字段会被正确映射为 null。
         */
        @Test
        @DisplayName("Should keep nullable rebalance fields as null")
        void shouldKeepNullableRebalanceFieldsAsNull() {
            RebalanceService.RebalanceStatus status = mock(RebalanceService.RebalanceStatus.class);
            when(status.isRunning()).thenReturn(false);
            when(status.isSuccess()).thenReturn(true);
            when(status.getType()).thenReturn(null);
            when(status.getTriggerNode()).thenReturn(null);
            when(status.getStartTime()).thenReturn(null);
            when(status.getEndTime()).thenReturn(null);
            when(status.getMigratedCount()).thenReturn(new java.util.concurrent.atomic.AtomicInteger(0));
            when(status.getFailedCount()).thenReturn(new java.util.concurrent.atomic.AtomicInteger(0));
            when(status.getError()).thenReturn(null);
            when(rebalanceService.getStatus()).thenReturn(status);

            Result<Map<String, Object>> result = storageService.getRebalanceStatus();

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().get("type")).isNull();
            assertThat(result.getData().get("startTime")).isNull();
            assertThat(result.getData().get("endTime")).isNull();
        }
    }

    @Nested
    @DisplayName("Direct Multipart Upload Tests")
    class DirectMultipartUploadTests {

        /**
         * 为直传 RPC 注入显式 tenant attachment，模拟经过 backend Dubbo filter 的真实调用。
         */
        @BeforeEach
        void setDirectUploadTenantContext() {
            RpcContext.getServerAttachment().setAttachment("tenant.id", "0");
        }

        /**
         * 清理 Dubbo tenant attachment，避免上下文泄漏到同 JVM 的其他测试。
         */
        @AfterEach
        void clearDirectUploadTenantContext() {
            RpcContext.getServerAttachment().removeAttachment("tenant.id");
        }

        @Test
        @DisplayName("Should create presigned PUT URLs for direct upload parts")
        void shouldCreatePresignedPutUrlsForDirectUploadParts() {
            byte[] chunkBytes = "direct-upload-create".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            when(faultDomainManager.getTargetNodes(chunkHash)).thenReturn(List.of("node1"));
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(clientManager.getClient("node1")).thenReturn(s3Client);
            when(clientManager.getPresigner("node1")).thenReturn(s3Presigner);
            when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
            when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                    .thenReturn(PresignedPutObjectRequest.builder()
                            .expiration(Instant.now().plusSeconds(900))
                            .isBrowserExecutable(true)
                            .signedHeaders(Map.of("host", List.of("storage.example")))
                            .httpRequest(SdkHttpFullRequest.builder()
                                    .method(SdkHttpMethod.PUT)
                                    .uri(URI.create("https://storage.example/upload/part-0"))
                                    .build())
                            .build());

            CreateDirectMultipartUploadRequest request = new CreateDirectMultipartUploadRequest(
                    "direct-session",
                    "direct.pdf",
                    512L,
                    512,
                    "application/pdf",
                    List.of(new DirectMultipartUploadPartRequest(
                            0,
                            chunkHash,
                            512L,
                            "application/pdf",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ))
            );

            Result<CreateDirectMultipartUploadResponse> result = storageService.createDirectMultipartUpload(request);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().sessionId()).isEqualTo("direct-session");
            assertThat(result.getData().parts()).hasSize(1);
            assertThat(result.getData().parts().getFirst().uploadUrl())
                    .isEqualTo("https://storage.example/upload/part-0");
            assertThat(result.getData().parts().getFirst().nodeName()).isEqualTo("node1");
            assertThat(result.getData().parts().getFirst().storagePath()).isEqualTo(directStoragePath(chunkHash));
            assertThat(result.getData().parts().getFirst().finalObjectName()).isEqualTo(directFinalObjectName(chunkHash));
            assertThat(result.getData().parts().getFirst().stagingObjectName())
                    .isEqualTo(directStagingObjectName("direct-session", 0));

            ArgumentCaptor<PutObjectPresignRequest> presignCaptor =
                    ArgumentCaptor.forClass(PutObjectPresignRequest.class);
            verify(s3Presigner).presignPutObject(presignCaptor.capture());
            assertThat(presignCaptor.getValue().signatureDuration().toSeconds()).isEqualTo(86_400);
            assertThat(presignCaptor.getValue().putObjectRequest().bucket()).isEqualTo("node1");
            assertThat(presignCaptor.getValue().putObjectRequest().key())
                    .isEqualTo(directStagingObjectName("direct-session", 0));
            verify(clientManager).acquireTopologyLease();
            verify(topologyLease).getClient("node1");
            verify(topologyLease).getPresigner("node1");
            verify(topologyLease).verifyCurrent();
            verify(topologyLease).close();
            verify(directUploadStagingTracker).record(argThat(descriptor ->
                    descriptor.tenantId() == 0L
                            && descriptor.sessionId().equals("direct-session")
                            && descriptor.partIndex() == 0
                            && descriptor.nodeName().equals("node1")
                            && descriptor.objectName().equals(directStagingObjectName("direct-session", 0))));
        }

        /**
         * 验证 topology 刷新后不会复用上一 endpoint 的 Bucket 存在性缓存。
         */
        @Test
        @DisplayName("Should isolate direct-upload bucket cache by topology revision")
        void shouldIsolateDirectUploadBucketCacheByTopologyRevision() {
            byte[] chunkBytes = "direct-upload-topology-cache".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            when(faultDomainManager.getTargetNodes(chunkHash)).thenReturn(List.of("node1"));
            when(s3Monitor.isNodeOnline("node1")).thenReturn(true);
            when(clientManager.getClient("node1")).thenReturn(s3Client);
            when(clientManager.getPresigner("node1")).thenReturn(s3Presigner);
            when(topologyLease.revision()).thenReturn(11L, 12L);
            when(s3Client.headBucket(any(HeadBucketRequest.class)))
                    .thenReturn(HeadBucketResponse.builder().build());
            when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                    .thenReturn(PresignedPutObjectRequest.builder()
                            .expiration(Instant.now().plusSeconds(900))
                            .isBrowserExecutable(true)
                            .signedHeaders(Map.of("host", List.of("storage.example")))
                            .httpRequest(SdkHttpFullRequest.builder()
                                    .method(SdkHttpMethod.PUT)
                                    .uri(URI.create("https://storage.example/upload/part-0"))
                                    .build())
                            .build());

            CreateDirectMultipartUploadRequest firstRequest = new CreateDirectMultipartUploadRequest(
                    "topology-session-1",
                    "direct.pdf",
                    512L,
                    512,
                    "application/pdf",
                    List.of(new DirectMultipartUploadPartRequest(
                            0,
                            chunkHash,
                            512L,
                            "application/pdf",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ))
            );
            CreateDirectMultipartUploadRequest secondRequest = new CreateDirectMultipartUploadRequest(
                    "topology-session-2",
                    firstRequest.fileName(),
                    firstRequest.totalSize(),
                    firstRequest.chunkSize(),
                    firstRequest.contentType(),
                    firstRequest.parts()
            );

            assertThat(storageService.createDirectMultipartUpload(firstRequest).getCode()).isEqualTo(200);
            assertThat(storageService.createDirectMultipartUpload(secondRequest).getCode()).isEqualTo(200);

            verify(s3Client, times(2)).headBucket(any(HeadBucketRequest.class));
            verify(clientManager, times(2)).acquireTopologyLease();
            verify(topologyLease, times(2)).verifyCurrent();
            verify(topologyLease, times(2)).close();
        }

        @Test
        @DisplayName("Should reject direct upload parts that completion cannot verify")
        void shouldRejectDirectUploadPartsThatCompletionCannotVerify() {
            String chunkHash = sha256Prefixed("direct-upload-create".getBytes(StandardCharsets.UTF_8));

            CreateDirectMultipartUploadRequest invalidHashRequest = new CreateDirectMultipartUploadRequest(
                    "direct-session",
                    "direct.pdf",
                    512L,
                    512,
                    "application/pdf",
                    List.of(new DirectMultipartUploadPartRequest(
                            0,
                            "chunk-hash-0",
                            512L,
                            "application/pdf",
                            "chunk-hash-0",
                            "chunk-hash-0",
                            "SHA-256"
                    ))
            );
            CreateDirectMultipartUploadRequest invalidAlgorithmRequest = new CreateDirectMultipartUploadRequest(
                    "direct-session",
                    "direct.pdf",
                    512L,
                    512,
                    "application/pdf",
                    List.of(new DirectMultipartUploadPartRequest(
                            0,
                            chunkHash,
                            512L,
                            "application/pdf",
                            chunkHash,
                            chunkHash,
                            "MD5"
                    ))
            );
            CreateDirectMultipartUploadRequest oversizedRequest = new CreateDirectMultipartUploadRequest(
                    "direct-session",
                    "direct.pdf",
                    100 * 1024 * 1024L + 1,
                    512,
                    "application/pdf",
                    List.of(new DirectMultipartUploadPartRequest(
                            0,
                            chunkHash,
                            100 * 1024 * 1024L + 1,
                            "application/pdf",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ))
            );
            CreateDirectMultipartUploadRequest nonCanonicalHashRequest = new CreateDirectMultipartUploadRequest(
                    "direct-session",
                    "direct.pdf",
                    512L,
                    512,
                    "application/pdf",
                    List.of(new DirectMultipartUploadPartRequest(
                            0,
                            chunkHash.toUpperCase(),
                            512L,
                            "application/pdf",
                            chunkHash.toUpperCase(),
                            chunkHash.toUpperCase(),
                            "SHA-256"
                    ))
            );
            CreateDirectMultipartUploadRequest paddedAlgorithmRequest = new CreateDirectMultipartUploadRequest(
                    "direct-session",
                    "direct.pdf",
                    512L,
                    512,
                    "application/pdf",
                    List.of(new DirectMultipartUploadPartRequest(
                            0,
                            chunkHash,
                            512L,
                            "application/pdf",
                            chunkHash,
                            chunkHash,
                            " SHA-256 "
                    ))
            );

            assertThat(storageService.createDirectMultipartUpload(invalidHashRequest).getCode())
                    .isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            assertThat(storageService.createDirectMultipartUpload(invalidAlgorithmRequest).getCode())
                    .isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            assertThat(storageService.createDirectMultipartUpload(oversizedRequest).getCode())
                    .isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            assertThat(storageService.createDirectMultipartUpload(nonCanonicalHashRequest).getCode())
                    .isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            assertThat(storageService.createDirectMultipartUpload(paddedAlgorithmRequest).getCode())
                    .isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            verifyNoInteractions(s3Client, s3Presigner);
        }

        @Test
        @DisplayName("Should reject non-canonical hashes and checksum text at the storage RPC boundary")
        void shouldRejectNonCanonicalCompletionIdentity() {
            byte[] chunkBytes = "direct-upload-canonical-complete".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            List<DirectMultipartCompletedPart> invalidParts = List.of(
                    new DirectMultipartCompletedPart(
                            0,
                            directStagingObjectName("direct-session", 0),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            chunkBytes.length,
                            "\"etag-0\"",
                            chunkHash.toUpperCase(),
                            chunkHash.toUpperCase(),
                            "SHA-256"
                    ),
                    new DirectMultipartCompletedPart(
                            0,
                            directStagingObjectName("direct-session", 0),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            chunkBytes.length,
                            "\"etag-0\"",
                            chunkHash,
                            chunkHash,
                            " SHA-256 "
                    )
            );

            for (DirectMultipartCompletedPart invalidPart : invalidParts) {
                Result<CompleteDirectMultipartUploadResponse> result =
                        storageService.completeDirectMultipartUpload(
                                new CompleteDirectMultipartUploadRequest(
                                        "direct-session",
                                        List.of(invalidPart)
                                )
                        );
                assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            }
            verifyNoInteractions(directUploadPromotionService);
        }

        @Test
        @DisplayName("Should validate and promote direct uploaded parts")
        void shouldValidateAndPromoteDirectUploadedParts() {
            byte[] chunkBytes = "direct-upload-chunk".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            when(faultDomainManager.getTargetNodes(chunkHash)).thenReturn(List.of("node1", "node2"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            when(storageProperties.getEffectiveQuorum()).thenReturn(2);
            when(storageProperties.getDegradedWrite()).thenReturn(new StorageProperties.DegradedWriteConfig());
            when(directUploadPromotionService.promote(any(), any(DirectUploadDigestAccumulator.class)))
                    .thenAnswer(invocation -> {
                        DirectUploadDigestAccumulator aggregateDigest = invocation.getArgument(1);
                        MessageDigest candidateDigest = aggregateDigest.fork();
                        candidateDigest.update(chunkBytes);
                        aggregateDigest.commit(candidateDigest);
                        return new DirectUploadPromotionResult(chunkBytes.length, "\"etag-final\"");
                    });

            CompleteDirectMultipartUploadRequest request = new CompleteDirectMultipartUploadRequest(
                    "direct-session",
                    List.of(new DirectMultipartCompletedPart(
                            0,
                            directStagingObjectName("direct-session", 0),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            chunkBytes.length,
                            "\"etag-0\"",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ))
            );

            Result<CompleteDirectMultipartUploadResponse> result = storageService.completeDirectMultipartUpload(request);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().sessionId()).isEqualTo("direct-session");
            assertThat(result.getData().contentHash()).isEqualTo(chunkHash);
            assertThat(result.getData().parts()).hasSize(1);
            assertThat(result.getData().parts().getFirst().storagePath())
                    .isEqualTo(directStoragePath(chunkHash));
            assertThat(result.getData().parts().getFirst().eTag()).isEqualTo("\"etag-final\"");
            assertThat(result.getData().parts().getFirst().cipherHash()).isEqualTo(chunkHash);

            ArgumentCaptor<DirectUploadPartDescriptor> descriptorCaptor =
                    ArgumentCaptor.forClass(DirectUploadPartDescriptor.class);
            verify(directUploadPromotionService).promote(
                    descriptorCaptor.capture(),
                    any(DirectUploadDigestAccumulator.class)
            );
            DirectUploadPartDescriptor descriptor = descriptorCaptor.getValue();
            assertThat(descriptor.tenantId()).isEqualTo(0L);
            assertThat(descriptor.sessionId()).isEqualTo("direct-session");
            assertThat(descriptor.targetNodes()).containsExactly("node1", "node2");
            assertThat(descriptor.requiredQuorum()).isEqualTo(2);
            assertThat(descriptor.stagingObjectName())
                    .isEqualTo(directStagingObjectName("direct-session", 0));
            assertThat(descriptor.finalObjectName()).isEqualTo(directFinalObjectName(chunkHash));
            verifyNoInteractions(s3Client);
        }

        /**
         * 验证故障域返回重复节点不会被误当作两个可用副本。
         */
        @Test
        @DisplayName("Should reject duplicate target nodes when degraded writes are disabled")
        void shouldRejectDuplicateDirectUploadTargetsWithoutDegradedWrite() {
            byte[] chunkBytes = "direct-upload-duplicate-targets".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            when(faultDomainManager.getTargetNodes(chunkHash)).thenReturn(List.of("node1", "node1"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            StorageProperties.DegradedWriteConfig degradedConfig = new StorageProperties.DegradedWriteConfig();
            degradedConfig.setEnabled(false);
            when(storageProperties.getDegradedWrite()).thenReturn(degradedConfig);

            Result<CompleteDirectMultipartUploadResponse> result = storageService.completeDirectMultipartUpload(
                    directCompleteRequest(chunkBytes, "\"etag-0\""));

            assertThat(result.getCode()).isEqualTo(ResultEnum.STORAGE_INSUFFICIENT_REPLICAS.getCode());
            verifyNoInteractions(directUploadPromotionService);
        }

        /**
         * 验证开启降级且唯一副本达到 min-replicas 时，仅传递去重节点并使用单副本仲裁。
         */
        @Test
        @DisplayName("Should allow one unique duplicate target at the degraded minimum")
        void shouldAllowDeduplicatedTargetAtDegradedMinimum() {
            byte[] chunkBytes = "direct-upload-degraded-duplicate".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            when(faultDomainManager.getTargetNodes(chunkHash)).thenReturn(List.of("node1", "node1"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            StorageProperties.DegradedWriteConfig degradedConfig = new StorageProperties.DegradedWriteConfig();
            degradedConfig.setEnabled(true);
            degradedConfig.setMinReplicas(1);
            when(storageProperties.getDegradedWrite()).thenReturn(degradedConfig);
            when(directUploadPromotionService.promote(any(), any(DirectUploadDigestAccumulator.class)))
                    .thenAnswer(invocation -> {
                        DirectUploadDigestAccumulator aggregateDigest = invocation.getArgument(1);
                        MessageDigest candidateDigest = aggregateDigest.fork();
                        candidateDigest.update(chunkBytes);
                        aggregateDigest.commit(candidateDigest);
                        return new DirectUploadPromotionResult(chunkBytes.length, "\"etag-final\"");
                    });

            Result<CompleteDirectMultipartUploadResponse> result = storageService.completeDirectMultipartUpload(
                    directCompleteRequest(chunkBytes, "\"etag-0\""));

            assertThat(result.getCode()).isEqualTo(200);
            ArgumentCaptor<DirectUploadPartDescriptor> descriptorCaptor =
                    ArgumentCaptor.forClass(DirectUploadPartDescriptor.class);
            verify(directUploadPromotionService).promote(
                    descriptorCaptor.capture(),
                    any(DirectUploadDigestAccumulator.class));
            assertThat(descriptorCaptor.getValue().targetNodes()).containsExactly("node1");
            assertThat(descriptorCaptor.getValue().requiredQuorum()).isEqualTo(1);
        }

        /**
         * 验证动态配置把降级写和同步追踪组合成不安全状态时，请求边界立即拒绝。
         */
        @Test
        @DisplayName("Should reject direct upload when degraded write tracking is disabled")
        void shouldRejectDirectUploadWhenDegradedTrackingIsDisabled() {
            byte[] chunkBytes = "direct-upload-untracked-degraded".getBytes(StandardCharsets.UTF_8);
            StorageProperties.DegradedWriteConfig degradedConfig = new StorageProperties.DegradedWriteConfig();
            degradedConfig.setEnabled(true);
            degradedConfig.setMinReplicas(1);
            degradedConfig.setTrackForSync(false);
            when(storageProperties.getDegradedWrite()).thenReturn(degradedConfig);
            doCallRealMethod().when(storageProperties).validateDegradedWriteTracking();

            Result<CompleteDirectMultipartUploadResponse> result = storageService.completeDirectMultipartUpload(
                    directCompleteRequest(chunkBytes, "\"etag-0\""));

            assertThat(result.getCode()).isEqualTo(ResultEnum.STORAGE_INSUFFICIENT_REPLICAS.getCode());
            verifyNoInteractions(directUploadPromotionService);
        }

        /**
         * 验证去重后唯一副本低于降级 min-replicas 时仍必须拒绝。
         */
        @Test
        @DisplayName("Should reject deduplicated targets below degraded minimum replicas")
        void shouldRejectDeduplicatedTargetsBelowDegradedMinimum() {
            byte[] chunkBytes = "direct-upload-degraded-minimum".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            when(faultDomainManager.getTargetNodes(chunkHash)).thenReturn(List.of("node1", "node1"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            StorageProperties.DegradedWriteConfig degradedConfig = new StorageProperties.DegradedWriteConfig();
            degradedConfig.setEnabled(true);
            degradedConfig.setMinReplicas(2);
            when(storageProperties.getDegradedWrite()).thenReturn(degradedConfig);

            Result<CompleteDirectMultipartUploadResponse> result = storageService.completeDirectMultipartUpload(
                    directCompleteRequest(chunkBytes, "\"etag-0\""));

            assertThat(result.getCode()).isEqualTo(ResultEnum.STORAGE_INSUFFICIENT_REPLICAS.getCode());
            verifyNoInteractions(directUploadPromotionService);
        }

        @Test
        @DisplayName("Should complete retry when direct upload part was already promoted")
        void shouldCompleteRetryWhenDirectUploadPartWasAlreadyPromoted() {
            byte[] chunkBytes = "direct-upload-chunk".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            when(faultDomainManager.getTargetNodes(chunkHash)).thenReturn(List.of("node1", "node2"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            when(storageProperties.getEffectiveQuorum()).thenReturn(2);
            when(directUploadPromotionService.promote(any(), any(DirectUploadDigestAccumulator.class)))
                    .thenAnswer(invocation -> {
                        DirectUploadDigestAccumulator aggregateDigest = invocation.getArgument(1);
                        MessageDigest candidateDigest = aggregateDigest.fork();
                        candidateDigest.update(chunkBytes);
                        aggregateDigest.commit(candidateDigest);
                        return new DirectUploadPromotionResult(chunkBytes.length, "\"etag-final\"");
                    });

            CompleteDirectMultipartUploadRequest request = new CompleteDirectMultipartUploadRequest(
                    "direct-session",
                    List.of(new DirectMultipartCompletedPart(
                            0,
                            directStagingObjectName("direct-session", 0),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            chunkBytes.length,
                            "\"etag-0\"",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ))
            );

            Result<CompleteDirectMultipartUploadResponse> result = storageService.completeDirectMultipartUpload(request);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().contentHash()).isEqualTo(chunkHash);
            assertThat(result.getData().parts()).hasSize(1);
            assertThat(result.getData().parts().getFirst().storagePath())
                    .isEqualTo(directStoragePath(chunkHash));
            assertThat(result.getData().parts().getFirst().eTag()).isEqualTo("\"etag-final\"");

            verify(directUploadPromotionService).promote(
                    any(),
                    any(DirectUploadDigestAccumulator.class)
            );
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Should reject completed parts whose object keys do not match the session")
        void shouldRejectCompletedPartsWhoseObjectKeysDoNotMatchTheSession() {
            byte[] chunkBytes = "direct-upload-chunk".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);

            CompleteDirectMultipartUploadRequest request = new CompleteDirectMultipartUploadRequest(
                    "direct-session",
                    List.of(new DirectMultipartCompletedPart(
                            0,
                            directStagingObjectName("other-session", 0),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            chunkBytes.length,
                            "\"etag-0\"",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ))
            );

            Result<CompleteDirectMultipartUploadResponse> result = storageService.completeDirectMultipartUpload(request);

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            verifyNoInteractions(directUploadPromotionService, s3Client);
        }

        /**
         * 验证所有完成分片在首个 promotion 前完成可信路径重绑定，后续非法分片不会留下部分 final。
         */
        @Test
        @DisplayName("Should validate every completed part before promoting any object")
        void shouldValidateEveryCompletedPartBeforePromotingAnyObject() {
            byte[] chunkBytes = "direct-upload-chunk".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            when(faultDomainManager.getTargetNodes(chunkHash)).thenReturn(List.of("node1"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(1);
            when(storageProperties.getEffectiveQuorum()).thenReturn(1);
            when(storageProperties.getDegradedWrite()).thenReturn(new StorageProperties.DegradedWriteConfig());

            List<DirectMultipartCompletedPart> parts = List.of(
                    new DirectMultipartCompletedPart(
                            0,
                            directStagingObjectName("direct-session", 0),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            chunkBytes.length,
                            "\"etag-0\"",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ),
                    new DirectMultipartCompletedPart(
                            1,
                            directStagingObjectName("other-session", 1),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            chunkBytes.length,
                            "\"etag-1\"",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    )
            );

            Result<CompleteDirectMultipartUploadResponse> result =
                    storageService.completeDirectMultipartUpload(
                            new CompleteDirectMultipartUploadRequest("direct-session", parts));

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            verifyNoInteractions(directUploadPromotionService);
        }

        /**
         * 验证三个直传写 RPC 在 tenant attachment 缺失或畸形时均失败关闭。
         */
        @Test
        @DisplayName("Should reject direct upload writes without an explicit tenant")
        void shouldRejectDirectUploadWritesWithoutExplicitTenant() {
            byte[] chunkBytes = "direct-upload-tenant".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            DirectMultipartCompletedPart completedPart = new DirectMultipartCompletedPart(
                    0,
                    directStagingObjectName("direct-session", 0),
                    directFinalObjectName(chunkHash),
                    "node1",
                    directStoragePath(chunkHash),
                    chunkBytes.length,
                    "\"etag-0\"",
                    chunkHash,
                    chunkHash,
                    "SHA-256"
            );
            CreateDirectMultipartUploadRequest createRequest = new CreateDirectMultipartUploadRequest(
                    "direct-session",
                    "direct.pdf",
                    chunkBytes.length,
                    chunkBytes.length,
                    "application/pdf",
                    List.of(new DirectMultipartUploadPartRequest(
                            0,
                            chunkHash,
                            chunkBytes.length,
                            "application/pdf",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ))
            );

            for (String invalidTenant : new String[]{null, "not-a-number", "-1"}) {
                if (invalidTenant == null) {
                    RpcContext.getServerAttachment().removeAttachment("tenant.id");
                } else {
                    RpcContext.getServerAttachment().setAttachment("tenant.id", invalidTenant);
                }

                assertThat(storageService.createDirectMultipartUpload(createRequest).getCode())
                        .isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
                assertThat(storageService.completeDirectMultipartUpload(
                        new CompleteDirectMultipartUploadRequest("direct-session", List.of(completedPart))).getCode())
                        .isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
                assertThat(storageService.abortDirectMultipartUpload(
                        new AbortDirectMultipartUploadRequest("direct-session", List.of(completedPart))).getCode())
                        .isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            }

            verifyNoInteractions(directUploadPromotionService, directUploadStagingTracker, s3Client, s3Presigner);
        }

        @Test
        @DisplayName("Should reject completion without a bounded provider ETag")
        void shouldRejectCompletionWithoutBoundedProviderEtag() {
            byte[] chunkBytes = "direct-upload-chunk".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            for (String invalidEtag : new String[]{null, "", " ", "line\nbreak", "e".repeat(256)}) {
                CompleteDirectMultipartUploadRequest request = new CompleteDirectMultipartUploadRequest(
                        "direct-session",
                        List.of(new DirectMultipartCompletedPart(
                                0,
                                directStagingObjectName("direct-session", 0),
                                directFinalObjectName(chunkHash),
                                "node1",
                                directStoragePath(chunkHash),
                                chunkBytes.length,
                                invalidEtag,
                                chunkHash,
                                chunkHash,
                                "SHA-256"
                        ))
                );

                Result<CompleteDirectMultipartUploadResponse> result =
                        storageService.completeDirectMultipartUpload(request);

                assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            }
            verifyNoInteractions(directUploadPromotionService);
        }

        /**
         * 验证 manifest 列可容纳的 255 字符可见 ASCII ETag 不会被边界误拒。
         */
        @Test
        @DisplayName("Should accept a 255-character visible ASCII completion ETag")
        void shouldAcceptMaximumLengthCompletionEtag() {
            byte[] chunkBytes = "direct-upload-max-etag".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            String maximumEtag = "e".repeat(255);
            when(faultDomainManager.getTargetNodes(chunkHash)).thenReturn(List.of("node1"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(1);
            when(storageProperties.getEffectiveQuorum()).thenReturn(1);
            when(storageProperties.getDegradedWrite()).thenReturn(new StorageProperties.DegradedWriteConfig());
            when(directUploadPromotionService.promote(any(), any(DirectUploadDigestAccumulator.class)))
                    .thenAnswer(invocation -> {
                        DirectUploadDigestAccumulator aggregateDigest = invocation.getArgument(1);
                        MessageDigest candidateDigest = aggregateDigest.fork();
                        candidateDigest.update(chunkBytes);
                        aggregateDigest.commit(candidateDigest);
                        return new DirectUploadPromotionResult(chunkBytes.length, "\"etag-final\"");
                    });

            Result<CompleteDirectMultipartUploadResponse> result = storageService.completeDirectMultipartUpload(
                    directCompleteRequest(chunkBytes, maximumEtag));

            assertThat(result.getCode()).isEqualTo(200);
            verify(directUploadPromotionService).promote(
                    argThat(descriptor -> maximumEtag.equals(descriptor.eTag())),
                    any(DirectUploadDigestAccumulator.class));
        }

        @Test
        @DisplayName("Should reject completion whose aggregate size exceeds the configured file limit")
        void shouldRejectCompletionAboveAggregateFileSizeLimit() {
            String chunkHash = sha256Prefixed("aggregate-limit".getBytes(StandardCharsets.UTF_8));
            StorageProperties.DirectUploadConfig config = new StorageProperties.DirectUploadConfig();
            config.setMaxFileSizeBytes(10);
            config.setMaxPartSizeBytes(10);
            when(storageProperties.getDirectUpload()).thenReturn(config);
            List<DirectMultipartCompletedPart> parts = List.of(
                    new DirectMultipartCompletedPart(
                            0,
                            directStagingObjectName("direct-session", 0),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            6,
                            "\"etag-0\"",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ),
                    new DirectMultipartCompletedPart(
                            1,
                            directStagingObjectName("direct-session", 1),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            6,
                            "\"etag-1\"",
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    )
            );

            Result<CompleteDirectMultipartUploadResponse> result =
                    storageService.completeDirectMultipartUpload(
                            new CompleteDirectMultipartUploadRequest("direct-session", parts));

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            verifyNoInteractions(directUploadPromotionService);
        }

        @Test
        @DisplayName("Should abort direct multipart upload without deleting shared final chunks")
        void shouldAbortDirectMultipartUploadWithoutDeletingSharedFinalChunks() {
            byte[] chunkBytes = "direct-upload-chunk".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);

            AbortDirectMultipartUploadRequest request = new AbortDirectMultipartUploadRequest(
                    "direct-session",
                    List.of(new DirectMultipartCompletedPart(
                            0,
                            directStagingObjectName("direct-session", 0),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            512L,
                            null,
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ))
            );

            Result<Boolean> result = storageService.abortDirectMultipartUpload(request);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isTrue();

            ArgumentCaptor<DirectUploadStagingDescriptor> descriptorCaptor =
                    ArgumentCaptor.forClass(DirectUploadStagingDescriptor.class);
            verify(directUploadPromotionService).abort(descriptorCaptor.capture());
            assertThat(descriptorCaptor.getValue().nodeName()).isEqualTo("node1");
            assertThat(descriptorCaptor.getValue().objectName())
                    .isEqualTo(directStagingObjectName("direct-session", 0));
            assertThat(descriptorCaptor.getValue().objectName()).isNotEqualTo(directFinalObjectName(chunkHash));
            verifyNoInteractions(s3Client);
        }

        /**
         * 验证 abort 先校验全部规范 staging 身份，后续非法分片不会导致前面分片被部分删除。
         */
        @Test
        @DisplayName("Should validate every abort part before deleting any staging object")
        void shouldValidateEveryAbortPartBeforeDeletingAnyStagingObject() {
            byte[] chunkBytes = "direct-upload-abort".getBytes(StandardCharsets.UTF_8);
            String chunkHash = sha256Prefixed(chunkBytes);
            List<DirectMultipartCompletedPart> parts = List.of(
                    new DirectMultipartCompletedPart(
                            0,
                            directStagingObjectName("direct-session", 0),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            chunkBytes.length,
                            null,
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    ),
                    new DirectMultipartCompletedPart(
                            1,
                            directStagingObjectName("other-session", 1),
                            directFinalObjectName(chunkHash),
                            "node1",
                            directStoragePath(chunkHash),
                            chunkBytes.length,
                            null,
                            chunkHash,
                            chunkHash,
                            "SHA-256"
                    )
            );

            Result<Boolean> result = storageService.abortDirectMultipartUpload(
                    new AbortDirectMultipartUploadRequest("direct-session", parts));

            assertThat(result.getCode()).isEqualTo(ResultEnum.PARAM_IS_INVALID.getCode());
            verifyNoInteractions(directUploadPromotionService);
        }
    }

    /**
     * 创建测试节点配置（默认无故障域）。
     *
     * @param name 节点名
     * @param enabled 是否启用
     * @return 节点配置
     */
    private NodeConfig createNodeConfig(String name, Boolean enabled) {
        return createNodeConfig(name, enabled, null);
    }

    /**
     * 创建测试节点配置。
     *
     * @param name 节点名
     * @param enabled 是否启用
     * @param faultDomain 故障域
     * @return 节点配置
     */
    private NodeConfig createNodeConfig(String name, Boolean enabled, String faultDomain) {
        NodeConfig config = new NodeConfig();
        config.setName(name);
        config.setEnabled(enabled);
        config.setFaultDomain(faultDomain);
        config.setPhysicalStorageId("physical-" + name);
        return config;
    }
}
