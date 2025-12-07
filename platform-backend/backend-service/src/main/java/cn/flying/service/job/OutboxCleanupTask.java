package cn.flying.service.job;

import cn.flying.common.lock.DistributedLock;
import cn.flying.dao.mapper.OutboxEventMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 定时清理 Outbox 事件任务。
 * 防止 outbox_event 表无限增长。
 * 使用分布式锁防止多实例重复执行。
 */
@Component
@Slf4j
public class OutboxCleanupTask {

    @Resource
    private OutboxEventMapper outboxEventMapper;

    @Value("${outbox.cleanup.sent-retention-days:7}")
    private int sentRetentionDays;

    @Value("${outbox.cleanup.failed-retention-days:30}")
    private int failedRetentionDays;

    @Value("${outbox.cleanup.max-retries:5}")
    private int maxRetries;

    /**
     * 清理已发送的 Outbox 事件。
     * 默认每天凌晨 3:00 执行。
     * 使用分布式锁（租约 30 分钟）防止多实例重复执行。
     */
    @Scheduled(cron = "${outbox.cleanup.cron:0 0 3 * * ?}")
    @DistributedLock(key = "outbox:cleanup", leaseTime = 1800)
    public void cleanupOutboxEvents() {
        log.info("开始执行 Outbox 事件清理任务...");

        // 清理已发送事件
        cleanupSentEvents();

        // 清理永久失败的事件
        cleanupFailedEvents();

        log.info("Outbox 事件清理任务完成");
    }

    private void cleanupSentEvents() {
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(sentRetentionDays);
            Date cutoffDate = Date.from(cutoffTime.atZone(ZoneId.systemDefault()).toInstant());

            int deletedCount = outboxEventMapper.cleanupSentEvents(cutoffDate);
            log.info("已发送事件清理完成: 删除 {} 条超过 {} 天的记录", deletedCount, sentRetentionDays);
        } catch (Exception e) {
            log.error("清理已发送 Outbox 事件失败", e);
        }
    }

    private void cleanupFailedEvents() {
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(failedRetentionDays);
            Date cutoffDate = Date.from(cutoffTime.atZone(ZoneId.systemDefault()).toInstant());

            int deletedCount = outboxEventMapper.cleanupFailedEvents(maxRetries, cutoffDate);
            log.info("永久失败事件清理完成: 删除 {} 条超过最大重试次数且超过 {} 天的记录",
                    deletedCount, failedRetentionDays);
        } catch (Exception e) {
            log.error("清理失败 Outbox 事件失败", e);
        }
    }
}
