package cn.flying.monitor.notification.service;

import cn.flying.monitor.notification.dto.AlertRuleDTO;
import cn.flying.monitor.notification.entity.AlertRule;
import cn.flying.monitor.notification.mapper.AlertRuleMapper;
import cn.flying.monitor.notification.service.impl.AlertRuleServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 告警规则服务测试
 */
@ExtendWith(MockitoExtension.class)
class AlertRuleServiceTest {

    @Mock
    private AlertRuleMapper alertRuleMapper;

    @InjectMocks
    private AlertRuleServiceImpl alertRuleService;

    private AlertRuleDTO validAlertRuleDTO;
    private AlertRule alertRule;

    @BeforeEach
    void setUp() {
        validAlertRuleDTO = new AlertRuleDTO();
        validAlertRuleDTO.setName("CPU Usage Alert");
        validAlertRuleDTO.setDescription("Alert when CPU usage exceeds threshold");
        validAlertRuleDTO.setMetricName("cpu_usage");
        validAlertRuleDTO.setConditionOperator(">");
        validAlertRuleDTO.setThresholdValue(new BigDecimal("80.0"));
        validAlertRuleDTO.setSeverity("high");
        validAlertRuleDTO.setEnabled(true);
        validAlertRuleDTO.setEvaluationInterval(60);
        validAlertRuleDTO.setConsecutiveFailuresRequired(1);
        validAlertRuleDTO.setCreatedBy(1L);

        alertRule = new AlertRule();
        alertRule.setId(1L);
        alertRule.setName("CPU Usage Alert");
        alertRule.setDescription("Alert when CPU usage exceeds threshold");
        alertRule.setMetricName("cpu_usage");
        alertRule.setConditionOperator(">");
        alertRule.setThresholdValue(new BigDecimal("80.0"));
        alertRule.setSeverity("high");
        alertRule.setEnabled(true);
        alertRule.setEvaluationInterval(60);
        alertRule.setConsecutiveFailuresRequired(1);
        alertRule.setCreatedBy(1L);
        alertRule.setCreatedAt(LocalDateTime.now());
        alertRule.setUpdatedAt(LocalDateTime.now());
    }

    @Test
    void testCreateAlertRule_Success() {
        // Given
        when(alertRuleMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(alertRuleMapper.insert(any(AlertRule.class))).thenReturn(1);

        // When
        AlertRule result = alertRuleService.createAlertRule(validAlertRuleDTO);

        // Then
        assertNotNull(result);
        assertEquals(validAlertRuleDTO.getName(), result.getName());
        assertEquals(validAlertRuleDTO.getMetricName(), result.getMetricName());
        verify(alertRuleMapper).insert(any(AlertRule.class));
    }

    @Test
    void testCreateAlertRule_DuplicateName() {
        // Given
        when(alertRuleMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> alertRuleService.createAlertRule(validAlertRuleDTO));
        
        assertTrue(exception.getMessage().contains("Alert rule name already exists"));
        verify(alertRuleMapper, never()).insert(any(AlertRule.class));
    }

    @Test
    void testUpdateAlertRule_Success() {
        // Given
        Long ruleId = 1L;
        when(alertRuleMapper.selectById(ruleId)).thenReturn(alertRule);
        when(alertRuleMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(alertRuleMapper.updateById(any(AlertRule.class))).thenReturn(1);

        // When
        AlertRule result = alertRuleService.updateAlertRule(ruleId, validAlertRuleDTO);

        // Then
        assertNotNull(result);
        assertEquals(validAlertRuleDTO.getName(), result.getName());
        verify(alertRuleMapper).updateById(any(AlertRule.class));
    }

    @Test
    void testUpdateAlertRule_NotFound() {
        // Given
        Long ruleId = 1L;
        when(alertRuleMapper.selectById(ruleId)).thenReturn(null);

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> alertRuleService.updateAlertRule(ruleId, validAlertRuleDTO));
        
        assertTrue(exception.getMessage().contains("Alert rule not found"));
        verify(alertRuleMapper, never()).updateById(any(AlertRule.class));
    }

    @Test
    void testDeleteAlertRule_Success() {
        // Given
        Long ruleId = 1L;
        when(alertRuleMapper.selectById(ruleId)).thenReturn(alertRule);
        when(alertRuleMapper.deleteById(ruleId)).thenReturn(1);

        // When
        assertDoesNotThrow(() -> alertRuleService.deleteAlertRule(ruleId));

        // Then
        verify(alertRuleMapper).deleteById(ruleId);
    }

    @Test
    void testGetEnabledAlertRules() {
        // Given
        List<AlertRule> enabledRules = Arrays.asList(alertRule);
        when(alertRuleMapper.selectEnabledRules()).thenReturn(enabledRules);

        // When
        List<AlertRule> result = alertRuleService.getEnabledAlertRules();

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(alertRule.getName(), result.get(0).getName());
        verify(alertRuleMapper).selectEnabledRules();
    }

    @Test
    void testGetAlertRulesByMetricName() {
        // Given
        String metricName = "cpu_usage";
        List<AlertRule> rules = Arrays.asList(alertRule);
        when(alertRuleMapper.selectByMetricName(metricName)).thenReturn(rules);

        // When
        List<AlertRule> result = alertRuleService.getAlertRulesByMetricName(metricName);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(metricName, result.get(0).getMetricName());
        verify(alertRuleMapper).selectByMetricName(metricName);
    }

    @Test
    void testToggleAlertRule_Success() {
        // Given
        Long ruleId = 1L;
        Boolean enabled = false;
        when(alertRuleMapper.selectById(ruleId)).thenReturn(alertRule);
        when(alertRuleMapper.updateById(any(AlertRule.class))).thenReturn(1);

        // When
        assertDoesNotThrow(() -> alertRuleService.toggleAlertRule(ruleId, enabled));

        // Then
        verify(alertRuleMapper).updateById(any(AlertRule.class));
    }

    @Test
    void testValidateAlertRule_Valid() {
        // When
        boolean result = alertRuleService.validateAlertRule(validAlertRuleDTO);

        // Then
        assertTrue(result);
    }

    @Test
    void testValidateAlertRule_InvalidOperator() {
        // Given
        validAlertRuleDTO.setConditionOperator("invalid");

        // When
        boolean result = alertRuleService.validateAlertRule(validAlertRuleDTO);

        // Then
        assertFalse(result);
    }

    @Test
    void testValidateAlertRule_InvalidSeverity() {
        // Given
        validAlertRuleDTO.setSeverity("invalid");

        // When
        boolean result = alertRuleService.validateAlertRule(validAlertRuleDTO);

        // Then
        assertFalse(result);
    }

    @Test
    void testValidateAlertRule_InvalidEvaluationInterval() {
        // Given
        validAlertRuleDTO.setEvaluationInterval(10); // Too low

        // When
        boolean result = alertRuleService.validateAlertRule(validAlertRuleDTO);

        // Then
        assertFalse(result);
    }
}