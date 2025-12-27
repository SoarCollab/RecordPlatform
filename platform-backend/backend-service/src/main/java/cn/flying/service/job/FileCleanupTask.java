package cn.flying.service.job;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.lock.DistributedLock;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.platformapi.request.DeleteFilesRequest;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.remote.FileRemoteClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定时清理软删除文件任务。
 * 从存储和区块链中清除已标记删除的文件记录。
 * 使用分布式锁防止多实例重复执行。
 * 按租户分别执行，确保多租户隔离。
 */
@Component
@Slf4j
public class FileCleanupTask {

    @Resource
    private FileMapper fileMapper;

    @Resource
    private TenantMapper tenantMapper;

    @Resource
    private FileRemoteClient fileRemoteClient;

    @Resource(name = "cacheManager")
    private CacheManager cacheManager;

    @Value("${file.cleanup.retention-days:30}")
    private int retentionDays;

    @Value("${file.cleanup.batch-size:100}")
    private int batchSize;

    /**
     * 清理超过保留期的软删除文件。
     * 默认每天凌晨 3:00 执行。
     * 使用分布式锁（租约 1 小时）防止多实例重复执行。
     * 按租户分别执行，确保多租户数据隔离。
     */
    @Scheduled(cron = "${file.cleanup.cron:0 0 3 * * ?}")
    @DistributedLock(key = "file:cleanup", leaseTime = 3600)
    public void cleanDeletedFiles() {
        log.info("开始执行文件清理任务...");

        // 获取所有活跃租户
        List<Long> activeTenantIds = tenantMapper.selectActiveTenantIds();
        if (activeTenantIds == null || activeTenantIds.isEmpty()) {
            log.warn("没有活跃租户，跳过文件清理");
            return;
        }

        log.info("发现 {} 个活跃租户，开始按租户清理文件", activeTenantIds.size());

        int totalSuccess = 0;
        int totalFail = 0;

        for (Long tenantId : activeTenantIds) {
            try {
                // 在租户上下文中执行清理
                int[] result = TenantContext.callWithTenant(tenantId, () -> cleanFilesForTenant(tenantId));
                totalSuccess += result[0];
                totalFail += result[1];
            } catch (Exception e) {
                log.error("租户 {} 文件清理失败: {}", tenantId, e.getMessage(), e);
            }
        }

        log.info("文件清理任务完成: 总成功={}, 总失败={}", totalSuccess, totalFail);
    }

    /**
     * 清理指定租户的软删除文件
     *
     * @param tenantId 租户ID
     * @return [成功数, 失败数]
     */
    private int[] cleanFilesForTenant(Long tenantId) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        Date cutoffDate = Date.from(cutoffTime.atZone(ZoneId.systemDefault()).toInstant());

        List<File> pendingFiles = fileMapper.selectDeletedFilesForCleanup(tenantId, cutoffDate, batchSize);

        if (pendingFiles == null || pendingFiles.isEmpty()) {
            log.debug("租户 {} 没有待清理的文件", tenantId);
            return new int[]{0, 0};
        }

        log.info("租户 {} 发现 {} 个待清理文件", tenantId, pendingFiles.size());
        int successCount = 0;
        int failCount = 0;
        Set<Long> affectedUserIds = new HashSet<>();

        for (File file : pendingFiles) {
            try {
                cleanupSingleFile(file, tenantId);
                affectedUserIds.add(file.getUid());
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.warn("租户 {} 清理文件失败: id={}, hash={}, error={}",
                    tenantId, file.getId(), file.getFileHash(), e.getMessage());
            }
        }

        evictCachesForUsers(affectedUserIds);
        log.info("租户 {} 文件清理完成: 成功={}, 失败={}", tenantId, successCount, failCount);
        return new int[]{successCount, failCount};
    }

    private void evictCachesForUsers(Set<Long> userIds) {
        Cache userFilesCache = cacheManager.getCache("userFiles");
        if (userFilesCache != null && !userIds.isEmpty()) {
            for (Long userId : userIds) {
                userFilesCache.evict(userId);
            }
            log.debug("清除 {} 个用户的缓存", userIds.size());
        }
    }

    private void cleanupSingleFile(File file, Long tenantId) {
        String userId = String.valueOf(file.getUid());
        String fileHash = file.getFileHash();

        // 检查是否有其他用户仍在使用该 fileHash（分享保存的文件）
        // 如果有其他活跃引用，只删除数据库记录，保留存储和区块链数据
        Long activeReferences = fileMapper.countActiveFilesByHash(fileHash, file.getId());
        boolean hasOtherReferences = activeReferences != null && activeReferences > 0;

        if (hasOtherReferences) {
            log.info("文件 {} 仍有 {} 个其他用户引用，仅删除数据库记录，保留存储数据",
                    fileHash, activeReferences);
        } else {
            // 没有其他引用，可以安全删除存储和区块链数据
            // 1. 尝试从区块链获取文件内容映射
            try {
                FileDetailVO detail = ResultUtils.getData(
                    fileRemoteClient.getFile(userId, fileHash)
                );

                if (detail != null && detail.getContent() != null) {
                    // 2. 解析存储位置并从 MinIO 删除
                    @SuppressWarnings("unchecked")
                    Map<String, String> contentMap = JsonConverter.parse(detail.getContent(), Map.class);
                    if (contentMap != null && !contentMap.isEmpty()) {
                        try {
                            fileRemoteClient.deleteStorageFile(contentMap);
                        } catch (Exception e) {
                            log.warn("存储删除失败: file={}, error={}", fileHash, e.getMessage());
                        }
                    }
                }

                // 3. 从区块链删除
                try {
                    fileRemoteClient.deleteFiles(DeleteFilesRequest.builder()
                            .uploader(userId)
                            .fileHashList(List.of(fileHash))
                            .build());
                } catch (Exception e) {
                    log.warn("区块链删除失败: file={}, error={}", fileHash, e.getMessage());
                }
            } catch (Exception e) {
                log.warn("获取文件详情失败: {}, 继续数据库清理: {}", fileHash, e.getMessage());
            }
        }

        // 4. 物理删除数据库记录（带租户隔离）
        fileMapper.physicalDeleteById(file.getId(), tenantId);

        log.debug("文件清理成功: tenantId={}, id={}, hash={}, 保留存储={}",
                tenantId, file.getId(), fileHash, hasOtherReferences);
    }
}
