package cn.flying.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
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
import cn.flying.dao.vo.file.FileProvenanceVO;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ShareAuditServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
class ShareAuditServiceImplTest {

    @Mock
    private ShareAccessLogMapper shareAccessLogMapper;

    @Mock
    private FileShareMapper fileShareMapper;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileSourceMapper fileSourceMapper;

    @Mock
    private AccountMapper accountMapper;

    @InjectMocks
    private ShareAuditServiceImpl shareAuditService;

    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 1001L;
    private static final Long FILE_ID = 2001L;
    private static final String SHARE_CODE = "ABC123";
    private static final String FILE_HASH = "sha256_test_hash";
    private static final String FILE_NAME = "test_file.pdf";
    private static final String IP_ADDRESS = "192.168.1.100";
    private static final String USER_AGENT = "Mozilla/5.0";

    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        lenient().when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(TENANT_ID);
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
        tenantContextMock.when(TenantContext::requireTenantId).thenReturn(TENANT_ID);
        tenantContextMock.when(() -> TenantContext.runWithTenantIsolation(anyLong(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    Runnable runnable = inv.getArgument(1);
                    runnable.run();
                    return null;
                });
    }

    @AfterEach
    void tearDown() {
        if (tenantContextMock != null) tenantContextMock.close();
    }

    @Nested
    @DisplayName("logShareView")
    class LogShareView {

        @Test
        @DisplayName("should log share view when share exists")
        void logShareView_success() {
            FileShare share = createFileShare();
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            shareAuditService.logShareView(SHARE_CODE, USER_ID, IP_ADDRESS, USER_AGENT);

            ArgumentCaptor<ShareAccessLog> captor = ArgumentCaptor.forClass(ShareAccessLog.class);
            verify(shareAccessLogMapper).insert(captor.capture());
            ShareAccessLog inserted = captor.getValue();
            assertEquals(TENANT_ID, inserted.getTenantId());
            assertEquals(USER_ID, inserted.getShareOwnerId());
            assertEquals(USER_ID, inserted.getActorUserId());
            assertEquals(IP_ADDRESS, inserted.getActorIp());
            assertEquals(USER_AGENT, inserted.getActorUa());
            assertEquals(ShareAccessLog.ACTION_VIEW, inserted.getActionType());
            verify(fileShareMapper).selectTenantIdByShareCodeGlobally(SHARE_CODE);
            verify(fileShareMapper).selectByShareCode(SHARE_CODE);
            tenantContextMock.verify(() -> TenantContext.runWithTenantIsolation(eq(TENANT_ID), any(Runnable.class)));
        }

        @Test
        @DisplayName("should not log when share does not exist")
        void logShareView_shareNotExists() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(null);

            shareAuditService.logShareView(SHARE_CODE, USER_ID, IP_ADDRESS, USER_AGENT);

            verify(shareAccessLogMapper, never()).insert(any(ShareAccessLog.class));
        }

