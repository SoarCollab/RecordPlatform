package cn.flying.storage.service;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.tenant.TenantContextUtil;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.util.*;
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
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

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

    @Nested
    @DisplayName("Store File Chunk Tests")
    class StoreFileChunkTests {

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
            verify(degradedWriteTracker).recordDegradedWrite(eq(TEST_FILE_HASH), anyList(), anyLong());
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
        return config;
    }
}
