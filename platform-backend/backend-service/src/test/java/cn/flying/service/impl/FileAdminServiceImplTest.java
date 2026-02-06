package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.dto.FileSource;
import cn.flying.dao.dto.ShareAccessLog;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FileSourceMapper;
import cn.flying.dao.mapper.ShareAccessLogMapper;
import cn.flying.dao.vo.admin.AdminFileDetailVO;
import cn.flying.dao.vo.admin.AdminFileQueryParam;
import cn.flying.dao.vo.admin.AdminFileVO;
import cn.flying.dao.vo.admin.AdminShareQueryParam;
import cn.flying.dao.vo.admin.AdminShareVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileAdminServiceImpl Tests")
class FileAdminServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // Initialize MyBatis-Plus lambda cache for File entity
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, File.class);
        TableInfoHelper.initTableInfo(assistant, FileShare.class);
        TableInfoHelper.initTableInfo(assistant, FileSource.class);
    }

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileShareMapper fileShareMapper;

    @Mock
    private FileSourceMapper fileSourceMapper;

    @Mock
    private ShareAccessLogMapper shareAccessLogMapper;

    @Mock
    private AccountMapper accountMapper;

    @InjectMocks
    private FileAdminServiceImpl fileAdminService;

    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 1001L;
    private static final Long FILE_ID = 2001L;
    private static final String SHARE_CODE = "ABC123";
    private static final String FILE_HASH = "sha256_test_hash";
    private static final String FILE_NAME = "test_file.pdf";

    private MockedStatic<TenantContext> tenantContextMock;
    private MockedStatic<IdUtils> idUtilsMock;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
        tenantContextMock.when(TenantContext::requireTenantId).thenReturn(TENANT_ID);
        tenantContextMock.when(() -> TenantContext.runWithoutIsolation(any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<?> supplier = inv.getArgument(0);
                    return supplier.get();
                });

        idUtilsMock = mockStatic(IdUtils.class);
        idUtilsMock.when(() -> IdUtils.toExternalId(anyLong())).thenAnswer(inv -> "ext_" + inv.getArgument(0));
        idUtilsMock.when(() -> IdUtils.fromExternalId(anyString())).thenAnswer(inv -> {
            String ext = inv.getArgument(0);
            if (ext.startsWith("ext_")) {
                return Long.parseLong(ext.substring(4));
            }
            throw new IllegalArgumentException("Invalid external ID format: " + ext);
        });
    }

    @AfterEach
    void tearDown() {
        if (tenantContextMock != null) tenantContextMock.close();
        if (idUtilsMock != null) idUtilsMock.close();
    }

    @Nested
    @DisplayName("getFileDetail - file not found")
    class GetFileDetailNotFound {

        @Test
        @DisplayName("should throw when file not found")
        void getFileDetail_notFound_throws() {
            when(fileMapper.selectById(999L)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> fileAdminService.getFileDetail("ext_999"));

            assertEquals(ResultEnum.PARAM_ERROR, ex.getResultEnum());
        }
    }

    @Nested
    @DisplayName("getFileDetail - original file")
    class GetFileDetailOriginal {

        @Test
        @DisplayName("should return detail for original file")
        void getFileDetail_originalFile() {
            File file = createFile();
            file.setOrigin(null);

            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());
            when(fileMapper.countActiveFilesByHash(FILE_HASH, FILE_ID)).thenReturn(0L);
            when(fileShareMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            Page<ShareAccessLog> emptyLogPage = new Page<>(1, 10);
            emptyLogPage.setRecords(List.of());
            when(shareAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(emptyLogPage);

            AdminFileDetailVO result = fileAdminService.getFileDetail("ext_" + FILE_ID);

            assertNotNull(result);
            assertTrue(result.getIsOriginal());
            assertEquals(0, result.getDepth());
            assertEquals(FILE_NAME, result.getFileName());
        }

        @Test
        @DisplayName("should include related shares")
        void getFileDetail_withRelatedShares() {
            File file = createFile();
            file.setOrigin(null);

            FileShare share = createFileShare();

            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());
            when(fileMapper.countActiveFilesByHash(FILE_HASH, FILE_ID)).thenReturn(0L);
            when(fileShareMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(share));

            Page<ShareAccessLog> emptyLogPage = new Page<>(1, 10);
            emptyLogPage.setRecords(List.of());
            when(shareAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(emptyLogPage);

            AdminFileDetailVO result = fileAdminService.getFileDetail("ext_" + FILE_ID);

            assertNotNull(result);
            assertNotNull(result.getRelatedShares());
            assertEquals(1, result.getRelatedShares().size());
            assertEquals(SHARE_CODE, result.getRelatedShares().get(0).getShareCode());
        }

        @Test
        @DisplayName("should include recent access logs")
        void getFileDetail_withAccessLogs() {
            File file = createFile();
            file.setOrigin(null);

            ShareAccessLog log = createShareAccessLog();

            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());
            when(fileMapper.countActiveFilesByHash(FILE_HASH, FILE_ID)).thenReturn(0L);
            when(fileShareMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            Page<ShareAccessLog> logPage = new Page<>(1, 10);
            logPage.setRecords(List.of(log));
            when(shareAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(logPage);

            AdminFileDetailVO result = fileAdminService.getFileDetail("ext_" + FILE_ID);

            assertNotNull(result);
            assertNotNull(result.getRecentAccessLogs());
            assertEquals(1, result.getRecentAccessLogs().size());
        }

        @Test
        @DisplayName("should return refCount")
        void getFileDetail_withRefCount() {
            File file = createFile();
            file.setOrigin(null);

            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());
            when(fileMapper.countActiveFilesByHash(FILE_HASH, FILE_ID)).thenReturn(5L);
            when(fileShareMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            Page<ShareAccessLog> emptyLogPage = new Page<>(1, 10);
            emptyLogPage.setRecords(List.of());
            when(shareAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(emptyLogPage);

            AdminFileDetailVO result = fileAdminService.getFileDetail("ext_" + FILE_ID);

            assertNotNull(result);
            assertEquals(5, result.getRefCount());
        }
    }

    @Nested
    @DisplayName("getFileDetail - saved file")
    class GetFileDetailSaved {

        @Test
        @DisplayName("should return detail for saved file with provenance")
        void getFileDetail_savedFile() {
            File file = createFile();
            file.setOrigin(3001L);
            file.setSharedFromUserId(USER_ID);

            File originFile = createFile();
            originFile.setId(3001L);
            originFile.setOrigin(null);

            FileSource source = new FileSource();
            source.setFileId(FILE_ID);
            source.setOriginFileId(3001L);
            source.setSourceFileId(3001L);
            source.setSourceUserId(USER_ID);
            source.setDepth(1);
            source.setShareCode(SHARE_CODE);
            source.setTenantId(TENANT_ID);

            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
            when(fileMapper.selectById(3001L)).thenReturn(originFile);
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());
            when(fileSourceMapper.selectByFileId(FILE_ID, TENANT_ID)).thenReturn(source);
            when(fileSourceMapper.selectProvenanceChain(FILE_ID, TENANT_ID)).thenReturn(List.of());
            when(fileMapper.countActiveFilesByHash(FILE_HASH, FILE_ID)).thenReturn(1L);
            when(fileShareMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            Page<ShareAccessLog> emptyLogPage = new Page<>(1, 10);
            emptyLogPage.setRecords(List.of());
            when(shareAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(emptyLogPage);

            AdminFileDetailVO result = fileAdminService.getFileDetail("ext_" + FILE_ID);

            assertNotNull(result);
            assertFalse(result.getIsOriginal());
            assertEquals(1, result.getDepth());
            assertEquals(SHARE_CODE, result.getSaveShareCode());
        }

        @Test
        @DisplayName("should include sharedFromUser info")
        void getFileDetail_savedWithSharedFrom() {
            Long sharedFromUserId = 9999L;
            File file = createFile();
            file.setOrigin(3001L);
            file.setSharedFromUserId(sharedFromUserId);

            File originFile = createFile();
            originFile.setId(3001L);
            originFile.setOrigin(null);

            Account sharedFromAccount = new Account();
            sharedFromAccount.setId(sharedFromUserId);
            sharedFromAccount.setUsername("sharer");

            FileSource source = new FileSource();
            source.setFileId(FILE_ID);
            source.setDepth(1);
            source.setShareCode(SHARE_CODE);

            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
            when(fileMapper.selectById(3001L)).thenReturn(originFile);
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());
            when(accountMapper.selectById(sharedFromUserId)).thenReturn(sharedFromAccount);
            when(fileSourceMapper.selectByFileId(FILE_ID, TENANT_ID)).thenReturn(source);
            when(fileSourceMapper.selectProvenanceChain(FILE_ID, TENANT_ID)).thenReturn(List.of());
            when(fileMapper.countActiveFilesByHash(FILE_HASH, FILE_ID)).thenReturn(0L);
            when(fileShareMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            Page<ShareAccessLog> emptyLogPage = new Page<>(1, 10);
            emptyLogPage.setRecords(List.of());
            when(shareAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(emptyLogPage);

            AdminFileDetailVO result = fileAdminService.getFileDetail("ext_" + FILE_ID);

            assertNotNull(result);
            assertEquals(String.valueOf(sharedFromUserId), result.getSharedFromUserId());
            assertEquals("sharer", result.getSharedFromUserName());
        }
    }

    @Nested
    @DisplayName("getAllFiles - query and conversion")
    class GetAllFilesQueryAndConversion {

        /**
         * 验证管理员文件列表在仅查询原始文件时，能正确返回并完成 VO 转换。
         */
        @Test
        @DisplayName("should return original file records with converted fields")
        void shouldReturnOriginalFileRecordsWithConvertedFields() {
            File file = createFile();
            file.setOrigin(null);

            Page<File> filePage = new Page<>(1, 10);
            filePage.setRecords(List.of(file));
            filePage.setTotal(1);

            when(fileMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(filePage);
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());

            AdminFileQueryParam param = new AdminFileQueryParam();
            param.setKeyword("test");
            param.setStatus(1);
            param.setOriginalOnly(true);

            IPage<AdminFileVO> result = fileAdminService.getAllFiles(param, new Page<>(1, 10));

            assertNotNull(result);
            assertEquals(1, result.getRecords().size());

            AdminFileVO vo = result.getRecords().get(0);
            assertTrue(vo.getIsOriginal());
            assertEquals(0, vo.getDepth());
            assertEquals("testuser", vo.getOwnerName());
        }

        /**
         * 验证分享保存文件在管理员列表中能正确填充来源所有者、直接分享者和链路深度。
         */
        @Test
        @DisplayName("should enrich shared-saved file with origin and source metadata")
        void shouldEnrichSharedSavedFileWithOriginAndSourceMetadata() {
            Long originFileId = 3001L;
            Long sharerUserId = 4001L;
            Long originOwnerId = 5001L;

            File savedFile = createFile();
            savedFile.setOrigin(originFileId);
            savedFile.setSharedFromUserId(sharerUserId);

            File originFile = createFile();
            originFile.setId(originFileId);
            originFile.setUid(originOwnerId);

            FileSource fileSource = new FileSource();
            fileSource.setFileId(savedFile.getId());
            fileSource.setDepth(3);
            fileSource.setTenantId(TENANT_ID);

            Page<File> filePage = new Page<>(1, 10);
            filePage.setRecords(List.of(savedFile));
            filePage.setTotal(1);

            Account owner = createAccount();
            Account originOwner = new Account();
            originOwner.setId(originOwnerId);
            originOwner.setUsername("origin-owner");
            Account sharer = new Account();
            sharer.setId(sharerUserId);
            sharer.setUsername("direct-sharer");

            when(fileMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(filePage);
            when(fileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(originFile));
            when(fileSourceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(fileSource));
            when(accountMapper.selectById(USER_ID)).thenReturn(owner);
            when(accountMapper.selectById(originOwnerId)).thenReturn(originOwner);
            when(accountMapper.selectById(sharerUserId)).thenReturn(sharer);

            AdminFileQueryParam param = new AdminFileQueryParam();
            param.setSharedOnly(true);

            IPage<AdminFileVO> result = fileAdminService.getAllFiles(param, new Page<>(1, 10));

            assertNotNull(result);
            assertEquals(1, result.getRecords().size());

            AdminFileVO vo = result.getRecords().get(0);
            assertFalse(vo.getIsOriginal());
            assertEquals(3, vo.getDepth());
            assertEquals(String.valueOf(originOwnerId), vo.getOriginOwnerId());
            assertEquals("origin-owner", vo.getOriginOwnerName());
            assertEquals(String.valueOf(sharerUserId), vo.getSharedFromUserId());
            assertEquals("direct-sharer", vo.getSharedFromUserName());
        }
    }

    @Nested
    @DisplayName("getAllShares - query and aggregation")
    class GetAllSharesQueryAndAggregation {

        /**
         * 验证空分享列表时，服务不应继续查询访问统计并返回空分页结果。
         */
        @Test
        @DisplayName("should return empty page when there are no shares")
        void shouldReturnEmptyPageWhenThereAreNoShares() {
            Page<FileShare> sharePage = new Page<>(1, 10);
            sharePage.setRecords(List.of());
            sharePage.setTotal(0);

            when(fileShareMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(sharePage);

            AdminShareQueryParam param = new AdminShareQueryParam();
            IPage<AdminShareVO> result = fileAdminService.getAllShares(param, new Page<>(1, 10));

            assertNotNull(result);
            assertTrue(result.getRecords().isEmpty());
            verify(shareAccessLogMapper, never()).batchCountByShareCodes(anyList(), anyLong());
            verify(shareAccessLogMapper, never()).batchCountDistinctActors(anyList(), anyLong());
        }

        /**
         * 验证分享列表聚合统计时，可忽略脏数据行并正确填充访问统计与私密分享标记。
         */
        @Test
        @DisplayName("should aggregate valid stats and ignore invalid stat rows")
        void shouldAggregateValidStatsAndIgnoreInvalidStatRows() {
            FileShare share = createFileShare();
            share.setShareType(1);

            Page<FileShare> sharePage = new Page<>(1, 10);
            sharePage.setRecords(List.of(share));
            sharePage.setTotal(1);

            File file = createFile();

            Map<String, Object> validView = new HashMap<>();
            validView.put("share_code", SHARE_CODE);
            validView.put("action_type", ShareAccessLog.ACTION_VIEW);
            validView.put("cnt", 5L);

            Map<String, Object> validDownload = new HashMap<>();
            validDownload.put("share_code", SHARE_CODE);
            validDownload.put("action_type", ShareAccessLog.ACTION_DOWNLOAD);
            validDownload.put("cnt", 2L);

            Map<String, Object> validSave = new HashMap<>();
            validSave.put("share_code", SHARE_CODE);
            validSave.put("action_type", ShareAccessLog.ACTION_SAVE);
            validSave.put("cnt", 1L);

            Map<String, Object> invalidStatRow = new HashMap<>();
            invalidStatRow.put("share_code", null);
            invalidStatRow.put("action_type", ShareAccessLog.ACTION_VIEW);
            invalidStatRow.put("cnt", 9L);

            Map<String, Object> validActors = new HashMap<>();
            validActors.put("share_code", SHARE_CODE);
            validActors.put("unique_actors", 3L);

            Map<String, Object> invalidActors = new HashMap<>();
            invalidActors.put("share_code", SHARE_CODE);
            invalidActors.put("unique_actors", "bad-value");

            when(fileShareMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(sharePage);
            when(fileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(file));
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());
            when(shareAccessLogMapper.batchCountByShareCodes(anyList(), eq(TENANT_ID))).thenReturn(
                    new ArrayList<>(List.of(validView, validDownload, validSave, invalidStatRow)));
            when(shareAccessLogMapper.batchCountDistinctActors(anyList(), eq(TENANT_ID))).thenReturn(
                    new ArrayList<>(List.of(validActors, invalidActors)));

            AdminShareQueryParam param = new AdminShareQueryParam();
            param.setKeyword("ABC");
            param.setStatus(FileShare.STATUS_ACTIVE);
            param.setShareType(1);

            IPage<AdminShareVO> result = fileAdminService.getAllShares(param, new Page<>(1, 10));

            assertNotNull(result);
            assertEquals(1, result.getRecords().size());

            AdminShareVO vo = result.getRecords().get(0);
            assertEquals(SHARE_CODE, vo.getShareCode());
            assertTrue(vo.getHasPassword());
            assertEquals(5L, vo.getViewCount());
            assertEquals(2L, vo.getDownloadCount());
            assertEquals(1L, vo.getSaveCount());
            assertEquals(3L, vo.getUniqueActors());
        }
    }

    @Nested
    @DisplayName("updateFileStatus - validation")
    class UpdateFileStatusValidation {

        @Test
        @DisplayName("should throw when file not found")
        void updateFileStatus_notFound_throws() {
            when(fileMapper.selectById(999L)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> fileAdminService.updateFileStatus("ext_999", 2, "test"));

            assertEquals(ResultEnum.PARAM_ERROR, ex.getResultEnum());
        }

        @Test
        @DisplayName("should update file status successfully")
        void updateFileStatus_success() {
            File file = createFile();
            when(fileMapper.selectById(FILE_ID)).thenReturn(file);

            assertDoesNotThrow(() -> fileAdminService.updateFileStatus("ext_" + FILE_ID, 2, "admin update"));

            verify(fileMapper).selectById(FILE_ID);
            verify(fileMapper).update(isNull(), any());
        }
    }

    @Nested
    @DisplayName("forceDeleteFile - validation")
    class ForceDeleteFileValidation {

        @Test
        @DisplayName("should throw when file not found")
        void forceDeleteFile_notFound_throws() {
            when(fileMapper.selectById(999L)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> fileAdminService.forceDeleteFile("ext_999", "test"));

            assertEquals(ResultEnum.PARAM_ERROR, ex.getResultEnum());
        }

        @Test
        @DisplayName("should delete file successfully")
        void forceDeleteFile_success() {
            File file = createFile();
            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
            when(fileSourceMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(0);
            when(fileMapper.physicalDeleteById(FILE_ID, TENANT_ID)).thenReturn(1);

            assertDoesNotThrow(() -> fileAdminService.forceDeleteFile("ext_" + FILE_ID, "admin delete"));

            verify(fileSourceMapper).delete(any(LambdaQueryWrapper.class));
            verify(fileMapper).physicalDeleteById(FILE_ID, TENANT_ID);
        }
    }

    @Nested
    @DisplayName("forceCancelShare - validation")
    class ForceCancelShareValidation {

        @Test
        @DisplayName("should throw when share not found")
        void forceCancelShare_notFound_throws() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> fileAdminService.forceCancelShare(SHARE_CODE, "test"));

            assertEquals(ResultEnum.PARAM_ERROR, ex.getResultEnum());
        }

        /**
         * 验证管理员强制取消分享时，会更新分享状态为已取消。
         */
        @Test
        @DisplayName("should update share status to cancelled")
        void forceCancelShare_success_updatesStatus() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(createFileShare());

            assertDoesNotThrow(() -> fileAdminService.forceCancelShare(SHARE_CODE, "admin-operation"));

            verify(fileShareMapper).update(isNull(), any());
        }
    }

    private File createFile() {
        File file = new File();
        file.setId(FILE_ID);
        file.setUid(USER_ID);
        file.setFileHash(FILE_HASH);
        file.setFileName(FILE_NAME);
        file.setFileSize(1024L);
        file.setContentType("application/pdf");
        file.setStatus(1);
        file.setTenantId(TENANT_ID);
        file.setCreateTime(new Date());
        return file;
    }

    private FileShare createFileShare() {
        FileShare share = new FileShare();
        share.setId(1L);
        share.setShareCode(SHARE_CODE);
        share.setUserId(USER_ID);
        share.setTenantId(TENANT_ID);
        share.setStatus(FileShare.STATUS_ACTIVE);
        share.setShareType(0);
        share.setFileHashes("[\"" + FILE_HASH + "\"]");
        share.setAccessCount(0);
        share.setCreateTime(new Date());
        return share;
    }

    private ShareAccessLog createShareAccessLog() {
        ShareAccessLog log = new ShareAccessLog();
        log.setId(1L);
        log.setShareCode(SHARE_CODE);
        log.setShareOwnerId(USER_ID);
        log.setActorUserId(USER_ID);
        log.setActionType(ShareAccessLog.ACTION_VIEW);
        log.setFileHash(FILE_HASH);
        log.setFileName(FILE_NAME);
        log.setActorIp("192.168.1.100");
        log.setAccessTime(new Date());
        log.setTenantId(TENANT_ID);
        return log;
    }

    private Account createAccount() {
        Account account = new Account();
        account.setId(USER_ID);
        account.setUsername("testuser");
        account.setTenantId(TENANT_ID);
        return account;
    }
}
