package cn.flying.service.job;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.remote.FileRemoteClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Scheduled task for cleaning up soft-deleted files from storage and blockchain.
 * Note: This task bypasses MyBatis-Plus logical delete interceptor to query deleted records.
 */
@Component
@Slf4j
public class FileCleanupTask {

    @Resource
    private FileMapper fileMapper;

    @Resource
    private FileRemoteClient fileRemoteClient;

    @Value("${file.cleanup.retention-days:30}")
    private int retentionDays;

    @Value("${file.cleanup.batch-size:100}")
    private int batchSize;

    /**
     * Clean up soft-deleted files that have been marked as deleted for more than the retention period.
     * Runs daily at 3:00 AM by default.
     */
    @Scheduled(cron = "${file.cleanup.cron:0 0 3 * * ?}")
    public void cleanDeletedFiles() {
        log.info("Starting scheduled file cleanup task...");

        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        Date cutoffDate = Date.from(cutoffTime.atZone(ZoneId.systemDefault()).toInstant());

        // Use native SQL to bypass MyBatis-Plus logical delete interceptor
        List<File> pendingFiles = fileMapper.selectDeletedFilesForCleanup(cutoffDate, batchSize);

        if (pendingFiles == null || pendingFiles.isEmpty()) {
            log.info("No files pending cleanup.");
            return;
        }

        log.info("Found {} files pending cleanup", pendingFiles.size());
        int successCount = 0;
        int failCount = 0;

        for (File file : pendingFiles) {
            try {
                cleanupSingleFile(file);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.warn("Failed to cleanup file: id={}, hash={}, error={}",
                    file.getId(), file.getFileHash(), e.getMessage());
            }
        }

        log.info("File cleanup completed: success={}, failed={}", successCount, failCount);
    }

    private void cleanupSingleFile(File file) {
        String userId = String.valueOf(file.getUid());
        String fileHash = file.getFileHash();

        // 1. Try to get file content mapping from blockchain (tolerant if not found)
        try {
            FileDetailVO detail = ResultUtils.getData(
                fileRemoteClient.getFile(userId, fileHash)
            );

            if (detail != null && detail.getContent() != null) {
                // 2. Parse storage locations and delete from MinIO
                @SuppressWarnings("unchecked")
                Map<String, String> contentMap = JsonConverter.parse(detail.getContent(), Map.class);
                if (contentMap != null && !contentMap.isEmpty()) {
                    try {
                        fileRemoteClient.deleteStorageFile(contentMap);
                    } catch (Exception e) {
                        log.warn("Storage deletion failed for file {}, continuing: {}", fileHash, e.getMessage());
                    }
                }
            }

            // 3. Try to delete from blockchain (tolerant if fails)
            try {
                fileRemoteClient.deleteFiles(userId, List.of(fileHash));
            } catch (Exception e) {
                log.warn("Blockchain deletion failed for file {}, continuing: {}", fileHash, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Could not retrieve file detail for {}, proceeding with DB cleanup: {}", fileHash, e.getMessage());
        }

        // 4. Physically delete from database (bypass logical delete)
        fileMapper.physicalDeleteById(file.getId());

        log.debug("Successfully cleaned up file: id={}, hash={}", file.getId(), fileHash);
    }
}
