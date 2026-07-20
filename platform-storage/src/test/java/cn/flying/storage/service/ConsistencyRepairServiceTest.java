package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3ClientManager.TopologyLease;
import cn.flying.storage.core.S3Monitor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsistencyRepairService Unit Tests")
class ConsistencyRepairServiceTest {

    @Mock
    private S3ClientManager clientManager;

    @Mock
    private TopologyLease topologyLease;

    @Mock
    private S3Monitor s3Monitor;

    @Mock
    private FaultDomainManager faultDomainManager;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private StorageProperties storageProperties;

    @Mock
    private RLock lock;

    @Mock
    private S3Client s3Client;

    @InjectMocks
    private ConsistencyRepairService service;

    private StorageProperties.DegradedWriteConfig degradedConfig;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "batchSize", 100);
        ReflectionTestUtils.setField(service, "lockTimeoutSeconds", 600L);
        ReflectionTestUtils.setField(service, "repairEnabled", true);
        lenient().when(storageProperties.getDirectUpload())
                .thenReturn(new StorageProperties.DirectUploadConfig());
        degradedConfig = new StorageProperties.DegradedWriteConfig();
        degradedConfig.setRepairTimeoutSeconds(5);
        lenient().when(storageProperties.getDegradedWrite()).thenReturn(degradedConfig);
        lenient().when(faultDomainManager.areNodesOnIndependentPhysicalStorage(
                        anyString(),
                        anyString()
                ))
                .thenReturn(true);
        lenient().when(clientManager.acquireTopologyLease()).thenReturn(topologyLease);
        lenient().when(topologyLease.getClient(anyString()))
                .thenAnswer(invocation -> clientManager.getClient(invocation.getArgument(0)));
        lenient().doAnswer(invocation -> {
            Runnable action = invocation.getArgument(0);
            action.run();
            return null;
        }).when(topologyLease).runIfCurrent(any(Runnable.class));
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

        /**
         * 验证两个逻辑域实际指向同一物理存储时不会读取对象或宣称完成跨域修复。
         */
        @Test
        @DisplayName("Physical aliases across domains should fail closed before provider access")
        void shouldSkipRepairAcrossDuplicatePhysicalStorage() {
            when(faultDomainManager.getActiveDomains()).thenReturn(List.of("domain-A", "domain-B"));
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            S3Client clientA = mock(S3Client.class);
            S3Client clientB = mock(S3Client.class);
            when(clientManager.getClient("node-a1")).thenReturn(clientA);
            when(clientManager.getClient("node-b1")).thenReturn(clientB);
            when(faultDomainManager.areNodesOnIndependentPhysicalStorage(
                    "node-a1",
                    "node-b1"
            )).thenReturn(false);
            when(faultDomainManager.areNodesOnIndependentPhysicalStorage(
                    "node-b1",
                    "node-a1"
            )).thenReturn(false);

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isEqualTo(2);
            assertThat(stats.filesChecked).isZero();
            assertThat(stats.filesRepaired).isZero();
            assertThat(stats.failureCount).isZero();
            verifyNoInteractions(clientA, clientB);
        }

        /**
         * 验证目标 HEAD 返回后物理身份漂移时，不会把源节点别名计为已有跨域副本。
         */
        @Test
        @DisplayName("Physical topology drift during target check should fail closed")
        void shouldFailClosedWhenPhysicalTopologyDriftsDuringTargetCheck() {
            when(faultDomainManager.getActiveDomains()).thenReturn(List.of("domain-A", "domain-B"));
            when(faultDomainManager.getNodesInDomain("domain-A")).thenReturn(Set.of("node-a1"));
            when(faultDomainManager.getNodesInDomain("domain-B")).thenReturn(Set.of("node-b1"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            S3Client clientA = mock(S3Client.class);
            S3Client clientB = mock(S3Client.class);
            when(clientManager.getClient("node-a1")).thenReturn(clientA);
            when(clientManager.getClient("node-b1")).thenReturn(clientB);
            mockBucketExists(clientA, "node-a1");
            mockBucketExists(clientB, "node-b1");
            when(clientA.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                    ListObjectsV2Response.builder()
                            .contents(S3Object.builder().key("file.txt").build())
                            .isTruncated(false)
                            .build()
            );
            when(clientB.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                    ListObjectsV2Response.builder()
                            .contents(List.of())
                            .isTruncated(false)
                            .build()
            );
            when(clientB.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder().build());
            when(faultDomainManager.areNodesOnIndependentPhysicalStorage(
                    "node-a1",
                    "node-b1"
            )).thenReturn(true, true, false);

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.filesChecked).isEqualTo(1);
            assertThat(stats.filesRepaired).isZero();
            assertThat(stats.failureCount).isEqualTo(1);
            verify(clientB).headObject(argThat((HeadObjectRequest request) ->
                    request != null && "file.txt".equals(request.key())));
            verify(clientB, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("Should check consistency between two domains with matching objects")
        void shouldCheckConsistencyBetweenTwoDomains() {
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

            // Both domains have the same objects
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

            // headObject succeeds for objects that exist in the target domain
            when(clientB.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder().build());
            when(clientA.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder().build());

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isEqualTo(2);
            assertThat(stats.filesRepaired).isZero();
        }

        @Test
        @DisplayName("Should detect and repair missing objects")
        void shouldDetectAndRepairMissingObjects() {
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

            // Domain A has file1 and file2
            ListObjectsV2Response responseA = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(
                            S3Object.builder().key("file1.txt").build(),
                            S3Object.builder().key("file2.txt").build()
                    ))
                    .isTruncated(false)
                    .build();

            // Domain B has only file1
            ListObjectsV2Response responseB = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(
                            S3Object.builder().key("file1.txt").build()
                    ))
                    .isTruncated(false)
                    .build();

            when(clientA.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseA);
            when(clientB.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseB);

            // When checking if objects exist in B: file1 exists, file2 does not
            when(clientB.headObject(argThat((HeadObjectRequest req) ->
                    req != null && "file1.txt".equals(req.key()))))
                    .thenReturn(HeadObjectResponse.builder().build());
            Map<String, String> metadata = Map.of("file-hash", "sha256:file2", "tenant-id", "7");
            HeadObjectResponse repairedHead = HeadObjectResponse.builder()
                    .contentLength(12L)
                    .contentType("text/plain")
                    .eTag("\"target-etag\"")
                    .metadata(metadata)
                    .build();
            when(clientB.headObject(argThat((HeadObjectRequest req) ->
                    req != null && "file2.txt".equals(req.key()))))
                    .thenThrow(NoSuchKeyException.builder().message("Not found").build())
                    .thenReturn(repairedHead);

            // When checking if objects exist in A: file1 exists
            when(clientA.headObject(argThat((HeadObjectRequest req) ->
                    req != null && "file1.txt".equals(req.key()) && "node-a1".equals(req.bucket()))))
                    .thenReturn(HeadObjectResponse.builder().build());

            // Mock the copy operation: headObject for source metadata
            HeadObjectResponse headResponse = HeadObjectResponse.builder()
                    .contentLength(12L)
                    .contentType("text/plain")
                    .eTag("\"source-etag\"")
                    .metadata(metadata)
                    .build();
            // Use a broader mock for the copy source head request
            when(clientA.headObject(argThat((HeadObjectRequest req) ->
                    req != null && "file2.txt".equals(req.key()) && "node-a1".equals(req.bucket()))))
                    .thenReturn(headResponse);

            ResponseInputStream<GetObjectResponse> mockStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream("test content".getBytes())
            );
            when(clientA.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);
            when(clientB.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenAnswer(invocation -> {
                        RequestBody body = invocation.getArgument(1);
                        try (var input = body.contentStreamProvider().newStream()) {
                            assertThat(input.readAllBytes()).containsExactly("test content".getBytes());
                        }
                        return PutObjectResponse.builder().eTag("\"target-etag\"").build();
                    });

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isEqualTo(2);
            assertThat(stats.filesRepaired).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should skip canonical and malformed direct-upload staging objects in both directions")
        void shouldSkipDirectUploadStagingObjectsInBothDirections() {
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

            when(clientA.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                    ListObjectsV2Response.builder()
                            .contents(Arrays.asList(
                                    S3Object.builder()
                                            .key("tenant/7/staging/direct-upload/session-1/part-0")
                                            .build(),
                                    S3Object.builder()
                                            .key("tenant/7/staging/direct-upload/session-1/not-a-part")
                                            .build()
                            ))
                            .isTruncated(false)
                            .build()
            );
            when(clientB.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                    ListObjectsV2Response.builder()
                            .contents(Arrays.asList(
                                    S3Object.builder()
                                            .key("tenant/8/staging/direct-upload/session-2/part-1")
                                            .build(),
                                    S3Object.builder()
                                            .key("tenant/not-a-number/staging/direct-upload/broken")
                                            .build(),
                                    S3Object.builder()
                                            .key("tenant//staging/direct-upload/missing-tenant")
                                            .build()
                            ))
                            .isTruncated(false)
                            .build()
            );

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isEqualTo(2);
            assertThat(stats.filesChecked).isZero();
            assertThat(stats.filesRepaired).isZero();
            assertThat(stats.failureCount).isZero();
            verify(clientA, never()).headObject(any(HeadObjectRequest.class));
            verify(clientB, never()).headObject(any(HeadObjectRequest.class));
            verify(clientA, never()).getObject(any(GetObjectRequest.class));
            verify(clientB, never()).getObject(any(GetObjectRequest.class));
            verify(clientA, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            verify(clientB, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("Should skip staging across pages and still repair a final content-addressed object")
        void shouldSkipStagingAcrossPagesAndRepairFinalObject() {
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

            String stagingKey = "tenant/7/staging/direct-upload/session-1/part-0";
            String malformedStagingKey = "tenant/7/staging/direct-upload/session-1/part-x";
            byte[] content = "final-content".getBytes();
            String finalKey = "tenant/7/sha256:" + sha256Hex(content);
            Map<String, String> metadata = Map.of("file-hash", finalKey.substring("tenant/7/".length()),
                    "tenant-id", "7");

            when(clientA.listObjectsV2(any(ListObjectsV2Request.class)))
                    .thenReturn(ListObjectsV2Response.builder()
                            .contents(Arrays.asList(
                                    S3Object.builder().key(stagingKey).build(),
                                    S3Object.builder().key(malformedStagingKey).build()
                            ))
                            .isTruncated(true)
                            .nextContinuationToken("page-2")
                            .build())
                    .thenReturn(ListObjectsV2Response.builder()
                            .contents(List.of(S3Object.builder().key(finalKey).build()))
                            .isTruncated(false)
                            .build());
            when(clientB.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                    ListObjectsV2Response.builder()
                            .contents(List.of())
                            .isTruncated(false)
                            .build()
            );

            HeadObjectResponse sourceHead = HeadObjectResponse.builder()
                    .contentLength((long) content.length)
                    .contentType("application/octet-stream")
                    .eTag("\"source-etag\"")
                    .metadata(metadata)
                    .build();
            HeadObjectResponse targetHead = HeadObjectResponse.builder()
                    .contentLength((long) content.length)
                    .contentType("application/octet-stream")
                    .eTag("\"target-etag\"")
                    .metadata(metadata)
                    .build();
            when(clientB.headObject(argThat((HeadObjectRequest request) ->
                    request != null && finalKey.equals(request.key()))))
                    .thenThrow(NoSuchKeyException.builder().message("Not found").build())
                    .thenReturn(targetHead);
            when(clientA.headObject(argThat((HeadObjectRequest request) ->
                    request != null && finalKey.equals(request.key()))))
                    .thenReturn(sourceHead);
            when(clientA.getObject(argThat((GetObjectRequest request) ->
                    request != null && finalKey.equals(request.key()))))
                    .thenAnswer(invocation -> new ResponseInputStream<>(
                            GetObjectResponse.builder().build(),
                            new ByteArrayInputStream(content)
                    ));
            when(clientB.getObject(argThat((GetObjectRequest request) ->
                    request != null && finalKey.equals(request.key()))))
                    .thenReturn(new ResponseInputStream<>(
                            GetObjectResponse.builder().build(),
                            new ByteArrayInputStream(content)
                    ));
            when(clientB.putObject(argThat((PutObjectRequest request) ->
                            request != null && finalKey.equals(request.key())),
                    any(RequestBody.class)))
                    .thenAnswer(invocation -> {
                        RequestBody body = invocation.getArgument(1);
                        try (var input = body.contentStreamProvider().newStream()) {
                            assertThat(input.readAllBytes()).containsExactly(content);
                        }
                        return PutObjectResponse.builder().eTag("\"target-etag\"").build();
                    });

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isEqualTo(2);
            assertThat(stats.filesChecked).isEqualTo(1);
            assertThat(stats.filesRepaired).isEqualTo(1);
            assertThat(stats.failureCount).isZero();
            verify(clientA, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
            verify(clientB, times(2)).headObject(argThat((HeadObjectRequest request) ->
                    request != null && finalKey.equals(request.key())));
            verify(clientA, never()).headObject(argThat((HeadObjectRequest request) ->
                    request != null && request.key().contains("/staging/direct-upload")));
            verify(clientB, never()).headObject(argThat((HeadObjectRequest request) ->
                    request != null && request.key().contains("/staging/direct-upload")));
            verify(clientA, times(2)).getObject(argThat((GetObjectRequest request) ->
                    request != null && finalKey.equals(request.key())
                            && "\"source-etag\"".equals(request.ifMatch())));
            verify(clientB).getObject(argThat((GetObjectRequest request) ->
                    request != null && finalKey.equals(request.key())
                            && "\"target-etag\"".equals(request.ifMatch())));
            verify(clientB).putObject(argThat((PutObjectRequest request) ->
                            request != null && finalKey.equals(request.key())),
                    any(RequestBody.class));
        }

        @Test
        @DisplayName("Should skip directory entries")
        void shouldSkipDirectoryEntries() {
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

            // Domain A has a directory entry and a file
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

            // file1 exists in both domains
            when(clientB.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder().build());
            when(clientA.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder().build());

            ConsistencyRepairService.RepairStatistics stats = service.repairAllDomains();

            assertThat(stats.domainsChecked).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle pagination when listing objects")
        void shouldHandlePaginationWhenListingObjects() {
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

            // Domain A: paginated response
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

            // Domain B has both files
            ListObjectsV2Response responseB = ListObjectsV2Response.builder()
                    .contents(Arrays.asList(
                            S3Object.builder().key("file1.txt").build(),
                            S3Object.builder().key("file2.txt").build()
                    ))
                    .isTruncated(false)
                    .build();
            when(clientB.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(responseB);

            // All objects exist in the other domain
            when(clientB.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder().build());
            when(clientA.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder().build());

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

            // file.txt exists in both domains
            when(clientB.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder().build());
            when(clientA.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(HeadObjectResponse.builder().build());

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

        /**
         * 验证 source/target 共享物理身份时在健康检查、客户端解析和任务调度前失败关闭。
         */
        @Test
        @DisplayName("Immediate repair should reject source and target on the same physical storage")
        void shouldRejectImmediateRepairAcrossPhysicalAliases() throws Exception {
            when(faultDomainManager.areNodesOnIndependentPhysicalStorage(
                    "source-node",
                    "target-node"
            )).thenReturn(false);

            ConsistencyRepairService.ImmediateRepairResult result =
                    service.scheduleImmediateRepairByNodesDetailedAsync(
                            "test-object",
                            "source-node",
                            "target-node"
                    ).get();

            assertThat(result.status())
                    .isEqualTo(ConsistencyRepairService.ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
            verifyNoInteractions(s3Monitor, clientManager);
        }

        /**
         * 验证复制期间物理身份漂移后，已写入对象仍不能被报告为独立副本修复成功。
         */
        @Test
        @DisplayName("Physical topology drift after copy should not report repair success")
        void shouldFailClosedWhenPhysicalTopologyDriftsAfterCopy() throws Exception {
            when(faultDomainManager.areNodesOnIndependentPhysicalStorage(
                    "source-node",
                    "target-node"
            )).thenReturn(true, true, false);
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            S3Client sourceClient = mock(S3Client.class);
            S3Client targetClient = mock(S3Client.class);
            when(clientManager.getClient("source-node")).thenReturn(sourceClient);
            when(clientManager.getClient("target-node")).thenReturn(targetClient);
            mockBucketExists(targetClient, "target-node");
            stubSuccessfulObjectTransfer(sourceClient, targetClient, "test".getBytes());

            ConsistencyRepairService.ImmediateRepairResult result =
                    service.scheduleImmediateRepairByNodesDetailedAsync(
                            "test-object",
                            "source-node",
                            "target-node"
                    ).get();

            assertThat(result.status())
                    .isEqualTo(ConsistencyRepairService.ImmediateRepairStatus.PREREQUISITE_UNAVAILABLE);
            verify(targetClient).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        /**
         * 验证配置拓扑在复制完成前换代时，修复结果失败关闭且旧拓扑租约必定释放。
         */
        @Test
        @DisplayName("S3 topology drift after copy should not report repair success")
        void shouldFailClosedAndReleaseLeaseWhenS3TopologyDriftsAfterCopy() throws Exception {
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            S3Client sourceClient = mock(S3Client.class);
            S3Client targetClient = mock(S3Client.class);
            when(clientManager.getClient("source-node")).thenReturn(sourceClient);
            when(clientManager.getClient("target-node")).thenReturn(targetClient);
            mockBucketExists(targetClient, "target-node");
            stubSuccessfulObjectTransfer(sourceClient, targetClient, "test".getBytes());
            doThrow(new IllegalStateException("S3 topology changed during operation"))
                    .when(topologyLease)
                    .runIfCurrent(any(Runnable.class));

            ConsistencyRepairService.ImmediateRepairResult result =
                    service.scheduleImmediateRepairByNodesDetailedAsync(
                            "test-object",
                            "source-node",
                            "target-node"
                    ).get();

            assertThat(result.status())
                    .isEqualTo(ConsistencyRepairService.ImmediateRepairStatus.COPY_FAILED);
            verify(targetClient, times(3))
                    .putObject(any(PutObjectRequest.class), any(RequestBody.class));
            verify(topologyLease, times(3)).close();
        }

        @Test
        @DisplayName("Should apply per-request timeouts and remove the abort task after normal close")
        void shouldScheduleImmediateRepairWithTimeoutsAndReleaseAbortTask() throws Exception {
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            ScheduledThreadPoolExecutor abortExecutor =
                    (ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(
                            ConsistencyRepairService.class,
                            "STREAM_ABORT_EXECUTOR"
                    );
            assertThat(abortExecutor).isNotNull();
            assertThat(abortExecutor.getRemoveOnCancelPolicy()).isTrue();
            int queuedBefore = abortExecutor.getQueue().size();

            S3Client sourceClient = mock(S3Client.class);
            S3Client targetClient = mock(S3Client.class);
            when(clientManager.getClient("source-node")).thenReturn(sourceClient);
            when(clientManager.getClient("target-node")).thenReturn(targetClient);

            mockBucketExists(targetClient, "target-node");

            HeadObjectResponse headResponse = HeadObjectResponse.builder()
                    .contentLength(4L)
                    .contentType("text/plain")
                    .eTag("\"source-etag\"")
                    .metadata(Map.of("file-hash", "sha256:test", "tenant-id", "7"))
                    .build();
            when(sourceClient.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse);
            when(targetClient.headObject(any(HeadObjectRequest.class))).thenReturn(
                    HeadObjectResponse.builder()
                            .contentLength(4L)
                            .contentType("text/plain")
                            .eTag("\"target-etag\"")
                            .metadata(headResponse.metadata())
                            .build()
            );

            ResponseInputStream<GetObjectResponse> mockStream = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream("test".getBytes())
            );
            when(sourceClient.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);
            when(targetClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenAnswer(invocation -> {
                        RequestBody body = invocation.getArgument(1);
                        try (var input = body.contentStreamProvider().newStream()) {
                            assertThat(input.readAllBytes()).containsExactly("test".getBytes());
                        }
                        return PutObjectResponse.builder().eTag("\"target-etag\"").build();
                    });

            boolean repaired = service.scheduleImmediateRepairByNodesAsync(
                    "test-object", "source-node", "target-node").get();

            assertThat(repaired).isTrue();
            verify(clientManager).getClient("source-node");
            verify(clientManager).getClient("target-node");
            assertThat(abortExecutor.getQueue()).hasSize(queuedBefore);

            ArgumentCaptor<HeadBucketRequest> headBucket =
                    ArgumentCaptor.forClass(HeadBucketRequest.class);
            ArgumentCaptor<HeadObjectRequest> sourceHead =
                    ArgumentCaptor.forClass(HeadObjectRequest.class);
            ArgumentCaptor<GetObjectRequest> getObject =
                    ArgumentCaptor.forClass(GetObjectRequest.class);
            ArgumentCaptor<PutObjectRequest> putObject =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            ArgumentCaptor<HeadObjectRequest> finalHead =
                    ArgumentCaptor.forClass(HeadObjectRequest.class);
            verify(targetClient).headBucket(headBucket.capture());
            verify(sourceClient).headObject(sourceHead.capture());
            verify(sourceClient).getObject(getObject.capture());
            verify(targetClient).putObject(putObject.capture(), any(RequestBody.class));
            verify(targetClient).headObject(finalHead.capture());
            assertRequestTimeouts(headBucket.getValue());
            assertRequestTimeouts(sourceHead.getValue());
            assertRequestTimeouts(getObject.getValue());
            assertRequestTimeouts(putObject.getValue());
            assertRequestTimeouts(finalHead.getValue());
        }

        @Test
        @DisplayName("Should apply request timeouts when creating a missing target bucket")
        void shouldApplyTimeoutsToCreateBucket() throws Exception {
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            S3Client sourceClient = mock(S3Client.class);
            S3Client targetClient = mock(S3Client.class);
            when(clientManager.getClient("source-node")).thenReturn(sourceClient);
            when(clientManager.getClient("target-node")).thenReturn(targetClient);
            when(targetClient.headBucket(any(HeadBucketRequest.class)))
                    .thenThrow(NoSuchBucketException.builder().message("missing").build());
            when(targetClient.createBucket(any(CreateBucketRequest.class)))
                    .thenReturn(CreateBucketResponse.builder().build());
            stubSuccessfulObjectTransfer(sourceClient, targetClient, "test".getBytes());

            ConsistencyRepairService.ImmediateRepairResult result =
                    service.scheduleImmediateRepairByNodesDetailedAsync(
                            "test-object", "source-node", "target-node").get();

            assertThat(result.status())
                    .isEqualTo(ConsistencyRepairService.ImmediateRepairStatus.SUCCEEDED);
            ArgumentCaptor<CreateBucketRequest> createBucket =
                    ArgumentCaptor.forClass(CreateBucketRequest.class);
            verify(targetClient).createBucket(createBucket.capture());
            assertRequestTimeouts(createBucket.getValue());
        }

        @Test
        @DisplayName("Canonical target bytes should fail repair when SHA-256 differs from object key")
        void shouldReturnCopyFailedWhenCanonicalTargetBytesAreCorrupt() throws Exception {
            byte[] expected = "good".getBytes();
            byte[] corrupt = "evil".getBytes();
            String objectName = "tenant/7/sha256:" + sha256Hex(expected);
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            S3Client sourceClient = mock(S3Client.class);
            S3Client targetClient = mock(S3Client.class);
            when(clientManager.getClient("source-node")).thenReturn(sourceClient);
            when(clientManager.getClient("target-node")).thenReturn(targetClient);
            mockBucketExists(targetClient, "target-node");

            HeadObjectResponse sourceHead = validHead(expected.length);
            when(sourceClient.headObject(any(HeadObjectRequest.class))).thenReturn(sourceHead);
            when(targetClient.headObject(any(HeadObjectRequest.class))).thenReturn(
                    HeadObjectResponse.builder()
                            .contentLength((long) corrupt.length)
                            .contentType("text/plain")
                            .eTag("\"target-etag\"")
                            .metadata(sourceHead.metadata())
                            .build()
            );
            when(sourceClient.getObject(any(GetObjectRequest.class))).thenAnswer(invocation ->
                    new ResponseInputStream<>(
                            GetObjectResponse.builder().build(),
                            new ByteArrayInputStream(expected)
                    ));
            when(targetClient.getObject(any(GetObjectRequest.class))).thenAnswer(invocation ->
                    new ResponseInputStream<>(
                            GetObjectResponse.builder().build(),
                            new ByteArrayInputStream(corrupt)
                    ));
            when(targetClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenAnswer(invocation -> {
                        RequestBody body = invocation.getArgument(1);
                        try (InputStream input = body.contentStreamProvider().newStream()) {
                            assertThat(input.readAllBytes()).containsExactly(expected);
                        }
                        return PutObjectResponse.builder().eTag("\"target-etag\"").build();
                    });

            ConsistencyRepairService.ImmediateRepairResult result =
                    service.scheduleImmediateRepairByNodesDetailedAsync(
                            objectName,
                            "source-node",
                            "target-node"
                    ).get(6, TimeUnit.SECONDS);

            assertThat(result.status())
                    .isEqualTo(ConsistencyRepairService.ImmediateRepairStatus.COPY_FAILED);
            ArgumentCaptor<GetObjectRequest> targetGet = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(targetClient, times(3)).getObject(targetGet.capture());
            assertThat(targetGet.getAllValues()).allSatisfy(request -> {
                assertThat(request.key()).isEqualTo(objectName);
                assertThat(request.ifMatch()).isEqualTo("\"target-etag\"");
                assertRequestTimeouts(request);
            });
        }

        /**
         * 验证 canonical 源对象内容损坏时在任何目标桶访问或写入前确定性失败。
         */
        @Test
        @DisplayName("Canonical corrupt source should fail before any target-side operation")
        void shouldReturnCopyFailedWithoutTargetWriteWhenCanonicalSourceIsCorrupt() throws Exception {
            byte[] expected = "declared-content".getBytes();
            byte[] corrupt = "corrupted-source".getBytes();
            String objectName = "tenant/7/sha256:" + sha256Hex(expected);
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            S3Client sourceClient = mock(S3Client.class);
            S3Client targetClient = mock(S3Client.class);
            when(clientManager.getClient("source-node")).thenReturn(sourceClient);
            when(clientManager.getClient("target-node")).thenReturn(targetClient);
            when(sourceClient.headObject(any(HeadObjectRequest.class)))
                    .thenReturn(validHead(corrupt.length));
            when(sourceClient.getObject(any(GetObjectRequest.class))).thenAnswer(invocation ->
                    new ResponseInputStream<>(
                            GetObjectResponse.builder().build(),
                            new ByteArrayInputStream(corrupt)
                    ));

            ConsistencyRepairService.ImmediateRepairResult result =
                    service.scheduleImmediateRepairByNodesDetailedAsync(
                            objectName,
                            "source-node",
                            "target-node"
                    ).get(6, TimeUnit.SECONDS);

            assertThat(result.status())
                    .isEqualTo(ConsistencyRepairService.ImmediateRepairStatus.COPY_FAILED);
            ArgumentCaptor<GetObjectRequest> sourceGet = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(sourceClient, times(3)).getObject(sourceGet.capture());
            assertThat(sourceGet.getAllValues()).allSatisfy(request -> {
                assertThat(request.key()).isEqualTo(objectName);
                assertThat(request.ifMatch()).isEqualTo("\"source-etag\"");
                assertRequestTimeouts(request);
            });
            verifyNoInteractions(targetClient);
        }

        @Test
        @DisplayName("Should actively abort a blocking GET body at the absolute deadline")
        void shouldAbortBlockingGetBodyAtDeadline() throws Exception {
            degradedConfig.setRepairTimeoutSeconds(1);
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            S3Client sourceClient = mock(S3Client.class);
            S3Client targetClient = mock(S3Client.class);
            when(clientManager.getClient("source-node")).thenReturn(sourceClient);
            when(clientManager.getClient("target-node")).thenReturn(targetClient);
            mockBucketExists(targetClient, "target-node");
            HeadObjectResponse sourceHead = validHead(4L);
            when(sourceClient.headObject(any(HeadObjectRequest.class))).thenReturn(sourceHead);
            BlockingAbortInputStream blocking = new BlockingAbortInputStream();
            ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    AbortableInputStream.create(blocking, blocking::abort)
            );
            when(sourceClient.getObject(any(GetObjectRequest.class))).thenReturn(response);
            when(targetClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenAnswer(invocation -> {
                        RequestBody body = invocation.getArgument(1);
                        try (InputStream input = body.contentStreamProvider().newStream()) {
                            input.readAllBytes();
                        }
                        return PutObjectResponse.builder().eTag("\"target-etag\"").build();
                    });

            CompletableFuture<ConsistencyRepairService.ImmediateRepairResult> future =
                    service.scheduleImmediateRepairByNodesDetailedAsync(
                            "test-object", "source-node", "target-node");
            ConsistencyRepairService.ImmediateRepairResult result = future.get(3, TimeUnit.SECONDS);

            assertThat(result.status())
                    .isEqualTo(ConsistencyRepairService.ImmediateRepairStatus.RETRYABLE_DEFERRED);
            assertThat(blocking.aborted()).isTrue();
            assertThat(blocking.closed()).isTrue();
        }

        @Test
        @DisplayName("Should abort and close a response returned after the repair deadline")
        void shouldReleaseResponseWhenDeadlineExpiresAfterGetReturns() throws Exception {
            degradedConfig.setRepairTimeoutSeconds(1);
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            S3Client sourceClient = mock(S3Client.class);
            S3Client targetClient = mock(S3Client.class);
            when(clientManager.getClient("source-node")).thenReturn(sourceClient);
            when(clientManager.getClient("target-node")).thenReturn(targetClient);
            mockBucketExists(targetClient, "target-node");
            when(sourceClient.headObject(any(HeadObjectRequest.class))).thenReturn(validHead(4L));
            AtomicBoolean aborted = new AtomicBoolean(false);
            AtomicBoolean closed = new AtomicBoolean(false);
            when(sourceClient.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
                Thread.sleep(1_100);
                return trackingResponse("test".getBytes(), aborted, closed);
            });
            consumeRequestBody(targetClient);

            ConsistencyRepairService.ImmediateRepairResult result =
                    service.scheduleImmediateRepairByNodesDetailedAsync(
                            "test-object", "source-node", "target-node")
                            .get(3, TimeUnit.SECONDS);

            assertThat(result.status())
                    .isEqualTo(ConsistencyRepairService.ImmediateRepairStatus.RETRYABLE_DEFERRED);
            assertThat(aborted).isTrue();
            assertThat(closed).isTrue();
        }

        @Test
        @DisplayName("Should abort and close a GET response when deadline scheduling is rejected")
        void shouldReleaseResponseWhenAbortSchedulingIsRejected() throws Exception {
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            S3Client sourceClient = mock(S3Client.class);
            S3Client targetClient = mock(S3Client.class);
            when(clientManager.getClient("source-node")).thenReturn(sourceClient);
            when(clientManager.getClient("target-node")).thenReturn(targetClient);
            mockBucketExists(targetClient, "target-node");
            when(sourceClient.headObject(any(HeadObjectRequest.class))).thenReturn(validHead(4L));
            AtomicBoolean aborted = new AtomicBoolean(false);
            AtomicBoolean closed = new AtomicBoolean(false);
            when(sourceClient.getObject(any(GetObjectRequest.class)))
                    .thenReturn(trackingResponse("test".getBytes(), aborted, closed));
            consumeRequestBody(targetClient);
            ScheduledThreadPoolExecutor rejectingExecutor = new ScheduledThreadPoolExecutor(1);
            rejectingExecutor.shutdownNow();
            ReflectionTestUtils.setField(service, "streamAbortExecutor", rejectingExecutor);

            ConsistencyRepairService.ImmediateRepairResult result =
                    service.scheduleImmediateRepairByNodesDetailedAsync(
                            "test-object", "source-node", "target-node")
                            .get(3, TimeUnit.SECONDS);

            assertThat(result.status())
                    .isEqualTo(ConsistencyRepairService.ImmediateRepairStatus.RETRYABLE_DEFERRED);
            assertThat(aborted).isTrue();
            assertThat(closed).isTrue();
        }

        @Test
        @DisplayName("Should skip repair when source node offline")
        void shouldSkipRepairWhenSourceNodeOffline() throws Exception {
            when(s3Monitor.isNodeOnline("source-node")).thenReturn(false);

            boolean repaired = service.scheduleImmediateRepairByNodesAsync(
                    "test-object", "source-node", "target-node").get();

            assertThat(repaired).isFalse();
            verify(clientManager, never()).getClient(anyString());
        }

        @Test
        @DisplayName("Should reject direct-upload staging before scheduling immediate repair")
        void shouldRejectStagingBeforeSchedulingImmediateRepair() throws Exception {
            boolean repaired = service.scheduleImmediateRepairByNodesAsync(
                    "tenant/7/staging/direct-upload/session-1/part-0",
                    "source-node",
                    "target-node"
            ).get();

            assertThat(repaired).isFalse();
            verifyNoInteractions(s3Monitor);
            verifyNoInteractions(clientManager);
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

    /**
     * 配置一个元数据、流内容和最终校验均成功的对象复制。
     */
    private void stubSuccessfulObjectTransfer(
            S3Client sourceClient,
            S3Client targetClient,
            byte[] content
    ) {
        HeadObjectResponse sourceHead = validHead(content.length);
        when(sourceClient.headObject(any(HeadObjectRequest.class))).thenReturn(sourceHead);
        when(targetClient.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder()
                        .contentLength((long) content.length)
                        .contentType("text/plain")
                        .eTag("\"target-etag\"")
                        .metadata(sourceHead.metadata())
                        .build()
        );
        when(sourceClient.getObject(any(GetObjectRequest.class))).thenAnswer(invocation ->
                new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(content)
                ));
        when(targetClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    try (InputStream input = body.contentStreamProvider().newStream()) {
                        assertThat(input.readAllBytes()).containsExactly(content);
                    }
                    return PutObjectResponse.builder().eTag("\"target-etag\"").build();
                });
    }

    /**
     * 返回满足条件复制要求的源对象元数据。
     */
    private HeadObjectResponse validHead(long contentLength) {
        return HeadObjectResponse.builder()
                .contentLength(contentLength)
                .contentType("text/plain")
                .eTag("\"source-etag\"")
                .metadata(Map.of("file-hash", "sha256:test", "tenant-id", "7"))
                .build();
    }

    /**
     * 计算 content-addressed 测试对象使用的 SHA-256 十六进制值。
     */
    private String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 让目标 PUT 真正打开并消费请求体，从而覆盖流式 GET 生命周期。
     */
    private void consumeRequestBody(S3Client targetClient) {
        when(targetClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    try (InputStream input = body.contentStreamProvider().newStream()) {
                        input.readAllBytes();
                    }
                    return PutObjectResponse.builder().eTag("\"target-etag\"").build();
                });
    }

    /**
     * 创建可分别观测 abort 与 close 的 provider 响应流。
     */
    private ResponseInputStream<GetObjectResponse> trackingResponse(
            byte[] content,
            AtomicBoolean aborted,
            AtomicBoolean closed
    ) {
        TrackingCloseInputStream tracking = new TrackingCloseInputStream(content, closed);
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(tracking, () -> aborted.set(true))
        );
    }

    /**
     * 断言 AWS 请求同时配置了整次 API call 和单次 attempt 超时。
     */
    private void assertRequestTimeouts(AwsRequest request) {
        AwsRequestOverrideConfiguration override = request.overrideConfiguration().orElseThrow();
        assertThat(override.apiCallTimeout()).isPresent();
        assertThat(override.apiCallAttemptTimeout()).isPresent();
        assertThat(override.apiCallAttemptTimeout().orElseThrow())
                .isLessThanOrEqualTo(override.apiCallTimeout().orElseThrow());
    }

    private void mockBucketExists(S3Client client, String bucketName) {
        when(client.headBucket(argThat((HeadBucketRequest req) ->
                req != null && bucketName.equals(req.bucket()))))
                .thenReturn(HeadBucketResponse.builder().build());
    }

    /**
     * 记录正常 close 的内存输入流。
     */
    private static final class TrackingCloseInputStream extends ByteArrayInputStream {
        private final AtomicBoolean closed;

        private TrackingCloseInputStream(byte[] content, AtomicBoolean closed) {
            super(content);
            this.closed = closed;
        }

        /**
         * 记录底层 provider 流已经关闭。
         */
        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

    /**
     * 在 abort 或 close 前持续阻塞读取，用于验证绝对 deadline 主动解除阻塞。
     */
    private static final class BlockingAbortInputStream extends InputStream {
        private boolean released;
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * 等待 provider 被中止或关闭后结束读取。
         */
        @Override
        public synchronized int read() throws IOException {
            while (!released) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("blocking stream interrupted", e);
                }
            }
            return -1;
        }

        /**
         * 模拟底层 HTTP 响应 abort 并唤醒阻塞读。
         */
        private synchronized void abort() {
            aborted.set(true);
            released = true;
            notifyAll();
        }

        /**
         * 记录关闭并保证阻塞读可以退出。
         */
        @Override
        public synchronized void close() {
            closed.set(true);
            released = true;
            notifyAll();
        }

        private boolean aborted() {
            return aborted.get();
        }

        private boolean closed() {
            return closed.get();
        }
    }
}
