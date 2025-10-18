package cn.flying.monitor.notification.service;

import cn.flying.monitor.notification.entity.AlertInstance;
import cn.flying.monitor.notification.entity.AlertRule;
import cn.flying.monitor.notification.mapper.AlertInstanceMapper;
import cn.flying.monitor.notification.service.impl.AlertEvaluationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * 告警评估服务测试
 */
@ExtendWith(MockitoExtension.class)
class AlertEvaluationServiceTest {

    @Mock
    private AlertRuleService alertRuleService;

    @Mock
    private AlertInstanceMapper alertInstanceMapper;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private AlertEvaluationServiceImpl alertEvaluationService;

    private AlertRule alertRule;
    private AlertInstance alertInstance;

    @BeforeEach
    void setUp() {
        alertRule = new AlertRule();
        alertRule.setId(1L);
        alertRule.setName("CPU Usage Alert");
        alertRule.setMetricName("cpu_usage");
        alertRule.setConditionOperator(">");
        alertRule.setThresholdValue(new BigDecimal("80.0"));
        alertRule.setSeverity("high");
        alertRule.setEnabled(true);
        alertRule.setEvaluationInterval(60);
        alertRule.setConsecutiveFailuresRequired(1);

        alertInstance = new AlertInstance();
        alertInstance.setId(1L);
        alertInstance.setRuleId(1L);
        alertInstance.setClientId("client-001");
        alertInstance.setStatus(AlertInstance.Status.FIRING.getValue());
        alertInstance.setFirstTriggered(LocalDateTime.now());
        alertInstance.setLastTriggered(LocalDateTime.now());
        alertInstance.setEscalationLevel(0);
    }

    @Test
    void testEvaluateMetric_TriggerNewAlert() {
        // Given
        String metricName = "cpu_usage";
        BigDecimal metricValue = new BigDecimal("85.0");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        when(alertRuleService.getAlertRulesByMetricName(metricName)).thenReturn(Arrays.asList(alertRule));
        when(alertInstanceMapper.selectByRuleAndClient(alertRule.getId(), clientId)).thenReturn(null);
        when(alertInstanceMapper.insert(any(AlertInstance.class))).thenReturn(1);

        // When
        alertEvaluationService.evaluateMetric(metricName, metricValue, clientId, metadata);

        // Then
        verify(alertInstanceMapper).insert(any(AlertInstance.class));
        verify(notificationService).sendAlertNotification(any(AlertInstance.class), eq(alertRule));
    }

    @Test
    void testEvaluateMetric_UpdateExistingAlert() {
        // Given
        String metricName = "cpu_usage";
        BigDecimal metricValue = new BigDecimal("85.0");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        when(alertRuleService.getAlertRulesByMetricName(metricName)).thenReturn(Arrays.asList(alertRule));
        when(alertInstanceMapper.selectByRuleAndClient(alertRule.getId(), clientId)).thenReturn(alertInstance);
        when(alertInstanceMapper.updateById(any(AlertInstance.class))).thenReturn(1);

        // When
        alertEvaluationService.evaluateMetric(metricName, metricValue, clientId, metadata);

        // Then
        verify(alertInstanceMapper).updateById(any(AlertInstance.class));
        verify(notificationService, never()).sendAlertNotification(any(AlertInstance.class), any(AlertRule.class));
    }

    @Test
    void testEvaluateMetric_ResolveAlert() {
        // Given
        String metricName = "cpu_usage";
        BigDecimal metricValue = new BigDecimal("70.0"); // Below threshold
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        when(alertRuleService.getAlertRulesByMetricName(metricName)).thenReturn(Arrays.asList(alertRule));
        when(alertInstanceMapper.selectByRuleAndClient(alertRule.getId(), clientId)).thenReturn(alertInstance);
        when(alertInstanceMapper.resolveAlert(eq(alertInstance.getId()), any(LocalDateTime.class))).thenReturn(1);

        // When
        alertEvaluationService.evaluateMetric(metricName, metricValue, clientId, metadata);

        // Then
        verify(alertInstanceMapper).resolveAlert(eq(alertInstance.getId()), any(LocalDateTime.class));
    }

    @Test
    void testEvaluateRule_GreaterThan_True() {
        // Given
        BigDecimal metricValue = new BigDecimal("85.0");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        // When
        boolean result = alertEvaluationService.evaluateRule(alertRule, metricValue, clientId, metadata);

        // Then
        assertTrue(result);
    }

    @Test
    void testEvaluateRule_GreaterThan_False() {
        // Given
        BigDecimal metricValue = new BigDecimal("75.0");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        // When
        boolean result = alertEvaluationService.evaluateRule(alertRule, metricValue, clientId, metadata);

        // Then
        assertFalse(result);
    }

    @Test
    void testEvaluateRule_LessThan() {
        // Given
        alertRule.setConditionOperator("<");
        alertRule.setThresholdValue(new BigDecimal("20.0"));
        BigDecimal metricValue = new BigDecimal("15.0");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        // When
        boolean result = alertEvaluationService.evaluateRule(alertRule, metricValue, clientId, metadata);

        // Then
        assertTrue(result);
    }

    @Test
    void testEvaluateRule_WithClientFilter_Match() {
        // Given
        Map<String, Object> clientFilter = new HashMap<>();
        clientFilter.put("client_ids", Arrays.asList("client-001", "client-002"));
        alertRule.setClientFilter(clientFilter);

        BigDecimal metricValue = new BigDecimal("85.0");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        // When
        boolean result = alertEvaluationService.evaluateRule(alertRule, metricValue, clientId, metadata);

        // Then
        assertTrue(result);
    }

