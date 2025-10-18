package cn.flying.monitor.notification.listener;

import cn.flying.monitor.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 增强的邮件队列监听器
 * 扩展原有的MailQueueListener功能，支持多种通知渠道
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RabbitListener(queues = {"mail", "notification"})
public class EnhancedMailQueueListener {

    private final NotificationService notificationService;

    /**
     * 处理邮件发送消息
     *
     * @param data 邮件信息
     */
    @RabbitHandler
    public void handleMailMessage(Map<String, Object> data) {
        log.info("Received mail message: {}", data);
        
        try {
            String messageType = (String) data.get("message_type");
            
            if ("alert_notification".equals(messageType)) {
                handleAlertNotification(data);
            } else if ("email".equals(messageType)) {
                handleEmailNotification(data);
            } else if ("sms".equals(messageType)) {
                handleSmsNotification(data);
            } else if ("webhook".equals(messageType)) {
                handleWebhookNotification(data);
            } else {
                // 兼容原有的邮件发送逻辑
                handleLegacyMailMessage(data);
            }
            
        } catch (Exception e) {
            log.error("Failed to process message: {}", data, e);
        }
    }

    /**
     * 处理告警通知消息
     *
     * @param data 消息数据
     */
    private void handleAlertNotification(Map<String, Object> data) {
        log.info("Processing alert notification");
        
        // 这里可以处理来自告警评估引擎的通知请求
        // 实际的通知发送逻辑在AlertEvaluationService中调用NotificationService
    }

    /**
     * 处理邮件通知消息
     *
     * @param data 消息数据
     */
    private void handleEmailNotification(Map<String, Object> data) {
        String recipient = (String) data.get("recipient");
        String title = (String) data.get("title");
        String content = (String) data.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) data.getOrDefault("config", Map.of());
        
        notificationService.sendEmailNotification(recipient, title, content, config);
    }

    /**
     * 处理短信通知消息
     *
     * @param data 消息数据
     */
    private void handleSmsNotification(Map<String, Object> data) {
        String recipient = (String) data.get("recipient");
        String content = (String) data.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) data.getOrDefault("config", Map.of());
        
        notificationService.sendSmsNotification(recipient, content, config);
    }

    /**
     * 处理Webhook通知消息
     *
     * @param data 消息数据
     */
    private void handleWebhookNotification(Map<String, Object> data) {
        String webhookUrl = (String) data.get("webhook_url");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) data.getOrDefault("config", Map.of());
        
        notificationService.sendWebhookNotification(webhookUrl, payload, config);
    }

    /**
     * 处理原有的邮件发送逻辑（向后兼容）
     *
     * @param data 邮件信息
     */
    private void handleLegacyMailMessage(Map<String, Object> data) {
        log.info("Processing legacy mail message");
        
        String email = (String) data.get("email");
        Integer code = (Integer) data.get("code");
        String type = (String) data.get("type");
        
        if (email == null || code == null || type == null) {
            log.warn("Invalid legacy mail message format: {}", data);
            return;
        }
        
        String title;
        String content;
        
        switch (type) {
            case "reset" -> {
                title = "您的密码重置邮件";
                content = "你好，您正在执行重置密码操作，验证码: " + code + "，有效时间3分钟，如非本人操作，请无视。";
            }
            case "modify" -> {
                title = "您的邮箱修改邮件";
                content = "你好，您正在绑定新的邮箱，验证码: " + code + "，有效时间3分钟，如非本人操作，请无视。";
            }
            default -> {
                log.warn("Unknown legacy mail type: {}", type);
                return;
            }
        }
        
        notificationService.sendEmailNotification(email, title, content, Map.of());
    }
}