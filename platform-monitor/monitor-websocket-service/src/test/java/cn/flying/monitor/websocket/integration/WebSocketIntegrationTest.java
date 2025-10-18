package cn.flying.monitor.websocket.integration;

import cn.flying.monitor.common.security.JwtTokenProvider;
import cn.flying.monitor.websocket.service.WebSocketConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import jakarta.annotation.Resource;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocket集成测试
 * 测试实时数据流和SSH代理功能
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.redis.host=localhost",
    "spring.redis.port=6379",
    "spring.redis.database=15"
})
public class WebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Resource
    private JwtTokenProvider jwtTokenProvider;

    @Resource
    private WebSocketConnectionManager connectionManager;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String validToken;

    @BeforeEach
    void setUp() {
        // 生成测试用JWT令牌
        validToken = jwtTokenProvider.generateAccessToken("test-user-id", "testuser", List.of("USER"), true);

        // 清理Redis测试数据
        redisTemplate.getConnectionFactory().getConnection().flushDb();
    }

    @Test
    void testMetricsWebSocketConnection() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        
        TestWebSocketHandler handler = new TestWebSocketHandler(connectionLatch, messageLatch);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/metrics?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        assertTrue(session.isOpen());
        
        // 验证连接管理器中有活跃连接
        assertEquals(1, connectionManager.getActiveConnectionCount());
        
        session.close();
    }

    @Test
    void testMetricsSubscription() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(2); // 连接确认 + 订阅确认
        
        TestWebSocketHandler handler = new TestWebSocketHandler(connectionLatch, messageLatch);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/metrics?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        
        // 发送订阅请求
        Map<String, Object> subscriptionMessage = Map.of(
            "type", "subscribe",
            "subscription", Map.of(
                "clientId", "test-client-1",
                "metrics", List.of("cpu_usage", "memory_usage"),
                "interval", 5
            )
        );
        
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(subscriptionMessage)));
        
        // 等待订阅确认
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
        
        // 验证订阅信息
        List<WebSocketConnectionManager.Subscription> subscriptions = 
            connectionManager.getSubscriptions(session.getId());
        assertEquals(1, subscriptions.size());
        assertEquals("test-client-1", subscriptions.get(0).getClientId());
        
        session.close();
    }

    @Test
    void testRealTimeDataStreaming() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(1);
        
        TestWebSocketHandler handler = new TestWebSocketHandler(connectionLatch, dataLatch);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/metrics?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        
        // 订阅测试客户端数据
        Map<String, Object> subscriptionMessage = Map.of(
            "type", "subscribe",
            "subscription", Map.of(
                "clientId", "test-client-1",
                "metrics", List.of("cpu_usage"),
                "interval", 1
            )
        );
        
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(subscriptionMessage)));
        
        // 模拟实时数据推送到Redis
        Map<String, Object> metricsData = Map.of(
            "cpu_usage", 45.2,
            "timestamp", System.currentTimeMillis()
        );
        redisTemplate.opsForValue().set("metrics:latest:test-client-1", metricsData);
        
        // 等待数据推送
        assertTrue(dataLatch.await(10, TimeUnit.SECONDS));
        
        session.close();
    }

    @Test
    void testWebSocketAuthentication() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        
        TestWebSocketHandler handler = new TestWebSocketHandler(connectionLatch, null);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        // 测试无效令牌
        String invalidUrl = "ws://localhost:" + port + "/ws/metrics?token=invalid";
        
        try {
            client.doHandshake(handler, null, URI.create(invalidUrl)).get(5, TimeUnit.SECONDS);
            fail("应该因为无效令牌而连接失败");
        } catch (Exception e) {
            // 预期的异常
            assertTrue(e.getMessage().contains("401") || e.getCause() != null);
        }
        
        // 测试缺少令牌
        String noTokenUrl = "ws://localhost:" + port + "/ws/metrics";
        
        try {
            client.doHandshake(handler, null, URI.create(noTokenUrl)).get(5, TimeUnit.SECONDS);
            fail("应该因为缺少令牌而连接失败");
        } catch (Exception e) {
            // 预期的异常
            assertTrue(e.getMessage().contains("401") || e.getCause() != null);
        }
    }

    @Test
    void testConnectionManagement() throws Exception {
        CountDownLatch connectionLatch1 = new CountDownLatch(1);
        CountDownLatch connectionLatch2 = new CountDownLatch(1);
        
        TestWebSocketHandler handler1 = new TestWebSocketHandler(connectionLatch1, null);
        TestWebSocketHandler handler2 = new TestWebSocketHandler(connectionLatch2, null);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/metrics?token=" + validToken;
        
        // 建立第一个连接
        WebSocketSession session1 = client.doHandshake(handler1, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        assertTrue(connectionLatch1.await(5, TimeUnit.SECONDS));
        assertEquals(1, connectionManager.getActiveConnectionCount());
        
        // 建立第二个连接
        WebSocketSession session2 = client.doHandshake(handler2, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        assertTrue(connectionLatch2.await(5, TimeUnit.SECONDS));
        assertEquals(2, connectionManager.getActiveConnectionCount());
        
        // 关闭第一个连接
        session1.close();
        Thread.sleep(1000); // 等待清理
        assertEquals(1, connectionManager.getActiveConnectionCount());
        
        // 关闭第二个连接
        session2.close();
        Thread.sleep(1000); // 等待清理
        assertEquals(0, connectionManager.getActiveConnectionCount());
    }

    @Test
    void testPingPongHeartbeat() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch pongLatch = new CountDownLatch(1);
        
        TestWebSocketHandler handler = new TestWebSocketHandler(connectionLatch, pongLatch);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/metrics?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        
        // 发送ping消息
        Map<String, Object> pingMessage = Map.of("type", "ping");
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pingMessage)));
        
        // 等待pong响应
        assertTrue(pongLatch.await(5, TimeUnit.SECONDS));
        
        session.close();
    }

    /**
     * 测试用WebSocket处理器
     */
    private static class TestWebSocketHandler implements WebSocketHandler {
        private final CountDownLatch connectionLatch;
        private final CountDownLatch messageLatch;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public TestWebSocketHandler(CountDownLatch connectionLatch, CountDownLatch messageLatch) {
            this.connectionLatch = connectionLatch;
            this.messageLatch = messageLatch;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            if (connectionLatch != null) {
                connectionLatch.countDown();
            }
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
            if (message instanceof TextMessage) {
                String payload = ((TextMessage) message).getPayload();
                @SuppressWarnings("unchecked")
                Map<String, Object> messageData = objectMapper.readValue(payload, Map.class);
                
                String type = (String) messageData.get("type");
                if ("pong".equals(type) || "subscription_confirmed".equals(type) || 
                    "metrics_update".equals(type) || "connection_established".equals(type)) {
                    if (messageLatch != null) {
                        messageLatch.countDown();
                    }
                }
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            // 处理传输错误
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
            // 处理连接关闭
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }
}