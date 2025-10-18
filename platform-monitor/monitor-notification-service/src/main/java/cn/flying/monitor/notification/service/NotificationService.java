package cn.flying.monitor.notification.service;

import cn.flying.monitor.notification.entity.AlertInstance;
import cn.flying.monitor.notification.entity.AlertRule;
import cn.flying.monitor.notification.entity.NotificationHistory;

import java.util.List;
import java.util.Map;

/**
 * 通知服务接口
 */
public interface NotificationService {

    /**
     * 发送告警通知
     *
     * @param alertInstance 告警实例
     * @param alertRule     告警规则
     */
    void sendAlertNotification(AlertInstance alertInstance, AlertRule alertRule);

    /**
     * 发送升级通知
     *
     * @param alertInstance 告警实例
     * @param alertRule     告警规则
     */
    void sendEscalationNotification(AlertInstance alertInstance, AlertRule alertRule);

    /**
     * 发送邮件通知
     *
     * @param recipient 接收者
     * @param title     标题
     * @param content   内容
     * @param config    配置
     * @return 通知历史
     */
    NotificationHistory sendEmailNotification(String recipient, String title, String content, Map<String, Object> config);

    /**
     * 发送短信通知
     *
     * @param recipient 接收者
     * @param content   内容
     * @param config    配置
     * @return 通知历史
     */
    NotificationHistory sendSmsNotification(String recipient, String content, Map<String, Object> config);

    /**
     * 发送Webhook通知
     *
     * @param webhookUrl Webhook URL
     * @param payload    负载数据
     * @param config     配置
     * @return 通知历史
     */
    NotificationHistory sendWebhookNotification(String webhookUrl, Map<String, Object> payload, Map<String, Object> config);

    /**
     * 重试失败的通知
     */
    void retryFailedNotifications();

    /**
     * 根据模板渲染通知内容
     *
     * @param templateContent 模板内容
     * @param variables       变量
     * @return 渲染后的内容
     */
    String renderTemplate(String templateContent, Map<String, Object> variables);

    /**
     * 获取通知历史
     *
     * @param alertInstanceId 告警实例ID
     * @return 通知历史列表
     */
    List<NotificationHistory> getNotificationHistory(Long alertInstanceId);

    /**
     * 测试通知配置
     *
     * @param notificationType 通知类型
     * @param config           配置
     * @return 测试结果
     */
    boolean testNotificationConfig(String notificationType, Map<String, Object> config);
}