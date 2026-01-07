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
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.util.*;

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
    }

    private NodeConfig createNodeConfig(String name, Boolean enabled) {
        NodeConfig config = new NodeConfig();
        config.setName(name);
        config.setEnabled(enabled);
        return config;
    }
}
