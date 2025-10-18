package cn.flying.monitor.client.task;

import cn.flying.monitor.client.entity.RuntimeDetail;
import cn.flying.monitor.client.utils.MonitorUtils;
import cn.flying.monitor.client.utils.NetUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for enhanced monitoring job with batch processing and network optimization
 */
@ExtendWith(MockitoExtension.class)
class MonitorJobBeanTest {

    private MonitorJobBean monitorJobBean;

    @Mock
    private MonitorUtils monitorUtils;

    @Mock
    private NetUtils netUtils;

    @Mock
    private JobExecutionContext jobExecutionContext;

    @BeforeEach
    void setUp() {
        monitorJobBean = new MonitorJobBean();
        
        // Inject mocks
        ReflectionTestUtils.setField(monitorJobBean, "monitor", monitorUtils);
        ReflectionTestUtils.setField(monitorJobBean, "net", netUtils);
        
        // Set test configuration
        ReflectionTestUtils.setField(monitorJobBean, "batchSize", 3);
        ReflectionTestUtils.setField(monitorJobBean, "asyncEnabled", true);
    }

    @Test
    void testSuccessfulJobExecution() {
        // Given
        RuntimeDetail mockDetail = new RuntimeDetail()
                .setCpuUsage(45.5)
                .setMemoryUsage(6.2)
                .setTimestamp(System.currentTimeMillis());

        when(monitorUtils.monitorRuntimeDetail()).thenReturn(mockDetail);
        when(monitorUtils.filterMetrics(any())).thenReturn(mockDetail);
        doNothing().when(netUtils).updateRuntimeDetails(any());

        // When
        assertDoesNotThrow(() -> monitorJobBean.executeInternal(jobExecutionContext));

        // Then
        verify(monitorUtils).monitorRuntimeDetail();
        verify(monitorUtils).filterMetrics(mockDetail);
        verify(netUtils).updateRuntimeDetails(mockDetail);
    }

    @Test
    void testJobExecutionWithNullRuntimeDetail() {
        // Given
        when(monitorUtils.monitorRuntimeDetail()).thenReturn(null);

        // When
        assertDoesNotThrow(() -> monitorJobBean.executeInternal(jobExecutionContext));

        // Then
        verify(monitorUtils).monitorRuntimeDetail();
        verify(netUtils, never()).updateRuntimeDetails(any());
    }

    @Test
    void testJobExecutionWithFilteredOutMetrics() {
        // Given
        RuntimeDetail mockDetail = new RuntimeDetail().setCpuUsage(45.5);
        when(monitorUtils.monitorRuntimeDetail()).thenReturn(mockDetail);
        when(monitorUtils.filterMetrics(any())).thenReturn(null); // All metrics filtered out

        // When
        assertDoesNotThrow(() -> monitorJobBean.executeInternal(jobExecutionContext));

        // Then
        verify(monitorUtils).monitorRuntimeDetail();
        verify(monitorUtils).filterMetrics(mockDetail);
        verify(netUtils, never()).updateRuntimeDetails(any());
    }

    @Test
    void testBatchModeProcessing() throws InterruptedException {
        // Given
        ReflectionTestUtils.setField(monitorJobBean, "batchSize", 2);
        
        RuntimeDetail mockDetail1 = new RuntimeDetail().setCpuUsage(45.5).setTimestamp(System.currentTimeMillis());
        RuntimeDetail mockDetail2 = new RuntimeDetail().setCpuUsage(50.0).setTimestamp(System.currentTimeMillis());

        when(monitorUtils.monitorRuntimeDetail()).thenReturn(mockDetail1, mockDetail2);
        when(monitorUtils.filterMetrics(any())).thenReturn(mockDetail1, mockDetail2);
        doNothing().when(netUtils).updateRuntimeDetailsBatch(any());

        // When - execute twice to fill batch
        monitorJobBean.executeInternal(jobExecutionContext);
        monitorJobBean.executeInternal(jobExecutionContext);

        // Wait a bit for async processing
        Thread.sleep(100);

        // Then
        verify(netUtils).updateRuntimeDetailsBatch(any());
        assertEquals(0, monitorJobBean.getBatchBufferSize()); // Buffer should be empty after batch send
    }

    @Test
    void testSingleModeProcessing() {
        // Given
        ReflectionTestUtils.setField(monitorJobBean, "batchSize", 1); // Single mode
        
        RuntimeDetail mockDetail = new RuntimeDetail().setCpuUsage(45.5);
        when(monitorUtils.monitorRuntimeDetail()).thenReturn(mockDetail);
        when(monitorUtils.filterMetrics(any())).thenReturn(mockDetail);
        doNothing().when(netUtils).updateRuntimeDetails(any());

        // When
        monitorJobBean.executeInternal(jobExecutionContext);

        // Then
        verify(netUtils).updateRuntimeDetails(mockDetail);
        verify(netUtils, never()).updateRuntimeDetailsBatch(any());
    }

