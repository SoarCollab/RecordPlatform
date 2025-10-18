package cn.flying.identity.gateway.pool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API网关连接池管理器测试
 */
@ExtendWith(MockitoExtension.class)
class ApiGatewayConnectionPoolManagerTest {

    private ApiGatewayConnectionPoolManager poolManager;

    @BeforeEach
    void setUp() {
        poolManager = new ApiGatewayConnectionPoolManager();
        
        // 设置测试配置
        ReflectionTestUtils.setField(poolManager, "maxTotal", 100);
        ReflectionTestUtils.setField(poolManager, "maxPerRoute", 20);
        ReflectionTestUtils.setField(poolManager, "connectTimeout", 5000);
        ReflectionTestUtils.setField(poolManager, "socketTimeout", 30000);
        ReflectionTestUtils.setField(poolManager, "requestTimeout", 30000);
        ReflectionTestUtils.setField(poolManager, "idleTimeout", 60);
        ReflectionTestUtils.setField(poolManager, "validateInterval", 30);
        ReflectionTestUtils.setField(poolManager, "leakDetectionThreshold", 5);
        ReflectionTestUtils.setField(poolManager, "dynamicSizingEnabled", true);
    }

    @Test
    void testInitialization() {
        // 测试初始化
        poolManager.init();
        
        assertTrue(poolManager.isHealthy());
        assertEquals(0, poolManager.getRestartCount());
        assertTrue(poolManager.getUptimeMinutes() >= 0);
    }

    @Test
    void testGetPoolStatistics() {
        poolManager.init();
        
        Map<String, Object> stats = poolManager.getPoolStatistics();
        
        assertNotNull(stats);
        assertTrue(stats.containsKey("maxTotal"));
        assertTrue(stats.containsKey("isHealthy"));
        assertTrue(stats.containsKey("uptime"));
        assertTrue(stats.containsKey("restartCount"));
    }

    @Test
    void testHealthCheck() {
        poolManager.init();
        
        boolean healthy = poolManager.triggerHealthCheck();
        assertTrue(healthy);
        assertTrue(poolManager.isHealthy());
    }

    @Test
    void testRecordRequestResult() {
        poolManager.init();
        
        // 记录成功请求
        poolManager.recordRequestResult("test-service", true, 100L);
        
        Map<String, Object> stats = poolManager.getPoolStatistics();
        assertNotNull(stats.get("serviceStatistics"));
    }

    @Test
    void testCircuitBreakerStatus() {
        poolManager.init();
        
        // 先获取客户端以创建熔断器
        try {
            poolManager.getServiceHttpClient("failing-service");
        } catch (Exception e) {
            // 忽略异常，我们只是想创建熔断器
        }
        
        // 记录失败请求触发熔断器
        for (int i = 0; i < 6; i++) {
            poolManager.recordRequestResult("failing-service", false, 0L);
        }
        
        Map<String, Object> status = poolManager.getCircuitBreakerStatus("failing-service");
        assertNotNull(status);
        assertTrue(status.containsKey("state"));
    }

    @Test
    void testDynamicPoolResize() {
        poolManager.init();
        
        // 测试动态调整
        poolManager.resizePool(200, 40);
        
        Map<String, Object> stats = poolManager.getPoolStatistics();
        assertEquals(200, stats.get("maxTotal"));
    }

    @Test
    void testForceRestart() {
        poolManager.init();
        assertTrue(poolManager.isHealthy());
        
        poolManager.forceRestart();
        
        // 重启后应该仍然健康
        assertTrue(poolManager.isHealthy());
        assertTrue(poolManager.getRestartCount() > 0);
    }

    @Test
    void testGetAllCircuitBreakerStatus() {
        poolManager.init();
        
        // 触发一些服务的熔断器
        poolManager.recordRequestResult("service1", false, 0L);
        poolManager.recordRequestResult("service2", true, 100L);
        
        Map<String, Map<String, Object>> allStatus = poolManager.getAllCircuitBreakerStatus();
        assertNotNull(allStatus);
    }

    @Test
    void testResetCircuitBreaker() {
        poolManager.init();
        
        // 先获取客户端以创建熔断器
        try {
            poolManager.getServiceHttpClient("test-service");
        } catch (Exception e) {
            // 忽略异常，我们只是想创建熔断器
        }
        
        // 触发熔断器
        for (int i = 0; i < 6; i++) {
            poolManager.recordRequestResult("test-service", false, 0L);
        }
        
        // 重置熔断器
        poolManager.resetCircuitBreaker("test-service");
        
        Map<String, Object> status = poolManager.getCircuitBreakerStatus("test-service");
        assertNotNull(status);
        assertEquals("CLOSED", status.get("state"));
    }
}