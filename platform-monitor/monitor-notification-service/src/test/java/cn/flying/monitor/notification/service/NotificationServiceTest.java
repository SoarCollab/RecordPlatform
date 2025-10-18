package cn.flying.monitor.notification.service;

import cn.flying.monitor.notification.entity.AlertInstance;
import cn.flying.monitor.notification.entity.AlertRule;
import cn.flying.monitor.notification.entity.NotificationHistory;
import cn.flying.monitor.notification.entity.NotificationTemplate;
import cn.flying.monitor.notification.mapper.NotificationHistoryMapper;
import cn.flying.monitor.notification.mapper.NotificationTemplateMapper;
import cn.flying.monitor.notification.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 通知服务测试
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private NotificationHistoryMapper notificationHistoryMapper;

    @Mock
    private NotificationTemplateMapper notificationTemplateMapper;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private AlertRule alertRule;
    private AlertInstance alertInstance;
    private NotificationTemplate emailTemplate;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(notificationService, "mailFrom", "test@example.com");

        alertRule = new AlertRule();
        alertRule.setId(1L);
        alertRule.setName("CPU Usage Alert");
        alertRule.setDescription("Alert when CPU usage exceeds threshold");
        alertRule.setMetricName("cpu_usage");
        alertRule.setConditionOperator(">");
        alertRule.setThresholdValue(new BigDecimal("80.0"));
        alertRule.setSeverity("high");

        alertInstance = new AlertInstance();
        alertInstance.setId(1L);
        alertInstance.setRuleId(1L);
        alertInstance.setClientId("client-001");
        alertInstance.setStatus(AlertInstance.Status.FIRING.getValue());
        alertInstance.setFirstTriggered(LocalDateTime.now());
        alertInstance.setLastTriggered(LocalDateTime.now());
        alertInstance.setEscalationLevel(0);

        emailTemplate = new NotificationTemplate();
        emailTemplate.setId(1L);
        emailTemplate.setName("High Severity Email Template");
        emailTemplate.setNotificationType("email");
        emailTemplate.setSeverity("high");
        emailTemplate.setTitleTemplate("Alert: {{rule_name}}");
        emailTemplate.setContentTemplate("Alert {{rule_name}} triggered for client {{client_id}}. Threshold: {{threshold_value}}, Status: {{status}}");
        emailTemplate.setEnabled(true);
    }

    @Test
    void testSendEmailNotification_Success() {
        // Given
        String recipient = "admin@example.com";
        String title = "Test Alert";
        String content = "Test alert content";
        Map<String, Object> config = new HashMap<>();

        when(notificationHistoryMapper.insert(any(NotificationHistory.class))).thenReturn(1);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // When
        NotificationHistory result = notificationService.sendEmailNotification(recipient, title, content, config);

        // Then
        assertNotNull(result);
        assertEquals(NotificationHistory.NotificationType.EMAIL.getValue(), result.getNotificationType());
        assertEquals(recipient, result.getRecipient());
        assertEquals(title, result.getTitle());
        assertEquals(content, result.getContent());
        assertEquals(NotificationHistory.Status.SENT.getValue(), result.getStatus());
        
        verify(mailSender).send(any(SimpleMailMessage.class));
        verify(notificationHistoryMapper).insert(any(NotificationHistory.class));
    }

    @Test
    void testSendEmailNotification_Failure() {
        // Given
        String recipient = "admin@example.com";
        String title = "Test Alert";
        String content = "Test alert content";
        Map<String, Object> config = new HashMap<>();

        when(notificationHistoryMapper.insert(any(NotificationHistory.class))).thenReturn(1);
        doThrow(new RuntimeException("Mail server error")).when(mailSender).send(any(SimpleMailMessage.class));

        // When
        NotificationHistory result = notificationService.sendEmailNotification(recipient, title, content, config);

        // Then
        assertNotNull(result);
        assertEquals(NotificationHistory.Status.FAILED.getValue(), result.getStatus());
        assertNotNull(result.getErrorMessage());
        assertNotNull(result.getNextRetryAt());
        
        verify(mailSender).send(any(SimpleMailMessage.class));
        verify(notificationHistoryMapper).insert(any(NotificationHistory.class));
    }

    @Test
    void testSendSmsNotification_Success() {
        // Given
        String recipient = "+1234567890";
        String content = "Test SMS content";
        Map<String, Object> config = new HashMap<>();

        when(notificationHistoryMapper.insert(any(NotificationHistory.class))).thenReturn(1);

        // When
        NotificationHistory result = notificationService.sendSmsNotification(recipient, content, config);

        // Then
        assertNotNull(result);
        assertEquals(NotificationHistory.NotificationType.SMS.getValue(), result.getNotificationType());
        assertEquals(recipient, result.getRecipient());
        assertEquals(content, result.getContent());
        assertEquals(NotificationHistory.Status.SENT.getValue(), result.getStatus());
        
        verify(notificationHistoryMapper).insert(any(NotificationHistory.class));
    }

    @Test
    void testSendWebhookNotification_Success() {
        // Given
        String webhookUrl = "https://example.com/webhook";
        Map<String, Object> payload = Map.of("alert", "test", "severity", "high");
        Map<String, Object> config = new HashMap<>();

        when(notificationHistoryMapper.insert(any(NotificationHistory.class))).thenReturn(1);

        // When
        NotificationHistory result = notificationService.sendWebhookNotification(webhookUrl, payload, config);

        // Then
        assertNotNull(result);
        assertEquals(NotificationHistory.NotificationType.WEBHOOK.getValue(), result.getNotificationType());
        assertEquals(webhookUrl, result.getRecipient());
        
        verify(notificationHistoryMapper).insert(any(NotificationHistory.class));
    }

    @Test
    void testSendAlertNotification_WithEmailChannel() {
        // Given
        Map<String, Object> notificationChannels = new HashMap<>();
        Map<String, Object> emailConfig = new HashMap<>();
        emailConfig.put("recipients", Arrays.asList("admin@example.com", "ops@example.com"));
        notificationChannels.put("email", emailConfig);
        alertRule.setNotificationChannels(notificationChannels);

        when(notificationTemplateMapper.selectByTypeAndSeverity("email", "high")).thenReturn(emailTemplate);
        when(notificationHistoryMapper.insert(any(NotificationHistory.class))).thenReturn(1);
        when(notificationHistoryMapper.updateById(any(NotificationHistory.class))).thenReturn(1);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // When
        notificationService.sendAlertNotification(alertInstance, alertRule);

        // Then
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class)); // Two recipients
        verify(notificationHistoryMapper, times(2)).insert(any(NotificationHistory.class));
        assertTrue(alertInstance.getNotificationSent());
    }

    @Test
    void testSendAlertNotification_NoChannels() {
        // Given
        alertRule.setNotificationChannels(null);

        // When
        notificationService.sendAlertNotification(alertInstance, alertRule);

        // Then
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(notificationHistoryMapper, never()).insert(any(NotificationHistory.class));
    }

    @Test
    void testSendEscalationNotification() {
        // Given
        alertInstance.setEscalationLevel(1);
        
        Map<String, Object> escalationRules = new HashMap<>();
        Map<String, Object> level1Config = new HashMap<>();
        Map<String, Object> emailConfig = new HashMap<>();
        emailConfig.put("recipients", Arrays.asList("manager@example.com"));
        level1Config.put("email", emailConfig);
        escalationRules.put("level_1", level1Config);
        alertRule.setEscalationRules(escalationRules);

        when(notificationTemplateMapper.selectByTypeAndSeverity("email", "high")).thenReturn(emailTemplate);
        when(notificationHistoryMapper.insert(any(NotificationHistory.class))).thenReturn(1);
        when(notificationHistoryMapper.updateById(any(NotificationHistory.class))).thenReturn(1);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // When
        notificationService.sendEscalationNotification(alertInstance, alertRule);

        // Then
        verify(mailSender).send(any(SimpleMailMessage.class));
        verify(notificationHistoryMapper).insert(any(NotificationHistory.class));
    }

    @Test
    void testRetryFailedNotifications() {
        // Given
        NotificationHistory failedNotification = new NotificationHistory();
        failedNotification.setId(1L);
        failedNotification.setNotificationType(NotificationHistory.NotificationType.EMAIL.getValue());
        failedNotification.setRecipient("admin@example.com");
        failedNotification.setTitle("Test Alert");
        failedNotification.setContent("Test content");
        failedNotification.setStatus(NotificationHistory.Status.RETRY.getValue());
        failedNotification.setRetryCount(1);

        when(notificationHistoryMapper.selectForRetry(any(LocalDateTime.class))).thenReturn(Arrays.asList(failedNotification));
        when(notificationHistoryMapper.updateById(any(NotificationHistory.class))).thenReturn(1);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // When
        notificationService.retryFailedNotifications();

        // Then
        verify(mailSender).send(any(SimpleMailMessage.class));
        verify(notificationHistoryMapper).updateById(any(NotificationHistory.class));
    }

    @Test
    void testRenderTemplate() {
        // Given
        String template = "Alert {{rule_name}} triggered for client {{client_id}}";
        Map<String, Object> variables = new HashMap<>();
        variables.put("rule_name", "CPU Usage Alert");
        variables.put("client_id", "client-001");

        // When
        String result = notificationService.renderTemplate(template, variables);

        // Then
        assertEquals("Alert CPU Usage Alert triggered for client client-001", result);
    }

    @Test
    void testRenderTemplate_EmptyTemplate() {
        // Given
        String template = "";
        Map<String, Object> variables = new HashMap<>();

        // When
        String result = notificationService.renderTemplate(template, variables);

        // Then
        assertEquals("", result);
    }

    @Test
    void testRenderTemplate_NullTemplate() {
        // Given
        String template = null;
        Map<String, Object> variables = new HashMap<>();

        // When
        String result = notificationService.renderTemplate(template, variables);

        // Then
        assertEquals("", result);
    }

    @Test
    void testGetNotificationHistory() {
        // Given
        Long alertInstanceId = 1L;
        List<NotificationHistory> historyList = Arrays.asList(new NotificationHistory());
        when(notificationHistoryMapper.selectByAlertInstanceId(alertInstanceId)).thenReturn(historyList);

        // When
        List<NotificationHistory> result = notificationService.getNotificationHistory(alertInstanceId);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        verify(notificationHistoryMapper).selectByAlertInstanceId(alertInstanceId);
    }

    @Test
    void testTestNotificationConfig_Email() {
        // Given
        String notificationType = "email";
        Map<String, Object> config = Map.of("test_recipient", "test@example.com");
        
        when(notificationHistoryMapper.insert(any(NotificationHistory.class))).thenReturn(1);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // When
        boolean result = notificationService.testNotificationConfig(notificationType, config);

        // Then
        assertTrue(result);
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void testTestNotificationConfig_InvalidType() {
        // Given
        String notificationType = "invalid";
        Map<String, Object> config = new HashMap<>();

        // When
        boolean result = notificationService.testNotificationConfig(notificationType, config);

        // Then
        assertFalse(result);
    }
}