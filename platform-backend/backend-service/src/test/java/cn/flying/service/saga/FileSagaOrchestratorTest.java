package cn.flying.service.saga;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.FileSaga;
import cn.flying.dao.entity.FileSagaStatus;
import cn.flying.dao.entity.FileSagaStep;
import cn.flying.dao.mapper.FileSagaMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.service.monitor.SagaMetrics;
import cn.flying.service.outbox.OutboxService;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.support.StoredObjectReference;
import cn.flying.service.support.StoredObjectReferenceCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FileSagaOrchestrator.
 * Verifies state machine transitions, compensation logic, and retry behavior.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileSagaOrchestrator Tests")
class FileSagaOrchestratorTest {

    @Mock
    private FileSagaMapper sagaMapper;
    @Mock
    private FileRemoteClient fileRemoteClient;
    @Mock
    private OutboxService outboxService;
    @Mock
    private SagaMetrics sagaMetrics;
    @Mock
    private TenantMapper tenantMapper;
    @Mock
    private SagaCompensationHelper compensationHelper;

    @InjectMocks
    private FileSagaOrchestrator orchestrator;

    /**
     * 初始化 orchestrator 的内部配置，并为监控埋点提供默认行为，避免测试依赖 JVM 自附加能力。
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(orchestrator, "maxCompensationRetries", 5);
        ReflectionTestUtils.setField(orchestrator, "compensationBatchSize", 50);
        ReflectionTestUtils.setField(orchestrator, "deadLetterEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "maxInMemoryChunkBytes", 80L * 1024L * 1024L);

        // Default timer behavior
        when(sagaMetrics.startSagaTimer()).thenReturn(null);
        when(sagaMetrics.startCompensationTimer()).thenReturn(null);
    }

    /**
     * 清理测试线程中的租户上下文，避免影响后续用例。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("FileSaga State Machine")
    class SagaStateMachine {

        @Test
        @DisplayName("saga should start in PENDING step and RUNNING status")
        void shouldStartInPendingRunning() {
            FileSaga saga = new FileSaga()
                    .setCurrentStep(FileSagaStep.PENDING.name())
                    .setStatus(FileSagaStatus.RUNNING.name());

            assertEquals(FileSagaStep.PENDING.name(), saga.getCurrentStep());
            assertEquals(FileSagaStatus.RUNNING.name(), saga.getStatus());
            assertFalse(saga.reachedStep(FileSagaStep.S3_UPLOADING));
        }

        @Test
        @DisplayName("should advance through steps correctly")
        void shouldAdvanceSteps() {
            FileSaga saga = new FileSaga()
                    .setCurrentStep(FileSagaStep.PENDING.name())
                    .setStatus(FileSagaStatus.RUNNING.name());

            saga.advanceTo(FileSagaStep.S3_UPLOADING);
            assertEquals(FileSagaStep.S3_UPLOADING.name(), saga.getCurrentStep());

            saga.advanceTo(FileSagaStep.S3_UPLOADED);
            assertEquals(FileSagaStep.S3_UPLOADED.name(), saga.getCurrentStep());
            assertTrue(saga.reachedStep(FileSagaStep.S3_UPLOADING));
            assertTrue(saga.reachedStep(FileSagaStep.S3_UPLOADED));
        }

        @Test
        @DisplayName("should track step reached status correctly")
        void shouldTrackReachedSteps() {
            FileSaga saga = new FileSaga()
                    .setCurrentStep(FileSagaStep.CHAIN_STORING.name());

            assertTrue(saga.reachedStep(FileSagaStep.PENDING));
            assertTrue(saga.reachedStep(FileSagaStep.S3_UPLOADING));
            assertTrue(saga.reachedStep(FileSagaStep.S3_UPLOADED));
            assertTrue(saga.reachedStep(FileSagaStep.CHAIN_STORING));
            assertFalse(saga.reachedStep(FileSagaStep.COMPLETED));
        }

        @Test
        @DisplayName("should mark status changes correctly")
        void shouldMarkStatusChanges() {
            FileSaga saga = new FileSaga()
                    .setStatus(FileSagaStatus.RUNNING.name());

            saga.markStatus(FileSagaStatus.COMPENSATING);
            assertEquals(FileSagaStatus.COMPENSATING.name(), saga.getStatus());

            saga.markStatus(FileSagaStatus.COMPENSATED);
            assertEquals(FileSagaStatus.COMPENSATED.name(), saga.getStatus());
        }
    }

    @Nested
    @DisplayName("Execute Upload")
    class ExecuteUpload {

        @TempDir
        private Path tempDir;

        /**
         * 验证普通上传链上内容使用有序数组，重复分片哈希不会被 Map 覆盖。
         */
        @Test
        @DisplayName("should persist ordered chain content when chunk hashes repeat")
        void shouldPersistOrderedChainContentWhenChunkHashesRepeat() throws Exception {
            Path firstChunk = Files.createTempFile("saga-dup-0", ".bin");
            Path secondChunk = Files.createTempFile("saga-dup-1", ".bin");
            try {
                TenantContext.setTenantId(77L);
                Files.writeString(firstChunk, "same", StandardCharsets.UTF_8);
                Files.writeString(secondChunk, "same", StandardCharsets.UTF_8);
                FileUploadCommand command = FileUploadCommand.builder()
                        .requestId("req-ordered")
                        .fileId(100L)
                        .userId(1L)
                        .fileName("dup.txt")
                        .fileParam("{}")
                        .fileList(List.of(firstChunk.toFile(), secondChunk.toFile()))
                        .fileHashList(List.of("hash-same", "hash-same"))
                        .tenantId(77L)
                        .build();

                when(sagaMapper.selectByRequestId("req-ordered", 77L)).thenReturn(null);
                when(fileRemoteClient.storeFileChunk(any(byte[].class), eq("hash-same")))
                        .thenReturn(Result.success("storage/tenant/77/chunk/hash-same"));
                when(fileRemoteClient.storeFileOnChainOnce(any()))
                        .thenReturn(Result.success(new StoreFileResponse("tx-1", "chain-hash")));

                FileUploadResult result = orchestrator.executeUpload(command);

                assertTrue(result.isSuccess());
                ArgumentCaptor<StoreFileRequest> requestCaptor = ArgumentCaptor.forClass(StoreFileRequest.class);
                verify(fileRemoteClient).storeFileOnChainOnce(requestCaptor.capture());
                List<StoredObjectReference> references =
                        StoredObjectReferenceCodec.parseChainContent(requestCaptor.getValue().content());
                assertEquals(2, references.size());
                assertEquals(0, references.get(0).index());
                assertEquals("hash-same", references.get(0).cipherHash());
                assertEquals(1, references.get(1).index());
                assertEquals("hash-same", references.get(1).cipherHash());
                assertEquals("storage/tenant/77/chunk/hash-same", references.get(1).storagePath());
            } finally {
                TenantContext.clear();
                Files.deleteIfExists(firstChunk);
                Files.deleteIfExists(secondChunk);
            }
        }