    @Test
    void testEvaluateRule_WithClientFilter_NoMatch() {
        // Given
        Map<String, Object> clientFilter = new HashMap<>();
        clientFilter.put("client_ids", Arrays.asList("client-002", "client-003"));
        alertRule.setClientFilter(clientFilter);

        BigDecimal metricValue = new BigDecimal("85.0");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        // When
        boolean result = alertEvaluationService.evaluateRule(alertRule, metricValue, clientId, metadata);

        // Then
        assertFalse(result);
    }

    @Test
    void testTriggerAlert() {
        // Given
        String clientId = "client-001";
        BigDecimal metricValue = new BigDecimal("85.0");
        when(alertInstanceMapper.insert(any(AlertInstance.class))).thenReturn(1);

        // When
        AlertInstance result = alertEvaluationService.triggerAlert(alertRule, clientId, metricValue);

        // Then
        assertNotNull(result);
        assertEquals(alertRule.getId(), result.getRuleId());
        assertEquals(clientId, result.getClientId());
        assertEquals(AlertInstance.Status.FIRING.getValue(), result.getStatus());
        verify(alertInstanceMapper).insert(any(AlertInstance.class));
    }

    @Test
    void testAcknowledgeAlert_Success() {
        // Given
        Long alertInstanceId = 1L;
        Long acknowledgedBy = 100L;
        when(alertInstanceMapper.selectById(alertInstanceId)).thenReturn(alertInstance);
        when(alertInstanceMapper.acknowledgeAlert(eq(alertInstanceId), eq(acknowledgedBy), any(LocalDateTime.class))).thenReturn(1);

        // When
        assertDoesNotThrow(() -> alertEvaluationService.acknowledgeAlert(alertInstanceId, acknowledgedBy));

        // Then
        verify(alertInstanceMapper).acknowledgeAlert(eq(alertInstanceId), eq(acknowledgedBy), any(LocalDateTime.class));
    }

    @Test
    void testAcknowledgeAlert_NotFound() {
        // Given
        Long alertInstanceId = 1L;
        Long acknowledgedBy = 100L;
        when(alertInstanceMapper.selectById(alertInstanceId)).thenReturn(null);

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> alertEvaluationService.acknowledgeAlert(alertInstanceId, acknowledgedBy));

        assertTrue(exception.getMessage().contains("Alert instance not found"));
        verify(alertInstanceMapper, never()).acknowledgeAlert(anyLong(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void testAcknowledgeAlert_InvalidStatus() {
        // Given
        Long alertInstanceId = 1L;
        Long acknowledgedBy = 100L;
        alertInstance.setStatus(AlertInstance.Status.RESOLVED.getValue());
        when(alertInstanceMapper.selectById(alertInstanceId)).thenReturn(alertInstance);

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> alertEvaluationService.acknowledgeAlert(alertInstanceId, acknowledgedBy));

        assertTrue(exception.getMessage().contains("Can only acknowledge firing alerts"));
        verify(alertInstanceMapper, never()).acknowledgeAlert(anyLong(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void testMatchesClientFilter_NoFilter() {
        // Given
        Map<String, Object> clientFilter = null;
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        // When
        boolean result = alertEvaluationService.matchesClientFilter(clientFilter, clientId, metadata);

        // Then
        assertTrue(result);
    }

    @Test
    void testMatchesClientFilter_EmptyFilter() {
        // Given
        Map<String, Object> clientFilter = new HashMap<>();
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        // When
        boolean result = alertEvaluationService.matchesClientFilter(clientFilter, clientId, metadata);

        // Then
        assertTrue(result);
    }

    @Test
    void testMatchesClientFilter_ClientIdMatch() {
        // Given
        Map<String, Object> clientFilter = new HashMap<>();
        clientFilter.put("client_ids", Arrays.asList("client-001", "client-002"));
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        // When
        boolean result = alertEvaluationService.matchesClientFilter(clientFilter, clientId, metadata);

        // Then
        assertTrue(result);
    }

    @Test
    void testMatchesClientFilter_EnvironmentMatch() {
        // Given
        Map<String, Object> clientFilter = new HashMap<>();
        clientFilter.put("environment", "production");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("environment", "production");

        // When
        boolean result = alertEvaluationService.matchesClientFilter(clientFilter, clientId, metadata);

        // Then
        assertTrue(result);
    }

    @Test
    void testMatchesClientFilter_EnvironmentNoMatch() {
        // Given
        Map<String, Object> clientFilter = new HashMap<>();
        clientFilter.put("environment", "production");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("environment", "development");

        // When
        boolean result = alertEvaluationService.matchesClientFilter(clientFilter, clientId, metadata);

        // Then
        assertFalse(result);
    }

    @Test
    void testProcessAlertEscalation() {
        // Given
        List<AlertInstance> alertsForEscalation = Arrays.asList(alertInstance);
        when(alertInstanceMapper.selectForEscalation(any(LocalDateTime.class))).thenReturn(alertsForEscalation);
        when(alertRuleService.getAlertRuleById(alertInstance.getRuleId())).thenReturn(alertRule);
        
        Map<String, Object> escalationRules = new HashMap<>();
        escalationRules.put("level_1", Map.of("email", Map.of("recipients", Arrays.asList("admin@example.com"))));
        alertRule.setEscalationRules(escalationRules);
        
        when(alertInstanceMapper.updateById(any(AlertInstance.class))).thenReturn(1);

        // When
        alertEvaluationService.processAlertEscalation();

        // Then
        verify(alertInstanceMapper).updateById(any(AlertInstance.class));
        verify(notificationService).sendEscalationNotification(any(AlertInstance.class), eq(alertRule));
    }
}