package cn.flying.service.job;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.lock.DistributedLock;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
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
 */
@Component
@Slf4j
public class FileCleanupTask {

    @Resource
    private FileMapper fileMapper;

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
     */
    @Scheduled(cron = "${file.cleanup.cron:0 0 3 * * ?}")
    @DistributedLock(key = "file:cleanup", leaseTime = 3600)
    public void cleanDeletedFiles() {
        log.info("开始执行文件清理任务...");

        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        Date cutoffDate = Date.from(cutoffTime.atZone(ZoneId.systemDefault()).toInstant());

        List<File> pendingFiles = fileMapper.selectDeletedFilesForCleanup(cutoffDate, batchSize);

        if (pendingFiles == null || pendingFiles.isEmpty()) {
            log.info("没有待清理的文件");
            return;
        }

        log.info("发现 {} 个待清理文件", pendingFiles.size());
        int successCount = 0;
        int failCount = 0;
        Set<Long> affectedUserIds = new HashSet<>();

        for (File file : pendingFiles) {
            try {
                cleanupSingleFile(file);
                affectedUserIds.add(file.getUid());
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.warn("清理文件失败: id={}, hash={}, error={}",
                    file.getId(), file.getFileHash(), e.getMessage());
            }
        }

        evictCachesForUsers(affectedUserIds);
        log.info("文件清理完成: 成功={}, 失败={}", successCount, failCount);
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

    private void cleanupSingleFile(File file) {
        String userId = String.valueOf(file.getUid());
        String fileHash = file.getFileHash();

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

        // 4. 物理删除数据库记录
        fileMapper.physicalDeleteById(file.getId());

        log.debug("文件清理成功: id={}, hash={}", file.getId(), fileHash);
    }
}