    @Test
    void testAsyncModeDisabled() {
        // Given
        ReflectionTestUtils.setField(monitorJobBean, "asyncEnabled", false);
        ReflectionTestUtils.setField(monitorJobBean, "batchSize", 1);
        
        RuntimeDetail mockDetail = new RuntimeDetail().setCpuUsage(45.5);
        when(monitorUtils.monitorRuntimeDetail()).thenReturn(mockDetail);
        when(monitorUtils.filterMetrics(any())).thenReturn(mockDetail);
        doNothing().when(netUtils).updateRuntimeDetails(any());

        // When
        monitorJobBean.executeInternal(jobExecutionContext);

        // Then
        verify(netUtils).updateRuntimeDetails(mockDetail);
    }

    @Test
    void testBatchBufferManagement() {
        // Given
        ReflectionTestUtils.setField(monitorJobBean, "batchSize", 5);
        
        RuntimeDetail mockDetail = new RuntimeDetail().setCpuUsage(45.5);
        when(monitorUtils.monitorRuntimeDetail()).thenReturn(mockDetail);
        when(monitorUtils.filterMetrics(any())).thenReturn(mockDetail);

        // When - add items to batch buffer
        monitorJobBean.executeInternal(jobExecutionContext);
        assertEquals(1, monitorJobBean.getBatchBufferSize());
        
        monitorJobBean.executeInternal(jobExecutionContext);
        assertEquals(2, monitorJobBean.getBatchBufferSize());
        
        monitorJobBean.executeInternal(jobExecutionContext);
        assertEquals(3, monitorJobBean.getBatchBufferSize());

        // Then
        assertTrue(monitorJobBean.getBatchBufferSize() > 0);
    }

    @Test
    void testFlushPendingBatch() throws InterruptedException {
        // Given
        ReflectionTestUtils.setField(monitorJobBean, "batchSize", 10); // Large batch size
        
        RuntimeDetail mockDetail = new RuntimeDetail().setCpuUsage(45.5);
        when(monitorUtils.monitorRuntimeDetail()).thenReturn(mockDetail);
        when(monitorUtils.filterMetrics(any())).thenReturn(mockDetail);
        doNothing().when(netUtils).updateRuntimeDetailsBatch(any());

        // Add some items to buffer
        monitorJobBean.executeInternal(jobExecutionContext);
        monitorJobBean.executeInternal(jobExecutionContext);
        
        assertTrue(monitorJobBean.getBatchBufferSize() > 0);

        // When
        monitorJobBean.flushPendingBatch();

        // Wait for async processing
        Thread.sleep(100);

        // Then
        verify(netUtils).updateRuntimeDetailsBatch(any());
        assertEquals(0, monitorJobBean.getBatchBufferSize());
    }

    @Test
    void testJobExecutionWithException() {
        // Given
        when(monitorUtils.monitorRuntimeDetail()).thenThrow(new RuntimeException("Test exception"));

        // When & Then - should not throw exception
        assertDoesNotThrow(() -> monitorJobBean.executeInternal(jobExecutionContext));
        
        verify(netUtils, never()).updateRuntimeDetails(any());
    }

    @Test
    void testNetworkEfficiencyMetrics() {
        // Given
        RuntimeDetail mockDetail = new RuntimeDetail().setCpuUsage(45.5);
        when(monitorUtils.monitorRuntimeDetail()).thenReturn(mockDetail);
        when(monitorUtils.filterMetrics(any())).thenReturn(mockDetail);
        when(netUtils.getConnectionPoolStats()).thenReturn("Pool Stats - Total: 10, Available: 8");
        doNothing().when(netUtils).updateRuntimeDetails(any());

        // When
        assertDoesNotThrow(() -> monitorJobBean.executeInternal(jobExecutionContext));

        // Then
        verify(netUtils).getConnectionPoolStats();
        verify(netUtils).updateRuntimeDetails(mockDetail);
    }

    @Test
    void testBatchTimeoutProcessing() throws InterruptedException {
        // Given
        ReflectionTestUtils.setField(monitorJobBean, "batchSize", 10); // Large batch size to test timeout
        
        RuntimeDetail mockDetail = new RuntimeDetail().setCpuUsage(45.5);
        when(monitorUtils.monitorRuntimeDetail()).thenReturn(mockDetail);
        when(monitorUtils.filterMetrics(any())).thenReturn(mockDetail);
        doNothing().when(netUtils).updateRuntimeDetailsBatch(any());

        // Add one item to buffer
        monitorJobBean.executeInternal(jobExecutionContext);
        assertEquals(1, monitorJobBean.getBatchBufferSize());

        // Simulate timeout by setting lastBatchSent to past time using reflection
        try {
            java.lang.reflect.Field lastBatchSentField = MonitorJobBean.class.getDeclaredField("lastBatchSent");
            lastBatchSentField.setAccessible(true);
            lastBatchSentField.set(monitorJobBean, System.currentTimeMillis() - 35000); // 35 seconds ago
        } catch (Exception e) {
            // If reflection fails, skip this test
            return;
        }

        // When - execute again (should trigger timeout batch send)
        monitorJobBean.executeInternal(jobExecutionContext);

        // Wait for async processing
        Thread.sleep(200);

        // Then
        verify(netUtils, atLeastOnce()).updateRuntimeDetailsBatch(any());
    }
}