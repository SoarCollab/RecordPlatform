package cn.flying.service.job;

import cn.flying.common.lock.DistributedLock;
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
 * 定时清理已处理消息任务。
 * 防止 processed_message 表无限增长。
 * 使用分布式锁防止多实例重复执行。
 */
@Component
@Slf4j
public class ProcessedMessageCleanupTask {

    @Resource
    private ProcessedMessageMapper processedMessageMapper;

    @Value("${processed-message.cleanup.retention-days:7}")
    private int retentionDays;

    /**
     * 清理过期的已处理消息记录。
     * 默认每天凌晨 3:30 执行。
     * 使用分布式锁（租约 30 分钟）防止多实例重复执行。
     */
    @Scheduled(cron = "${processed-message.cleanup.cron:0 30 3 * * ?}")
    @DistributedLock(key = "processed-message:cleanup", leaseTime = 1800)
    public void cleanupOldMessages() {
        log.info("开始执行已处理消息清理任务...");

        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        Date cutoffDate = Date.from(cutoffTime.atZone(ZoneId.systemDefault()).toInstant());

        try {
            int deletedCount = processedMessageMapper.cleanupOldMessages(cutoffDate);
            log.info("已处理消息清理完成: 删除 {} 条超过 {} 天的记录",
                    deletedCount, retentionDays);
        } catch (Exception e) {
            log.error("清理已处理消息失败", e);
        }
    }
}
