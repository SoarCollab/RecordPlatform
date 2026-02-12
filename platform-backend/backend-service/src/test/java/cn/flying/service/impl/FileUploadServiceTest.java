package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.lock.DistributedLock;
import cn.flying.common.util.UidEncoder;
import cn.flying.dao.vo.file.FileUploadState;
import cn.flying.dao.vo.file.ProgressVO;
import cn.flying.dao.vo.file.ResumeUploadVO;
import cn.flying.dao.vo.file.StartUploadVO;
import cn.flying.service.FileService;
import cn.flying.service.QuotaService;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FileUploadServiceImpl.
 * Verifies chunk upload workflow, state management, pause/resume, and progress tracking.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileUploadService Tests")
class FileUploadServiceTest {

    @Mock
    private FileUploadRedisStateManager redisStateManager;

    @Mock
    private EncryptionStrategyFactory encryptionStrategyFactory;

    @Mock
    private FileService fileService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private QuotaService quotaService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock quotaLock;

    @InjectMocks
    private FileUploadServiceImpl fileUploadService;

    private static MockedStatic<UidEncoder> uidEncoderMock;

    private static final Long USER_ID = 100L;
    private static final String SUID = "encoded_uid_100";
    private static final String CLIENT_ID = "test_client_123";

    @BeforeAll
    static void setUpClass() {
        uidEncoderMock = mockStatic(UidEncoder.class);
        uidEncoderMock.when(() -> UidEncoder.encodeUid(anyString())).thenReturn(SUID);
        uidEncoderMock.when(() -> UidEncoder.encodeCid(anyString())).thenReturn(CLIENT_ID);
    }

    @AfterAll
    static void tearDownClass() {
        uidEncoderMock.close();
    }

    @BeforeEach
    void setUp() {
        FileUploadStateTestBuilder.resetClientIdCounter();
        // Skip @PostConstruct initialization
        ReflectionTestUtils.setField(fileUploadService, "eventPublisher", eventPublisher);
        // 让异步分片处理在测试中同步执行，避免线程池/时序不稳定
        ReflectionTestUtils.setField(fileUploadService, "fileProcessingExecutor", (java.util.concurrent.Executor) Runnable::run);
    }

    @Nested
    @DisplayName("Start Upload")
    class StartUpload {

        @Test
        @DisplayName("should create upload session with valid parameters")
        void shouldCreateUploadSession() {
            // Given
            String fileName = "test.pdf";
            long fileSize = 1024 * 1024; // 1MB
            String contentType = "application/pdf";
            int chunkSize = 256 * 1024; // 256KB
            int totalChunks = 4;

            when(redisStateManager.getSessionIdByFileClientKey(anyString(), anyString())).thenReturn(null);

            // When
            StartUploadVO result = fileUploadService.startUpload(
                    USER_ID, fileName, fileSize, contentType, null, chunkSize, totalChunks);

            // Then
            assertNotNull(result);
            assertEquals(CLIENT_ID, result.getClientId());
            assertEquals(chunkSize, result.getChunkSize());
            assertEquals(totalChunks, result.getTotalChunks());
            assertFalse(result.isResumed());
            assertTrue(result.getProcessedChunks().isEmpty());

            verify(redisStateManager).saveNewState(any(FileUploadState.class), eq(SUID));
        }

        @Test
        @DisplayName("should resume existing session if found")
        void shouldResumeExistingSession() {
            // Given
            String fileName = "test.pdf";
            long fileSize = 1024 * 1024;
            String existingClientId = "existing_client";

            FileUploadState existingState = FileUploadStateTestBuilder.anUploadStateWithChunks(4, 2, 2);
            ReflectionTestUtils.setField(existingState, "clientId", existingClientId);
            ReflectionTestUtils.setField(existingState, "fileName", fileName);
            ReflectionTestUtils.setField(existingState, "fileSize", fileSize);

            when(redisStateManager.getSessionIdByFileClientKey(anyString(), anyString())).thenReturn(existingClientId);
            when(redisStateManager.getState(existingClientId)).thenReturn(existingState);

            // When
            StartUploadVO result = fileUploadService.startUpload(
                    USER_ID, fileName, fileSize, "application/pdf", null, 256 * 1024, 4);

            // Then
            assertNotNull(result);
            assertTrue(result.isResumed());
            assertEquals(existingClientId, result.getClientId());
            assertFalse(result.getProcessedChunks().isEmpty());

            verify(redisStateManager).removePausedSession(existingClientId);
            verify(redisStateManager).updateLastActivityTime(existingClientId);
        }

