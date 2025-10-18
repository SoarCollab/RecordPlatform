package cn.flying.monitor.notification.integration;

import cn.flying.monitor.notification.dto.AlertRuleDTO;
import cn.flying.monitor.notification.entity.AlertRule;
import cn.flying.monitor.notification.service.AlertEvaluationService;
import cn.flying.monitor.notification.service.AlertRuleService;
import cn.flying.monitor.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 告警系统集成测试
 */
@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
class AlertIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockBean
    private AlertRuleService alertRuleService;

    @MockBean
    private AlertEvaluationService alertEvaluationService;

    @MockBean
    private NotificationService notificationService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private AlertRule testAlertRule;
    private AlertRuleDTO testAlertRuleDTO;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        objectMapper = new ObjectMapper();

        testAlertRule = new AlertRule();
        testAlertRule.setId(1L);
        testAlertRule.setName("CPU Usage Alert");
        testAlertRule.setDescription("Alert when CPU usage exceeds threshold");
        testAlertRule.setMetricName("cpu_usage");
        testAlertRule.setConditionOperator(">");
        testAlertRule.setThresholdValue(new BigDecimal("80.0"));
        testAlertRule.setSeverity("high");
        testAlertRule.setEnabled(true);
        testAlertRule.setEvaluationInterval(60);
        testAlertRule.setConsecutiveFailuresRequired(1);

        testAlertRuleDTO = new AlertRuleDTO();
        testAlertRuleDTO.setName("CPU Usage Alert");
        testAlertRuleDTO.setDescription("Alert when CPU usage exceeds threshold");
        testAlertRuleDTO.setMetricName("cpu_usage");
        testAlertRuleDTO.setConditionOperator(">");
        testAlertRuleDTO.setThresholdValue(new BigDecimal("80.0"));
        testAlertRuleDTO.setSeverity("high");
        testAlertRuleDTO.setEnabled(true);
        testAlertRuleDTO.setEvaluationInterval(60);
        testAlertRuleDTO.setConsecutiveFailuresRequired(1);
    }

    @Test
    void testCreateAlertRule_Success() throws Exception {
        // Given
        when(alertRuleService.createAlertRule(any(AlertRuleDTO.class))).thenReturn(testAlertRule);

        // When & Then
        mockMvc.perform(post("/api/v1/alert-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testAlertRuleDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("CPU Usage Alert"))
                .andExpect(jsonPath("$.metricName").value("cpu_usage"))
                .andExpect(jsonPath("$.severity").value("high"));

        verify(alertRuleService).createAlertRule(any(AlertRuleDTO.class));
    }

    @Test
    void testCreateAlertRule_ValidationError() throws Exception {
        // Given - Invalid DTO with missing required fields
        AlertRuleDTO invalidDTO = new AlertRuleDTO();
        invalidDTO.setName(""); // Empty name should fail validation

        // When & Then
        mockMvc.perform(post("/api/v1/alert-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidDTO)))
                .andExpect(status().isBadRequest());

        verify(alertRuleService, never()).createAlertRule(any(AlertRuleDTO.class));
    }

    @Test
    void testGetAlertRule_Success() throws Exception {
        // Given
        Long ruleId = 1L;
        when(alertRuleService.getAlertRuleById(ruleId)).thenReturn(testAlertRule);

        // When & Then
        mockMvc.perform(get("/api/v1/alert-rules/{id}", ruleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("CPU Usage Alert"));

        verify(alertRuleService).getAlertRuleById(ruleId);
    }

    @Test
    void testGetAlertRule_NotFound() throws Exception {
        // Given
        Long ruleId = 999L;
        when(alertRuleService.getAlertRuleById(ruleId)).thenReturn(null);

        // When & Then
        mockMvc.perform(get("/api/v1/alert-rules/{id}", ruleId))
                .andExpect(status().isNotFound());

        verify(alertRuleService).getAlertRuleById(ruleId);
    }

    @Test
    void testUpdateAlertRule_Success() throws Exception {
        // Given
        Long ruleId = 1L;
        when(alertRuleService.updateAlertRule(eq(ruleId), any(AlertRuleDTO.class))).thenReturn(testAlertRule);

        // When & Then
        mockMvc.perform(put("/api/v1/alert-rules/{id}", ruleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testAlertRuleDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("CPU Usage Alert"));

        verify(alertRuleService).updateAlertRule(eq(ruleId), any(AlertRuleDTO.class));
    }

    @Test
    void testDeleteAlertRule_Success() throws Exception {
        // Given
        Long ruleId = 1L;
        doNothing().when(alertRuleService).deleteAlertRule(ruleId);

        // When & Then
        mockMvc.perform(delete("/api/v1/alert-rules/{id}", ruleId))
                .andExpect(status().isOk());

        verify(alertRuleService).deleteAlertRule(ruleId);
    }

    @Test
    void testGetEnabledAlertRules() throws Exception {
        // Given
        when(alertRuleService.getEnabledAlertRules()).thenReturn(Arrays.asList(testAlertRule));

        // When & Then
        mockMvc.perform(get("/api/v1/alert-rules/enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].enabled").value(true));

        verify(alertRuleService).getEnabledAlertRules();
    }

    @Test
    void testGetAlertRulesByMetric() throws Exception {
        // Given
        String metricName = "cpu_usage";
        when(alertRuleService.getAlertRulesByMetricName(metricName)).thenReturn(Arrays.asList(testAlertRule));

        // When & Then
        mockMvc.perform(get("/api/v1/alert-rules/metric/{metricName}", metricName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].metricName").value(metricName));

        verify(alertRuleService).getAlertRulesByMetricName(metricName);
    }

    @Test
    void testToggleAlertRule() throws Exception {
        // Given
        Long ruleId = 1L;
        Boolean enabled = false;
        doNothing().when(alertRuleService).toggleAlertRule(ruleId, enabled);

        // When & Then
        mockMvc.perform(patch("/api/v1/alert-rules/{id}/toggle", ruleId)
                .param("enabled", enabled.toString()))
                .andExpect(status().isOk());

        verify(alertRuleService).toggleAlertRule(ruleId, enabled);
    }

    @Test
    void testValidateAlertRule_Valid() throws Exception {
        // Given
        when(alertRuleService.validateAlertRule(any(AlertRuleDTO.class))).thenReturn(true);

        // When & Then
        mockMvc.perform(post("/api/v1/alert-rules/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testAlertRuleDTO)))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));

        verify(alertRuleService).validateAlertRule(any(AlertRuleDTO.class));
    }

    @Test
    void testValidateAlertRule_Invalid() throws Exception {
        // Given
        when(alertRuleService.validateAlertRule(any(AlertRuleDTO.class))).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/v1/alert-rules/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testAlertRuleDTO)))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));

        verify(alertRuleService).validateAlertRule(any(AlertRuleDTO.class));
    }

    @Test
    void testEndToEndAlertFlow() throws Exception {
        // Given - Create alert rule
        when(alertRuleService.createAlertRule(any(AlertRuleDTO.class))).thenReturn(testAlertRule);
        when(alertRuleService.getAlertRuleById(1L)).thenReturn(testAlertRule);

        // Step 1: Create alert rule
        mockMvc.perform(post("/api/v1/alert-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testAlertRuleDTO)))
                .andExpect(status().isOk());

        // Step 2: Simulate metric evaluation that triggers alert
        String metricName = "cpu_usage";
        BigDecimal metricValue = new BigDecimal("85.0");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        // This would normally be triggered by the data ingestion service
        doNothing().when(alertEvaluationService).evaluateMetric(metricName, metricValue, clientId, metadata);

        // Step 3: Verify alert rule was created and can be retrieved
        mockMvc.perform(get("/api/v1/alert-rules/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CPU Usage Alert"));

        // Verify services were called
        verify(alertRuleService).createAlertRule(any(AlertRuleDTO.class));
        verify(alertRuleService).getAlertRuleById(1L);
    }

    @Test
    void testAlertRuleLifecycle() throws Exception {
        // Given
        Long ruleId = 1L;
        when(alertRuleService.createAlertRule(any(AlertRuleDTO.class))).thenReturn(testAlertRule);
        when(alertRuleService.getAlertRuleById(ruleId)).thenReturn(testAlertRule);
        when(alertRuleService.updateAlertRule(eq(ruleId), any(AlertRuleDTO.class))).thenReturn(testAlertRule);
        doNothing().when(alertRuleService).toggleAlertRule(ruleId, false);
        doNothing().when(alertRuleService).deleteAlertRule(ruleId);

        // Step 1: Create
        mockMvc.perform(post("/api/v1/alert-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testAlertRuleDTO)))
                .andExpect(status().isOk());

        // Step 2: Read
        mockMvc.perform(get("/api/v1/alert-rules/{id}", ruleId))
                .andExpect(status().isOk());

        // Step 3: Update
        testAlertRuleDTO.setDescription("Updated description");
        mockMvc.perform(put("/api/v1/alert-rules/{id}", ruleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testAlertRuleDTO)))
                .andExpect(status().isOk());

        // Step 4: Disable
        mockMvc.perform(patch("/api/v1/alert-rules/{id}/toggle", ruleId)
                .param("enabled", "false"))
                .andExpect(status().isOk());

        // Step 5: Delete
        mockMvc.perform(delete("/api/v1/alert-rules/{id}", ruleId))
                .andExpect(status().isOk());

        // Verify all operations
        verify(alertRuleService).createAlertRule(any(AlertRuleDTO.class));
        verify(alertRuleService).getAlertRuleById(ruleId);
        verify(alertRuleService).updateAlertRule(eq(ruleId), any(AlertRuleDTO.class));
        verify(alertRuleService).toggleAlertRule(ruleId, false);
        verify(alertRuleService).deleteAlertRule(ruleId);
    }
}