        /**
         * 验证 DB 链前检查点确定失败时 Saga 不推进链边界且链 RPC 为零次。
         */
        @Test
        void definiteBeforeChainFailureShouldRemainBeforeChainBoundary() throws Exception {
            FileUploadCommand command = prepareBoundaryCommand("req-before-chain-fail");
            RuntimeException checkpointFailure = new IllegalStateException("claim CAS conflict");
            AtomicReference<FileSaga> insertedSaga = captureInsertedSaga();

            RuntimeException actual = assertThrows(
                    RuntimeException.class,
                    () -> orchestrator.executeUpload(
                            command,
                            () -> { throw checkpointFailure; },
                            ignored -> fail("链后检查点不应执行")));

            assertSame(checkpointFailure, actual);
            assertEquals(FileSagaStep.S3_UPLOADED.name(), insertedSaga.get().getCurrentStep());
            assertEquals(FileSagaStatus.FAILED.name(), insertedSaga.get().getStatus());
            verify(fileRemoteClient, never()).storeFileOnChainOnce(any());
        }

        /**
         * 验证 DB ATTESTING 已确认后 Saga 链边界检查点响应未知会转人工且不发链 RPC。
         */
        @Test
        void sagaChainBoundaryCheckpointFailureShouldRequireManualReconciliation() throws Exception {
            FileUploadCommand command = prepareBoundaryCommand("req-saga-boundary-fail");
            AtomicReference<FileSaga> insertedSaga = captureInsertedSaga();
            doAnswer(invocation -> {
                FileSaga saga = invocation.getArgument(0);
                if (FileSagaStep.CHAIN_STORING.name().equals(saga.getCurrentStep())) {
                    throw new IllegalStateException("saga checkpoint response unknown");
                }
                return null;
            }).when(compensationHelper).updateSagaStepInNewTransaction(any(FileSaga.class));

            assertThrows(
                    IllegalStateException.class,
                    () -> orchestrator.executeUpload(command, () -> { }, ignored -> { }));

            assertEquals(FileSagaStep.CHAIN_STORING.name(), insertedSaga.get().getCurrentStep());
            assertEquals(
                    FileSagaStatus.MANUAL_RECONCILIATION.name(),
                    insertedSaga.get().getStatus());
            verify(fileRemoteClient, never()).storeFileOnChainOnce(any());
        }

