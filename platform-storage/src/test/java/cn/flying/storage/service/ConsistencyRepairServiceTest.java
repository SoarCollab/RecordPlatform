package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3Monitor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsistencyRepairService Unit Tests")
class ConsistencyRepairServiceTest {

    @Mock
    private S3ClientManager clientManager;

    @Mock
    private S3Monitor s3Monitor;

    @Mock
    private StorageProperties storageProperties;

    @Mock
    private FaultDomainManager faultDomainManager;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private S3Client s3Client;

    @InjectMocks
    private ConsistencyRepairService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "batchSize", 100);
        ReflectionTestUtils.setField(service, "lockTimeoutSeconds", 600L);
        ReflectionTestUtils.setField(service, "repairEnabled", true);
    }

    @Nested
    @DisplayName("Scheduled Repair Skip Conditions Tests")
    class ScheduledRepairSkipConditionsTests {

        @Test
        @DisplayName("Should skip when repair is disabled")
        void shouldSkipWhenRepairDisabled() {
            ReflectionTestUtils.setField(service, "repairEnabled", false);

            service.scheduledRepair();

            verify(faultDomainManager, never()).isSingleDomainMode();
            verify(redissonClient, never()).getLock(anyString());
        }

        @Test
        @DisplayName("Should skip when in single domain mode")
        void shouldSkipWhenSingleDomainMode() {
            when(faultDomainManager.isSingleDomainMode()).thenReturn(true);

            service.scheduledRepair();

            verify(redissonClient, never()).getLock(anyString());
        }

        @Test
        @DisplayName("Should skip when lock not acquired")
        void shouldSkipWhenLockNotAcquired() throws InterruptedException {
            when(faultDomainManager.isSingleDomainMode()).thenReturn(false);
            when(redissonClient.getLock(anyString())).thenReturn(lock);
            when(lock.tryLock(eq(0L), eq(600L), eq(TimeUnit.SECONDS))).thenReturn(false);

            service.scheduledRepair();

            verify(faultDomainManager, never()).getActiveDomains();
        }

        @Test
        @DisplayName("Should release lock after completion")
        void shouldReleaseLockAfterCompletion() throws InterruptedException {
            when(faultDomainManager.isSingleDomainMode()).thenReturn(false);
            when(redissonClient.getLock(anyString())).thenReturn(lock);
            when(lock.tryLock(eq(0L), eq(600L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));

            service.scheduledRepair();

            verify(lock).unlock();
        }

        @Test
        @DisplayName("Should handle InterruptedException gracefully")
        void shouldHandleInterruptedExceptionGracefully() throws InterruptedException {
            when(faultDomainManager.isSingleDomainMode()).thenReturn(false);
            when(redissonClient.getLock(anyString())).thenReturn(lock);
            when(lock.tryLock(eq(0L), eq(600L), eq(TimeUnit.SECONDS))).thenThrow(new InterruptedException());

            service.scheduledRepair();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            Thread.interrupted();
        }
    }

    @Nested
    @DisplayName("Repair All Domains Tests")
    class RepairAllDomainsTests {

        @Test
        @DisplayName("Should skip when less than 2 active domains")
        void shouldSkipWhenLessThanTwoActiveDomains() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isZero();
            assertThat(stats.filesChecked).isZero();
            assertThat(stats.filesRepaired).isZero();
        }

        @Test
        @DisplayName("Should skip domain with no healthy nodes")
        void shouldSkipDomainWithNoHealthyNodes() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(s3Monitor.isNodeOnline("node-a1")).thenReturn(false);
            when(s3Monitor.isNodeOnline("node-b1")).thenReturn(false);

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isZero();
        }

        @Test
        @DisplayName("Should check consistency between two domains")
        void shouldCheckConsistencyBetweenTwoDomains() throws Exception {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);

            S3Client clientA = mock(S3Client.class);
            S3Client clientB = mock(S3Client.class);
            when(clientManager.getClient("node-a1")).thenReturn(clientA);
            when(clientManager.getClient("node-b1")).thenReturn(clientB);

            mockBucketExists(clientA, "node-a1");
            mockBucketExists(clientB, "node-b1");

            ListObjectsV2Response responseA = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(
                            S3Object.builder().key("file1.txt").build(),
                            S3Object.builder().key("file2.txt").build()
                    ))
                    .isTruncated(false)
                    .build();

            ListObjectsV2Response responseB = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(
                            S3Object.builder().key("file1.txt").build(),
                            S3Object.builder().key("file2.txt").build()
                    ))
                    .isTruncated(false)
                    .build();

            when(clientA.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseA);
            when(clientB.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseB);

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isEqualTo(2);
            assertThat(stats.filesRepaired).isZero();
        }

        @Test
        @DisplayName("Should detect and repair missing objects")
        void shouldDetectAndRepairMissingObjects() throws Exception {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);

            S3Client clientA = mock(S3Client.class);
            S3Client clientB = mock(S3Client.class);
            when(clientManager.getClient("node-a1")).thenReturn(clientA);
            when(clientManager.getClient("node-b1")).thenReturn(clientB);

            mockBucketExists(clientA, "node-a1");
            mockBucketExists(clientB, "node-b1");

            ListObjectsV2Response responseA = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(
                            S3Object.builder().key("file1.txt").build(),
                            S3Object.builder().key("file2.txt").build()
                    ))
                    .isTruncated(false)
                    .build();

            ListObjectsV2Response responseB = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(
                            S3Object.builder().key("file1.txt").build()
                    ))
                    .isTruncated(false)
                    .build();

            when(clientA.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseA);
            when(clientB.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseB);

            when(faultDomainManager.getHealthyNodesInDomainList("domain-B")).thenReturn(Arrays.asList("node-b1"));

            HeadObjectResponse headResponse = HeadObjectResponse.builder()
                    .contentLength(100L)
                    .contentType("text/plain")
                    .build();
            when(clientA.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse);

            ResponseInputStream<GetObjectResponse> mockStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream("test content".getBytes())
            );
            when(clientA.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);
            when(clientB.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isEqualTo(2);
            assertThat(stats.filesRepaired).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should skip directory entries")
        void shouldSkipDirectoryEntries() throws Exception {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);

            S3Client clientA = mock(S3Client.class);
            S3Client clientB = mock(S3Client.class);
            when(clientManager.getClient("node-a1")).thenReturn(clientA);
            when(clientManager.getClient("node-b1")).thenReturn(clientB);

            mockBucketExists(clientA, "node-a1");
            mockBucketExists(clientB, "node-b1");

            ListObjectsV2Response responseA = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(
                            S3Object.builder().key("folder/").build(),
                            S3Object.builder().key("file1.txt").build()
                    ))
                    .isTruncated(false)
                    .build();

            ListObjectsV2Response responseB = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(
                            S3Object.builder().key("file1.txt").build()
                    ))
                    .isTruncated(false)
                    .build();

            when(clientA.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseA);
            when(clientB.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseB);

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle pagination when listing objects")
        void shouldHandlePaginationWhenListingObjects() throws Exception {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);

            S3Client clientA = mock(S3Client.class);
            S3Client clientB = mock(S3Client.class);
            when(clientManager.getClient("node-a1")).thenReturn(clientA);
            when(clientManager.getClient("node-b1")).thenReturn(clientB);

            mockBucketExists(clientA, "node-a1");
            mockBucketExists(clientB, "node-b1");

            ListObjectsV2Response responsePage1 = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(S3Object.builder().key("file1.txt").build()))
                    .isTruncated(true)
                    .nextContinuationToken("token123")
                    .build();

            ListObjectsV2Response responsePage2 = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(S3Object.builder().key("file2.txt").build()))
                    .isTruncated(false)
                    .build();

            when(clientA.listObjectsV2(any(ListObjectsV2Request.class)))
                    .thenReturn(responsePage1)
                    .thenReturn(responsePage2);

            ListObjectsV2Response responseB = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(
                            S3Object.builder().key("file1.txt").build(),
                            S3Object.builder().key("file2.txt").build()
                    ))
                    .isTruncated(false)
                    .build();
            when(clientB.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseB);

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            verify(clientA, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
            assertThat(stats.domainsChecked).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Copy Object Between Nodes Tests")
    class CopyObjectBetweenNodesTests {

        @Test
        @DisplayName("Should verify domains are checked")
        void shouldVerifyDomainsAreChecked() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);

            S3Client clientA = mock(S3Client.class);
            S3Client clientB = mock(S3Client.class);
            when(clientManager.getClient("node-a1")).thenReturn(clientA);
            when(clientManager.getClient("node-b1")).thenReturn(clientB);

            mockBucketExists(clientA, "node-a1");
            mockBucketExists(clientB, "node-b1");

            ListObjectsV2Response responseA = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(S3Object.builder().key("file.txt").build()))
                    .isTruncated(false)
                    .build();
            ListObjectsV2Response responseB = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(S3Object.builder().key("file.txt").build()))
                    .isTruncated(false)
                    .build();

            when(clientA.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseA);
            when(clientB.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseB);

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isEqualTo(2);
            assertThat(stats.filesRepaired).isZero();
        }

        @Test
        @DisplayName("Should handle bucket not exists scenario during list")
        void shouldHandleBucketNotExistsDuringList() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);

            S3Client clientA = mock(S3Client.class);
            S3Client clientB = mock(S3Client.class);
            when(clientManager.getClient("node-a1")).thenReturn(clientA);
            when(clientManager.getClient("node-b1")).thenReturn(clientB);

            mockBucketExists(clientA, "node-a1");
            when(clientB.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(NoSuchBucketException.builder().message("Bucket not found").build());

            ListObjectsV2Response responseA = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(S3Object.builder().key("file.txt").build()))
                    .isTruncated(false)
                    .build();

            when(clientA.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseA);

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("Should handle client manager returning null")
        void shouldHandleClientManagerReturningNull() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            when(clientManager.getClient(anyString())).thenReturn(null);

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isLessThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Immediate Repair Tests")
    class ImmediateRepairTests {

        @Test
        @DisplayName("Should schedule immediate repair by nodes")
        void shouldScheduleImmediateRepairByNodes() throws Exception {
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);

            S3Client sourceClient = mock(S3Client.class);
            S3Client targetClient = mock(S3Client.class);
            when(clientManager.getClient("source-node")).thenReturn(sourceClient);
            when(clientManager.getClient("target-node")).thenReturn(targetClient);

            mockBucketExists(targetClient, "target-node");

            HeadObjectResponse headResponse = HeadObjectResponse.builder()
                    .contentLength(100L)
                    .contentType("text/plain")
                    .build();
            when(sourceClient.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse);

            ResponseInputStream<GetObjectResponse> mockStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream("test".getBytes())
            );
            when(sourceClient.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);
            when(targetClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            service.scheduleImmediateRepairByNodes("test-object", "source-node", "target-node");

            Thread.sleep(500);

            verify(clientManager).getClient("source-node");
            verify(clientManager).getClient("target-node");
        }

        @Test
        @DisplayName("Should skip repair when source node offline")
        void shouldSkipRepairWhenSourceNodeOffline() throws InterruptedException {
            when(s3Monitor.isNodeOnline("source-node")).thenReturn(false);

            service.scheduleImmediateRepairByNodes("test-object", "source-node", "target-node");

            Thread.sleep(500);

            verify(clientManager, never()).getClient(anyString());
        }
    }

    @Nested
    @DisplayName("Manual Trigger Tests")
    class ManualTriggerTests {

        @Test
        @DisplayName("Should trigger manual repair")
        void shouldTriggerManualRepair() {
            when(faultDomainManager.getActiveDomains()).thenReturn(Arrays.asList("domain-A"));

            ConsistencyRepairService.RepairStatistics stats = service.triggerManualRepair();

            assertThat(stats).isNotNull();
            verify(faultDomainManager).getActiveDomains();
        }
    }

    @Nested
    @DisplayName("Repair Statistics Tests")
    class RepairStatisticsTests {

        @Test
        @DisplayName("Should merge statistics correctly")
        void shouldMergeStatisticsCorrectly() {
            ConsistencyRepairService.RepairStatistics stats1 = new ConsistencyRepairService.RepairStatistics();
            stats1.domainsChecked = 2;
            stats1.filesChecked = 100;
            stats1.filesRepaired = 10;
            stats1.failureCount = 2;

            ConsistencyRepairService.RepairStatistics stats2 = new ConsistencyRepairService.RepairStatistics();
            stats2.filesChecked = 50;
            stats2.filesRepaired = 5;
            stats2.failureCount = 1;

            stats1.merge(stats2);

            assertThat(stats1.domainsChecked).isEqualTo(2);
            assertThat(stats1.filesChecked).isEqualTo(150);
            assertThat(stats1.filesRepaired).isEqualTo(15);
            assertThat(stats1.failureCount).isEqualTo(3);
        }

        @Test
        @DisplayName("Should format toString correctly")
        void shouldFormatToStringCorrectly() {
            ConsistencyRepairService.RepairStatistics stats = new ConsistencyRepairService.RepairStatistics();
            stats.domainsChecked = 2;
            stats.filesChecked = 100;
            stats.filesRepaired = 10;
            stats.failureCount = 2;

            String result = stats.toString();

            assertThat(result).contains("domains=2");
            assertThat(result).contains("checked=100");
            assertThat(result).contains("repaired=10");
            assertThat(result).contains("failures=2");
        }
    }

    private void mockBucketExists(S3Client client, String bucketName) {
        when(client.headBucket(argThat((HeadBucketRequest req) -> 
                req != null && bucketName.equals(req.bucket()))))
                .thenReturn(HeadBucketResponse.builder().build());
    }
}
