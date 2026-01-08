package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.event.NodeTopologyChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RebalanceService Tests")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RebalanceServiceTest {

    @Mock
    private FaultDomainManager faultDomainManager;

    @Mock
    private S3ClientManager clientManager;

    @Mock
    private S3Monitor s3Monitor;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private RMap<String, String> statusMap;

    @Mock
    private S3Client sourceS3Client;

    @Mock
    private S3Client targetS3Client;

    @InjectMocks
    private RebalanceService rebalanceService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(rebalanceService, "rateLimitPerSecond", 100);
        ReflectionTestUtils.setField(rebalanceService, "rebalanceEnabled", true);
        ReflectionTestUtils.setField(rebalanceService, "cleanupSourceAfterRebalance", false);

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        doReturn(statusMap).when(redissonClient).getMap(anyString());
        when(statusMap.isEmpty()).thenReturn(true);
    }

    @Nested
    @DisplayName("Rebalance Enabled/Disabled")
    class RebalanceToggleTests {

        @Test
        @DisplayName("should not process topology change when rebalance is disabled")
        void shouldNotProcessWhenDisabled() throws Exception {
            ReflectionTestUtils.setField(rebalanceService, "rebalanceEnabled", false);

            NodeTopologyChangeEvent event = new NodeTopologyChangeEvent(
                    this, "node1", NodeTopologyChangeEvent.TopologyChangeType.NODE_OFFLINE, "domainA");

            rebalanceService.onTopologyChange(event);

            verify(lock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("triggerManualRebalance should return null when disabled")
        void triggerManualRebalanceShouldReturnNullWhenDisabled() {
            ReflectionTestUtils.setField(rebalanceService, "rebalanceEnabled", false);

            String taskId = rebalanceService.triggerManualRebalance(null);

            assertThat(taskId).isNull();
        }

        @Test
        @DisplayName("triggerManualRebalance should return task ID when enabled")
        void triggerManualRebalanceShouldReturnTaskIdWhenEnabled() {
            when(faultDomainManager.getActiveDomains()).thenReturn(List.of("domainA"));
            when(faultDomainManager.getHealthyNodesInDomainList("domainA")).thenReturn(List.of());

            String taskId = rebalanceService.triggerManualRebalance(null);

            assertThat(taskId).startsWith("rebalance-");
        }
    }

    @Nested
    @DisplayName("Topology Change Events")
    class TopologyChangeEventTests {

        @Test
        @DisplayName("should schedule rebalance on NODE_OFFLINE event")
        void shouldScheduleRebalanceOnNodeOffline() throws InterruptedException {
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            when(faultDomainManager.getNodeDomain("node1")).thenReturn("domainA");
            when(faultDomainManager.getActiveDomains()).thenReturn(List.of("domainB"));
            when(faultDomainManager.getHealthyNodesInDomainList("domainB")).thenReturn(List.of());

            NodeTopologyChangeEvent event = new NodeTopologyChangeEvent(
                    this, "node1", NodeTopologyChangeEvent.TopologyChangeType.NODE_OFFLINE, "domainA");

            rebalanceService.onTopologyChange(event);

            Thread.sleep(100);
            verify(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("should skip rebalance when another is already running")
        void shouldSkipWhenAnotherIsRunning() throws InterruptedException {
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            rebalanceService.scheduleRebalance("node1", RebalanceService.RebalanceType.MIGRATE_FROM_FAILED);

            verify(faultDomainManager, never()).getNodeDomain(anyString());
        }
    }

    @Nested
    @DisplayName("Status Management")
    class StatusManagementTests {

        @Test
        @DisplayName("should return current status when running locally")
        void shouldReturnCurrentStatusWhenRunningLocally() {
            RebalanceService.RebalanceStatus status = rebalanceService.getStatus();

            assertThat(status).isNotNull();
            assertThat(status.isRunning()).isFalse();
        }

        @Test
        @DisplayName("should load status from Redis when not running locally")
        void shouldLoadStatusFromRedisWhenNotRunningLocally() {
            when(statusMap.isEmpty()).thenReturn(false);
            when(statusMap.readAllMap()).thenReturn(Map.of(
                    "running", "false",
                    "success", "true",
                    "type", "MIGRATE_FROM_FAILED",
                    "triggerNode", "node1",
                    "migratedCount", "10",
                    "failedCount", "2"
            ));

            RebalanceService.RebalanceStatus status = rebalanceService.getStatus();

            assertThat(status).isNotNull();
            assertThat(status.isSuccess()).isTrue();
            assertThat(status.getType()).isEqualTo(RebalanceService.RebalanceType.MIGRATE_FROM_FAILED);
            assertThat(status.getTriggerNode()).isEqualTo("node1");
            assertThat(status.getMigratedCount().get()).isEqualTo(10);
            assertThat(status.getFailedCount().get()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Object Copy Tests")
    class ObjectCopyTests {

        @Test
        @DisplayName("should return false when source client is null")
        void shouldReturnFalseWhenSourceClientIsNull() {
            when(clientManager.getClient("sourceNode")).thenReturn(null);
            when(clientManager.getClient("targetNode")).thenReturn(targetS3Client);

            boolean result = rebalanceService.copyObject("sourceNode", "targetNode", "path/to/object");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when target client is null")
        void shouldReturnFalseWhenTargetClientIsNull() {
            when(clientManager.getClient("sourceNode")).thenReturn(sourceS3Client);
            when(clientManager.getClient("targetNode")).thenReturn(null);

            boolean result = rebalanceService.copyObject("sourceNode", "targetNode", "path/to/object");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when object does not exist")
        void shouldReturnFalseWhenObjectDoesNotExist() {
            when(clientManager.getClient("sourceNode")).thenReturn(sourceS3Client);
            when(clientManager.getClient("targetNode")).thenReturn(targetS3Client);
            when(sourceS3Client.headObject(any(HeadObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().message("Not found").build());

            boolean result = rebalanceService.copyObject("sourceNode", "targetNode", "path/to/object");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when object exceeds max in-memory size")
        void shouldReturnFalseWhenObjectTooLarge() {
            when(clientManager.getClient("sourceNode")).thenReturn(sourceS3Client);
            when(clientManager.getClient("targetNode")).thenReturn(targetS3Client);
            HeadObjectResponse headResponse = HeadObjectResponse.builder()
                    .contentLength(200 * 1024 * 1024L)
                    .build();
            when(sourceS3Client.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse);

            boolean result = rebalanceService.copyObject("sourceNode", "targetNode", "path/to/object");

            assertThat(result).isFalse();
            verify(sourceS3Client, never()).getObject(any(GetObjectRequest.class));
        }
    }

    @Nested
    @DisplayName("Rebalance Type Tests")
    class RebalanceTypeTests {

        @Test
        @DisplayName("RebalanceType enum should have expected values")
        void rebalanceTypeEnumShouldHaveExpectedValues() {
            RebalanceService.RebalanceType[] types = RebalanceService.RebalanceType.values();

            assertThat(types).containsExactlyInAnyOrder(
                    RebalanceService.RebalanceType.MIGRATE_FROM_FAILED,
                    RebalanceService.RebalanceType.COPY_TO_PROMOTED,
                    RebalanceService.RebalanceType.REBALANCE_TO_NEW
            );
        }
    }

    @Nested
    @DisplayName("RebalanceStatus Tests")
    class RebalanceStatusTests {

        @Test
        @DisplayName("should initialize counters to zero")
        void shouldInitializeCountersToZero() {
            RebalanceService.RebalanceStatus status = new RebalanceService.RebalanceStatus();

            assertThat(status.getMigratedCount().get()).isEqualTo(0);
            assertThat(status.getFailedCount().get()).isEqualTo(0);
        }

        @Test
        @DisplayName("should allow atomic counter increments")
        void shouldAllowAtomicCounterIncrements() {
            RebalanceService.RebalanceStatus status = new RebalanceService.RebalanceStatus();

            status.getMigratedCount().incrementAndGet();
            status.getMigratedCount().incrementAndGet();
            status.getFailedCount().incrementAndGet();

            assertThat(status.getMigratedCount().get()).isEqualTo(2);
            assertThat(status.getFailedCount().get()).isEqualTo(1);
        }
    }
}
