package cn.flying.monitor.common.service;

import java.util.Map;

/**
 * 通知服务接口 - 通用版本
 */
public interface NotificationService {

    /**
     * 发送邮件通知
     *
     * @param recipient 接收者
     * @param title     标题
     * @param content   内容
     * @param config    配置
     */
    void sendEmailNotification(String recipient, String title, String content, Map<String, Object> config);

    /**
     * 发送短信通知
     *
     * @param recipient 接收者
     * @param content   内容
     * @param config    配置
     */
    void sendSmsNotification(String recipient, String content, Map<String, Object> config);

    /**
     * 发送Webhook通知
     *
     * @param webhookUrl Webhook URL
     * @param payload    负载数据
     * @param config     配置
     */
    void sendWebhookNotification(String webhookUrl, Map<String, Object> payload, Map<String, Object> config);
}