package cn.flying.monitor.client.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for network failure recovery and retry mechanisms
 */
class NetworkRecoveryServiceTest {

    private NetworkRecoveryService networkRecoveryService;

    @BeforeEach
    void setUp() {
        networkRecoveryService = new NetworkRecoveryService();
        
        // Set test configuration for faster testing
        ReflectionTestUtils.setField(networkRecoveryService, "maxRetryAttempts", 3);
        ReflectionTestUtils.setField(networkRecoveryService, "initialRetryDelay", 100L);
        ReflectionTestUtils.setField(networkRecoveryService, "maxRetryDelay", 1000L);
        ReflectionTestUtils.setField(networkRecoveryService, "retryMultiplier", 2.0);
        
        networkRecoveryService.initialize();
    }

    @Test
    void testSuccessfulOperationWithoutRetry() {
        // Given
        AtomicInteger callCount = new AtomicInteger(0);

        // When
        String result = networkRecoveryService.executeWithRetry(() -> {
            callCount.incrementAndGet();
            return "success";
        }, "test-operation");

        // Then
        assertEquals("success", result);
        assertEquals(1, callCount.get());
        assertTrue(networkRecoveryService.isNetworkHealthy());
    }

    @Test
    void testRetryOnFailure() {
        // Given
        AtomicInteger callCount = new AtomicInteger(0);

        // When
        String result = networkRecoveryService.executeWithRetry(() -> {
            int count = callCount.incrementAndGet();
            if (count < 3) {
                throw new RuntimeException("Connection failed");
            }
            return "success";
        }, "test-operation");

        // Then
        assertEquals("success", result);
        assertEquals(3, callCount.get());
    }

    @Test
    void testFailureAfterMaxRetries() {
        // Given
        AtomicInteger callCount = new AtomicInteger(0);

        // When & Then
        assertThrows(NetworkRecoveryService.NetworkOperationException.class, () -> {
            networkRecoveryService.executeWithRetry(() -> {
                callCount.incrementAndGet();
                throw new RuntimeException("Connection failed");
            }, "test-operation");
        });

        assertEquals(3, callCount.get()); // Should retry max attempts
        assertFalse(networkRecoveryService.isNetworkHealthy());
    }

    @Test
    void testNetworkStatistics() {
        // Given - execute some operations
        networkRecoveryService.executeWithRetry(() -> "success", "test-1");
        
        try {
            networkRecoveryService.executeWithRetry(() -> {
                throw new RuntimeException("fail");
            }, "test-2");
        } catch (Exception ignored) {}

        // When
        NetworkRecoveryService.NetworkStats stats = networkRecoveryService.getNetworkStats();

        // Then
        assertNotNull(stats);
        assertEquals(1, stats.getSuccessfulOperations());
        assertEquals(1, stats.getFailedOperations());
        assertTrue(stats.getTotalRetries() > 0);
        assertEquals(0.5, stats.getFailureRate(), 0.01);
    }

    @Test
    void testVoidOperationRetry() {
        // Given
        AtomicInteger callCount = new AtomicInteger(0);

        // When
        assertDoesNotThrow(() -> {
            networkRecoveryService.executeWithRetry(() -> {
                callCount.incrementAndGet();
                // Void operation that succeeds
            }, "void-test");
        });

        // Then
        assertEquals(1, callCount.get());
    }

    @Test
    void testStatisticsReset() {
        // Given - generate some statistics
        networkRecoveryService.executeWithRetry(() -> "success", "test");
        NetworkRecoveryService.NetworkStats statsBefore = networkRecoveryService.getNetworkStats();
        assertTrue(statsBefore.getSuccessfulOperations() > 0);

        // When
        networkRecoveryService.resetStats();

        // Then
        NetworkRecoveryService.NetworkStats statsAfter = networkRecoveryService.getNetworkStats();
        assertEquals(0, statsAfter.getSuccessfulOperations());
        assertEquals(0, statsAfter.getFailedOperations());
        assertEquals(0, statsAfter.getTotalRetries());
    }

    @Test
    void testNetworkHealthCalculation() {
        // Initially healthy
        assertTrue(networkRecoveryService.isNetworkHealthy());

        // After successful operations, should remain healthy
        networkRecoveryService.executeWithRetry(() -> "success", "test");
        assertTrue(networkRecoveryService.isNetworkHealthy());

        // After failures, should become unhealthy
        try {
            networkRecoveryService.executeWithRetry(() -> {
                throw new RuntimeException("fail");
            }, "test");
        } catch (Exception ignored) {}

        assertFalse(networkRecoveryService.isNetworkHealthy());
    }
}