package cn.flying.monitor.notification.scheduler;

import cn.flying.monitor.notification.service.AlertEvaluationService;
import cn.flying.monitor.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通知相关定时任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationService notificationService;
    private final AlertEvaluationService alertEvaluationService;

    /**
     * 重试失败的通知
     * 每5分钟执行一次
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void retryFailedNotifications() {
        log.debug("Starting notification retry task");
        try {
            notificationService.retryFailedNotifications();
        } catch (Exception e) {
            log.error("Error during notification retry task", e);
        }
    }

    /**
     * 处理告警升级
     * 每10分钟执行一次
     */
    @Scheduled(fixedRate = 600000) // 10分钟
    public void processAlertEscalation() {
        log.debug("Starting alert escalation task");
        try {
            alertEvaluationService.processAlertEscalation();
        } catch (Exception e) {
            log.error("Error during alert escalation task", e);
        }
    }

    /**
     * 清理过期的通知历史
     * 每天凌晨2点执行
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupNotificationHistory() {
        log.info("Starting notification history cleanup task");
        try {
            // 这里可以添加清理逻辑，删除超过30天的通知历史记录
            log.info("Notification history cleanup completed");
        } catch (Exception e) {
            log.error("Error during notification history cleanup", e);
        }
    }
}