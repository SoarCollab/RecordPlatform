package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.ShareType;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.dto.FileSource;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FileSourceMapper;
import cn.flying.dao.vo.file.ShareFileVO;
import cn.flying.dao.vo.file.ShareInfoVO;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.CancelShareRequest;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.QuotaService;
import cn.flying.service.ShareAuditService;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.saga.FileSagaOrchestrator;
import cn.flying.test.builders.FileTestBuilder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;
import java.util.Map;
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

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock versionLock;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private QuotaService quotaService;

    @InjectMocks
    private FileServiceImpl fileService;

    // Pre-created mock results to avoid stubbing issues
    @Mock
    private Result<String> shareResult;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final String SHARE_CODE = "ABC123";
    private static final String FILE_HASH = "sha256_test_hash";

    /**
     * 初始化 MyBatis-Plus Lambda 缓存，避免在纯 Mockito 场景下构造 LambdaWrapper 失败。
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, File.class);
        TableInfoHelper.initTableInfo(assistant, FileShare.class);
    }

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
                .setTenantId(1L)
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

    @Nested
    @DisplayName("Get File")
    class GetFileTests {

        /**
         * 验证非管理员访问他人文件时会被拒绝。
         */
        @Test
        @DisplayName("should reject non-admin when file is not owned")
        void shouldRejectNonAdminWhenFileIsNotOwned() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                when(fileMapper.selectCount(any())).thenReturn(0L);

                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileService.getFile(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
            }
        }

        /**
         * 验证远端返回空文件详情时会抛出业务异常。
         */
        @Test
        @DisplayName("should throw when remote file detail is null")
        void shouldThrowWhenRemoteFileDetailIsNull() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                when(fileMapper.selectOne(any())).thenReturn(new File()
                        .setUid(USER_ID)
                        .setFileHash(FILE_HASH)
                        .setFileSize(1024L));
                when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH)).thenReturn(Result.success((FileDetailVO) null));

                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileService.getFile(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.FAIL.getCode(), ex.getResultEnum().getCode());
            }
        }

        /**
         * 验证普通用户通过本地所有权校验后，可以按区块链文件内容映射读取分片字节。
         */
        @Test
        @DisplayName("should return file bytes for owned file")
        void shouldReturnFileBytesForOwnedFile() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                File ownedFile = new File()
                        .setUid(USER_ID)
                        .setFileHash(FILE_HASH)
                        .setFileSize(1024L);
                FileDetailVO detail = new FileDetailVO(
                        String.valueOf(USER_ID),
                        "owned.txt",
                        "{}",
                        "{\"node-a\":\"hash-a\"}",
                        FILE_HASH,
                        "2026-06-27T00:00:00Z",
                        1L,
                        1024L,
                        "text/plain");
                byte[] payload = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);

                when(fileMapper.selectOne(any())).thenReturn(ownedFile);
                when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH)).thenReturn(Result.success(detail));
                when(fileRemoteClient.getFileListByHash(List.of("hash-a"), List.of("node-a")))
                        .thenReturn(Result.success(List.of(payload)));

                List<byte[]> result = fileService.getFile(USER_ID, FILE_HASH);

                assertEquals(1, result.size());
                assertArrayEquals(payload, result.get(0));
                verify(fileRemoteClient).getFileListByHash(List.of("hash-a"), List.of("node-a"));
            }
        }

        /**
         * 验证超出当前内存型下载上限的文件不会继续调用远端 byte[] 聚合接口。
         */
        @Test
        @DisplayName("should reject oversized in-memory download before remote fetch")
        void shouldRejectOversizedInMemoryDownloadBeforeRemoteFetch() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                when(fileMapper.selectOne(any())).thenReturn(new File()
                        .setUid(USER_ID)
                        .setFileHash(FILE_HASH)
                        .setFileSize(81L * 1024 * 1024));

                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileService.getFile(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.PARAM_ERROR.getCode(), ex.getResultEnum().getCode());
                verify(fileRemoteClient, never()).getFile(anyString(), anyString());
                verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
            }
        }
    }

    @Nested
    @DisplayName("Share File Lookup")
    class ShareFileLookupTests {

        /**
         * 验证分享过期时间为负值时返回“已取消”异常。
         */
        @Test
        @DisplayName("should throw cancelled when expiration is negative")
        void shouldThrowCancelledWhenExpirationIsNegative() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE))
                    .thenReturn(aFileShare(s -> s.setStatus(FileShare.STATUS_CANCELLED)));

            GeneralException ex = assertThrows(GeneralException.class, () -> fileService.getShareFile(SHARE_CODE));

            assertEquals(ResultEnum.SHARE_CANCELLED.getCode(), ex.getResultEnum().getCode());
        }

        /**
         * 验证分享过期时间早于当前时间时返回“已过期”异常。
         */
        @Test
        @DisplayName("should throw expired when share is timeout")
        void shouldThrowExpiredWhenShareIsTimeout() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE))
                    .thenReturn(aFileShare(s -> s.setExpireTime(new Date(System.currentTimeMillis() - 1000))));

            GeneralException ex = assertThrows(GeneralException.class, () -> fileService.getShareFile(SHARE_CODE));

            assertEquals(ResultEnum.SHARE_EXPIRED.getCode(), ex.getResultEnum().getCode());
        }

        /**
         * 验证分享有效位为 false 时返回“已取消”异常。
         */
        @Test
        @DisplayName("should throw cancelled when share is invalid")
        void shouldThrowCancelledWhenShareIsInvalid() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE))
                    .thenReturn(aFileShare(s -> s.setStatus(FileShare.STATUS_CANCELLED)));

            GeneralException ex = assertThrows(GeneralException.class, () -> fileService.getShareFile(SHARE_CODE));

            assertEquals(ResultEnum.SHARE_CANCELLED.getCode(), ex.getResultEnum().getCode());
        }

        /**
         * 验证公开分享文件列表只在分享所属租户内读取，并转换为安全展示 VO。
         */
        @Test
        @DisplayName("should return safe shared files for public share")
        void shouldReturnSafeSharedFilesForPublicShare() {
            File sourceFile = new File()
                    .setId(7L)
                    .setTenantId(1L)
                    .setUid(USER_ID)
                    .setFileName("public.txt")
                    .setFileHash(FILE_HASH)
                    .setFileSize(1024L)
                    .setContentType("text/plain")
                    .setDeleted(0);
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(aFileShare());
            when(fileMapper.selectList(any())).thenReturn(List.of(sourceFile));

            List<ShareFileVO> result;
            try (MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                idUtilsMock.when(() -> IdUtils.toExternalId(7L)).thenReturn("ext_7");
                result = fileService.getShareFile(SHARE_CODE);
            }

            assertEquals(1, result.size());
            assertEquals("ext_7", result.get(0).id());
            assertEquals("public.txt", result.get(0).fileName());
            assertEquals(FILE_HASH, result.get(0).fileHash());
        }
    }

    @Nested
    @DisplayName("Share Info Security")
    class ShareInfoSecurity {

        /**
         * 验证公开分享详情只返回安全文件视图，不暴露 fileParam 中的解密密钥。
         */
        @Test
        @DisplayName("should return safe file view for public share info")
        void shouldReturnSafeFileViewForPublicShareInfo() {
            File sourceFile = new File()
                    .setId(1L)
                    .setTenantId(1L)
                    .setUid(USER_ID)
                    .setFileName("public.txt")
                    .setFileHash(FILE_HASH)
                    .setFileParam("{\"initialKey\":\"secret\"}")
                    .setDeleted(0);
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(aFileShare());
            when(fileMapper.selectList(any())).thenReturn(List.of(sourceFile));

            ShareInfoVO info;
            try (MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                idUtilsMock.when(() -> IdUtils.toExternalId(1L)).thenReturn("ext_1");
                info = fileService.getShareInfo(SHARE_CODE);
            }

            assertNotNull(info);
            assertEquals(SHARE_CODE, info.getShareCode());
            assertEquals(1, info.getFiles().size());
            assertEquals("ext_1", info.getFiles().get(0).id());
            assertEquals("public.txt", info.getFiles().get(0).fileName());
        }

        /**
         * 验证匿名分享详情入口不能返回私密分享元数据。
         */
        @Test
        @DisplayName("should reject private share info")
        void shouldRejectPrivateShareInfo() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE))
                    .thenReturn(aFileShare(s -> s.setShareType(ShareType.PRIVATE.getCode())));

            GeneralException ex = assertThrows(GeneralException.class, () -> fileService.getShareInfo(SHARE_CODE));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
            verify(fileMapper, never()).selectList(any());
        }

        /**
         * 验证公开分享缺少文件哈希时返回空文件状态，不继续读取文件表。
         */
        @Test
        @DisplayName("should return empty status when share has no file hashes")
        void shouldReturnEmptyStatusWhenShareHasNoFileHashes() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE))
                    .thenReturn(aFileShare(s -> s.setFileHashes("[]")));

            ShareInfoVO info = fileService.getShareInfo(SHARE_CODE);

            assertNotNull(info);
            assertEquals(SHARE_CODE, info.getShareCode());
            assertEquals(ShareInfoVO.STATUS_EMPTY_FILES, info.getStatus());
            verify(fileMapper, never()).selectList(any());
        }
    }

    @Nested
    @DisplayName("Save Share File Authorization")
    class SaveShareFileAuthorization {

        /**
         * 验证保存分享文件时必须校验文件 ID 属于该分享码授权的文件集合。
         */
        @Test
        @DisplayName("should reject file id not authorized by share code before copying")
        void shouldRejectFileIdNotAuthorizedByShareCodeBeforeCopying() {
            FileShare share = aFileShare(s -> {
                s.setUserId(OTHER_USER_ID);
                s.setFileHashes("[\"allowed-hash\"]");
            });
            File sourceFile = new File()
                    .setId(99L)
                    .setTenantId(1L)
                    .setUid(OTHER_USER_ID)
                    .setFileHash("other-hash")
                    .setFileName("secret.txt")
                    .setFileParam("{\"initialKey\":\"secret\"}")
                    .setDeleted(0);

            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
            when(fileMapper.selectList(any())).thenReturn(List.of(sourceFile));

            MDC.put(Const.ATTR_USER_ID, String.valueOf(USER_ID));
            TenantContext.setTenantId(2L);
            try {
                GeneralException ex = assertThrows(GeneralException.class,
                        () -> fileService.saveShareFile(List.of("99"), SHARE_CODE, "127.0.0.1"));

                assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
                verify(fileMapper, never()).insert(any(File.class));
                verify(fileSourceMapper, never()).insert(any(FileSource.class));
                verify(shareAuditService, never()).logShareSave(anyString(), anyLong(), anyString(), anyString(), anyString());
            } finally {
                MDC.clear();
                TenantContext.clear();
            }
        }

        /**
         * 验证保存分享文件必须绑定有效分享码，旧式仅传文件 ID 的克隆请求会被拒绝。
         */
        @Test
        @DisplayName("should reject save share file without share code")
        void shouldRejectSaveShareFileWithoutShareCode() {
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> fileService.saveShareFile(List.of("99"), null, "127.0.0.1"));

            assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), ex.getResultEnum().getCode());
            verify(fileMapper, never()).selectList(any());
            verify(fileMapper, never()).insert(any(File.class));
        }

        /**
         * 验证保存分享文件成功时复制源文件、写入来源链路，并记录分享保存审计。
         */
        @Test
        @DisplayName("should copy authorized shared file and record provenance")
        void shouldCopyAuthorizedSharedFileAndRecordProvenance() {
            FileShare share = aFileShare(s -> {
                s.setTenantId(1L);
                s.setUserId(OTHER_USER_ID);
                s.setFileHashes("[\"allowed-hash\"]");
            });
            File sourceFile = new File()
                    .setId(99L)
                    .setTenantId(1L)
                    .setUid(OTHER_USER_ID)
                    .setFileHash("allowed-hash")
                    .setFileName("shared.txt")
                    .setFileParam("{\"fileSize\":1024,\"contentType\":\"text/plain\"}")
                    .setStatus(1)
                    .setDeleted(0)
                    .setVersion(1)
                    .setIsLatest(1)
                    .setVersionGroupId(99L);

            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
            when(fileMapper.selectList(any())).thenReturn(List.of(sourceFile));
            when(fileSourceMapper.selectByFileId(99L, 1L)).thenReturn(null);
            when(fileMapper.insert(any(File.class))).thenAnswer(invocation -> {
                File copied = invocation.getArgument(0);
                copied.setId(300L);
                return 1;
            });

            MDC.put(Const.ATTR_USER_ID, String.valueOf(USER_ID));
            TenantContext.setTenantId(2L);
            try {
                fileService.saveShareFile(List.of("99"), SHARE_CODE, "127.0.0.1");
            } finally {
                MDC.clear();
                TenantContext.clear();
            }

            ArgumentCaptor<File> copiedCaptor = ArgumentCaptor.forClass(File.class);
            verify(fileMapper).insert(copiedCaptor.capture());
            File copied = copiedCaptor.getValue();
            assertEquals(USER_ID, copied.getUid());
            assertEquals(99L, copied.getOrigin());
            assertEquals(OTHER_USER_ID, copied.getSharedFromUserId());
            assertEquals("allowed-hash", copied.getFileHash());

            ArgumentCaptor<FileSource> sourceCaptor = ArgumentCaptor.forClass(FileSource.class);
            verify(fileSourceMapper).insert(sourceCaptor.capture());
            FileSource provenance = sourceCaptor.getValue();
            assertEquals(300L, provenance.getFileId());
            assertEquals(99L, provenance.getSourceFileId());
            assertEquals(OTHER_USER_ID, provenance.getSourceUserId());
            assertEquals(SHARE_CODE, provenance.getShareCode());
            verify(shareAuditService).logShareSave(SHARE_CODE, USER_ID, "allowed-hash", "shared.txt", "127.0.0.1");
        }
    }

    @Nested
    @DisplayName("Create New Version Quota")
    class CreateNewVersionQuota {

        /**
         * 验证创建新版本 PREPARE 记录前会执行用户/租户配额检查。
         */
        @Test
        @DisplayName("should check quota before creating prepare version")
        void shouldCheckQuotaBeforeCreatingPrepareVersion() throws Exception {
            File parent = createLatestParentFile();
            when(fileMapper.selectById(parent.getId())).thenReturn(parent, parent);
            when(redissonClient.getLock("file:version:" + parent.getVersionGroupId())).thenReturn(versionLock);
            when(versionLock.tryLock(5, 30, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
            when(versionLock.isHeldByCurrentThread()).thenReturn(true);
            when(transactionTemplate.execute(any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                TransactionCallback<File> callback = inv.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });
            when(fileMapper.insert(any(File.class))).thenReturn(1);

            try (MockedStatic<IdUtils> idUtils = mockStatic(IdUtils.class)) {
                idUtils.when(IdUtils::nextEntityId).thenReturn(9002L);

                File result = fileService.createNewVersion(USER_ID, parent.getId(), "v2.txt", 4096L, "text/plain");

                assertNotNull(result);
                assertEquals(9002L, result.getId());
                verify(quotaService).checkUploadQuota(parent.getTenantId(), USER_ID, 4096L);
                verify(fileMapper).clearLatestInChain(parent.getVersionGroupId(), parent.getTenantId());
                verify(fileMapper).insert(any(File.class));
            }
        }

        /**
         * 验证配额拒绝时不会写入新版本 PREPARE 记录。
         */
        @Test
        @DisplayName("should not create prepare version when quota is exceeded")
        void shouldNotCreatePrepareVersionWhenQuotaExceeded() throws Exception {
            File parent = createLatestParentFile();
            when(fileMapper.selectById(parent.getId())).thenReturn(parent, parent);
            when(redissonClient.getLock("file:version:" + parent.getVersionGroupId())).thenReturn(versionLock);
            when(versionLock.tryLock(5, 30, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
            when(versionLock.isHeldByCurrentThread()).thenReturn(true);
            doThrow(new GeneralException(ResultEnum.QUOTA_EXCEEDED))
                    .when(quotaService).checkUploadQuota(parent.getTenantId(), USER_ID, 4096L);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> fileService.createNewVersion(USER_ID, parent.getId(), "v2.txt", 4096L, "text/plain"));

            assertEquals(ResultEnum.QUOTA_EXCEEDED.getCode(), ex.getResultEnum().getCode());
            verify(transactionTemplate, never()).execute(any());
            verify(fileMapper, never()).insert(any(File.class));
            verify(versionLock).unlock();
        }

        private File createLatestParentFile() {
            return new File()
                    .setId(9001L)
                    .setTenantId(2L)
                    .setUid(USER_ID)
                    .setFileName("v1.txt")
                    .setFileHash(FILE_HASH)
                    .setStatus(cn.flying.common.constant.FileUploadStatus.SUCCESS.getCode())
                    .setVersion(1)
                    .setIsLatest(1)
                    .setVersionGroupId(9001L);
        }
    }

    @Nested
    @DisplayName("Generate Sharing Code Failure")
    class GenerateSharingCodeFailure {

        /**
         * 验证区块链返回空分享码时会抛出区块链错误。
         */
        @Test
        @DisplayName("should throw blockchain error when share code is empty")
        void shouldThrowBlockchainErrorWhenShareCodeIsEmpty() {
            when(fileMapper.selectCount(any())).thenReturn(1L);
            when(fileRemoteClient.shareFiles(any())).thenReturn(Result.success(""));

            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.generateSharingCode(USER_ID, List.of(FILE_HASH), 30, ShareType.PUBLIC.getCode()));

            assertEquals(ResultEnum.BLOCKCHAIN_ERROR.getCode(), ex.getResultEnum().getCode());
            verify(fileShareMapper, never()).insert(any(FileShare.class));
        }
    }

    @Nested
    @DisplayName("Cancel Share Blockchain Paths")
    class CancelShareBlockchainPaths {

        /**
         * 验证区块链取消分享返回失败时会抛出区块链错误。
         */
        @Test
        @DisplayName("should throw blockchain error when remote cancel fails")
        void shouldThrowBlockchainErrorWhenRemoteCancelFails() {
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_ACTIVE));
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
            when(fileRemoteClient.cancelShare(any(CancelShareRequest.class))).thenReturn(Result.success(false));

            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.cancelShare(USER_ID, SHARE_CODE));

            assertEquals(ResultEnum.BLOCKCHAIN_ERROR.getCode(), ex.getResultEnum().getCode());
            verify(fileShareMapper, never()).update(isNull(), any());
        }

        /**
         * 验证区块链取消分享成功后会更新本地分享状态。
         */
        @Test
        @DisplayName("should update status when remote cancel succeeds")
        void shouldUpdateStatusWhenRemoteCancelSucceeds() {
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_ACTIVE));
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
            when(fileRemoteClient.cancelShare(any(CancelShareRequest.class))).thenReturn(Result.success(true));

            assertDoesNotThrow(() -> fileService.cancelShare(USER_ID, SHARE_CODE));

            verify(fileShareMapper).update(isNull(), any());
        }
    }
}
