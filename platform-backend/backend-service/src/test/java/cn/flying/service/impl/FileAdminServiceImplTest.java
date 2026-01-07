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
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Date;
import java.util.List;
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