        @Test
        @DisplayName("should reject invalid file name")
        void shouldRejectInvalidFileName() {
            // Given
            String invalidFileName = "../../../etc/passwd"; // Path traversal

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, invalidFileName, 1024, "text/plain", null, 256, 1));
        }

        @Test
        @DisplayName("should reject file exceeding size limit")
        void shouldRejectOversizedFile() {
            // Given
            long tooLargeSize = 5L * 1024 * 1024 * 1024; // 5GB (limit is 4GB)

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, "large.zip", tooLargeSize, "application/zip", null, 256 * 1024, 100));
        }

        @Test
        @DisplayName("should reject unsupported file type")
        void shouldRejectUnsupportedType() {
            // Given
            String unsupportedFile = "script.exe";
            String unsupportedContentType = "application/x-msdownload";

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, unsupportedFile, 1024, unsupportedContentType, null, 256, 1));
        }

        @Test
        @DisplayName("should reject zero file size")
        void shouldRejectZeroFileSize() {
            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, "empty.txt", 0, "text/plain", null, 256, 0));
        }

        @Test
        @DisplayName("should reject invalid chunk size")
        void shouldRejectInvalidChunkSize() {
            // When & Then - chunk size exceeds max (80MB)
            int tooLargeChunkSize = 100 * 1024 * 1024; // 100MB
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, "test.pdf", 1024 * 1024, "application/pdf", null, tooLargeChunkSize, 1));
        }
    }

    @Nested
    @DisplayName("Get Upload Progress")
    class GetUploadProgress {

        @Test
        @DisplayName("should return progress for valid session")
        void shouldReturnProgress() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(4, 2, 1);
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When
            ProgressVO result = fileUploadService.getUploadProgress(USER_ID, CLIENT_ID);

            // Then
            assertNotNull(result);
            assertEquals(CLIENT_ID, result.getClientId());
            assertEquals(4, result.getTotalChunks());
            assertEquals(2, result.getUploadedChunkCount());
            assertEquals(1, result.getProcessedChunkCount());
            assertTrue(result.getProgress() > 0);
            assertTrue(result.getProgress() <= 100);

            verify(redisStateManager).updateLastActivityTime(CLIENT_ID);
        }

        @Test
        @DisplayName("should throw exception for non-existent session")
        void shouldThrowForNonExistentSession() {
            // Given
            when(redisStateManager.getState(anyString())).thenReturn(null);

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.getUploadProgress(USER_ID, "non_existent"));
        }

        @Test
        @DisplayName("should reject unauthorized access")
        void shouldRejectUnauthorizedAccess() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", 999L); // Different user

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.getUploadProgress(USER_ID, CLIENT_ID));
        }
    }

    @Nested
    @DisplayName("Pause and Resume Upload")
    class PauseResume {

        @Test
        @DisplayName("should pause active upload")
        void shouldPauseUpload() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When
            fileUploadService.pauseUpload(USER_ID, CLIENT_ID);

            // Then
            verify(redisStateManager).addPausedSession(CLIENT_ID);
            verify(redisStateManager).updateLastActivityTime(CLIENT_ID);
        }

        @Test
        @DisplayName("should resume paused upload")
        void shouldResumeUpload() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(4, 2, 2);
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.removePausedSession(CLIENT_ID)).thenReturn(true);

            // When
            ResumeUploadVO result = fileUploadService.resumeUpload(USER_ID, CLIENT_ID);

            // Then
            assertNotNull(result);
            assertEquals(4, result.getTotalChunks());
            assertEquals(2, result.getProcessedChunks().size());

            verify(redisStateManager).removePausedSession(CLIENT_ID);
            verify(redisStateManager).updateLastActivityTime(CLIENT_ID);
        }

        @Test
        @DisplayName("should reject pause for non-existent session")
        void shouldRejectPauseForNonExistent() {
            // Given
            when(redisStateManager.getState(anyString())).thenReturn(null);

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.pauseUpload(USER_ID, "non_existent"));
        }

        /**
         * 验证恢复上传时如果会话不存在，会抛出会话不存在异常。
         */
        @Test
        @DisplayName("should reject resume for non-existent session")
        void shouldRejectResumeForNonExistentSession() {
            when(redisStateManager.getState(anyString())).thenReturn(null);

            assertThrows(GeneralException.class, () ->
                    fileUploadService.resumeUpload(USER_ID, "non_existent"));
        }

        /**
         * 验证恢复上传时会执行所有权校验，非所有者会被拒绝。
         */
        @Test
        @DisplayName("should reject resume for unauthorized user")
        void shouldRejectResumeForUnauthorizedUser() {
            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(4, 2, 2);
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", 999L);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            assertThrows(GeneralException.class, () ->
                    fileUploadService.resumeUpload(USER_ID, CLIENT_ID));
        }

        /**
         * 验证暂停上传时会执行所有权校验，非所有者会被拒绝。
         */
        @Test
        @DisplayName("should reject pause for unauthorized user")
        void shouldRejectPauseForUnauthorizedUser() {
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", 999L);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            assertThrows(GeneralException.class, () ->
                    fileUploadService.pauseUpload(USER_ID, CLIENT_ID));
        }
    }

    @Nested
    @DisplayName("Cancel Upload")
    class CancelUpload {

        @Test
        @DisplayName("should cancel and cleanup session")
        void shouldCancelUpload() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When
            boolean result = fileUploadService.cancelUpload(USER_ID, CLIENT_ID);

            // Then
            assertTrue(result);
            verify(redisStateManager).removeSession(CLIENT_ID, SUID);
        }

        @Test
        @DisplayName("should return false for non-existent session")
        void shouldReturnFalseForNonExistent() {
            // Given
            when(redisStateManager.getState(anyString())).thenReturn(null);

            // When
            boolean result = fileUploadService.cancelUpload(USER_ID, "non_existent");

            // Then
            assertFalse(result);
        }

        /**
         * 验证取消上传时会使用用户编码后的 SUID 清理会话状态。
         */
        @Test
        @DisplayName("should remove session with encoded suid")
        void shouldRemoveSessionWithEncodedSuid() {
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            boolean result = fileUploadService.cancelUpload(USER_ID, CLIENT_ID);

            assertTrue(result);
            verify(redisStateManager).removeSession(CLIENT_ID, SUID);
        }
    }

    @Nested
    @DisplayName("Complete Upload Quota Guard")
    class CompleteUploadQuotaGuard {

        /**
         * 反射调用 reserveQuotaAndPrepareStoreFile，便于单测覆盖幂等分支。
         *
         * @param userId 用户ID
         * @param state 上传会话状态
         */
        private void invokeReserveQuotaAndPrepareStoreFile(Long userId, FileUploadState state) throws Exception {
            Method method = FileUploadServiceImpl.class.getDeclaredMethod(
                    "reserveQuotaAndPrepareStoreFile", Long.class, FileUploadState.class
            );
            method.setAccessible(true);
            method.invoke(fileUploadService, userId, state);
        }

        /**
         * 验证 completeUpload 在租户级锁内执行配额复核，并在超限时阻断 PREPARE 入库。
         */
        @Test
        @DisplayName("should recheck quota under tenant lock before prepare")
        void shouldRecheckQuotaUnderTenantLockBeforePrepare() {
            String clientId = "quota-guard-client";
            FileUploadState state = new FileUploadState(
                    USER_ID,
                    "quota-guard.bin",
                    1024L,
                    "application/octet-stream",
                    clientId,
                    256,
                    0
            );
            state.setTenantId(77L);

            when(redisStateManager.getState(clientId)).thenReturn(state);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            doThrow(new GeneralException(ResultEnum.QUOTA_EXCEEDED, "quota exceeded"))
                    .when(quotaService).checkUploadQuota(77L, USER_ID, 1024L);

            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileUploadService.completeUpload(USER_ID, clientId));

            assertEquals(ResultEnum.QUOTA_EXCEEDED, ex.getResultEnum());
            verify(quotaLock).lock(60L, TimeUnit.SECONDS);
            verify(quotaService).checkUploadQuota(77L, USER_ID, 1024L);
            verify(fileService, never()).prepareStoreFile(anyLong(), anyString(), anyLong());
            verify(quotaLock).unlock();
        }

        /**
         * 验证首次预占位会写入 PREPARE 元数据并回写会话幂等标记。
         */
        @Test
        @DisplayName("should mark prepare stored after reserve success")
        void shouldMarkPrepareStoredAfterReserveSuccess() throws Exception {
            FileUploadState state = new FileUploadState(
                    USER_ID,
                    "prepare-once.bin",
                    2048L,
                    "application/octet-stream",
                    "prepare-once-client",
                    256,
                    0
            );
            state.setTenantId(77L);
            state.setPrepareStored(false);

            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(redisStateManager.getState("prepare-once-client")).thenReturn(state);

            invokeReserveQuotaAndPrepareStoreFile(USER_ID, state);

            verify(quotaService).checkUploadQuota(77L, USER_ID, 2048L);
            verify(fileService).prepareStoreFile(USER_ID, "prepare-once.bin", 2048L);
            verify(redisStateManager).updateState(argThat(FileUploadState::isPrepareStored));
            verify(quotaLock).unlock();
        }

        /**
         * 验证会话已落库 PREPARE 时，重试调用不会重复计费或重复插入元数据。
         */
        @Test
        @DisplayName("should skip duplicate prepare when already reserved")
        void shouldSkipDuplicatePrepareWhenAlreadyReserved() throws Exception {
            FileUploadState state = new FileUploadState(
                    USER_ID,
                    "prepare-retry.bin",
                    2048L,
                    "application/octet-stream",
                    "prepare-retry-client",
                    256,
                    0
            );
            state.setTenantId(77L);
            state.setPrepareStored(true);

            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(redisStateManager.getState("prepare-retry-client")).thenReturn(state);

            invokeReserveQuotaAndPrepareStoreFile(USER_ID, state);

            verify(quotaService, never()).checkUploadQuota(anyLong(), anyLong(), anyLong());
            verify(fileService, never()).prepareStoreFile(anyLong(), anyString(), anyLong());
            verify(redisStateManager, never()).updateState(any(FileUploadState.class));
            verify(quotaLock).unlock();
        }
    }

    @Nested
    @DisplayName("NEXT_KEY Idempotency")
    class NextKeyIdempotency {

        /**
         * 验证重复执行追加密钥时不会重复写入 NEXT_KEY 元数据。
         */
        @Test
        @DisplayName("should append next key metadata only once")
        void shouldAppendNextKeyMetadataOnlyOnce() throws Exception {
            Path tempChunk = Files.createTempFile("chunk-next-key-", ".bin");
            tempChunk.toFile().deleteOnExit();
            Files.writeString(tempChunk, "cipher-data\n--HASH--\nhash-value", StandardCharsets.UTF_8);

            byte[] nextKey = new byte[32];
            Arrays.fill(nextKey, (byte) 7);

            Method appendMethod = FileUploadServiceImpl.class.getDeclaredMethod(
                    "appendKeyToFile", Path.class, byte[].class, int.class
            );
            appendMethod.setAccessible(true);

            appendMethod.invoke(fileUploadService, tempChunk, nextKey, 0);
            appendMethod.invoke(fileUploadService, tempChunk, nextKey, 0);

            String content = Files.readString(tempChunk, StandardCharsets.UTF_8);
            String separatorRegex = "\\Q\n--NEXT_KEY--\n\\E";
            int separatorCount = content.split(separatorRegex, -1).length - 1;
            assertEquals(1, separatorCount);
        }
    }

    @Nested
    @DisplayName("Check File Status")
    class CheckFileStatus {

        @Test
        @DisplayName("should return UPLOADING status for active upload")
        void shouldReturnUploadingStatus() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(4, 2, 1);
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);

            // When
            var result = fileUploadService.checkFileStatus(USER_ID, CLIENT_ID);

            // Then
            assertNotNull(result);
            assertEquals("UPLOADING", result.getStatus());
            assertFalse(result.isPaused());
        }

        @Test
        @DisplayName("should return PAUSED status for paused upload")
        void shouldReturnPausedStatus() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(true);

            // When
            var result = fileUploadService.checkFileStatus(USER_ID, CLIENT_ID);

            // Then
            assertEquals("PAUSED", result.getStatus());
            assertTrue(result.isPaused());
        }

        @Test
        @DisplayName("should return PROCESSING_COMPLETE when all chunks processed")
        void shouldReturnProcessingComplete() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.aCompletedUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);

            // When
            var result = fileUploadService.checkFileStatus(USER_ID, CLIENT_ID);

            // Then
            assertEquals("PROCESSING_COMPLETE", result.getStatus());
        }
    }

    @Nested
    @DisplayName("Ownership Validation")
    class OwnershipValidation {

        @Test
        @DisplayName("should allow owner to access session")
        void shouldAllowOwner() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When & Then - no exception
            assertDoesNotThrow(() -> fileUploadService.getUploadProgress(USER_ID, CLIENT_ID));
        }

        @Test
        @DisplayName("should reject non-owner access")
        void shouldRejectNonOwner() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", 999L);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When & Then
            Long differentUserId = 888L;
            assertThrows(GeneralException.class, () ->
                    fileUploadService.getUploadProgress(differentUserId, CLIENT_ID));
        }
    }

    @Nested
    @DisplayName("CleanupExpiredUploadSessions")
    class CleanupExpiredUploadSessions {

        @Test
        @DisplayName("should be protected by distributed lock to avoid multi-instance overlap")
        void shouldBeProtectedByDistributedLock() throws Exception {
            Method method = FileUploadServiceImpl.class.getDeclaredMethod("cleanupExpiredUploadSessions");
            DistributedLock lock = method.getAnnotation(DistributedLock.class);

            assertNotNull(lock);
            assertEquals("upload:session:cleanup", lock.key());
            assertEquals(3600, lock.leaseTime());
            assertFalse(lock.throwOnFailure());
        }

        @Test
        @DisplayName("should cleanup expired active session")
        void shouldCleanupExpiredActiveSession() {
            String clientId = "expired_active_1";
            FileUploadState state = new FileUploadState(USER_ID, "expired.pdf", 1024, "application/pdf", clientId, 256, 1);
            state.setLastActivityTime(System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(13));

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(clientId));
            when(redisStateManager.getState(clientId)).thenReturn(state);
            when(redisStateManager.isSessionPaused(clientId)).thenReturn(false);

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager).removeSession(clientId, "");
        }

        @Test
        @DisplayName("should cleanup expired paused session with longer timeout")
        void shouldCleanupExpiredPausedSession() {
            String clientId = "expired_paused_1";
            FileUploadState state = new FileUploadState(USER_ID, "paused.pdf", 1024, "application/pdf", clientId, 256, 1);
            state.setLastActivityTime(System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(25));

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(clientId));
            when(redisStateManager.getState(clientId)).thenReturn(state);
            when(redisStateManager.isSessionPaused(clientId)).thenReturn(true);

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager).removeSession(clientId, "");
        }

        @Test
        @DisplayName("should handle missing state session by removing from paused set")
        void shouldHandleMissingStateSession() {
            String clientId = "missing_state_1";

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(clientId));
            when(redisStateManager.getState(clientId)).thenReturn(null);
            when(redisStateManager.removePausedSession(clientId)).thenReturn(true);

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager).removePausedSession(clientId);
            verify(redisStateManager, never()).removeSession(eq(clientId), anyString());
        }
    }
}
