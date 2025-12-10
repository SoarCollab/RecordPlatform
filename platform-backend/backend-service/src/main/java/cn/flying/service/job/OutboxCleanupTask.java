package cn.flying.service.job;

import cn.flying.common.lock.DistributedLock;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.mapper.OutboxEventMapper;
import cn.flying.dao.mapper.TenantMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 定时清理 Outbox 事件任务。
 * 防止 outbox_event 表无限增长。
 * 使用分布式锁防止多实例重复执行。
 * 多租户隔离：按租户分别执行清理。
 */
@Component
@Slf4j
public class OutboxCleanupTask {

    @Resource
    private OutboxEventMapper outboxEventMapper;

    @Resource
    private TenantMapper tenantMapper;

    @Value("${outbox.cleanup.sent-retention-days:7}")
    private int sentRetentionDays;

    @Value("${outbox.cleanup.failed-retention-days:30}")
    private int failedRetentionDays;

    @Value("${outbox.cleanup.max-retries:5}")
    private int maxRetries;

    /**
     * 清理 Outbox 事件。
     * 默认每天凌晨 3:00 执行。
     * 使用分布式锁（租约 30 分钟）防止多实例重复执行。
     * 按租户分别执行清理。
     */
    @Scheduled(cron = "${outbox.cleanup.cron:0 0 3 * * ?}")
    @DistributedLock(key = "outbox:cleanup", leaseTime = 1800)
    public void cleanupOutboxEvents() {
        log.info("开始执行 Outbox 事件清理任务...");

        // 获取所有活跃租户
        List<Long> activeTenantIds = tenantMapper.selectActiveTenantIds();
        if (activeTenantIds == null || activeTenantIds.isEmpty()) {
            log.warn("没有活跃租户，跳过 Outbox 清理");
            return;
        }

        int totalSentDeleted = 0;
        int totalFailedDeleted = 0;

        for (Long tenantId : activeTenantIds) {
            try {
                int[] result = TenantContext.callWithTenant(tenantId, () -> cleanupForTenant(tenantId));
                totalSentDeleted += result[0];
                totalFailedDeleted += result[1];
            } catch (Exception e) {
                log.error("租户 {} Outbox 清理失败: {}", tenantId, e.getMessage(), e);
            }
        }

        log.info("Outbox 事件清理任务完成: 已发送事件删除={}, 失败事件删除={}", totalSentDeleted, totalFailedDeleted);
    }

    /**
     * 清理指定租户的 Outbox 事件
     *
     * @param tenantId 租户ID
     * @return [已发送事件删除数, 失败事件删除数]
     */
    private int[] cleanupForTenant(Long tenantId) {
        int sentDeleted = 0;
        int failedDeleted = 0;

        // 清理已发送事件
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(sentRetentionDays);
            Date cutoffDate = Date.from(cutoffTime.atZone(ZoneId.systemDefault()).toInstant());

            sentDeleted = outboxEventMapper.cleanupSentEvents(tenantId, cutoffDate);
            if (sentDeleted > 0) {
                log.info("租户 {} 已发送事件清理完成: 删除 {} 条", tenantId, sentDeleted);
            }
        } catch (Exception e) {
            log.error("租户 {} 清理已发送 Outbox 事件失败", tenantId, e);
        }

        // 清理永久失败的事件
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(failedRetentionDays);
            Date cutoffDate = Date.from(cutoffTime.atZone(ZoneId.systemDefault()).toInstant());

            failedDeleted = outboxEventMapper.cleanupFailedEvents(tenantId, maxRetries, cutoffDate);
            if (failedDeleted > 0) {
                log.info("租户 {} 永久失败事件清理完成: 删除 {} 条", tenantId, failedDeleted);
            }
        } catch (Exception e) {
            log.error("租户 {} 清理失败 Outbox 事件失败", tenantId, e);
        }

        return new int[]{sentDeleted, failedDeleted};
    }
}