        @Test
        @DisplayName("should handle null actor user id for anonymous access")
        void logShareView_anonymousAccess() {
            FileShare share = createFileShare();
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            shareAuditService.logShareView(SHARE_CODE, null, IP_ADDRESS, USER_AGENT);

            verify(shareAccessLogMapper).insert(any(ShareAccessLog.class));
        }
    }

    @Nested
    @DisplayName("logShareDownload")
    class LogShareDownload {

        @Test
        @DisplayName("should log share download when share exists")
        void logShareDownload_success() {
            FileShare share = createFileShare();
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            shareAuditService.logShareDownload(SHARE_CODE, USER_ID, FILE_HASH, FILE_NAME, IP_ADDRESS);

            ArgumentCaptor<ShareAccessLog> captor = ArgumentCaptor.forClass(ShareAccessLog.class);
            verify(shareAccessLogMapper).insert(captor.capture());
            ShareAccessLog inserted = captor.getValue();
            assertEquals(TENANT_ID, inserted.getTenantId());
            assertEquals(USER_ID, inserted.getShareOwnerId());
            assertEquals(FILE_HASH, inserted.getFileHash());
            assertEquals(FILE_NAME, inserted.getFileName());
            assertEquals(IP_ADDRESS, inserted.getActorIp());
            assertEquals(ShareAccessLog.ACTION_DOWNLOAD, inserted.getActionType());
        }

        @Test
        @DisplayName("should not log when share does not exist")
        void logShareDownload_shareNotExists() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(null);

            shareAuditService.logShareDownload(SHARE_CODE, USER_ID, FILE_HASH, FILE_NAME, IP_ADDRESS);

            verify(shareAccessLogMapper, never()).insert(any(ShareAccessLog.class));
        }

        /**
         * 验证成功和缺失分支的文本日志都不会泄露分享码或文件哈希。
         */
        @Test
        @DisplayName("should not log raw share credentials")
        void shouldNotLogRawShareCredentials() {
            FileShare share = createFileShare();
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            Logger logger = (Logger) LoggerFactory.getLogger(ShareAuditServiceImpl.class);
            Level previousLevel = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
            try {
                shareAuditService.logShareDownload(
                        SHARE_CODE,
                        USER_ID,
                        FILE_HASH,
                        FILE_NAME,
                        IP_ADDRESS);
                when(fileShareMapper.selectTenantIdByShareCodeGlobally(SHARE_CODE)).thenReturn(null);
                shareAuditService.logShareView(SHARE_CODE, USER_ID, IP_ADDRESS, USER_AGENT);
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(previousLevel);
                appender.stop();
            }

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertTrue(messages.stream().noneMatch(message -> message.contains(SHARE_CODE)));
            assertTrue(messages.stream().noneMatch(message -> message.contains(FILE_HASH)));
        }
    }

    @Nested
    @DisplayName("logShareSave")
    class LogShareSave {

        @Test
        @DisplayName("should log share save when share exists")
        void logShareSave_success() {
            FileShare share = createFileShare();
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);

            shareAuditService.logShareSave(SHARE_CODE, USER_ID, FILE_HASH, FILE_NAME, IP_ADDRESS);

            verify(shareAccessLogMapper).insert(any(ShareAccessLog.class));
        }

        @Test
        @DisplayName("should not log when share does not exist")
        void logShareSave_shareNotExists() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(null);

            shareAuditService.logShareSave(SHARE_CODE, USER_ID, FILE_HASH, FILE_NAME, IP_ADDRESS);

            verify(shareAccessLogMapper, never()).insert(any(ShareAccessLog.class));
        }
    }

    @Nested
    @DisplayName("getShareAccessLogs")
    class GetShareAccessLogs {

        @Test
        @DisplayName("should throw when share not found")
        void getShareAccessLogs_shareNotFound_throws() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> shareAuditService.getShareAccessLogs(SHARE_CODE, new Page<>()));

            assertEquals(ResultEnum.PARAM_ERROR, ex.getResultEnum());
        }

        @Test
        @DisplayName("should return paginated logs when share exists")
        void getShareAccessLogs_success() {
            FileShare share = createFileShare();
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(share);
            
            Page<ShareAccessLog> mockPage = new Page<>(1, 10);
            mockPage.setRecords(List.of(createShareAccessLog()));
            mockPage.setTotal(1);
            when(shareAccessLogMapper.selectPage(any(), any())).thenReturn(mockPage);
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());

            IPage<ShareAccessLogVO> result = shareAuditService.getShareAccessLogs(SHARE_CODE, new Page<>());

            assertNotNull(result);
            assertEquals(1, result.getTotal());
        }
    }

    @Nested
    @DisplayName("getShareAccessStats")
    class GetShareAccessStats {

        @Test
        @DisplayName("should throw when share not found")
        void getShareAccessStats_shareNotFound_throws() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> shareAuditService.getShareAccessStats(SHARE_CODE));

            assertEquals(ResultEnum.PARAM_ERROR, ex.getResultEnum());
        }
    }

    @Nested
    @DisplayName("getFileProvenance")
    class GetFileProvenance {

        @Test
        @DisplayName("should throw when file not found")
        void getFileProvenance_fileNotFound_throws() {
            when(fileMapper.selectById(FILE_ID)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> shareAuditService.getFileProvenance(FILE_ID));

            assertEquals(ResultEnum.PARAM_ERROR, ex.getResultEnum());
        }

        @Test
        @DisplayName("should return provenance for original file")
        void getFileProvenance_originalFile() {
            File file = createFile();
            file.setOrigin(null);
            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());

            FileProvenanceVO result = shareAuditService.getFileProvenance(FILE_ID);

            assertTrue(result.isOriginal());
            assertEquals(0, result.depth());
            assertTrue(result.chain().isEmpty());
        }

        @Test
        @DisplayName("should return provenance for saved file")
        void getFileProvenance_savedFile() {
            File file = createFile();
            file.setOrigin(3001L);
            
            File originFile = createFile();
            originFile.setId(3001L);
            originFile.setOrigin(null);
            
            FileSource source = new FileSource();
            source.setOriginFileId(3001L);
            source.setSourceFileId(3001L);
            source.setSourceUserId(USER_ID);
            source.setDepth(1);
            source.setShareCode(SHARE_CODE);
            
            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
            when(fileMapper.selectById(3001L)).thenReturn(originFile);
            when(fileSourceMapper.selectByFileId(FILE_ID, TENANT_ID)).thenReturn(source);
            when(fileSourceMapper.selectProvenanceChain(FILE_ID, TENANT_ID)).thenReturn(List.of());
            when(accountMapper.selectById(USER_ID)).thenReturn(createAccount());

            FileProvenanceVO result = shareAuditService.getFileProvenance(FILE_ID);

            assertFalse(result.isOriginal());
            assertEquals(1, result.depth());
        }
    }

    private FileShare createFileShare() {
        FileShare share = new FileShare();
        share.setId(1L);
        share.setShareCode(SHARE_CODE);
        share.setUserId(USER_ID);
        share.setTenantId(TENANT_ID);
        share.setStatus(FileShare.STATUS_ACTIVE);
        share.setFileHashes("[\"" + FILE_HASH + "\"]");
        return share;
    }

    private ShareAccessLog createShareAccessLog() {
        ShareAccessLog log = new ShareAccessLog();
        log.setId(1L);
        log.setShareCode(SHARE_CODE);
        log.setShareOwnerId(USER_ID);
        log.setActorUserId(USER_ID);
        log.setActionType(ShareAccessLog.ACTION_VIEW);
        log.setActorIp(IP_ADDRESS);
        log.setAccessTime(new Date());
        log.setTenantId(TENANT_ID);
        return log;
    }

    private File createFile() {
        File file = new File();
        file.setId(FILE_ID);
        file.setUid(USER_ID);
        file.setFileHash(FILE_HASH);
        file.setFileName(FILE_NAME);
        file.setTenantId(TENANT_ID);
        file.setCreateTime(new Date());
        return file;
    }

    private Account createAccount() {
        Account account = new Account();
        account.setId(USER_ID);
        account.setUsername("testuser");
        account.setTenantId(TENANT_ID);
        return account;
    }
}
