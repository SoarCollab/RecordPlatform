package cn.flying.service.job;

import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.service.remote.FileRemoteClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache userFilesCache;

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
        ReflectionTestUtils.setField(fileCleanupTask, "cacheManager", cacheManager);
        ReflectionTestUtils.setField(fileCleanupTask, "retentionDays", 30);
        ReflectionTestUtils.setField(fileCleanupTask, "batchSize", 100);
    }

    /**
     * 清理租户上下文，避免测试间污染。
     */
    @AfterEach
    void tearDown() {
        cn.flying.common.tenant.TenantContext.clear();
    }

    /**
     * 验证软删除文件清理后按 tenantId:userId 驱逐用户文件缓存。
     */
    @Test
    void shouldEvictTenantScopedUserFilesCacheAfterCleanup() {
        File file = new File()
                .setId(10L)
                .setTenantId(7L)
                .setUid(42L)
                .setFileHash("hash-cleanup");

        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(7L));
        when(fileMapper.selectDeletedFilesForCleanup(eq(7L), any(Date.class), eq(100)))
                .thenReturn(List.of(file));
        when(fileMapper.countActiveFilesByHash("hash-cleanup", 10L)).thenReturn(1L);
        when(cacheManager.getCache("userFiles")).thenReturn(userFilesCache);

        fileCleanupTask.cleanDeletedFiles();

        verify(userFilesCache).evict("7:42");
        verify(userFilesCache, never()).evict(42L);
        verify(fileMapper).physicalDeleteById(10L, 7L);
    }
}
