package cn.flying.service.job;

import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.remote.FileRemoteClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FileCleanupTask 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FileCleanupTaskTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private FileRemoteClient fileRemoteClient;

    private FileCleanupTask fileCleanupTask;

    /**
     * 初始化定时清理任务和依赖。
     */
    @BeforeEach
    void setUp() {
        fileCleanupTask = new FileCleanupTask();
        ReflectionTestUtils.setField(fileCleanupTask, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(fileCleanupTask, "tenantMapper", tenantMapper);
        ReflectionTestUtils.setField(fileCleanupTask, "fileRemoteClient", fileRemoteClient);
        ReflectionTestUtils.setField(fileCleanupTask, "retentionDays", 30);
        ReflectionTestUtils.setField(fileCleanupTask, "batchSize", 100);
        ReflectionTestUtils.setField(fileCleanupTask, "legacyPhysicalDeleteEnabled", false);
    }

    /**
     * 清理租户上下文，避免测试间污染。
     */
    @AfterEach
    void tearDown() {
        cn.flying.common.tenant.TenantContext.clear();
    }

    /**
     * 验证移除用户文件列表缓存后，软删除清理仍会物理删除到期记录。
     */
    @Test
    void shouldPhysicallyDeleteExpiredFileWithoutListCacheMaintenance() {
        ReflectionTestUtils.setField(fileCleanupTask, "legacyPhysicalDeleteEnabled", true);
        File file = new File()
                .setId(10L)
                .setTenantId(7L)
                .setUid(42L)
                .setFileHash("hash-cleanup");

        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(7L));
        when(fileMapper.selectDeletedFilesForCleanup(eq(7L), any(Date.class), eq(100)))
                .thenReturn(List.of(file));
        when(fileMapper.countActiveFilesByHash("hash-cleanup", 10L)).thenReturn(1L);
        fileCleanupTask.cleanDeletedFiles();

        verify(fileMapper).physicalDeleteById(10L, 7L);
    }

    /**
     * 验证远端清理和单条数据库删除失败时，任务会隔离异常并继续处理同批次其他文件。
     */
    @Test
    void shouldContinueCleanupWhenRemoteOrSingleRecordDeletionFails() {
        ReflectionTestUtils.setField(fileCleanupTask, "legacyPhysicalDeleteEnabled", true);
        File remoteFailure = expiredFile(10L, "hash-storage-failure");
        File detailFailure = expiredFile(11L, "hash-detail-failure");
        File databaseFailure = expiredFile(12L, "hash-database-failure");
        FileDetailVO detail = new FileDetailVO(
                "42",
                "expired.bin",
                "{}",
                "{\"sha256:cipher\":\"storage/tenant/7/chunk/sha256:cipher\"}",
                remoteFailure.getFileHash(),
                "",
                null,
                8L,
                "application/octet-stream");

        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(7L));
        when(fileMapper.selectDeletedFilesForCleanup(eq(7L), any(Date.class), eq(100)))
                .thenReturn(List.of(remoteFailure, detailFailure, databaseFailure));
        when(fileMapper.countActiveFilesByHash(remoteFailure.getFileHash(), remoteFailure.getId()))
                .thenReturn(0L);
        when(fileMapper.countActiveFilesByHash(detailFailure.getFileHash(), detailFailure.getId()))
                .thenReturn(0L);
        when(fileMapper.countActiveFilesByHash(databaseFailure.getFileHash(), databaseFailure.getId()))
                .thenReturn(1L);
        when(fileRemoteClient.getFile("42", remoteFailure.getFileHash())).thenReturn(Result.success(detail));
        when(fileRemoteClient.getFile("42", detailFailure.getFileHash()))
                .thenThrow(new IllegalStateException("detail unavailable"));
        doThrow(new IllegalStateException("storage unavailable"))
                .when(fileRemoteClient).deleteStorageFile(any());
        doThrow(new IllegalStateException("chain unavailable"))
                .when(fileRemoteClient).deleteFiles(any());
        doAnswer(invocation -> {
            if (databaseFailure.getId().equals(invocation.getArgument(0))) {
                throw new IllegalStateException("database unavailable");
            }
            return null;
        }).when(fileMapper).physicalDeleteById(anyLong(), eq(7L));

        fileCleanupTask.cleanDeletedFiles();

        verify(fileMapper).physicalDeleteById(remoteFailure.getId(), 7L);
        verify(fileMapper).physicalDeleteById(detailFailure.getId(), 7L);
        verify(fileMapper).physicalDeleteById(databaseFailure.getId(), 7L);
    }

    /**
     * Verifies the unsafe legacy deletion path is disabled unless rollout explicitly enables it.
     */
    @Test
    void shouldSkipLegacyPhysicalDeletionByDefault() {
        fileCleanupTask.cleanDeletedFiles();

        verifyNoInteractions(fileMapper, tenantMapper, fileRemoteClient);
    }

    /**
     * 构造同一租户下的到期软删除文件。
     */
    private File expiredFile(Long id, String fileHash) {
        return new File()
                .setId(id)
                .setTenantId(7L)
                .setUid(42L)
                .setFileHash(fileHash);
    }
}
