package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.ShareType;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FileSourceMapper;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.service.ShareAuditService;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.saga.FileSagaOrchestrator;
import cn.flying.test.builders.FileTestBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

/**
 * Tests for FileServiceImpl.
 * Verifies file sharing, ownership validation, and share management.
 *
 * Note: Some tests that require MyBatis-Plus lambda expressions are covered
 * in integration tests (DatabaseIT) due to lambda cache requirements.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileService Tests")
class FileServiceTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileShareMapper fileShareMapper;

    @Mock
    private FileSourceMapper fileSourceMapper;

    @Mock
    private FileRemoteClient fileRemoteClient;

    @Mock
    private FileSagaOrchestrator sagaOrchestrator;

    @Mock
    private ShareAuditService shareAuditService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @InjectMocks
    private FileServiceImpl fileService;

    // Pre-created mock results to avoid stubbing issues
    @Mock
    private Result<String> shareResult;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final String SHARE_CODE = "ABC123";
    private static final String FILE_HASH = "sha256_test_hash";

    @BeforeEach
    void setUp() {
        FileTestBuilder.resetIdCounter();
        ReflectionTestUtils.setField(fileService, "baseMapper", fileMapper);
        when(cacheManager.getCache(anyString())).thenReturn(cache);
    }

    // ================== Helper Methods ==================

    private FileShare aFileShare() {
        return new FileShare()
                .setId(1L)
                .setUserId(USER_ID)
                .setShareCode(SHARE_CODE)
                .setShareType(ShareType.PUBLIC.getCode())
                .setFileHashes("[\"" + FILE_HASH + "\"]")
                .setExpireTime(new Date(System.currentTimeMillis() + 3600000)) // 1 hour from now
                .setAccessCount(0)
                .setStatus(FileShare.STATUS_ACTIVE)
                .setCreateTime(new Date());
    }

    private FileShare aFileShare(Consumer<FileShare> customizer) {
        FileShare share = aFileShare();
        customizer.accept(share);
        return share;
    }

    private UpdateShareVO createUpdateShareVO(String shareCode, Integer shareType, Integer extendMinutes) {
        UpdateShareVO vo = new UpdateShareVO();
        vo.setShareCode(shareCode);
        vo.setShareType(shareType);
        vo.setExtendMinutes(extendMinutes);
        return vo;
    }

    private void setupShareResultSuccess(String shareCode) {
        lenient().when(shareResult.getCode()).thenReturn(200);
        lenient().when(shareResult.getData()).thenReturn(shareCode);
    }

    // ================== Generate Sharing Code Tests ==================

    @Nested
    @DisplayName("Generate Sharing Code")
    class GenerateSharingCode {

        @Test
        @DisplayName("should generate share code for owned files")
        void shouldGenerateShareCodeForOwnedFiles() {
            // Given
            List<String> fileHashes = List.of(FILE_HASH);
            int expireMinutes = 60;

            when(fileMapper.selectCount(any())).thenReturn(1L);
            setupShareResultSuccess(SHARE_CODE);
            when(fileRemoteClient.shareFiles(any())).thenReturn(shareResult);
            doAnswer(inv -> {
                FileShare fs = inv.getArgument(0);
                fs.setId(1L);
                return 1;
            }).when(fileShareMapper).insert(any(FileShare.class));

            // When
            String result = fileService.generateSharingCode(USER_ID, fileHashes, expireMinutes, ShareType.PUBLIC.getCode());

            // Then
            assertEquals(SHARE_CODE, result);

            // Verify FileShare was created with correct parameters
            ArgumentCaptor<FileShare> shareCaptor = ArgumentCaptor.forClass(FileShare.class);
            verify(fileShareMapper).insert(shareCaptor.capture());
            FileShare capturedShare = shareCaptor.getValue();

            assertEquals(USER_ID, capturedShare.getUserId());
            assertEquals(SHARE_CODE, capturedShare.getShareCode());
            assertEquals(ShareType.PUBLIC.getCode(), capturedShare.getShareType());
            assertTrue(capturedShare.getFileHashes().contains(FILE_HASH));
            assertNotNull(capturedShare.getExpireTime());
            assertEquals(FileShare.STATUS_ACTIVE, capturedShare.getStatus());
        }

        @Test
        @DisplayName("should generate private share code")
        void shouldGeneratePrivateShareCode() {
            // Given
            List<String> fileHashes = List.of(FILE_HASH);

            when(fileMapper.selectCount(any())).thenReturn(1L);
            setupShareResultSuccess(SHARE_CODE);
            when(fileRemoteClient.shareFiles(any())).thenReturn(shareResult);
            doAnswer(inv -> {
                FileShare fs = inv.getArgument(0);
                fs.setId(1L);
                return 1;
            }).when(fileShareMapper).insert(any(FileShare.class));

            // When
            String result = fileService.generateSharingCode(USER_ID, fileHashes, 30, ShareType.PRIVATE.getCode());

            // Then
            assertEquals(SHARE_CODE, result);

            ArgumentCaptor<FileShare> shareCaptor = ArgumentCaptor.forClass(FileShare.class);
            verify(fileShareMapper).insert(shareCaptor.capture());
            assertEquals(ShareType.PRIVATE.getCode(), shareCaptor.getValue().getShareType());
        }

        @Test
        @DisplayName("should reject empty file list")
        void shouldRejectEmptyFileList() {
            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.generateSharingCode(USER_ID, List.of(), 60, ShareType.PUBLIC.getCode()));

            assertEquals(ResultEnum.PARAM_ERROR.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject invalid expire time")
        void shouldRejectInvalidExpireTime() {
            // Given
            List<String> fileHashes = List.of(FILE_HASH);

            // When & Then - null expire time
            GeneralException ex1 = assertThrows(GeneralException.class, () ->
                    fileService.generateSharingCode(USER_ID, fileHashes, null, ShareType.PUBLIC.getCode()));
            assertEquals(ResultEnum.PARAM_ERROR.getCode(), ex1.getResultEnum().getCode());

            // When & Then - zero expire time
            GeneralException ex2 = assertThrows(GeneralException.class, () ->
                    fileService.generateSharingCode(USER_ID, fileHashes, 0, ShareType.PUBLIC.getCode()));
            assertEquals(ResultEnum.PARAM_ERROR.getCode(), ex2.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject sharing unowned files")
        void shouldRejectSharingUnownedFiles() {
            // Given
            List<String> fileHashes = List.of(FILE_HASH);
            when(fileMapper.selectCount(any())).thenReturn(0L); // User doesn't own the file

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.generateSharingCode(USER_ID, fileHashes, 60, ShareType.PUBLIC.getCode()));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
        }
    }

    // ================== Cancel Share Tests ==================
    // Note: Full cancel share tests are in integration tests due to MyBatis-Plus lambda cache requirements

    @Nested
    @DisplayName("Cancel Share")
    class CancelShare {

        @Test
        @DisplayName("should reject cancelling non-existent share")
        void shouldRejectCancellingNonExistentShare() {
            // Given
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.cancelShare(USER_ID, SHARE_CODE));

            assertEquals(ResultEnum.FAIL.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject cancelling others' share")
        void shouldRejectCancellingOthersShare() {
            // Given
            FileShare share = aFileShare(s -> s.setUserId(OTHER_USER_ID));
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.cancelShare(USER_ID, SHARE_CODE));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject cancelling already cancelled share")
        void shouldRejectCancellingAlreadyCancelledShare() {
            // Given
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_CANCELLED));
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.cancelShare(USER_ID, SHARE_CODE));

            assertEquals(ResultEnum.FAIL.getCode(), ex.getResultEnum().getCode());
        }
    }

    // ================== Update Share Tests ==================
    // Note: Full update share tests are in integration tests due to MyBatis-Plus lambda cache requirements

    @Nested
    @DisplayName("Update Share")
    class UpdateShare {

        @Test
        @DisplayName("should reject updating non-existent share")
        void shouldRejectUpdatingNonExistentShare() {
            // Given
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(null);
            UpdateShareVO updateVO = createUpdateShareVO(SHARE_CODE, ShareType.PRIVATE.getCode(), null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.updateShare(USER_ID, updateVO));

            assertEquals(ResultEnum.FAIL.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject updating others' share")
        void shouldRejectUpdatingOthersShare() {
            // Given
            FileShare share = aFileShare(s -> s.setUserId(OTHER_USER_ID));
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
            UpdateShareVO updateVO = createUpdateShareVO(SHARE_CODE, ShareType.PRIVATE.getCode(), null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.updateShare(USER_ID, updateVO));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject updating cancelled share")
        void shouldRejectUpdatingCancelledShare() {
            // Given
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_CANCELLED));
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
            UpdateShareVO updateVO = createUpdateShareVO(SHARE_CODE, ShareType.PRIVATE.getCode(), null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.updateShare(USER_ID, updateVO));

            assertEquals(ResultEnum.FAIL.getCode(), ex.getResultEnum().getCode());
        }
    }

    // ================== Delete Files Tests ==================
    // Note: Full delete tests are in integration tests due to MyBatis-Plus lambda cache requirements

    @Nested
    @DisplayName("Delete Files")
    class DeleteFiles {

        @Test
        @DisplayName("should skip empty identifier list")
        void shouldSkipEmptyIdentifierList() {
            // When
            fileService.deleteFiles(USER_ID, List.of());

            // Then
            verify(fileMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("should skip null identifier list")
        void shouldSkipNullIdentifierList() {
            // When
            fileService.deleteFiles(USER_ID, null);

            // Then
            verify(fileMapper, never()).selectList(any());
        }
    }

    // ================== Get User Files Tests ==================

    @Nested
    @DisplayName("Get User Files")
    class GetUserFiles {

        @Test
        @DisplayName("should return user files list")
        void shouldReturnUserFilesList() {
            // Given
            File file1 = FileTestBuilder.aFile(f -> f.setUid(USER_ID).setFileName("file1.txt"));
            File file2 = FileTestBuilder.aFile(f -> f.setUid(USER_ID).setFileName("file2.txt"));

            when(fileMapper.selectList(any())).thenReturn(List.of(file1, file2));

            // When
            List<File> result = fileService.getUserFilesList(USER_ID);

            // Then
            assertNotNull(result);
            assertEquals(2, result.size());
            verify(fileMapper).selectList(any());
        }

        @Test
        @DisplayName("should return empty list when user has no files")
        void shouldReturnEmptyListWhenNoFiles() {
            // Given
            when(fileMapper.selectList(any())).thenReturn(List.of());

            // When
            List<File> result = fileService.getUserFilesList(USER_ID);

            // Then
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ================== Get Share By Code Tests ==================

    @Nested
    @DisplayName("Get Share By Code")
    class GetShareByCode {

        @Test
        @DisplayName("should return active share and increment access count")
        void shouldReturnActiveShareAndIncrementCount() {
            // Given
            FileShare share = aFileShare();
            when(fileShareMapper.markAsExpiredIfNecessary(SHARE_CODE)).thenReturn(0);
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            // When
            FileShare result = fileService.getShareByCode(SHARE_CODE);

            // Then
            assertNotNull(result);
            assertEquals(FileShare.STATUS_ACTIVE, result.getStatus());
            assertEquals(SHARE_CODE, result.getShareCode());
            assertEquals(USER_ID, result.getUserId());
            assertTrue(result.getFileHashes().contains(FILE_HASH));
            verify(fileShareMapper).incrementAccessCountIfActive(SHARE_CODE);
        }

        @Test
        @DisplayName("should mark expired share and return it")
        void shouldMarkExpiredShareAndReturnIt() {
            // Given
            FileShare share = aFileShare(); // Will be marked as expired
            when(fileShareMapper.markAsExpiredIfNecessary(SHARE_CODE)).thenReturn(1);
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            // When
            FileShare result = fileService.getShareByCode(SHARE_CODE);

            // Then
            assertNotNull(result);
            assertEquals(FileShare.STATUS_EXPIRED, result.getStatus());
            verify(fileShareMapper, never()).incrementAccessCountIfActive(SHARE_CODE);
        }

        @Test
        @DisplayName("should return null for non-existent share")
        void shouldReturnNullForNonExistentShare() {
            // Given
            when(fileShareMapper.markAsExpiredIfNecessary(SHARE_CODE)).thenReturn(0);
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(null);

            // When
            FileShare result = fileService.getShareByCode(SHARE_CODE);

            // Then
            assertNull(result);
        }

        @Test
        @DisplayName("should not increment count for non-active share")
        void shouldNotIncrementCountForNonActiveShare() {
            // Given
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_CANCELLED));
            when(fileShareMapper.markAsExpiredIfNecessary(SHARE_CODE)).thenReturn(0);
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            // When
            FileShare result = fileService.getShareByCode(SHARE_CODE);

            // Then
            assertNotNull(result);
            verify(fileShareMapper, never()).incrementAccessCountIfActive(SHARE_CODE);
        }
    }

    // ================== Share Validation Tests ==================

    @Nested
    @DisplayName("Share Validation")
    class ShareValidation {

        @Test
        @DisplayName("should validate share ownership correctly")
        void shouldValidateShareOwnership() {
            // Given - share owned by USER_ID
            FileShare share = aFileShare();
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            // When & Then - OTHER_USER_ID tries to cancel -> should fail
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.cancelShare(OTHER_USER_ID, SHARE_CODE));
            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should validate share status correctly")
        void shouldValidateShareStatus() {
            // Given - already cancelled share
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_CANCELLED));
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            // When & Then - owner tries to cancel again -> should fail
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.cancelShare(USER_ID, SHARE_CODE));
            assertEquals(ResultEnum.FAIL.getCode(), ex.getResultEnum().getCode());
        }
    }
}
