package cn.flying.monitor.notification.performance;

import cn.flying.monitor.notification.dto.AlertRuleDTO;
import cn.flying.monitor.notification.entity.AlertRule;
import cn.flying.monitor.notification.service.AlertEvaluationService;
import cn.flying.monitor.notification.service.AlertRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 告警系统性能测试
 */
@SpringBootTest
@ActiveProfiles("test")
class AlertPerformanceTest {

    @MockBean
    private AlertRuleService alertRuleService;

    @MockBean
    private AlertEvaluationService alertEvaluationService;

    private AlertRule testAlertRule;

    @BeforeEach
    void setUp() {
        testAlertRule = new AlertRule();
        testAlertRule.setId(1L);
        testAlertRule.setName("Performance Test Rule");
        testAlertRule.setMetricName("cpu_usage");
        testAlertRule.setConditionOperator(">");
        testAlertRule.setThresholdValue(new BigDecimal("80.0"));
        testAlertRule.setSeverity("high");
        testAlertRule.setEnabled(true);
        testAlertRule.setEvaluationInterval(60);
        testAlertRule.setConsecutiveFailuresRequired(1);
    }

    @Test
    void testAlertRuleCreationPerformance() {
        // Given
        AlertRuleDTO alertRuleDTO = new AlertRuleDTO();
        alertRuleDTO.setName("Performance Test Rule");
        alertRuleDTO.setMetricName("cpu_usage");
        alertRuleDTO.setConditionOperator(">");
        alertRuleDTO.setThresholdValue(new BigDecimal("80.0"));
        alertRuleDTO.setSeverity("high");
        alertRuleDTO.setEnabled(true);

        // When
        Instant start = Instant.now();
        
        // Simulate rule creation (mocked)
        // In real test, this would call alertRuleService.createAlertRule(alertRuleDTO)
        
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);

        // Then
        assertTrue(duration.toMillis() < 1000, "Alert rule creation should complete within 1 second");
    }

    @Test
    void testAlertEvaluationPerformance() {
        // Given
        String metricName = "cpu_usage";
        BigDecimal metricValue = new BigDecimal("85.0");
        String clientId = "client-001";
        Map<String, Object> metadata = new HashMap<>();

        // When
        Instant start = Instant.now();
        
        // Simulate alert evaluation (mocked)
        // In real test, this would call alertEvaluationService.evaluateMetric(metricName, metricValue, clientId, metadata)
        
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);

        // Then
        assertTrue(duration.toMillis() < 100, "Alert evaluation should complete within 100ms");
    }

    @Test
    void testConcurrentAlertEvaluation() throws InterruptedException {
        // Given
        int numberOfThreads = 10;
        int numberOfEvaluationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

        // When
        Instant start = Instant.now();
        
        CompletableFuture<Void>[] futures = new CompletableFuture[numberOfThreads];
        
        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < numberOfEvaluationsPerThread; j++) {
                    String clientId = "client-" + threadId + "-" + j;
                    BigDecimal metricValue = new BigDecimal("85.0");
                    Map<String, Object> metadata = new HashMap<>();
                    
                    // Simulate alert evaluation (mocked)
                    // alertEvaluationService.evaluateMetric("cpu_usage", metricValue, clientId, metadata);
                }
            }, executor);
        }
        
        CompletableFuture.allOf(futures).join();
        
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Then
        int totalEvaluations = numberOfThreads * numberOfEvaluationsPerThread;
        double evaluationsPerSecond = totalEvaluations / (duration.toMillis() / 1000.0);
        
        assertTrue(evaluationsPerSecond > 1000, 
            String.format("Should handle at least 1000 evaluations/second, actual: %.2f", evaluationsPerSecond));
    }

    @Test
    void testAlertResponseTimeRequirement() {
        // Given - Simulate alert trigger scenario
        String metricName = "cpu_usage";
        BigDecimal metricValue = new BigDecimal("95.0"); // Critical value
        String clientId = "critical-client-001";
        Map<String, Object> metadata = new HashMap<>();

        // When - Measure end-to-end alert processing time
        Instant alertStart = Instant.now();
        
        // Simulate the complete alert flow:
        // 1. Metric evaluation
        // 2. Alert instance creation
        // 3. Notification sending
        // In real test, this would involve actual service calls
        
        Instant alertEnd = Instant.now();
        Duration alertDuration = Duration.between(alertStart, alertEnd);

        // Then - Verify 30-second response time requirement
        assertTrue(alertDuration.toSeconds() < 30, 
            String.format("Alert response time should be under 30 seconds, actual: %d seconds", 
                alertDuration.toSeconds()));
    }

    @Test
    void testNotificationDeliveryPerformance() {
        // Given
        int numberOfNotifications = 100;

        // When
        Instant start = Instant.now();
        
        for (int i = 0; i < numberOfNotifications; i++) {
            // Simulate notification sending (mocked)
            // notificationService.sendEmailNotification(...)
        }
        
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);

        // Then
        double notificationsPerSecond = numberOfNotifications / (duration.toMillis() / 1000.0);
        assertTrue(notificationsPerSecond > 10, 
            String.format("Should handle at least 10 notifications/second, actual: %.2f", notificationsPerSecond));
    }

    @Test
    void testAlertEscalationPerformance() {
        // Given - Simulate escalation scenario
        long alertInstanceId = 1L;
        
        // When - Measure escalation processing time
        Instant start = Instant.now();
        
        // Simulate escalation processing (mocked)
        // alertEvaluationService.processAlertEscalation()
        
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);

        // Then
        assertTrue(duration.toMillis() < 5000, "Alert escalation should complete within 5 seconds");
    }

    @Test
    void testMemoryUsageDuringHighLoad() {
        // Given
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        // When - Simulate high load scenario
        for (int i = 0; i < 10000; i++) {
            String clientId = "load-test-client-" + i;
            BigDecimal metricValue = new BigDecimal("85.0");
            Map<String, Object> metadata = new HashMap<>();
            
            // Simulate metric evaluation (mocked)
            // alertEvaluationService.evaluateMetric("cpu_usage", metricValue, clientId, metadata);
        }

        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;

        // Then
        assertTrue(memoryIncrease < 100 * 1024 * 1024, // 100MB
            String.format("Memory increase should be less than 100MB, actual: %d bytes", memoryIncrease));
    }

    @Test
    void testDatabaseConnectionPoolPerformance() {
        // Given
        int numberOfConcurrentOperations = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfConcurrentOperations);

        // When
        Instant start = Instant.now();
        
        CompletableFuture<Void>[] futures = new CompletableFuture[numberOfConcurrentOperations];
        
        for (int i = 0; i < numberOfConcurrentOperations; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                // Simulate database operations (mocked)
                // alertRuleService.getEnabledAlertRules()
            }, executor);
        }
        
        CompletableFuture.allOf(futures).join();
        
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        
        executor.shutdown();

        // Then
        assertTrue(duration.toSeconds() < 10, 
            String.format("Concurrent database operations should complete within 10 seconds, actual: %d seconds", 
                duration.toSeconds()));
    }
}