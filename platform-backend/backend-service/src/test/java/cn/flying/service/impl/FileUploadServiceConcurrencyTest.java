package cn.flying.service.impl;

import cn.flying.common.util.UidEncoder;
import cn.flying.dao.vo.file.FileUploadState;
import cn.flying.dao.vo.file.ProgressVO;
import cn.flying.dao.vo.file.StartUploadVO;
import cn.flying.service.FileService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.service.encryption.EncryptionStrategyFactory;
import cn.flying.test.builders.FileUploadStateTestBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileUploadService Concurrency Tests")
class FileUploadServiceConcurrencyTest {

    @Mock
    private FileUploadRedisStateManager redisStateManager;

    @Mock
    private EncryptionStrategyFactory encryptionStrategyFactory;

    @Mock
    private FileService fileService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private FileUploadServiceImpl fileUploadService;

    private static MockedStatic<UidEncoder> uidEncoderMock;

    private static final int THREAD_COUNT = 10;
    private static final int OPERATIONS_PER_THREAD = 50;

    @BeforeAll
    static void setUpClass() {
        uidEncoderMock = mockStatic(UidEncoder.class);
        uidEncoderMock.when(() -> UidEncoder.encodeUid(anyString()))
                .thenAnswer(inv -> "suid_" + inv.getArgument(0));
        uidEncoderMock.when(() -> UidEncoder.encodeCid(anyString()))
                .thenAnswer(inv -> "cid_" + UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterAll
    static void tearDownClass() {
        uidEncoderMock.close();
    }

    @BeforeEach
    void setUp() {
        FileUploadStateTestBuilder.resetClientIdCounter();
        ReflectionTestUtils.setField(fileUploadService, "eventPublisher", eventPublisher);
        // 让异步分片处理在测试中同步执行，避免线程池/时序不稳定
        ReflectionTestUtils.setField(fileUploadService, "fileProcessingExecutor", (java.util.concurrent.Executor) Runnable::run);
    }

    @Nested
    @DisplayName("Concurrent Upload Start")
    class ConcurrentUploadStart {

        @Test
        @DisplayName("should handle concurrent upload starts from same user")
        void shouldHandleConcurrentStartsFromSameUser() throws Exception {
            Long userId = 100L;
            when(redisStateManager.getSessionIdByFileClientKey(anyString(), anyString())).thenReturn(null);

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            List<String> clientIds = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < THREAD_COUNT; i++) {
                final int fileIndex = i;
                executor.submit(() -> {
                    try {
                        String fileName = "concurrent-test-" + fileIndex + ".pdf";
                        StartUploadVO result = fileUploadService.startUpload(
                                userId, fileName, 1024 * 1024, "application/pdf", null, 256 * 1024, 4);
                        if (result != null && result.getClientId() != null) {
                            clientIds.add(result.getClientId());
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(successCount.get()).isEqualTo(THREAD_COUNT);
            assertThat(failureCount.get()).isZero();
            assertThat(clientIds).hasSize(THREAD_COUNT);
            assertThat(new HashSet<>(clientIds)).hasSize(THREAD_COUNT);
        }

        @Test
        @DisplayName("should handle concurrent upload starts from different users")
        void shouldHandleConcurrentStartsFromDifferentUsers() throws Exception {
            when(redisStateManager.getSessionIdByFileClientKey(anyString(), anyString())).thenReturn(null);

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
            AtomicInteger successCount = new AtomicInteger(0);
            Map<Long, String> userClientIds = new ConcurrentHashMap<>();

            for (int i = 0; i < THREAD_COUNT; i++) {
                final long userId = 100L + i;
                executor.submit(() -> {
                    try {
                        StartUploadVO result = fileUploadService.startUpload(
                                userId, "user-" + userId + "-file.pdf", 1024 * 1024,
                                "application/pdf", null, 256 * 1024, 4);
                        if (result != null) {
                            userClientIds.put(userId, result.getClientId());
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Ignore
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(successCount.get()).isEqualTo(THREAD_COUNT);
            assertThat(userClientIds).hasSize(THREAD_COUNT);
        }
    }

    @Nested
    @DisplayName("Concurrent Progress Queries")
    class ConcurrentProgressQueries {

        @Test
        @DisplayName("should handle concurrent progress queries for same session")
        void shouldHandleConcurrentProgressQueries() throws Exception {
            Long userId = 100L;
            String clientId = "test_client_concurrent";

            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(10, 5, 3);
            ReflectionTestUtils.setField(state, "clientId", clientId);
            ReflectionTestUtils.setField(state, "userId", userId);

            when(redisStateManager.getState(clientId)).thenReturn(state);

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(OPERATIONS_PER_THREAD);
            AtomicInteger successCount = new AtomicInteger(0);
            List<ProgressVO> results = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                executor.submit(() -> {
                    try {
                        ProgressVO progress = fileUploadService.getUploadProgress(userId, clientId);
                        if (progress != null) {
                            results.add(progress);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Ignore
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(successCount.get()).isEqualTo(OPERATIONS_PER_THREAD);
            assertThat(results).allSatisfy(progress -> {
                assertThat(progress.getClientId()).isEqualTo(clientId);
                assertThat(progress.getTotalChunks()).isEqualTo(10);
            });
        }
    }

    @Nested
    @DisplayName("Concurrent Pause/Resume Operations")
    class ConcurrentPauseResume {

        @Test
        @DisplayName("should handle rapid pause/resume cycles")
        void shouldHandleRapidPauseResumeCycles() throws Exception {
            Long userId = 100L;
            String clientId = "test_client_pause_resume";

            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(10, 5, 3);
            ReflectionTestUtils.setField(state, "clientId", clientId);
            ReflectionTestUtils.setField(state, "userId", userId);

            when(redisStateManager.getState(clientId)).thenReturn(state);
            when(redisStateManager.removePausedSession(clientId)).thenReturn(true);

            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountDownLatch latch = new CountDownLatch(20);
            AtomicInteger pauseCount = new AtomicInteger(0);
            AtomicInteger resumeCount = new AtomicInteger(0);

            for (int i = 0; i < 20; i++) {
                final boolean isPause = i % 2 == 0;
                executor.submit(() -> {
                    try {
                        if (isPause) {
                            fileUploadService.pauseUpload(userId, clientId);
                            pauseCount.incrementAndGet();
                        } else {
                            fileUploadService.resumeUpload(userId, clientId);
                            resumeCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Expected in some race conditions
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(pauseCount.get() + resumeCount.get()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Concurrent Cancel Operations")
    class ConcurrentCancel {

        @Test
        @DisplayName("should handle concurrent cancel attempts")
        void shouldHandleConcurrentCancelAttempts() throws Exception {
            Long userId = 100L;
            String clientId = "test_client_cancel";

            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", clientId);
            ReflectionTestUtils.setField(state, "userId", userId);

            AtomicInteger callCount = new AtomicInteger(0);
            when(redisStateManager.getState(clientId)).thenAnswer(inv -> {
                if (callCount.incrementAndGet() <= 1) {
                    return state;
                }
                return null;
            });

            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch latch = new CountDownLatch(5);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    try {
                        boolean result = fileUploadService.cancelUpload(userId, clientId);
                        if (result) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(successCount.get()).isLessThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("High Load Simulation")
    class HighLoadSimulation {

        @Test
        @DisplayName("should handle high volume of mixed operations")
        void shouldHandleHighVolumeOfMixedOperations() throws Exception {
            when(redisStateManager.getSessionIdByFileClientKey(anyString(), anyString())).thenReturn(null);

            Map<String, FileUploadState> stateStore = new ConcurrentHashMap<>();

            doAnswer(inv -> {
                FileUploadState state = inv.getArgument(0);
                stateStore.put(state.getClientId(), state);
                return null;
            }).when(redisStateManager).saveNewState(any(FileUploadState.class), anyString());

            when(redisStateManager.getState(anyString())).thenAnswer(inv -> {
                String clientId = inv.getArgument(0);
                return stateStore.get(clientId);
            });

            int totalOperations = THREAD_COUNT * OPERATIONS_PER_THREAD;
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(totalOperations);
            AtomicInteger operationCount = new AtomicInteger(0);
            List<String> createdClientIds = Collections.synchronizedList(new ArrayList<>());

            for (int t = 0; t < THREAD_COUNT; t++) {
                final long userId = 100L + t;
                executor.submit(() -> {
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        try {
                            int opType = i % 3;
                            switch (opType) {
                                case 0 -> {
                                    StartUploadVO result = fileUploadService.startUpload(
                                            userId, "file-" + userId + "-" + i + ".pdf",
                                            1024 * 1024, "application/pdf", null, 256 * 1024, 4);
                                    if (result != null) {
                                        createdClientIds.add(result.getClientId());
                                    }
                                }
                                case 1 -> {
                                    if (!createdClientIds.isEmpty()) {
                                        String clientId = createdClientIds.get(
                                                ThreadLocalRandom.current().nextInt(createdClientIds.size()));
                                        FileUploadState state = stateStore.get(clientId);
                                        if (state != null && state.getUserId().equals(userId)) {
                                            fileUploadService.getUploadProgress(userId, clientId);
                                        }
                                    }
                                }
                                case 2 -> {
                                    if (!createdClientIds.isEmpty()) {
                                        String clientId = createdClientIds.get(
                                                ThreadLocalRandom.current().nextInt(createdClientIds.size()));
                                        FileUploadState state = stateStore.get(clientId);
                                        if (state != null && state.getUserId().equals(userId)) {
                                            fileUploadService.checkFileStatus(userId, clientId);
                                        }
                                    }
                                }
                            }
                            operationCount.incrementAndGet();
                        } catch (Exception e) {
                            // Expected for cross-user access attempts
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }

            latch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(operationCount.get()).isGreaterThan(totalOperations / 2);
            assertThat(createdClientIds).isNotEmpty();
        }

        @Test
        @DisplayName("should maintain data consistency under concurrent access")
        void shouldMaintainDataConsistencyUnderConcurrentAccess() throws Exception {
            Long userId = 100L;
            String clientId = "consistency_test_client";

            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(100, 0, 0);
            ReflectionTestUtils.setField(state, "clientId", clientId);
            ReflectionTestUtils.setField(state, "userId", userId);

            when(redisStateManager.getState(clientId)).thenReturn(state);

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch latch = new CountDownLatch(100);
            List<Integer> observedTotalChunks = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    try {
                        ProgressVO progress = fileUploadService.getUploadProgress(userId, clientId);
                        observedTotalChunks.add(progress.getTotalChunks());
                    } catch (Exception e) {
                        // Ignore
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(observedTotalChunks).isNotEmpty();
            assertThat(observedTotalChunks).allMatch(chunks -> chunks == 100);
        }
    }

    @Nested
    @DisplayName("Throughput Measurement")
    class ThroughputMeasurement {

        @Test
        @DisplayName("should measure start upload throughput")
        void shouldMeasureStartUploadThroughput() throws Exception {
            when(redisStateManager.getSessionIdByFileClientKey(anyString(), anyString())).thenReturn(null);

            int operationCount = 100;
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(operationCount);
            AtomicInteger successCount = new AtomicInteger(0);

            long startTime = System.nanoTime();

            for (int i = 0; i < operationCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        fileUploadService.startUpload(
                                100L + (index % 10), "throughput-test-" + index + ".pdf",
                                1024 * 1024, "application/pdf", null, 256 * 1024, 4);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // Ignore
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            long endTime = System.nanoTime();
            double durationMs = (endTime - startTime) / 1_000_000.0;
            double throughput = successCount.get() / (durationMs / 1000.0);

            assertThat(successCount.get()).isGreaterThan(operationCount / 2);
            assertThat(throughput).isGreaterThan(10.0);
        }
    }
}
