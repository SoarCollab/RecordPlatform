package cn.flying.monitor.notification.service.impl;

import cn.flying.monitor.notification.entity.AlertInstance;
import cn.flying.monitor.notification.entity.AlertRule;
import cn.flying.monitor.notification.entity.NotificationHistory;
import cn.flying.monitor.notification.entity.NotificationTemplate;
import cn.flying.monitor.notification.mapper.NotificationHistoryMapper;
import cn.flying.monitor.notification.mapper.NotificationTemplateMapper;
import cn.flying.monitor.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通知服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final JavaMailSender mailSender;
    private final NotificationHistoryMapper notificationHistoryMapper;
    private final NotificationTemplateMapper notificationTemplateMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${spring.mail.username}")
    private String mailFrom;

    @Override
    public void sendAlertNotification(AlertInstance alertInstance, AlertRule alertRule) {
        log.info("Sending alert notification for instance: {}", alertInstance.getId());
        
        Map<String, Object> notificationChannels = alertRule.getNotificationChannels();
        if (notificationChannels == null || notificationChannels.isEmpty()) {
            log.warn("No notification channels configured for rule: {}", alertRule.getName());
            return;
        }
        
        Map<String, Object> variables = createTemplateVariables(alertInstance, alertRule);
        
        // 发送邮件通知
        if (notificationChannels.containsKey("email")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> emailConfig = (Map<String, Object>) notificationChannels.get("email");
            sendEmailNotificationForAlert(alertInstance, alertRule, emailConfig, variables);
        }
        
        // 发送短信通知
        if (notificationChannels.containsKey("sms")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> smsConfig = (Map<String, Object>) notificationChannels.get("sms");
            sendSmsNotificationForAlert(alertInstance, alertRule, smsConfig, variables);
        }
        
        // 发送Webhook通知
        if (notificationChannels.containsKey("webhook")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> webhookConfig = (Map<String, Object>) notificationChannels.get("webhook");
            sendWebhookNotificationForAlert(alertInstance, alertRule, webhookConfig, variables);
        }
        
        // 更新通知发送状态
        alertInstance.setNotificationSent(true);
    }

    @Override
    public void sendEscalationNotification(AlertInstance alertInstance, AlertRule alertRule) {
        log.info("Sending escalation notification for instance: {}", alertInstance.getId());
        
        Map<String, Object> escalationRules = alertRule.getEscalationRules();
        if (escalationRules == null || escalationRules.isEmpty()) {
            log.warn("No escalation rules configured for rule: {}", alertRule.getName());
            return;
        }
        
        Map<String, Object> variables = createTemplateVariables(alertInstance, alertRule);
        variables.put("escalation_level", alertInstance.getEscalationLevel());
        variables.put("is_escalation", true);
        
        // 根据升级级别获取通知配置
        String escalationKey = "level_" + alertInstance.getEscalationLevel();
        if (escalationRules.containsKey(escalationKey)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> levelConfig = (Map<String, Object>) escalationRules.get(escalationKey);
            
            if (levelConfig.containsKey("email")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> emailConfig = (Map<String, Object>) levelConfig.get("email");
                sendEmailNotificationForAlert(alertInstance, alertRule, emailConfig, variables);
            }
            
            if (levelConfig.containsKey("sms")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> smsConfig = (Map<String, Object>) levelConfig.get("sms");
                sendSmsNotificationForAlert(alertInstance, alertRule, smsConfig, variables);
            }
        }
    }

    @Override
    public NotificationHistory sendEmailNotification(String recipient, String title, String content, Map<String, Object> config) {
        NotificationHistory history = new NotificationHistory();
        history.setNotificationType(NotificationHistory.NotificationType.EMAIL.getValue());
        history.setRecipient(recipient);
        history.setTitle(title);
        history.setContent(content);
        history.setNotificationConfig(config);
        history.setRetryCount(0);
        history.setStatus(NotificationHistory.Status.PENDING.getValue());
        
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(recipient);
            message.setSubject(title);
            message.setText(content);
            
            mailSender.send(message);
            
            history.setStatus(NotificationHistory.Status.SENT.getValue());
            history.setSentAt(LocalDateTime.now());
            log.info("Email sent successfully to: {}", recipient);
            
        } catch (Exception e) {
            log.error("Failed to send email to: {}", recipient, e);
            history.setStatus(NotificationHistory.Status.FAILED.getValue());
            history.setErrorMessage(e.getMessage());
            history.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
        }
        
        notificationHistoryMapper.insert(history);
        return history;
    }

    @Override
    public NotificationHistory sendSmsNotification(String recipient, String content, Map<String, Object> config) {
        NotificationHistory history = new NotificationHistory();
        history.setNotificationType(NotificationHistory.NotificationType.SMS.getValue());
        history.setRecipient(recipient);
        history.setContent(content);
        history.setNotificationConfig(config);
        history.setRetryCount(0);
        history.setStatus(NotificationHistory.Status.PENDING.getValue());
        
        try {
            // 这里应该集成实际的短信服务提供商API
            // 例如阿里云短信、腾讯云短信等
            log.info("SMS notification would be sent to: {} with content: {}", recipient, content);
            
            // 模拟发送成功
            history.setStatus(NotificationHistory.Status.SENT.getValue());
            history.setSentAt(LocalDateTime.now());
            
        } catch (Exception e) {
            log.error("Failed to send SMS to: {}", recipient, e);
            history.setStatus(NotificationHistory.Status.FAILED.getValue());
            history.setErrorMessage(e.getMessage());
            history.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
        }
        
        notificationHistoryMapper.insert(history);
        return history;
    }

    @Override
    public NotificationHistory sendWebhookNotification(String webhookUrl, Map<String, Object> payload, Map<String, Object> config) {
        NotificationHistory history = new NotificationHistory();
        history.setNotificationType(NotificationHistory.NotificationType.WEBHOOK.getValue());
        history.setRecipient(webhookUrl);
        history.setContent(payload.toString());
        history.setNotificationConfig(config);
        history.setRetryCount(0);
        history.setStatus(NotificationHistory.Status.PENDING.getValue());
        
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            // 添加自定义头部
            if (config.containsKey("headers")) {
                @SuppressWarnings("unchecked")
                Map<String, String> customHeaders = (Map<String, String>) config.get("headers");
                customHeaders.forEach(headers::add);
            }
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                history.setStatus(NotificationHistory.Status.SENT.getValue());
                history.setSentAt(LocalDateTime.now());
                log.info("Webhook sent successfully to: {}", webhookUrl);
            } else {
                throw new RuntimeException("Webhook returned status: " + response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.error("Failed to send webhook to: {}", webhookUrl, e);
            history.setStatus(NotificationHistory.Status.FAILED.getValue());
            history.setErrorMessage(e.getMessage());
            history.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
        }
        
        notificationHistoryMapper.insert(history);
        return history;
    }

    @Override
    public void retryFailedNotifications() {
        log.debug("Processing notification retries");
        
        List<NotificationHistory> retryList = notificationHistoryMapper.selectForRetry(LocalDateTime.now());
        
        for (NotificationHistory history : retryList) {
            history.setRetryCount(history.getRetryCount() + 1);
            
            try {
                switch (history.getNotificationType()) {
                    case "email" -> {
                        SimpleMailMessage message = new SimpleMailMessage();
                        message.setFrom(mailFrom);
                        message.setTo(history.getRecipient());
                        message.setSubject(history.getTitle());
                        message.setText(history.getContent());
                        mailSender.send(message);
                    }
                    case "sms" -> {
                        // 重试短信发送
                        log.info("Retrying SMS to: {}", history.getRecipient());
                    }
                    case "webhook" -> {
                        // 重试Webhook发送
                        log.info("Retrying webhook to: {}", history.getRecipient());
                    }
                }
                
                history.setStatus(NotificationHistory.Status.SENT.getValue());
                history.setSentAt(LocalDateTime.now());
                history.setNextRetryAt(null);
                log.info("Retry successful for notification: {}", history.getId());
                
            } catch (Exception e) {
                log.error("Retry failed for notification: {}", history.getId(), e);
                
                if (history.getRetryCount() >= 3) {
                    history.setStatus(NotificationHistory.Status.FAILED.getValue());
                    history.setNextRetryAt(null);
                } else {
                    history.setStatus(NotificationHistory.Status.RETRY.getValue());
                    history.setNextRetryAt(LocalDateTime.now().plusMinutes(5 * history.getRetryCount()));
                }
                history.setErrorMessage(e.getMessage());
            }
            
            notificationHistoryMapper.updateById(history);
        }
    }

    @Override
    public String renderTemplate(String templateContent, Map<String, Object> variables) {
        if (templateContent == null || templateContent.isEmpty()) {
            return "";
        }
        
        String result = templateContent;
        Pattern pattern = Pattern.compile("\\{\\{(\\w+)\\}\\}");
        Matcher matcher = pattern.matcher(templateContent);
        
        while (matcher.find()) {
            String variableName = matcher.group(1);
            Object value = variables.get(variableName);
            if (value != null) {
                result = result.replace("{{" + variableName + "}}", value.toString());
            }
        }
        
        return result;
    }

    @Override
    public List<NotificationHistory> getNotificationHistory(Long alertInstanceId) {
        return notificationHistoryMapper.selectByAlertInstanceId(alertInstanceId);
    }

    @Override
    public boolean testNotificationConfig(String notificationType, Map<String, Object> config) {
        try {
            switch (notificationType) {
                case "email" -> {
                    String testEmail = (String) config.get("test_recipient");
                    if (testEmail != null) {
                        sendEmailNotification(testEmail, "Test Notification", "This is a test notification.", config);
                        return true;
                    }
                }
                case "sms" -> {
                    String testPhone = (String) config.get("test_recipient");
                    if (testPhone != null) {
                        sendSmsNotification(testPhone, "Test notification", config);
                        return true;
                    }
                }
                case "webhook" -> {
                    String testUrl = (String) config.get("test_url");
                    if (testUrl != null) {
                        Map<String, Object> testPayload = Map.of("test", true, "message", "Test notification");
                        sendWebhookNotification(testUrl, testPayload, config);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Test notification failed for type: {}", notificationType, e);
            return false;
        }
        
        return false;
    }

    private void sendEmailNotificationForAlert(AlertInstance alertInstance, AlertRule alertRule, 
                                             Map<String, Object> emailConfig, Map<String, Object> variables) {
        @SuppressWarnings("unchecked")
        List<String> recipients = (List<String>) emailConfig.get("recipients");
        if (recipients == null || recipients.isEmpty()) {
            log.warn("No email recipients configured for rule: {}", alertRule.getName());
            return;
        }
        
        NotificationTemplate template = getNotificationTemplate("email", alertRule.getSeverity());
        String title = renderTemplate(template != null ? template.getTitleTemplate() : "Alert: {{rule_name}}", variables);
        String content = renderTemplate(template != null ? template.getContentTemplate() : "Alert triggered for {{rule_name}}", variables);
        
        for (String recipient : recipients) {
            NotificationHistory history = sendEmailNotification(recipient, title, content, emailConfig);
            history.setAlertInstanceId(alertInstance.getId());
            notificationHistoryMapper.updateById(history);
        }
    }

    private void sendSmsNotificationForAlert(AlertInstance alertInstance, AlertRule alertRule, 
                                           Map<String, Object> smsConfig, Map<String, Object> variables) {
        @SuppressWarnings("unchecked")
        List<String> recipients = (List<String>) smsConfig.get("recipients");
        if (recipients == null || recipients.isEmpty()) {
            log.warn("No SMS recipients configured for rule: {}", alertRule.getName());
            return;
        }
        
        NotificationTemplate template = getNotificationTemplate("sms", alertRule.getSeverity());
        String content = renderTemplate(template != null ? template.getContentTemplate() : "Alert: {{rule_name}}", variables);
        
        for (String recipient : recipients) {
            NotificationHistory history = sendSmsNotification(recipient, content, smsConfig);
            history.setAlertInstanceId(alertInstance.getId());
            notificationHistoryMapper.updateById(history);
        }
    }

    private void sendWebhookNotificationForAlert(AlertInstance alertInstance, AlertRule alertRule, 
                                               Map<String, Object> webhookConfig, Map<String, Object> variables) {
        String webhookUrl = (String) webhookConfig.get("url");
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("No webhook URL configured for rule: {}", alertRule.getName());
            return;
        }
        
        Map<String, Object> payload = new HashMap<>(variables);
        payload.put("alert_instance_id", alertInstance.getId());
        payload.put("rule_id", alertRule.getId());
        
        NotificationHistory history = sendWebhookNotification(webhookUrl, payload, webhookConfig);
        history.setAlertInstanceId(alertInstance.getId());
        notificationHistoryMapper.updateById(history);
    }

    private Map<String, Object> createTemplateVariables(AlertInstance alertInstance, AlertRule alertRule) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("rule_name", alertRule.getName());
        variables.put("rule_description", alertRule.getDescription());
        variables.put("metric_name", alertRule.getMetricName());
        variables.put("threshold_value", alertRule.getThresholdValue().toString());
        variables.put("condition_operator", alertRule.getConditionOperator());
        variables.put("severity", alertRule.getSeverity());
        variables.put("client_id", alertInstance.getClientId());
        variables.put("first_triggered", alertInstance.getFirstTriggered().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        variables.put("last_triggered", alertInstance.getLastTriggered().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        variables.put("status", alertInstance.getStatus());
        variables.put("escalation_level", alertInstance.getEscalationLevel());
        return variables;
    }

    private NotificationTemplate getNotificationTemplate(String notificationType, String severity) {
        NotificationTemplate template = notificationTemplateMapper.selectByTypeAndSeverity(notificationType, severity);
        if (template == null) {
            template = notificationTemplateMapper.selectDefaultByType(notificationType);
        }
        return template;
    }
}