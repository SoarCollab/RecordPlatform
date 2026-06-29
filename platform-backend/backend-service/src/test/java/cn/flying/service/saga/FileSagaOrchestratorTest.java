package cn.flying.service.saga;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.FileSaga;
import cn.flying.dao.entity.FileSagaStatus;
import cn.flying.dao.entity.FileSagaStep;
import cn.flying.dao.mapper.FileMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
    private FileMapper fileMapper;
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

        // Default timer behavior
        when(sagaMetrics.startSagaTimer()).thenReturn(null);
        when(sagaMetrics.startCompensationTimer()).thenReturn(null);
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
                when(fileRemoteClient.storeFileOnChain(any()))
                        .thenReturn(Result.success(new StoreFileResponse("tx-1", "chain-hash")));

                FileUploadResult result = orchestrator.executeUpload(command);

                assertTrue(result.isSuccess());
                ArgumentCaptor<StoreFileRequest> requestCaptor = ArgumentCaptor.forClass(StoreFileRequest.class);
                verify(fileRemoteClient).storeFileOnChain(requestCaptor.capture());
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
    }

    @Nested
    @DisplayName("Retry Compensation")
    class RetryCompensation {

        @Test
        @DisplayName("should compensate successfully on retry")
        void shouldCompensateOnRetry() {
            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setFileId(100L)
                    .setRequestId("req-123")
                    .setUserId(1L)
                    .setCurrentStep(FileSagaStep.S3_UPLOADED.name())
                    .setStatus(FileSagaStatus.PENDING_COMPENSATION.name())
                    .setRetryCount(1)
                    .setPayload("{\"storedPaths\":{\"hash1\":\"path1\"},\"compensatedSteps\":[]}");

            // Mock S3 deletion success
            Result<Boolean> deleteResult = Result.success(true);
            when(fileRemoteClient.deleteStorageFile(anyMap())).thenReturn(deleteResult);

            // Mock DB update
            when(fileMapper.updateById(any(File.class))).thenReturn(1);

            orchestrator.retryCompensation(saga);

            // Verify status updates
            ArgumentCaptor<FileSaga> sagaCaptor = ArgumentCaptor.forClass(FileSaga.class);
            verify(compensationHelper, atLeastOnce()).updateSagaStatusInNewTransaction(sagaCaptor.capture());

            // Should end in COMPENSATED status
            List<FileSaga> capturedSagas = sagaCaptor.getAllValues();
            assertTrue(capturedSagas.stream()
                    .anyMatch(s -> FileSagaStatus.COMPENSATED.name().equals(s.getStatus())));

            verify(sagaMetrics).recordSagaCompensated();
        }

        /**
         * 验证旧版 payload 中直接保存的 hash -> path 映射仍会参与 S3 补偿。
         */
        @Test
        @DisplayName("should compensate legacy raw stored path payload")
        void shouldCompensateLegacyRawStoredPathPayload() {
            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setFileId(100L)
                    .setRequestId("req-legacy")
                    .setUserId(1L)
                    .setCurrentStep(FileSagaStep.S3_UPLOADED.name())
                    .setStatus(FileSagaStatus.PENDING_COMPENSATION.name())
                    .setRetryCount(1)
                    .setPayload("{\"hash1\":\"minio/node/node-a/hash1\"}");

            when(fileRemoteClient.deleteStorageFile(anyMap())).thenReturn(Result.success(true));
            when(fileMapper.updateById(any(File.class))).thenReturn(1);

            orchestrator.retryCompensation(saga);

            ArgumentCaptor<Map<String, String>> storedPathsCaptor = ArgumentCaptor.captor();
            verify(fileRemoteClient).deleteStorageFile(storedPathsCaptor.capture());
            assertEquals(Map.of("hash1", "minio/node/node-a/hash1"), storedPathsCaptor.getValue());
            verify(sagaMetrics).recordSagaCompensated();
        }

        @Test
        @DisplayName("should schedule retry on compensation failure")
        void shouldScheduleRetryOnFailure() {
            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setFileId(100L)
                    .setCurrentStep(FileSagaStep.S3_UPLOADED.name())
                    .setStatus(FileSagaStatus.PENDING_COMPENSATION.name())
                    .setRetryCount(1)
                    .setPayload("{\"storedPaths\":{\"hash1\":\"path1\"},\"compensatedSteps\":[]}");

            // Mock S3 deletion failure
            Result<Boolean> deleteResult = new Result<>(500, "S3 error", null);
            when(fileRemoteClient.deleteStorageFile(anyMap())).thenReturn(deleteResult);

            orchestrator.retryCompensation(saga);

            // Should schedule next retry
            assertNotNull(saga.getNextRetryAt());
            assertEquals(2, saga.getRetryCount());

            ArgumentCaptor<FileSaga> sagaCaptor = ArgumentCaptor.forClass(FileSaga.class);
            verify(compensationHelper, atLeastOnce()).updateSagaStatusInNewTransaction(sagaCaptor.capture());

            // Should be in PENDING_COMPENSATION for retry
            assertTrue(sagaCaptor.getAllValues().stream()
                    .anyMatch(s -> FileSagaStatus.PENDING_COMPENSATION.name().equals(s.getStatus())));
        }

        @Test
        @DisplayName("should mark as failed after max retries exceeded")
        void shouldMarkFailedAfterMaxRetries() {
            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setFileId(100L)
                    .setCurrentStep(FileSagaStep.S3_UPLOADED.name())
                    .setStatus(FileSagaStatus.PENDING_COMPENSATION.name())
                    .setRetryCount(5) // At max
                    .setPayload("{\"storedPaths\":{\"hash1\":\"path1\"},\"compensatedSteps\":[]}");

            // Mock S3 deletion failure
            Result<Boolean> deleteResult = new Result<>(500, "S3 error", null);
            when(fileRemoteClient.deleteStorageFile(anyMap())).thenReturn(deleteResult);

            orchestrator.retryCompensation(saga);

            // Should be marked as FAILED
            ArgumentCaptor<FileSaga> sagaCaptor = ArgumentCaptor.forClass(FileSaga.class);
            verify(compensationHelper, atLeastOnce()).updateSagaStatusInNewTransaction(sagaCaptor.capture());

            assertTrue(sagaCaptor.getAllValues().stream()
                    .anyMatch(s -> FileSagaStatus.FAILED.name().equals(s.getStatus())));

            // Should publish dead letter event
            verify(compensationHelper).publishEventInNewTransaction(
                    eq(outboxService),
                    eq("SAGA_DEAD_LETTER"),
                    eq(1L),
                    eq("saga.compensation.failed"),
                    anyString());

            verify(sagaMetrics).recordSagaFailed();
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
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("should skip already compensated saga")
        void shouldSkipAlreadyCompensated() {
            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setStatus(FileSagaStatus.COMPENSATED.name())
                    .setPayload("{\"storedPaths\":{},\"compensatedSteps\":[\"S3_DELETED\",\"DB_ROLLBACK\"]}");

            orchestrator.retryCompensation(saga);

            // Should not attempt S3 deletion
            verify(fileRemoteClient, never()).deleteStorageFile(anyMap());
        }

        @Test
        @DisplayName("should skip S3 compensation if already done")
        void shouldSkipS3IfDone() {
            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setFileId(100L)
                    .setCurrentStep(FileSagaStep.S3_UPLOADED.name())
                    .setStatus(FileSagaStatus.PENDING_COMPENSATION.name())
                    .setRetryCount(1)
                    .setPayload("{\"storedPaths\":{\"hash1\":\"path1\"},\"compensatedSteps\":[\"S3_DELETED\"]}");

            // Only DB update needed
            when(fileMapper.updateById(any(File.class))).thenReturn(1);

            orchestrator.retryCompensation(saga);

            // Should not call S3 deletion
            verify(fileRemoteClient, never()).deleteStorageFile(anyMap());
            // Should update DB
            verify(fileMapper).updateById(any(File.class));
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

    @Nested
    @DisplayName("Dead Letter")
    class DeadLetter {

        @Test
        @DisplayName("should publish dead letter event on max retries")
        void shouldPublishDeadLetter() {
            ReflectionTestUtils.setField(orchestrator, "deadLetterEnabled", true);

            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setRequestId("req-123")
                    .setUserId(100L)
                    .setFileName("test.txt")
                    .setCurrentStep(FileSagaStep.S3_UPLOADED.name())
                    .setStatus(FileSagaStatus.PENDING_COMPENSATION.name())
                    .setRetryCount(5)
                    .setPayload("{\"storedPaths\":{\"hash1\":\"path1\"},\"compensatedSteps\":[]}");

            Result<Boolean> deleteResult = new Result<>(500, "S3 error", null);
            when(fileRemoteClient.deleteStorageFile(anyMap())).thenReturn(deleteResult);

            orchestrator.retryCompensation(saga);

            verify(compensationHelper).publishEventInNewTransaction(
                    eq(outboxService),
                    eq("SAGA_DEAD_LETTER"),
                    eq(1L),
                    eq("saga.compensation.failed"),
                    argThat(json -> json.contains("req-123") && json.contains("test.txt")));
        }

        @Test
        @DisplayName("should skip dead letter if disabled")
        void shouldSkipDeadLetterIfDisabled() {
            ReflectionTestUtils.setField(orchestrator, "deadLetterEnabled", false);

            FileSaga saga = new FileSaga()
                    .setId(1L)
                    .setCurrentStep(FileSagaStep.S3_UPLOADED.name())
                    .setStatus(FileSagaStatus.PENDING_COMPENSATION.name())
                    .setRetryCount(5)
                    .setPayload("{\"storedPaths\":{\"hash1\":\"path1\"},\"compensatedSteps\":[]}");

            Result<Boolean> deleteResult = new Result<>(500, "S3 error", null);
            when(fileRemoteClient.deleteStorageFile(anyMap())).thenReturn(deleteResult);

            orchestrator.retryCompensation(saga);

            // Should not publish dead letter event
            verify(compensationHelper, never()).publishEventInNewTransaction(
                    any(), eq("SAGA_DEAD_LETTER"), any(), any(), any());
        }
    }
}
