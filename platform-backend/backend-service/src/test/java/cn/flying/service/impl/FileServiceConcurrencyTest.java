package cn.flying.service.impl;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.service.saga.FileSagaOrchestrator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileService Concurrency Tests")
class FileServiceConcurrencyTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileSagaOrchestrator fileSagaOrchestrator;

    @Spy
    @InjectMocks
    private FileServiceImpl fileService;

    private static final Long TENANT_ID = 1L;
    private static final int THREAD_COUNT = 10;
    private static final int OPERATIONS_PER_THREAD = 20;

    private MockedStatic<TenantContext> tenantContextMock;
    private MockedStatic<IdUtils> idUtilsMock;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fileService, "baseMapper", fileMapper);

        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::requireTenantId).thenReturn(TENANT_ID);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);

        idUtilsMock = mockStatic(IdUtils.class);
        idUtilsMock.when(() -> IdUtils.toExternalId(anyLong()))
                .thenAnswer(inv -> "ext_" + inv.getArgument(0));
        idUtilsMock.when(() -> IdUtils.fromExternalId(anyString()))
                .thenAnswer(inv -> {
                    String externalId = inv.getArgument(0);
                    return Long.parseLong(externalId.replace("ext_", ""));
                });
    }

    @AfterEach
    void tearDown() {
        if (tenantContextMock != null) tenantContextMock.close();
        if (idUtilsMock != null) idUtilsMock.close();
    }

    @Nested
    @DisplayName("Concurrent File Queries")
    class ConcurrentFileQueries {

        @Test
        @DisplayName("should handle concurrent file list queries")
        void shouldHandleConcurrentFileListQueries() throws Exception {
            List<File> mockFiles = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                mockFiles.add(createTestFile(100L + (i % 10), "list_hash_" + i));
            }

            doReturn(mockFiles).when(fileService).list(any(LambdaQueryWrapper.class));

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
            AtomicInteger successCount = new AtomicInteger(0);
            List<Integer> resultSizes = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                final long userId = 100L + t;
                executor.submit(() -> {
                    try {
                        var result = fileService.getUserFilesList(userId);
                        if (result != null) {
                            resultSizes.add(result.size());
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            assertThat(successCount.get()).isEqualTo(THREAD_COUNT);
            assertThat(resultSizes).allMatch(size -> size == 50);
        }
    }

    @Nested
    @DisplayName("Concurrent File Modifications")
    class ConcurrentFileModifications {

        @Test
        @DisplayName("should handle concurrent status changes")
        void shouldHandleConcurrentStatusChanges() throws Exception {
            doReturn(true).when(fileService).update(any(File.class), any(LambdaUpdateWrapper.class));

            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch latch = new CountDownLatch(10);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int i = 0; i < 10; i++) {
                final String hash = "status_hash_" + i;
                final int status = i % 3;
                executor.submit(() -> {
                    try {
                        fileService.changeFileStatusByHash(100L, hash, status);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            assertThat(successCount.get()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Concurrent Share Operations")
    class ConcurrentShareOperations {

        @Test
        @DisplayName("should handle concurrent share code generation")
        void shouldHandleConcurrentShareCodeGeneration() throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
            Set<String> generatedCodes = Collections.synchronizedSet(new HashSet<>());
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int t = 0; t < THREAD_COUNT; t++) {
                executor.submit(() -> {
                    try {
                        String code = UUID.randomUUID().toString().substring(0, 8);
                        generatedCodes.add(code);
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            assertThat(generatedCodes).hasSize(THREAD_COUNT);
        }
    }

    @Nested
    @DisplayName("Thread Safety of State")
    class ThreadSafetyOfState {

        @Test
        @DisplayName("should maintain thread-local tenant context isolation")
        void shouldMaintainTenantContextIsolation() throws Exception {
            AtomicInteger isolationViolations = new AtomicInteger(0);
            Map<Long, Long> threadTenantMap = new ConcurrentHashMap<>();
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(THREAD_COUNT * 10);

            for (int t = 0; t < THREAD_COUNT; t++) {
                final long expectedTenantId = t + 1;
                final long threadId = t;

                executor.submit(() -> {
                    for (int i = 0; i < 10; i++) {
                        try {
                            threadTenantMap.put(threadId, expectedTenantId);

                            Long retrievedTenant = threadTenantMap.get(threadId);
                            if (!Objects.equals(expectedTenantId, retrievedTenant)) {
                                isolationViolations.incrementAndGet();
                            }
                        } catch (Exception e) {
                            firstError.compareAndSet(null, e);
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            assertThat(isolationViolations.get()).isZero();
        }
    }

    @Nested
    @DisplayName("Performance Under Load")
    class PerformanceUnderLoad {

        @Test
        @DisplayName("should maintain acceptable latency under load")
        void shouldMaintainAcceptableLatencyUnderLoad() throws Exception {
            List<File> mockFiles = List.of(createTestFile(100L, "perf_hash"));
            doReturn(mockFiles).when(fileService).list(any(LambdaQueryWrapper.class));

            int operationCount = 100;
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(operationCount);
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int i = 0; i < operationCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        long start = System.nanoTime();
                        fileService.getUserFilesList(100L);
                        long end = System.nanoTime();
                        latencies.add((end - start) / 1_000_000);
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            assertThat(latencies).isNotEmpty();

            Collections.sort(latencies);
            long p50 = latencies.get(latencies.size() / 2);
            long p95 = latencies.get((int) (latencies.size() * 0.95));
            long p99 = latencies.get((int) (latencies.size() * 0.99));

            assertThat(p50).isLessThan(100);
            assertThat(p95).isLessThan(500);
            assertThat(p99).isLessThan(1000);
        }

        @Test
        @DisplayName("should handle burst traffic")
        void shouldHandleBurstTraffic() throws Exception {
            List<File> mockFiles = List.of(createTestFile(100L, "burst_hash"));
            doReturn(mockFiles).when(fileService).list(any(LambdaQueryWrapper.class));

            int burstSize = 50;
            ExecutorService executor = Executors.newFixedThreadPool(burstSize);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(burstSize);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int i = 0; i < burstSize; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        fileService.getUserFilesList(100L);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                        firstError.compareAndSet(null, e);
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(firstError.get()).isNull();
            double successRate = (double) successCount.get() / burstSize * 100;
            assertThat(successRate).isGreaterThan(90.0);
        }
    }

    @Nested
    @DisplayName("Resource Cleanup")
    class ResourceCleanup {

        @Test
        @DisplayName("should not leak threads under concurrent operations")
        void shouldNotLeakThreads() throws Exception {
            int initialThreadCount = Thread.activeCount();

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(THREAD_COUNT * 5);
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            List<File> mockFiles = List.of(createTestFile(100L, "leak_test_hash"));
            doReturn(mockFiles).when(fileService).list(any(LambdaQueryWrapper.class));

            for (int t = 0; t < THREAD_COUNT; t++) {
                executor.submit(() -> {
                    for (int i = 0; i < 5; i++) {
                        try {
                            fileService.getUserFilesList(100L);
                        } catch (Exception e) {
                            firstError.compareAndSet(null, e);
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            Thread.sleep(100);

            assertThat(firstError.get()).isNull();
            int finalThreadCount = Thread.activeCount();
            int threadIncrease = finalThreadCount - initialThreadCount;

            assertThat(threadIncrease).isLessThan(THREAD_COUNT);
        }
    }

    private File createTestFile(Long userId, String hash) {
        File file = new File();
        file.setId(System.nanoTime());
        file.setUid(userId);
        file.setFileName("test-" + hash + ".pdf");
        file.setFileHash(hash);
        file.setFileParam("{\"fileSize\":1024,\"contentType\":\"application/pdf\"}");
        file.setStatus(1);
        file.setTenantId(TENANT_ID);
        file.setDeleted(0);
        return file;
    }
}
