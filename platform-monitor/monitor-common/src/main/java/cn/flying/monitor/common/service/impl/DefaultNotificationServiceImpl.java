package cn.flying.monitor.common.service.impl;

import cn.flying.monitor.common.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 默认通知服务实现 - 用于测试和开发环境
 */
@Slf4j
@Service
@ConditionalOnMissingBean(NotificationService.class)
public class DefaultNotificationServiceImpl implements NotificationService {

    @Override
    public void sendEmailNotification(String recipient, String title, String content, Map<String, Object> config) {
        log.info("Email notification would be sent to: {}", recipient);
        log.info("Title: {}", title);
        log.info("Content: {}", content);
        log.info("Config: {}", config);
        
        // 在实际实现中，这里会调用邮件服务API
        // 目前只记录日志用于测试
    }

    @Override
    public void sendSmsNotification(String recipient, String content, Map<String, Object> config) {
        log.info("SMS notification would be sent to: {}", recipient);
        log.info("Content: {}", content);
        log.info("Config: {}", config);
        
        // 在实际实现中，这里会调用短信服务API
    }

    @Override
    public void sendWebhookNotification(String webhookUrl, Map<String, Object> payload, Map<String, Object> config) {
        log.info("Webhook notification would be sent to: {}", webhookUrl);
        log.info("Payload: {}", payload);
        log.info("Config: {}", config);
        
        // 在实际实现中，这里会发送HTTP请求到webhook URL
    }
}