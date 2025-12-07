package cn.flying.service.job;

import cn.flying.dao.mapper.ProcessedMessageMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Scheduled task for cleaning up old processed messages.
 * Prevents the processed_message table from growing indefinitely.
 */
@Component
@Slf4j
public class ProcessedMessageCleanupTask {

    @Resource
    private ProcessedMessageMapper processedMessageMapper;

    @Value("${processed-message.cleanup.retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "${processed-message.cleanup.cron:0 30 3 * * ?}")
    public void cleanupOldMessages() {
        log.info("Starting processed message cleanup task...");

        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        Date cutoffDate = Date.from(cutoffTime.atZone(ZoneId.systemDefault()).toInstant());

        try {
            int deletedCount = processedMessageMapper.cleanupOldMessages(cutoffDate);
            log.info("Processed message cleanup completed: deleted {} messages older than {} days",
                    deletedCount, retentionDays);
        } catch (Exception e) {
            log.error("Failed to cleanup processed messages", e);
        }
    }
}