        /**
         * 构造边界测试的单分片命令和存储 RPC 响应。
         */
        private FileUploadCommand prepareBoundaryCommand(String requestId) throws Exception {
            TenantContext.setTenantId(77L);
            Path chunk = tempDir.resolve(requestId + ".bin");
            Files.writeString(chunk, "chunk", StandardCharsets.UTF_8);
            when(sagaMapper.selectByRequestId(requestId, 77L)).thenReturn(null);
            when(fileRemoteClient.storeFileChunk(any(byte[].class), eq("cipher-hash")))
                    .thenReturn(Result.success("storage/tenant/77/cipher-hash"));
            return FileUploadCommand.builder()
                    .requestId(requestId)
                    .fileId(100L)
                    .userId(1L)
                    .tenantId(77L)
                    .fileName("boundary.txt")
                    .fileParam("{}")
                    .fileList(List.of(chunk.toFile()))
                    .fileHashList(List.of("cipher-hash"))
                    .build();
        }

        /**
         * 捕获新建 Saga，并模拟独立事务插入后分配稳定主键。
         */
        private AtomicReference<FileSaga> captureInsertedSaga() {
            AtomicReference<FileSaga> captured = new AtomicReference<>();
            doAnswer(invocation -> {
                FileSaga saga = invocation.getArgument(0);
                saga.setId(9001L).setTenantId(77L);
                captured.set(saga);
                return null;
            }).when(compensationHelper).insertSagaInNewTransaction(any(FileSaga.class));
            return captured;
        }
    }

    @Nested
    @DisplayName("Upload Memory Guard")
    class UploadMemoryGuard {

        @TempDir
        private Path tempDir;

        /**
         * 验证合法小分片仍可经过 Saga 上传和链上存证路径。
         */
        @Test
        @DisplayName("should upload valid chunk through storage and blockchain")
        void shouldUploadValidChunkThroughStorageAndBlockchain() throws Exception {
            java.io.File chunk = writeChunk("small.bin", new byte[] {1, 2, 3, 4});
            prepareNewSaga("req-small");
            when(fileRemoteClient.storeFileChunk(any(byte[].class), eq("hash-small")))
                    .thenReturn(Result.success("minio/tenant/77/hash-small"));
            when(fileRemoteClient.storeFileOnChainOnce(any(StoreFileRequest.class)))
                    .thenReturn(Result.success(new StoreFileResponse("tx-small", "file-hash")));

            FileUploadResult result = orchestrator.executeUpload(FileUploadCommand.builder()
                    .requestId("req-small")
                    .userId(100L)
                    .fileName("small.bin")
                    .fileList(List.of(chunk))
                    .fileHashList(List.of("hash-small"))
                    .build());

            assertTrue(result.isSuccess());
            assertEquals("tx-small", result.getTransactionHash());
            verify(fileRemoteClient).storeFileChunk(
                    ArgumentMatchers.<byte[]>argThat(bytes -> Arrays.equals(bytes, new byte[] {1, 2, 3, 4})),
                    eq("hash-small"));
            verify(fileRemoteClient).storeFileOnChainOnce(any(StoreFileRequest.class));
        }

        /**
         * 验证超限分片在读取为 byte[] 和远程存储调用前被拒绝。
         */
        @Test
        @DisplayName("should reject oversized chunk before storage RPC")
        void shouldRejectOversizedChunkBeforeStorageRpc() throws Exception {
            ReflectionTestUtils.setField(orchestrator, "maxInMemoryChunkBytes", 4L);
            java.io.File chunk = writeChunk("large.bin", new byte[] {1, 2, 3, 4, 5});
            prepareNewSaga("req-large");

            GeneralException exception = assertThrows(GeneralException.class, () ->
                    orchestrator.executeUpload(FileUploadCommand.builder()
                            .requestId("req-large")
                            .userId(100L)
                            .fileName("large.bin")
                            .fileList(List.of(chunk))
                            .fileHashList(List.of("hash-large"))
                            .build()));

            assertEquals(ResultEnum.FILE_UPLOAD_ERROR, exception.getResultEnum());
            assertEquals("分片大小超过后端代理上传上限，请使用 Multipart 直传链路", exception.getData());
            verify(fileRemoteClient, never()).storeFileChunk(any(), any());
            verify(fileRemoteClient, never()).storeFileOnChainOnce(any());
        }

        /**
         * 创建临时分片文件并写入指定内容。
         */
        private java.io.File writeChunk(String fileName, byte[] bytes) throws Exception {
            Path path = tempDir.resolve(fileName);
            Files.write(path, bytes);
            return path.toFile();
        }

        /**
         * 准备一个新 Saga 启动场景所需的租户上下文和持久化 mock。
         */
        private void prepareNewSaga(String requestId) {
            TenantContext.setTenantId(77L);
            when(sagaMapper.selectByRequestId(eq(requestId), eq(77L))).thenReturn(null);
            doAnswer(invocation -> {
                FileSaga saga = invocation.getArgument(0);
                saga.setId(999L).setTenantId(77L);
                return null;
            }).when(compensationHelper).insertSagaInNewTransaction(any(FileSaga.class));
        }
    }

    @Nested
    @DisplayName("Retry Compensation")
    class RetryCompensation {

        /**
         * 验证历史链前补偿任务只收敛 FAILED 状态，不删除共享对象或直接回写文件表。
         */
        @Test
        void retryBeforeChainBoundaryShouldFailClosedWithoutDestructiveCompensation() {
            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setFileId(100L)
                    .setRequestId("req-123")
                    .setUserId(1L)
                    .setCurrentStep(FileSagaStep.S3_UPLOADED.name())
                    .setStatus(FileSagaStatus.PENDING_COMPENSATION.name())
                    .setRetryCount(1);

            orchestrator.retryCompensation(saga);

            assertEquals(FileSagaStatus.FAILED.name(), saga.getStatus());
            assertEquals(2, saga.getRetryCount());
            assertTrue(saga.getLastError().contains("自动物理删除补偿已停用"));
            verify(compensationHelper).updateSagaStatusAndPublishEventInNewTransaction(
                    eq(saga),
                    eq(outboxService), eq("SAGA_DEAD_LETTER"), eq(1L),
                    eq("saga.compensation.failed"), anyString());
            verify(sagaMetrics).recordSagaFailed();
            verify(fileRemoteClient, never()).deleteStorageFile(anyMap());
        }

        /**
         * 验证已经越过链边界的历史任务转人工对账，仍不执行对象删除或数据库回滚。
         */
        @Test
        void retryAfterChainBoundaryShouldRequireManualReconciliationWithoutDeletes() {
            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setFileId(100L)
                    .setCurrentStep(FileSagaStep.CHAIN_STORING.name())
                    .setStatus(FileSagaStatus.PENDING_COMPENSATION.name())
                    .setRetryCount(1);

            orchestrator.retryCompensation(saga);

            assertEquals(FileSagaStatus.MANUAL_RECONCILIATION.name(), saga.getStatus());
            assertEquals(2, saga.getRetryCount());
            verify(compensationHelper).updateSagaStatusAndPublishEventInNewTransaction(
                    eq(saga),
                    eq(outboxService), eq("SAGA_DEAD_LETTER"), eq(1L),
                    eq("saga.compensation.failed"), anyString());
            verify(sagaMetrics).recordSagaFailed();
            verify(fileRemoteClient, never()).deleteStorageFile(anyMap());
        }

        /**
         * 验证终态 Saga 的重复调度不会再次发布死信或修改持久状态。
         */
        @Test
        void retryTerminalSagaShouldBeIdempotent() {
            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setCurrentStep(FileSagaStep.S3_UPLOADED.name())
                    .setStatus(FileSagaStatus.FAILED.name())
                    .setRetryCount(2);

            orchestrator.retryCompensation(saga);

            assertEquals(2, saga.getRetryCount());
            verifyNoInteractions(compensationHelper);
            verify(sagaMetrics, never()).recordSagaFailed();
            verify(fileRemoteClient, never()).deleteStorageFile(anyMap());
        }

        /**
         * 验证显式禁用死信时仍能关闭历史 Saga，且不会伪造 Outbox 事件。
         */
        @Test
        void retryWithDeadLetterDisabledShouldPersistTerminalStateOnly() {
            ReflectionTestUtils.setField(orchestrator, "deadLetterEnabled", false);
            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setCurrentStep(FileSagaStep.S3_UPLOADED.name())
                    .setStatus(FileSagaStatus.PENDING_COMPENSATION.name())
                    .setRetryCount(1);

            try {
                orchestrator.retryCompensation(saga);
            } finally {
                ReflectionTestUtils.setField(orchestrator, "deadLetterEnabled", true);
            }

            assertEquals(FileSagaStatus.FAILED.name(), saga.getStatus());
            verify(compensationHelper).updateSagaStatusInNewTransaction(saga);
            verify(compensationHelper, never()).updateSagaStatusAndPublishEventInNewTransaction(
                    any(), any(), anyString(), anyLong(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Exponential Backoff")
    class ExponentialBackoff {

        @Test
        @DisplayName("should calculate exponential backoff correctly")
        void shouldCalculateExponentialBackoff() {
            FileSaga saga = new FileSaga()
                    .setRetryCount(0);

            // First retry delay
            saga.scheduleNextRetry();
            assertNotNull(saga.getNextRetryAt());
            long firstDelay = saga.getNextRetryAt().getTime() - System.currentTimeMillis();

            // Second retry delay (increment retry count manually)
            saga.setRetryCount(1);
            saga.scheduleNextRetry();
            long secondDelay = saga.getNextRetryAt().getTime() - System.currentTimeMillis();

            // Third retry delay
            saga.setRetryCount(2);
            saga.scheduleNextRetry();
            long thirdDelay = saga.getNextRetryAt().getTime() - System.currentTimeMillis();

            // Delays should increase with retry count (exponential backoff)
            assertTrue(secondDelay > firstDelay, "Second delay should be longer than first");
            assertTrue(thirdDelay > secondDelay, "Third delay should be longer than second");
        }

        @Test
        @DisplayName("should check if retry is due")
        void shouldCheckRetryDue() {
            FileSaga saga = new FileSaga()
                    .setRetryCount(1);

            // No next retry scheduled
            assertTrue(saga.isRetryDue());

            // Schedule for future
            saga.scheduleNextRetry();
            assertFalse(saga.isRetryDue());
        }
    }

    @Nested
    @DisplayName("Scheduled Compensation")
    class ScheduledCompensation {

        @Test
        @DisplayName("should process pending sagas for each tenant")
        void shouldProcessPerTenant() {
            when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(1L, 2L));
            when(sagaMapper.selectPendingCompensation(anyLong(), anyInt())).thenReturn(List.of());

            orchestrator.processRetriableSagas();

            // Should query for each tenant
            verify(sagaMapper).selectPendingCompensation(eq(1L), eq(50));
            verify(sagaMapper).selectPendingCompensation(eq(2L), eq(50));
        }

        @Test
        @DisplayName("should not process if no active tenants")
        void shouldNotProcessIfNoTenants() {
            when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of());

            orchestrator.processRetriableSagas();

            verify(sagaMapper, never()).selectPendingCompensation(anyLong(), anyInt());
        }

        @Test
        @DisplayName("should handle tenant processing errors gracefully")
        void shouldHandleTenantErrors() {
            when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(1L, 2L));
            when(sagaMapper.selectPendingCompensation(eq(1L), anyInt()))
                    .thenThrow(new RuntimeException("DB error"));
            when(sagaMapper.selectPendingCompensation(eq(2L), anyInt())).thenReturn(List.of());

            // Should not throw, and should continue to process tenant 2
            assertDoesNotThrow(() -> orchestrator.processRetriableSagas());

            verify(sagaMapper).selectPendingCompensation(eq(2L), eq(50));
        }
    }

}
