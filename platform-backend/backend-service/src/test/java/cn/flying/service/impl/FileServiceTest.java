package cn.flying.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import cn.flying.dao.entity.ProofBundleIssuance;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FileSourceMapper;
import cn.flying.dao.mapper.ProofBundleIssuanceMapper;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.ShareFileVO;
import cn.flying.dao.vo.file.ShareInfoVO;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.CancelShareRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.DirectMultipartCompletedPartVO;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.FileService;
import cn.flying.service.QuotaService;
import cn.flying.service.ShareAuditService;
import cn.flying.service.key.FileKeyEnvelopeService;
import cn.flying.service.key.FileParamEnvelopeResult;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.saga.FileSagaOrchestrator;
import cn.flying.service.saga.FileUploadResult;
import cn.flying.test.builders.FileTestBuilder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    private RedissonClient redissonClient;

    @Mock
    private RLock versionLock;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private QuotaService quotaService;

    @Mock
    private FileKeyEnvelopeService fileKeyEnvelopeService;

    @Mock
    private ProofBundleIssuanceMapper proofBundleIssuanceMapper;

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
        TableInfoHelper.initTableInfo(assistant, ProofBundleIssuance.class);
    }

    @BeforeEach
    void setUp() {
        FileTestBuilder.resetIdCounter();
        ReflectionTestUtils.setField(fileService, "baseMapper", fileMapper);
    }

    /**
     * 清理测试设置的租户上下文，避免 ThreadLocal 跨用例污染。
     */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
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
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(null);
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
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(null);
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

        /**
         * 验证创建公开分享时应用文本日志不会写入可直接使用的分享码。
         */
        @Test
        @DisplayName("should not log raw share code after creation")
        void shouldNotLogRawShareCodeAfterCreation() {
            when(fileMapper.selectCount(any())).thenReturn(1L);
            setupShareResultSuccess(SHARE_CODE);
            when(fileRemoteClient.shareFiles(any())).thenReturn(shareResult);
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(null);
            doAnswer(invocation -> {
                FileShare share = invocation.getArgument(0);
                share.setId(10L);
                return 1;
            }).when(fileShareMapper).insert(any(FileShare.class));

            Logger logger = (Logger) LoggerFactory.getLogger(FileServiceImpl.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                fileService.generateSharingCode(
                        USER_ID,
                        List.of(FILE_HASH),
                        60,
                        ShareType.PUBLIC.getCode());
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }

            assertTrue(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(SHARE_CODE)));
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

        /**
         * 验证服务边界不会把未知分享类型降级为公开分享。
         */
        @Test
        @DisplayName("should reject unsupported share type before remote or database side effects")
        void shouldRejectUnsupportedShareType() {
            GeneralException error = assertThrows(
                    GeneralException.class,
                    () -> fileService.generateSharingCode(USER_ID, List.of(FILE_HASH), 60, 99));

            assertEquals(ResultEnum.PARAM_IS_INVALID, error.getResultEnum());
            verifyNoInteractions(fileRemoteClient);
            verify(fileShareMapper, never()).insert(any(FileShare.class));
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

        /**
         * 验证直接调用服务时未知分享类型也在读取或更新数据库前被拒绝。
         */
        @Test
        @DisplayName("should reject unsupported share type before update lookup")
        void shouldRejectUnsupportedShareType() {
            UpdateShareVO updateVO = createUpdateShareVO(SHARE_CODE, 99, null);

            GeneralException error = assertThrows(
                    GeneralException.class,
                    () -> fileService.updateShare(USER_ID, updateVO));

            assertEquals(ResultEnum.PARAM_IS_INVALID, error.getResultEnum());
            verify(fileShareMapper, never()).selectByShareCode(anyString());
            verify(fileShareMapper, never()).update(any(), any());
        }

        /**
         * 验证延期使用 owner、非取消状态条件，并在同一 SQL 中恢复 active 状态。
         */
        @Test
        @DisplayName("should extend expired share with one cancellation-safe conditional update")
        void shouldExtendExpiredShareWithConditionalUpdate() {
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_EXPIRED));
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
            when(fileShareMapper.update(isNull(), any())).thenReturn(1);
            UpdateShareVO updateVO = createUpdateShareVO(SHARE_CODE, null, 60);

            fileService.updateShare(USER_ID, updateVO);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Wrapper<FileShare>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(fileShareMapper).update(isNull(), wrapperCaptor.capture());
            LambdaUpdateWrapper<FileShare> wrapper = (LambdaUpdateWrapper<FileShare>) wrapperCaptor.getValue();
            assertTrue(wrapper.getSqlSet().contains("expire_time"));
            assertTrue(wrapper.getSqlSet().contains("status"));
            assertTrue(wrapper.getSqlSegment().contains("share_code"));
            assertTrue(wrapper.getSqlSegment().contains("user_id"));
            assertTrue(wrapper.getSqlSegment().contains("status"));
        }

        /**
         * 验证空更新不会生成无 SET 子句的数据库语句。
         */
        @Test
        @DisplayName("should reject update without any changed field")
        void shouldRejectUpdateWithoutAnyChangedField() {
            UpdateShareVO updateVO = createUpdateShareVO(SHARE_CODE, null, null);

            GeneralException error = assertThrows(
                    GeneralException.class,
                    () -> fileService.updateShare(USER_ID, updateVO));

            assertEquals(ResultEnum.PARAM_IS_INVALID, error.getResultEnum());
            verify(fileShareMapper, never()).selectByShareCode(anyString());
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

        /**
         * 为无请求租户上下文的兼容入口提供窄全局租户定位。
         */
        @BeforeEach
        void stubGlobalTenantLookup() {
            lenient().when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(1L);
        }

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
            assertFalse(TenantContext.isSet());
            assertFalse(TenantContext.isIgnoreIsolation());
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
         * 验证有序链上内容不会因重复分片哈希而丢失分片。
         */
        @Test
        @DisplayName("should keep duplicate chunk hashes from ordered chain content")
        void shouldKeepDuplicateChunkHashesFromOrderedChainContent() {
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
                        """
                                [
                                  {"index":0,"cipherHash":"sha256:same","storagePath":"s3://node-a/final-0"},
                                  {"index":1,"cipherHash":"sha256:same","storagePath":"s3://node-a/final-1"}
                                ]
                                """,
                        FILE_HASH,
                        "2026-06-27T00:00:00Z",
                        1L,
                        1024L,
                        "text/plain");
                byte[] first = "first".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] second = "second".getBytes(java.nio.charset.StandardCharsets.UTF_8);

                when(fileMapper.selectOne(any())).thenReturn(ownedFile);
                when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH)).thenReturn(Result.success(detail));
                when(fileRemoteClient.getFileListByHash(
                        List.of("s3://node-a/final-0", "s3://node-a/final-1"),
                        List.of("sha256:same", "sha256:same")))
                        .thenReturn(Result.success(List.of(first, second)));

                List<byte[]> result = fileService.getFile(USER_ID, FILE_HASH);

                assertEquals(2, result.size());
                assertArrayEquals(first, result.get(0));
                assertArrayEquals(second, result.get(1));
                verify(fileRemoteClient).getFileListByHash(
                        List.of("s3://node-a/final-0", "s3://node-a/final-1"),
                        List.of("sha256:same", "sha256:same"));
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
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_CANCELLED));
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(share.getTenantId());
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            GeneralException ex = assertThrows(GeneralException.class, () -> fileService.getShareFile(SHARE_CODE));

            assertEquals(ResultEnum.SHARE_CANCELLED.getCode(), ex.getResultEnum().getCode());
        }

        /**
         * 验证分享过期时间早于当前时间时返回“已过期”异常。
         */
        @Test
        @DisplayName("should throw expired when share is timeout")
        void shouldThrowExpiredWhenShareIsTimeout() {
            FileShare share = aFileShare(s -> s.setExpireTime(new Date(System.currentTimeMillis() - 1000)));
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(share.getTenantId());
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            GeneralException ex = assertThrows(GeneralException.class, () -> fileService.getShareFile(SHARE_CODE));

            assertEquals(ResultEnum.SHARE_EXPIRED.getCode(), ex.getResultEnum().getCode());
        }

        /**
         * 验证分享有效位为 false 时返回“已取消”异常。
         */
        @Test
        @DisplayName("should throw cancelled when share is invalid")
        void shouldThrowCancelledWhenShareIsInvalid() {
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_CANCELLED));
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(share.getTenantId());
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

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
            FileShare share = aFileShare();
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(share.getTenantId());
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
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
            FileShare share = aFileShare();
            File sourceFile = new File()
                    .setId(1L)
                    .setTenantId(1L)
                    .setUid(USER_ID)
                    .setFileName("public.txt")
                    .setFileHash(FILE_HASH)
                    .setFileParam("{\"initialKey\":\"secret\"}")
                    .setDeleted(0);
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenAnswer(invocation -> {
                assertFalse(TenantContext.isIgnoreIsolation());
                assertEquals(99L, TenantContext.getTenantId());
                return share.getTenantId();
            });
            AtomicInteger shareLookups = new AtomicInteger();
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenAnswer(invocation -> {
                shareLookups.incrementAndGet();
                assertFalse(TenantContext.isIgnoreIsolation());
                assertEquals(share.getTenantId(), TenantContext.getTenantId());
                return share;
            });
            when(fileShareMapper.markAsExpiredIfNecessary(SHARE_CODE)).thenAnswer(invocation -> {
                assertFalse(TenantContext.isIgnoreIsolation());
                assertEquals(share.getTenantId(), TenantContext.getTenantId());
                return 0;
            });
            when(fileMapper.selectList(any())).thenReturn(List.of(sourceFile));

            TenantContext.setTenantId(99L);

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
            assertEquals(99L, TenantContext.getTenantId());
            assertFalse(TenantContext.isIgnoreIsolation());
            assertEquals(1, shareLookups.get());
        }

        /**
         * 验证匿名分享详情入口不能返回私密分享元数据。
         */
        @Test
        @DisplayName("should reject private share info")
        void shouldRejectPrivateShareInfo() {
            FileShare share = aFileShare(s -> s.setShareType(ShareType.PRIVATE.getCode()));
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(share.getTenantId());
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            GeneralException ex = assertThrows(GeneralException.class, () -> fileService.getShareInfo(SHARE_CODE));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
            verify(fileMapper, never()).selectList(any());
            verify(fileShareMapper, never()).incrementAccessCountIfActive(SHARE_CODE);
        }

        /**
         * 验证匿名分享详情对缺失或未知状态、类型均失败关闭。
         */
        @Test
        @DisplayName("should reject invalid status or type on public share info")
        void shouldRejectInvalidStatusOrTypeOnPublicShareInfo() {
            for (Integer invalidStatus : new Integer[]{null, 99}) {
                FileShare share = aFileShare(s -> s.setStatus(invalidStatus));
                when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(share.getTenantId());
                when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

                GeneralException ex = assertThrows(
                        GeneralException.class,
                        () -> fileService.getShareInfo(SHARE_CODE));
                assertEquals(ResultEnum.FAIL, ex.getResultEnum());
            }

            for (Integer invalidType : new Integer[]{null, 99}) {
                FileShare share = aFileShare(s -> s.setShareType(invalidType));
                when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(share.getTenantId());
                when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

                GeneralException ex = assertThrows(
                        GeneralException.class,
                        () -> fileService.getShareInfo(SHARE_CODE));
                assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED, ex.getResultEnum());
            }
            verify(fileMapper, never()).selectList(any());
            verify(fileShareMapper, never()).incrementAccessCountIfActive(SHARE_CODE);
        }

        /**
         * 验证公开分享缺少文件哈希时返回空文件状态，不继续读取文件表。
         */
        @Test
        @DisplayName("should return empty status when share has no file hashes")
        void shouldReturnEmptyStatusWhenShareHasNoFileHashes() {
            FileShare share = aFileShare(s -> s.setFileHashes("[]"));
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(share.getTenantId());
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            ShareInfoVO info = fileService.getShareInfo(SHARE_CODE);

            assertNotNull(info);
            assertEquals(SHARE_CODE, info.getShareCode());
            assertEquals(ShareInfoVO.STATUS_EMPTY_FILES, info.getStatus());
            verify(fileMapper, never()).selectList(any());
        }
    }

    @Nested
    @DisplayName("Public Share Tenant Boundary")
    class PublicShareTenantBoundary {

        /**
         * 验证公开分片下载只跨租户定位分享元数据，随后所有文件读取、远程调用和计数都在 owner 租户执行。
         */
        @Test
        @DisplayName("should execute public download in share owner tenant and restore caller context")
        void shouldExecutePublicDownloadInShareOwnerTenantAndRestoreCallerContext() {
            FileShare share = aFileShare(s -> s.setTenantId(7L));
            File sourceFile = new File()
                    .setId(8L)
                    .setTenantId(7L)
                    .setUid(USER_ID)
                    .setFileName("public.txt")
                    .setFileHash(FILE_HASH)
                    .setFileSize(3L);
            AtomicInteger shareLookups = stubGlobalThenOwnerShareLookup(share, 99L);

            when(fileMapper.selectOne(any())).thenAnswer(invocation -> {
                assertOwnerTenant(7L);
                return sourceFile;
            });
            when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH)).thenAnswer(invocation -> {
                assertOwnerTenant(7L);
                return Result.success(new FileDetailVO(
                        String.valueOf(USER_ID),
                        "public.txt",
                        null,
                        "{\"cipher-hash\":\"chunks/public-0\"}",
                        FILE_HASH,
                        null,
                        null,
                        3L,
                        "text/plain"
                ));
            });
            when(fileRemoteClient.getFileListByHash(
                    List.of("chunks/public-0"), List.of("cipher-hash"))).thenAnswer(invocation -> {
                assertOwnerTenant(7L);
                return Result.success(List.of("abc".getBytes()));
            });
            when(fileShareMapper.incrementAccessCount(SHARE_CODE)).thenAnswer(invocation -> {
                assertOwnerTenant(7L);
                return 1;
            });

            TenantContext.setTenantId(99L);
            TenantContext.setIgnoreIsolation(true);
            List<byte[]> chunks = fileService.getPublicFile(SHARE_CODE, FILE_HASH);

            assertEquals(1, chunks.size());
            assertArrayEquals("abc".getBytes(), chunks.getFirst());
            assertEquals(1, shareLookups.get());
            assertEquals(99L, TenantContext.getTenantId());
            assertTrue(TenantContext.isIgnoreIsolation());
        }

        /**
         * 验证公开解密信息在 owner 租户读取文件和密钥信封，并在返回后恢复原调用者租户。
         */
        @Test
        @DisplayName("should execute public decrypt lookup in share owner tenant and restore caller context")
        void shouldExecutePublicDecryptLookupInShareOwnerTenantAndRestoreCallerContext() {
            FileShare share = aFileShare(s -> s.setTenantId(7L));
            File sourceFile = new File()
                    .setId(8L)
                    .setTenantId(7L)
                    .setUid(USER_ID)
                    .setFileName("public.txt")
                    .setFileHash(FILE_HASH)
                    .setFileParam("""
                            {"encryptionAlgorithm":"AES-GCM","fileName":"public.txt","fileSize":3,"contentType":"text/plain","chunkCount":1}
                            """);
            AtomicInteger shareLookups = stubGlobalThenOwnerShareLookup(share, 99L);

            when(fileMapper.selectOne(any(), anyBoolean())).thenAnswer(invocation -> {
                assertOwnerTenant(7L);
                return sourceFile;
            });
            when(fileKeyEnvelopeService.unwrapActiveShareInitialKey(
                    eq(sourceFile),
                    eq(FILE_HASH),
                    eq(share),
                    isNull(),
                    eq("SHARE_DECRYPT")
            )).thenAnswer(invocation -> {
                assertOwnerTenant(7L);
                return Optional.of("owner-share-key");
            });

            TenantContext.setTenantId(99L);
            FileDecryptInfoVO decryptInfo = fileService.getPublicFileDecryptInfo(SHARE_CODE, FILE_HASH);

            assertEquals("owner-share-key", decryptInfo.initialKey());
            assertEquals(FILE_HASH, decryptInfo.fileHash());
            assertEquals(1, shareLookups.get());
            assertEquals(99L, TenantContext.getTenantId());
            assertFalse(TenantContext.isIgnoreIsolation());
        }

        /**
         * 验证匿名公开入口不能借分享码读取私密分享的文件内容或解密信息。
         */
        @Test
        @DisplayName("should reject private share on both anonymous public file endpoints")
        void shouldRejectPrivateShareOnAnonymousPublicFileEndpoints() {
            FileShare share = aFileShare(s -> s.setShareType(ShareType.PRIVATE.getCode()));
            stubPublicShareLookup(share);

            GeneralException downloadError = assertThrows(
                    GeneralException.class,
                    () -> fileService.getPublicFile(SHARE_CODE, FILE_HASH));
            GeneralException decryptError = assertThrows(
                    GeneralException.class,
                    () -> fileService.getPublicFileDecryptInfo(SHARE_CODE, FILE_HASH));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED, downloadError.getResultEnum());
            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED, decryptError.getResultEnum());
            assertNoPublicFileDataWasRead();
        }

        /**
         * 验证取消状态的分享在两个匿名公开文件入口都失败关闭。
         */
        @Test
        @DisplayName("should reject cancelled share on both anonymous public file endpoints")
        void shouldRejectCancelledShareOnAnonymousPublicFileEndpoints() {
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_CANCELLED));
            stubPublicShareLookup(share);

            GeneralException downloadError = assertThrows(
                    GeneralException.class,
                    () -> fileService.getPublicFile(SHARE_CODE, FILE_HASH));
            GeneralException decryptError = assertThrows(
                    GeneralException.class,
                    () -> fileService.getPublicFileDecryptInfo(SHARE_CODE, FILE_HASH));

            assertEquals(ResultEnum.FAIL, downloadError.getResultEnum());
            assertEquals(ResultEnum.FAIL, decryptError.getResultEnum());
            assertNoPublicFileDataWasRead();
        }

        /**
         * 验证数据库已标记过期的分享即使过期时间异常地仍在未来，也不能被公开入口读取。
         */
        @Test
        @DisplayName("should reject expired status even when expire time is still in future")
        void shouldRejectExpiredStatusEvenWhenExpireTimeIsStillInFuture() {
            FileShare share = aFileShare(s -> s.setStatus(FileShare.STATUS_EXPIRED));
            stubPublicShareLookup(share);

            GeneralException downloadError = assertThrows(
                    GeneralException.class,
                    () -> fileService.getPublicFile(SHARE_CODE, FILE_HASH));
            GeneralException decryptError = assertThrows(
                    GeneralException.class,
                    () -> fileService.getPublicFileDecryptInfo(SHARE_CODE, FILE_HASH));

            assertEquals(ResultEnum.SHARE_EXPIRED, downloadError.getResultEnum());
            assertEquals(ResultEnum.SHARE_EXPIRED, decryptError.getResultEnum());
            assertNoPublicFileDataWasRead();
        }

        /**
         * 验证缺失或未知状态在两个匿名文件入口都失败关闭且不会触发数据读取。
         */
        @Test
        @DisplayName("should reject missing or unknown status on anonymous public file endpoints")
        void shouldRejectMissingOrUnknownStatusOnAnonymousPublicFileEndpoints() {
            for (Integer invalidStatus : new Integer[]{null, 99}) {
                FileShare share = aFileShare(s -> s.setStatus(invalidStatus));
                stubPublicShareLookup(share);

                GeneralException downloadError = assertThrows(
                        GeneralException.class,
                        () -> fileService.getPublicFile(SHARE_CODE, FILE_HASH));
                GeneralException decryptError = assertThrows(
                        GeneralException.class,
                        () -> fileService.getPublicFileDecryptInfo(SHARE_CODE, FILE_HASH));

                assertEquals(ResultEnum.FAIL, downloadError.getResultEnum());
                assertEquals(ResultEnum.FAIL, decryptError.getResultEnum());
            }
            assertNoPublicFileDataWasRead();
        }

        /**
         * 验证缺失或未知类型不能借枚举默认值降级成公开分享。
         */
        @Test
        @DisplayName("should reject missing or unknown type on anonymous public file endpoints")
        void shouldRejectMissingOrUnknownTypeOnAnonymousPublicFileEndpoints() {
            for (Integer invalidType : new Integer[]{null, 99}) {
                FileShare share = aFileShare(s -> s.setShareType(invalidType));
                stubPublicShareLookup(share);

                GeneralException downloadError = assertThrows(
                        GeneralException.class,
                        () -> fileService.getPublicFile(SHARE_CODE, FILE_HASH));
                GeneralException decryptError = assertThrows(
                        GeneralException.class,
                        () -> fileService.getPublicFileDecryptInfo(SHARE_CODE, FILE_HASH));

                assertEquals(ResultEnum.FAIL, downloadError.getResultEnum());
                assertEquals(ResultEnum.FAIL, decryptError.getResultEnum());
            }
            assertNoPublicFileDataWasRead();
        }

        /**
         * 验证未知持久化类型不能通过登录态分享下载或解密入口绕过类型校验。
         */
        @Test
        @DisplayName("should reject unknown type on authenticated share file endpoints")
        void shouldRejectUnknownTypeOnAuthenticatedShareFileEndpoints() {
            FileShare share = aFileShare(s -> s.setShareType(99));
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            GeneralException downloadError = assertThrows(
                    GeneralException.class,
                    () -> fileService.getSharedFileContent(OTHER_USER_ID, SHARE_CODE, FILE_HASH));
            GeneralException decryptError = assertThrows(
                    GeneralException.class,
                    () -> fileService.getSharedFileDecryptInfo(OTHER_USER_ID, SHARE_CODE, FILE_HASH));

            assertEquals(ResultEnum.FAIL, downloadError.getResultEnum());
            assertEquals(ResultEnum.FAIL, decryptError.getResultEnum());
            assertNoPublicFileDataWasRead();
        }

        /**
         * 验证自然过期只调用带状态和时间条件的原子更新，不再执行无条件状态覆盖。
         */
        @Test
        @DisplayName("should avoid unconditional status update when share expires naturally")
        void shouldAvoidUnconditionalStatusUpdateWhenShareExpiresNaturally() {
            FileShare share = aFileShare(s -> s.setExpireTime(new Date(System.currentTimeMillis() - 1_000L)));
            stubPublicShareLookup(share);
            when(fileShareMapper.markAsExpiredIfNecessary(SHARE_CODE)).thenReturn(1);

            GeneralException error = assertThrows(
                    GeneralException.class,
                    () -> fileService.getPublicFile(SHARE_CODE, FILE_HASH));

            assertEquals(ResultEnum.SHARE_EXPIRED, error.getResultEnum());
            verify(fileShareMapper).markAsExpiredIfNecessary(SHARE_CODE);
            verify(fileShareMapper, never()).update(any(), any());
            assertNoPublicFileDataWasRead();
        }

        /**
         * 验证分享未授权的文件哈希不能触发文件、密钥或远端存储读取。
         */
        @Test
        @DisplayName("should reject file hash that is not included in public share")
        void shouldRejectFileHashThatIsNotIncludedInPublicShare() {
            FileShare share = aFileShare();
            stubPublicShareLookup(share);
            String unauthorizedHash = "sha256_not_in_share";

            GeneralException downloadError = assertThrows(
                    GeneralException.class,
                    () -> fileService.getPublicFile(SHARE_CODE, unauthorizedHash));
            GeneralException decryptError = assertThrows(
                    GeneralException.class,
                    () -> fileService.getPublicFileDecryptInfo(SHARE_CODE, unauthorizedHash));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED, downloadError.getResultEnum());
            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED, decryptError.getResultEnum());
            assertNoPublicFileDataWasRead();
        }

        /**
         * 为失败关闭用例伪造窄全局租户定位与 owner 租户内分享查询。
         *
         * @param share 待校验分享
         */
        private void stubPublicShareLookup(FileShare share) {
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(share.getTenantId());
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
        }

        /**
         * 断言授权失败发生在读取租户文件、密钥或远端存储之前。
         */
        private void assertNoPublicFileDataWasRead() {
            verify(fileMapper, never()).selectOne(any());
            verify(fileMapper, never()).selectOne(any(), anyBoolean());
            verify(fileShareMapper, never()).incrementAccessCount(SHARE_CODE);
            verifyNoInteractions(fileRemoteClient, fileKeyEnvelopeService);
        }

        /**
         * 伪造全局分享定位与 owner 租户内的二次查询，并校验两阶段隔离边界。
         *
         * @param share 分享记录
         * @param callerTenantId 原调用者租户
         * @return 分享查询次数
         */
        private AtomicInteger stubGlobalThenOwnerShareLookup(FileShare share, long callerTenantId) {
            AtomicInteger shareLookups = new AtomicInteger();
            when(fileShareMapper.selectTenantIdByShareCodeGlobally(share.getShareCode())).thenAnswer(invocation -> {
                assertEquals(callerTenantId, TenantContext.getTenantId());
                return share.getTenantId();
            });
            when(fileShareMapper.selectByShareCode(share.getShareCode())).thenAnswer(invocation -> {
                shareLookups.incrementAndGet();
                assertOwnerTenant(share.getTenantId());
                return share;
            });
            return shareLookups;
        }

        /**
         * 断言当前执行只受 owner 租户隔离，未扩大跨租户绕过范围。
         *
         * @param ownerTenantId 分享 owner 租户
         */
        private void assertOwnerTenant(Long ownerTenantId) {
            assertEquals(ownerTenantId, TenantContext.getTenantId());
            assertFalse(TenantContext.isIgnoreIsolation());
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
                    .setContentHash("sha256:" + "a".repeat(64))
                    .setFileName("shared.txt")
                    .setFileParam("{\"fileSize\":1024,\"contentType\":\"text/plain\"}")
                    .setStatus(1)
                    .setDeleted(0)
                    .setVersion(3)
                    .setParentVersionId(98L)
                    .setIsLatest(1)
                    .setVersionGroupId(97L);

            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
            when(fileMapper.selectList(any())).thenReturn(List.of(sourceFile));
            when(fileSourceMapper.selectByFileId(99L, 1L)).thenReturn(null);
            when(fileMapper.insert(any(File.class))).thenReturn(1);

            MDC.put(Const.ATTR_USER_ID, String.valueOf(USER_ID));
            TenantContext.setTenantId(2L);
            try (MockedStatic<IdUtils> idUtils = mockStatic(IdUtils.class)) {
                idUtils.when(IdUtils::nextEntityId).thenReturn(300L);
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
            assertEquals("sha256:" + "a".repeat(64), copied.getContentHash());
            assertEquals(1, copied.getVersion());
            assertNull(copied.getParentVersionId());
            assertEquals(1, copied.getIsLatest());
            assertEquals(300L, copied.getVersionGroupId());

            ArgumentCaptor<FileSource> sourceCaptor = ArgumentCaptor.forClass(FileSource.class);
            verify(fileSourceMapper).insert(sourceCaptor.capture());
            FileSource provenance = sourceCaptor.getValue();
            assertEquals(300L, provenance.getFileId());
            assertEquals(99L, provenance.getSourceFileId());
            assertEquals(OTHER_USER_ID, provenance.getSourceUserId());
            assertEquals(SHARE_CODE, provenance.getShareCode());
            verify(fileKeyEnvelopeService).saveCopiedOwnerEnvelope(
                    sourceFile,
                    copied,
                    share,
                    USER_ID,
                    USER_ID,
                    "SHARE_SAVE_OWNER_ENVELOPE"
            );
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
            when(fileMapper.lockVersionGroupForProofLifecycle(
                    parent.getTenantId(), parent.getVersionGroupId())).thenReturn(parent.getId());
            when(fileMapper.insert(any(File.class))).thenReturn(1);

            try (MockedStatic<IdUtils> idUtils = mockStatic(IdUtils.class)) {
                idUtils.when(IdUtils::nextEntityId).thenReturn(9002L);

                File result = fileService.createNewVersion(USER_ID, parent.getId(), "v2.txt", 4096L, "text/plain");

                assertNotNull(result);
                assertEquals(9002L, result.getId());
                verify(quotaService).checkUploadQuota(parent.getTenantId(), USER_ID, 4096L);
                InOrder versionWriteOrder = inOrder(fileMapper);
                versionWriteOrder.verify(fileMapper).lockVersionGroupForProofLifecycle(
                        parent.getTenantId(), parent.getVersionGroupId());
                versionWriteOrder.verify(fileMapper).clearLatestInChain(
                        parent.getVersionGroupId(), parent.getTenantId());
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
    @DisplayName("File Store Integrity")
    class FileStoreIntegrity {

        private static final String CONTENT_HASH =
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        private static final String SANITIZED_FILE_PARAM =
                "{\"contentHash\":\"" + CONTENT_HASH + "\"}";

        /**
         * 验证同名直传会话只按稳定主键创建和复用 PREPARE，不执行按文件名取最新记录的查询。
         */
        @Test
        void prepareStoreFileWithStableIdShouldIsolateSameNameSessionsAndRemainIdempotent() {
            AtomicReference<File> firstInserted = new AtomicReference<>();
            AtomicReference<File> secondInserted = new AtomicReference<>();
            when(fileMapper.selectById(8101L)).thenAnswer(invocation -> firstInserted.get());
            when(fileMapper.selectById(8102L)).thenAnswer(invocation -> secondInserted.get());
            when(fileMapper.insert(any(File.class))).thenAnswer(invocation -> {
                File inserted = invocation.getArgument(0);
                if (Objects.equals(inserted.getId(), 8101L)) {
                    firstInserted.set(inserted);
                } else if (Objects.equals(inserted.getId(), 8102L)) {
                    secondInserted.set(inserted);
                }
                return 1;
            });

            File first = fileService.prepareStoreFileWithStableId(
                    USER_ID, null, 8101L, "same-name.pdf", 1024L);
            File second = fileService.prepareStoreFileWithStableId(
                    USER_ID, null, 8102L, "same-name.pdf", 1024L);
            File firstRetry = fileService.prepareStoreFileWithStableId(
                    USER_ID, null, 8101L, "same-name.pdf", 1024L);

            assertEquals(8101L, first.getId());
            assertEquals(8102L, second.getId());
            assertSame(first, firstRetry);
            assertEquals("same-name.pdf", first.getFileName());
            assertEquals("same-name.pdf", second.getFileName());
            verify(fileMapper, times(2)).insert(any(File.class));
            verify(fileMapper, never()).selectOne(any(), anyBoolean());
        }

        /**
         * 初始化普通上传和直传共享的 PREPARE 记录、事务回调与脱敏文件参数。
         *
         * @return 可推进为 SUCCESS 的首版本文件
         */
        private File prepareStoreFixture() {
            File existing = new File()
                    .setId(7001L)
                    .setTenantId(2L)
                    .setUid(USER_ID)
                    .setFileName("stored.txt")
                    .setFileSize(1024L)
                    .setVersion(1)
                    .setVersionGroupId(7001L)
                    .setStatus(cn.flying.common.constant.FileUploadStatus.PREPARE.getCode());
            when(fileMapper.selectOne(any(), anyBoolean())).thenReturn(existing);
            when(fileMapper.update(any(File.class), any())).thenReturn(1);
            when(fileKeyEnvelopeService.prepareFileParam(anyString()))
                    .thenReturn(FileParamEnvelopeResult.withoutEnvelope(SANITIZED_FILE_PARAM));
            doAnswer(invocation -> {
                Consumer<TransactionStatus> callback = invocation.getArgument(0);
                callback.accept(mock(TransactionStatus.class));
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());
            return existing;
        }

        /**
         * 验证普通分片上传成功后在事务内保存内容摘要、密钥信封并返回 SUCCESS 快照。
         */
        @Test
        void storeFileShouldPersistTrustedContentHashAndTransactionResult() {
            File existing = prepareStoreFixture();
            when(sagaOrchestrator.executeUpload(any(), any(), any()))
                    .thenAnswer(invocation -> {
                        Runnable beforeChain = invocation.getArgument(1);
                        Consumer<StoreFileResponse> afterChain = invocation.getArgument(2);
                        StoreFileResponse chainResult =
                                new StoreFileResponse("tx-normal", "file-hash-normal");
                        beforeChain.run();
                        afterChain.accept(chainResult);
                        return FileUploadResult.success(
                                chainResult.transactionHash(), chainResult.fileHash());
                    });

            File actual = fileService.storeFile(
                    USER_ID,
                    existing.getId(),
                    existing.getFileName(),
                    List.of(new java.io.File("chunk-0")),
                    List.of("chunk-hash-0"),
                    "raw-file-param");

            assertEquals(CONTENT_HASH, actual.getContentHash());
            assertEquals(1024L, actual.getFileSize());
            assertEquals("file-hash-normal", actual.getFileHash());
            assertEquals("tx-normal", actual.getTransactionHash());
            assertEquals(cn.flying.common.constant.FileUploadStatus.SUCCESS.getCode(), actual.getStatus());
            verify(fileKeyEnvelopeService).saveOwnerEnvelope(
                    eq(existing), eq("file-hash-normal"), eq(USER_ID), any(FileParamEnvelopeResult.class));
            ArgumentCaptor<File> updates = ArgumentCaptor.forClass(File.class);
            verify(fileMapper, times(4)).update(updates.capture(), any());
            File successUpdate = updates.getAllValues().stream()
                    .filter(update -> Objects.equals(
                            update.getStatus(),
                            cn.flying.common.constant.FileUploadStatus.SUCCESS.getCode()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(SANITIZED_FILE_PARAM, successUpdate.getFileParam());
            assertFalse(successUpdate.getFileParam().contains("_finalizationClaim"));
            ArgumentCaptor<cn.flying.service.saga.FileUploadCommand> commandCaptor =
                    ArgumentCaptor.forClass(cn.flying.service.saga.FileUploadCommand.class);
            verify(sagaOrchestrator).executeUpload(
                    commandCaptor.capture(), any(Runnable.class), any(Consumer.class));
            assertFalse(commandCaptor.getValue().getFileParam().contains("_finalizationClaim"));
        }

        /**
         * 验证直传完成元数据在链上登记成功后写回可信内容摘要与事务标识。
         */
        @Test
        void storeDirectUploadedFileShouldPersistTrustedContentHashAndTransactionResult() {
            File existing = prepareStoreFixture();
            DirectMultipartCompletedPartVO part = new DirectMultipartCompletedPartVO(
                    0,
                    "s3://node-a/final-0",
                    6L,
                    "\"etag-0\"",
                    "sha256:plain",
                    "sha256:cipher",
                    "SHA-256");
            when(fileRemoteClient.storeFileOnChainOnce(any()))
                    .thenReturn(Result.success(new StoreFileResponse("tx-direct", "file-hash-direct")));

            File actual = fileService.storeDirectUploadedFile(
                    USER_ID,
                    existing.getId(),
                    existing.getFileName(),
                    6L,
                    List.of(part),
                    "raw-file-param");

            assertEquals(6L, actual.getFileSize());
            assertEquals(CONTENT_HASH, actual.getContentHash());
            assertEquals("file-hash-direct", actual.getFileHash());
            assertEquals("tx-direct", actual.getTransactionHash());
            assertEquals(cn.flying.common.constant.FileUploadStatus.SUCCESS.getCode(), actual.getStatus());
            verify(fileKeyEnvelopeService).saveOwnerEnvelope(
                    eq(existing), eq("file-hash-direct"), eq(USER_ID), any(FileParamEnvelopeResult.class));
            ArgumentCaptor<File> updates = ArgumentCaptor.forClass(File.class);
            verify(fileMapper, times(4)).update(updates.capture(), any());
            File successUpdate = updates.getAllValues().stream()
                    .filter(update -> Objects.equals(
                            update.getStatus(),
                            cn.flying.common.constant.FileUploadStatus.SUCCESS.getCode()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(SANITIZED_FILE_PARAM, successUpdate.getFileParam());
            assertFalse(successUpdate.getFileParam().contains("_finalizationClaim"));
            ArgumentCaptor<cn.flying.platformapi.request.StoreFileRequest> requestCaptor =
                    ArgumentCaptor.forClass(cn.flying.platformapi.request.StoreFileRequest.class);
            verify(fileRemoteClient).storeFileOnChainOnce(requestCaptor.capture());
            assertFalse(requestCaptor.getValue().param().contains("_finalizationClaim"));
        }

        /**
         * 验证直传链响应不确定后 durable claim 阻断其他 owner，且不会再次调用链客户端。
         */
        @Test
        void directAmbiguousChainResultShouldBlockAnotherOwnerWithoutReplay() {
            File existing = prepareStoreFixture();
            DirectMultipartCompletedPartVO part = new DirectMultipartCompletedPartVO(
                    0, "s3://node-a/final-0", 6L, "\"etag-0\"",
                    "sha256:plain", "sha256:cipher", "SHA-256");
            when(fileRemoteClient.storeFileOnChainOnce(any()))
                    .thenThrow(new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "response lost"));

            assertThrows(GeneralException.class, () -> fileService.attestDirectUploadedFile(
                    USER_ID, existing.getId(), existing.getFileName(), List.of(part),
                    "raw-file-param", "direct-owner-a"));
            GeneralException blocked = assertThrows(GeneralException.class, () ->
                    fileService.attestDirectUploadedFile(
                            USER_ID, existing.getId(), existing.getFileName(), List.of(part),
                            "raw-file-param", "direct-owner-b"));

            assertEquals(ResultEnum.BLOCKCHAIN_ERROR, blocked.getResultEnum());
            assertTrue(existing.getFileParam().contains("\"phase\":\"CHAIN_ATTESTING\""));
            verify(fileRemoteClient, times(1)).storeFileOnChainOnce(any());
        }

        /**
         * 验证 DB 已持久化 ATTESTED 后同 owner 重入直接复用 tx/fileHash，不重复上链。
         */
        @Test
        void directAttestedClaimShouldRecoverWithoutRechain() {
            File existing = prepareStoreFixture();
            DirectMultipartCompletedPartVO part = new DirectMultipartCompletedPartVO(
                    0, "s3://node-a/final-0", 6L, "\"etag-0\"",
                    "sha256:plain", "sha256:cipher", "SHA-256");
            StoreFileResponse expected = new StoreFileResponse("tx-direct", "file-hash-direct");
            when(fileRemoteClient.storeFileOnChainOnce(any())).thenReturn(Result.success(expected));

            StoreFileResponse first = fileService.attestDirectUploadedFile(
                    USER_ID, existing.getId(), existing.getFileName(), List.of(part),
                    "raw-file-param", "direct-owner-a");
            StoreFileResponse recovered = fileService.attestDirectUploadedFile(
                    USER_ID, existing.getId(), existing.getFileName(), List.of(part),
                    "raw-file-param", "direct-owner-a");

            assertEquals(expected, first);
            assertEquals(expected, recovered);
            assertTrue(existing.getFileParam().contains("\"phase\":\"CHAIN_ATTESTED\""));
            verify(fileRemoteClient, times(1)).storeFileOnChainOnce(any());
        }

        /**
         * 验证首次 claim CAS 冲突在任何链或 Saga 副作用前以可重试错误失败。
         */
        @Test
        void finalizationClaimCasConflictShouldFailBeforeExternalCalls() {
            File existing = prepareStoreFixture();
            when(fileMapper.update(any(File.class), any())).thenReturn(0);
            DirectMultipartCompletedPartVO part = new DirectMultipartCompletedPartVO(
                    0, "s3://node-a/final-0", 6L, "\"etag-0\"",
                    "sha256:plain", "sha256:cipher", "SHA-256");

            assertThrows(cn.flying.common.exception.RetryableException.class, () ->
                    fileService.attestDirectUploadedFile(
                            USER_ID, existing.getId(), existing.getFileName(), List.of(part),
                            "raw-file-param", "direct-owner-a"));

            verifyNoInteractions(fileRemoteClient, sagaOrchestrator);
        }

        /**
         * 验证直传 owner 已进入 ATTESTING 后普通上传 loser 在 Saga/S3 前被同一 DB claim 阻断。
         */
        @Test
        void directClaimShouldBlockLegacySagaForSamePreparedFile() {
            File existing = prepareStoreFixture();
            DirectMultipartCompletedPartVO part = new DirectMultipartCompletedPartVO(
                    0, "s3://node-a/final-0", 6L, "\"etag-0\"",
                    "sha256:plain", "sha256:cipher", "SHA-256");
            when(fileRemoteClient.storeFileOnChainOnce(any()))
                    .thenThrow(new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "response lost"));

            assertThrows(GeneralException.class, () -> fileService.attestDirectUploadedFile(
                    USER_ID, existing.getId(), existing.getFileName(), List.of(part),
                    "raw-file-param", "direct-owner"));
            assertThrows(GeneralException.class, () -> fileService.storeFile(
                    USER_ID, existing.getId(), existing.getFileName(),
                    List.of(new java.io.File("chunk-0")), List.of("chunk-hash-0"),
                    "raw-file-param", "legacy-owner"));

            verify(fileRemoteClient, times(1)).storeFileOnChainOnce(any());
            verifyNoInteractions(sagaOrchestrator);
        }

        /**
         * 验证内容摘要仅接受规范字符串，并把 JSON、null、类型和格式漂移统一映射为记录错误。
         */
        @Test
        void requireContentHashShouldRejectMalformedMetadataAndNormalizeValidHash() {
            String normalized = ReflectionTestUtils.invokeMethod(
                    fileService,
                    "requireContentHash",
                    "{\"contentHash\":\"  SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA  \"}");
            assertEquals(CONTENT_HASH, normalized);

            for (String invalid : List.of("{", "null", "{\"contentHash\":42}", "{\"contentHash\":\"bad\"}")) {
                GeneralException error = assertThrows(
                        GeneralException.class,
                        () -> ReflectionTestUtils.invokeMethod(fileService, "requireContentHash", invalid));
                assertEquals(ResultEnum.FILE_RECORD_ERROR, error.getResultEnum());
            }
        }

        /**
         * 验证迟到的失败监听器不会把已经提交的 SUCCESS 文件反写为 FAIL。
         */
        @Test
        void markFileUploadFailedShouldIgnorePersistedSuccess() {
            File success = new File()
                    .setId(7201L)
                    .setTenantId(2L)
                    .setUid(USER_ID)
                    .setStatus(cn.flying.common.constant.FileUploadStatus.SUCCESS.getCode());
            when(fileMapper.selectById(7201L)).thenReturn(success);

            boolean safeToDelete = fileService.markFileUploadFailed(USER_ID, 7201L);

            assertFalse(safeToDelete);
            verify(fileMapper, never()).update(any(File.class), any());
        }

        /**
         * 验证失败回写的数据库条件显式限定 PREPARE，关闭查询后状态并发推进的竞态。
         */
        @Test
        void markFileUploadFailedShouldUsePrepareStatusCas() {
            File prepare = new File()
                    .setId(7202L)
                    .setTenantId(2L)
                    .setUid(USER_ID)
                    .setStatus(cn.flying.common.constant.FileUploadStatus.PREPARE.getCode());
            when(fileMapper.selectById(7202L)).thenReturn(prepare);
            when(fileMapper.update(any(File.class), any())).thenReturn(1);

            boolean safeToDelete = fileService.markFileUploadFailed(USER_ID, 7202L);

            assertTrue(safeToDelete);
            ArgumentCaptor<Wrapper<File>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
            ArgumentCaptor<File> updateCaptor = ArgumentCaptor.forClass(File.class);
            verify(fileMapper).update(updateCaptor.capture(), wrapperCaptor.capture());
            assertEquals(
                    cn.flying.common.constant.FileUploadStatus.FAIL.getCode(),
                    updateCaptor.getValue().getStatus());
            LambdaUpdateWrapper<File> wrapper =
                    (LambdaUpdateWrapper<File>) wrapperCaptor.getValue();
            assertTrue(wrapper.getSqlSegment().contains("status"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue(
                    cn.flying.common.constant.FileUploadStatus.PREPARE.getCode()));
        }

        /**
         * 验证 PREPARE CAS 失败时不会继续恢复父版本 latest，避免覆盖并发成功事务。
         */
        @Test
        void markFileUploadFailedShouldStopWhenPrepareCasLosesRace() {
            File prepare = new File()
                    .setId(7203L)
                    .setTenantId(2L)
                    .setUid(USER_ID)
                    .setParentVersionId(7100L)
                    .setIsLatest(1)
                    .setStatus(cn.flying.common.constant.FileUploadStatus.PREPARE.getCode());
            when(fileMapper.selectById(7203L)).thenReturn(prepare);
            when(fileMapper.update(any(File.class), any())).thenReturn(0);

            boolean safeToDelete = fileService.markFileUploadFailed(USER_ID, 7203L);

            assertFalse(safeToDelete);
            verify(fileMapper, times(1)).update(any(File.class), any());
            verify(fileMapper, never()).selectById(7100L);
        }

        /**
         * 验证版本组锚点锁后的重读观察到并发新 latest 时，失败回写不得复活父版本形成双 latest。
         */
        @Test
        void markFileUploadFailedShouldRespectLatestChangedUnderStableVersionGroupLock() {
            File staleLatest = new File()
                    .setId(7210L)
                    .setTenantId(2L)
                    .setUid(USER_ID)
                    .setVersion(2)
                    .setVersionGroupId(7100L)
                    .setParentVersionId(7100L)
                    .setIsLatest(1)
                    .setStatus(cn.flying.common.constant.FileUploadStatus.PREPARE.getCode());
            File noLongerLatest = new File()
                    .setId(7210L)
                    .setTenantId(2L)
                    .setUid(USER_ID)
                    .setVersion(2)
                    .setVersionGroupId(7100L)
                    .setParentVersionId(7100L)
                    .setIsLatest(0)
                    .setStatus(cn.flying.common.constant.FileUploadStatus.PREPARE.getCode());
            when(fileMapper.selectById(7210L)).thenReturn(staleLatest, noLongerLatest);
            when(fileMapper.lockVersionGroupForProofLifecycle(2L, 7100L)).thenReturn(7100L);
            when(fileMapper.update(any(File.class), any())).thenReturn(1);

            boolean safeToDelete = fileService.markFileUploadFailed(USER_ID, 7210L);

            InOrder order = inOrder(fileMapper);
            order.verify(fileMapper).selectById(7210L);
            order.verify(fileMapper).lockVersionGroupForProofLifecycle(2L, 7100L);
            order.verify(fileMapper).selectById(7210L);
            ArgumentCaptor<File> updateCaptor = ArgumentCaptor.forClass(File.class);
            ArgumentCaptor<Wrapper<File>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
            order.verify(fileMapper).update(updateCaptor.capture(), wrapperCaptor.capture());
            assertEquals(cn.flying.common.constant.FileUploadStatus.FAIL.getCode(),
                    updateCaptor.getValue().getStatus());
            assertNull(updateCaptor.getValue().getIsLatest());
            LambdaUpdateWrapper<File> failWrapper =
                    (LambdaUpdateWrapper<File>) wrapperCaptor.getValue();
            assertTrue(failWrapper.getSqlSegment().contains("is_latest"));
            assertTrue(failWrapper.getParamNameValuePairs().containsValue(0));
            verify(fileMapper, never()).selectById(7100L);
            assertTrue(safeToDelete);
        }

        /**
         * 验证未知高低版本、分数版本和半完成 ATTESTED claim 全部映射为 UNKNOWN，不能降级成可重试。
         */
        @Test
        void finalizationRecoveryPhaseShouldRejectUnsupportedOrMalformedClaims() {
            File prepare = new File()
                    .setId(7220L)
                    .setTenantId(2L)
                    .setUid(USER_ID)
                    .setStatus(cn.flying.common.constant.FileUploadStatus.PREPARE.getCode());
            when(fileMapper.selectById(7220L)).thenReturn(prepare);

            for (String claim : List.of(
                    finalizationClaimJson("0", "CLAIMED", "", ""),
                    finalizationClaimJson("2", "CLAIMED", "", ""),
                    finalizationClaimJson("1.5", "CLAIMED", "", ""),
                    finalizationClaimJson("1", "CHAIN_ATTESTED", "tx-only", ""))) {
                prepare.setFileParam(claim);
                assertEquals(
                        FileService.FinalizationRecoveryPhase.UNKNOWN,
                        fileService.getFinalizationRecoveryPhase(USER_ID, 7220L));
            }
        }

        /**
         * 构造精确版本和阶段的最终化 claim JSON，供恢复边界测试复用。
         */
        private String finalizationClaimJson(
                String version,
                String phase,
                String transactionHash,
                String fileHash
        ) {
            return "{\"contentHash\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                    + "\"_finalizationClaim\":{\"v\":" + version
                    + ",\"ownerToken\":\"owner\",\"mode\":\"DIRECT\",\"fingerprint\":\"fingerprint\","
                    + "\"phase\":\"" + phase + "\",\"txHash\":\"" + transactionHash
                    + "\",\"fileHash\":\"" + fileHash + "\"}}";
        }

        /**
         * 验证文件已经处于 FAIL 时清理许可保持幂等，不再执行数据库更新。
         */
        @Test
        void markFileUploadFailedShouldAllowCleanupForExistingFail() {
            File failed = new File()
                    .setId(7204L)
                    .setTenantId(2L)
                    .setUid(USER_ID)
                    .setStatus(cn.flying.common.constant.FileUploadStatus.FAIL.getCode());
            when(fileMapper.selectById(7204L)).thenReturn(failed);

            boolean safeToDelete = fileService.markFileUploadFailed(USER_ID, 7204L);

            assertTrue(safeToDelete);
            verify(fileMapper, never()).update(any(File.class), any());
        }

        /**
         * 验证空主键、缺失记录和跨用户记录都不能授权删除上传会话。
         */
        @Test
        void markFileUploadFailedShouldRejectMissingOrCrossUserTarget() {
            File otherUserFile = new File()
                    .setId(7205L)
                    .setTenantId(2L)
                    .setUid(USER_ID + 1)
                    .setStatus(cn.flying.common.constant.FileUploadStatus.PREPARE.getCode());
            when(fileMapper.selectById(7205L)).thenReturn(otherUserFile);

            assertFalse(fileService.markFileUploadFailed(USER_ID, null));
            assertFalse(fileService.markFileUploadFailed(USER_ID, 7999L));
            assertFalse(fileService.markFileUploadFailed(USER_ID, 7205L));
            verify(fileMapper, never()).update(any(File.class), any());
        }

        /**
         * 验证版本链锁在首版本跳过，并对空主键、缺失锚点和有效锚点分别处理。
         */
        @Test
        void proofLifecycleLockShouldHandleFirstVersionAndFailClosedOnMissingAnchor() {
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                    fileService,
                    "lockProofLifecycleVersionGroupBeforeFileMutation",
                    new File().setVersion(null)));
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                    fileService,
                    "lockProofLifecycleVersionGroupBeforeFileMutation",
                    new File().setVersion(1)));

            GeneralException missingTenant = assertThrows(
                    GeneralException.class,
                    () -> ReflectionTestUtils.invokeMethod(
                            fileService, "lockProofLifecycleVersionGroup", null, 7001L, "missing chain"));
            GeneralException missingGroup = assertThrows(
                    GeneralException.class,
                    () -> ReflectionTestUtils.invokeMethod(
                            fileService, "lockProofLifecycleVersionGroup", 2L, null, "missing chain"));
            when(fileMapper.lockVersionGroupForProofLifecycle(2L, 7001L)).thenReturn(null, 7001L, 7001L);
            GeneralException missingAnchor = assertThrows(
                    GeneralException.class,
                    () -> ReflectionTestUtils.invokeMethod(
                            fileService, "lockProofLifecycleVersionGroup", 2L, 7001L, "missing chain"));
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                    fileService, "lockProofLifecycleVersionGroup", 2L, 7001L, "missing chain"));
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                    fileService,
                    "lockProofLifecycleVersionGroupBeforeFileMutation",
                    new File().setTenantId(2L).setVersionGroupId(7001L).setVersion(2)));

            assertEquals(ResultEnum.FILE_RECORD_ERROR, missingTenant.getResultEnum());
            assertEquals(ResultEnum.FILE_RECORD_ERROR, missingGroup.getResultEnum());
            assertEquals(ResultEnum.FILE_RECORD_ERROR, missingAnchor.getResultEnum());
        }

        /**
         * 验证后续版本不存在旧成功文件时只完成版本链锁，不更新 proof 状态。
         */
        @Test
        void markOlderProofIssuancesShouldReturnWhenNoOlderSuccessfulFileExists() {
            File completedVersion = new File()
                    .setId(7002L)
                    .setTenantId(2L)
                    .setVersionGroupId(7001L)
                    .setVersion(2);
            when(fileMapper.lockVersionGroupForProofLifecycle(2L, 7001L)).thenReturn(7001L);
            when(fileMapper.selectList(any())).thenReturn(List.of());

            ReflectionTestUtils.invokeMethod(
                    fileService, "markOlderProofIssuancesSuperseded", completedVersion);

            verify(fileMapper).selectList(any());
            verifyNoInteractions(proofBundleIssuanceMapper);
        }
    }

    @Nested
    @DisplayName("Proof Status Lifecycle")
    class ProofStatusLifecycle {

        /**
         * 验证新版本上传成功后，只按同租户版本链中的旧成功文件推进 ACTIVE proof 状态。
         */
        @Test
        @DisplayName("should supersede active proofs for older successful versions")
        void shouldSupersedeActiveProofsForOlderSuccessfulVersions() {
            File completedVersion = new File()
                    .setId(3003L)
                    .setTenantId(2L)
                    .setVersionGroupId(3001L)
                    .setVersion(3);
            when(fileMapper.lockVersionGroupForProofLifecycle(2L, 3001L)).thenReturn(3001L);
            when(fileMapper.selectList(any())).thenReturn(List.of(
                    new File().setId(3001L),
                    new File().setId(3002L)));

            ReflectionTestUtils.invokeMethod(
                    fileService,
                    "markOlderProofIssuancesSuperseded",
                    completedVersion);

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> wrapperCaptor =
                    ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
            InOrder lifecycleOrder = inOrder(fileMapper, proofBundleIssuanceMapper);
            lifecycleOrder.verify(fileMapper).lockVersionGroupForProofLifecycle(2L, 3001L);
            lifecycleOrder.verify(fileMapper).selectList(any());
            lifecycleOrder.verify(proofBundleIssuanceMapper).update(isNull(), wrapperCaptor.capture());
            com.baomidou.mybatisplus.core.conditions.Wrapper<?> wrapper = wrapperCaptor.getValue();
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> updateWrapper =
                    (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>) wrapper;
            assertTrue(wrapper.getSqlSegment().contains("tenant_id"));
            assertTrue(wrapper.getSqlSegment().contains("file_id IN"));
            assertTrue(wrapper.getSqlSegment().contains("status"));
            assertTrue(updateWrapper.getParamNameValuePairs().containsValue(2L));
            assertTrue(updateWrapper.getParamNameValuePairs().containsValue(3001L));
            assertTrue(updateWrapper.getParamNameValuePairs().containsValue(3002L));
            assertTrue(updateWrapper.getParamNameValuePairs().containsValue("ACTIVE"));
            assertTrue(updateWrapper.getParamNameValuePairs().containsValue("SUPERSEDED"));
            assertTrue(updateWrapper.getParamNameValuePairs().containsValue("newer_file_version"));
            assertTrue(updateWrapper.getSqlSet().contains("status_version = status_version + 1"));
        }

        /**
         * 验证首版本和 PREPARE 阶段不会提前产生不可逆的 superseded 状态。
         */
        @Test
        @DisplayName("should not supersede proofs before a later version succeeds")
        void shouldNotSupersedeProofsBeforeLaterVersionSucceeds() {
            File firstVersion = new File()
                    .setId(3001L)
                    .setTenantId(2L)
                    .setVersionGroupId(3001L)
                    .setVersion(1);

            ReflectionTestUtils.invokeMethod(
                    fileService,
                    "markOlderProofIssuancesSuperseded",
                    firstVersion);

            verify(fileMapper, never()).selectList(any());
            verifyNoInteractions(proofBundleIssuanceMapper);
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
