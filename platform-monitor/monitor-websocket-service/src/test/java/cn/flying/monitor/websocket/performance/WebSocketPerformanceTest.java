package cn.flying.monitor.websocket.performance;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocket性能测试
 * 测试并发连接、数据吞吐量和延迟
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.redis.host=localhost",
    "spring.redis.port=6379",
    "spring.redis.database=15"
})
public class WebSocketPerformanceTest {

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
        validToken = jwtTokenProvider.generateAccessToken("test-user-id", "testuser", List.of("USER"), true);
        redisTemplate.getConnectionFactory().getConnection().flushDb();
    }

    @Test
    void testConcurrentConnections() throws Exception {
        int connectionCount = 50;
        CountDownLatch connectionLatch = new CountDownLatch(connectionCount);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<WebSocketSession> sessions = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < connectionCount; i++) {
            executor.submit(() -> {
                try {
                    TestWebSocketHandler handler = new TestWebSocketHandler(connectionLatch, null);
                    StandardWebSocketClient client = new StandardWebSocketClient();
                    
                    String url = "ws://localhost:" + port + "/ws/metrics?token=" + validToken;
                    WebSocketSession session = client.doHandshake(handler, null, URI.create(url))
                        .get(10, TimeUnit.SECONDS);
                    
                    synchronized (sessions) {
                        sessions.add(session);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("连接失败: " + e.getMessage());
                }
            });
        }

        // 等待所有连接建立
        assertTrue(connectionLatch.await(30, TimeUnit.SECONDS));
        long connectionTime = System.currentTimeMillis() - startTime;

        // 验证连接数
        assertEquals(connectionCount, successCount.get());
        assertEquals(connectionCount, connectionManager.getActiveConnectionCount());

        System.out.println("并发连接测试结果:");
        System.out.println("连接数: " + connectionCount);
        System.out.println("成功连接: " + successCount.get());
        System.out.println("连接时间: " + connectionTime + "ms");
        System.out.println("平均连接时间: " + (connectionTime / connectionCount) + "ms");

        // 清理连接
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                session.close();
            }
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void testDataThroughput() throws Exception {
        int messageCount = 1000;
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(messageCount);
        
        TestWebSocketHandler handler = new TestWebSocketHandler(connectionLatch, messageLatch);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/metrics?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url))
            .get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        
        // 订阅测试数据
        Map<String, Object> subscriptionMessage = Map.of(
            "type", "subscribe",
            "subscription", Map.of(
                "clientId", "perf-test-client",
                "metrics", List.of("cpu_usage"),
                "interval", 1
            )
        );
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(subscriptionMessage)));

        long startTime = System.currentTimeMillis();

        // 模拟高频数据推送
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            for (int i = 0; i < messageCount; i++) {
                try {
                    Map<String, Object> metricsData = Map.of(
                        "cpu_usage", Math.random() * 100,
                        "timestamp", System.currentTimeMillis()
                    );
                    redisTemplate.opsForValue().set("metrics:latest:perf-test-client", metricsData);
                    
                    // 发布到实时通道
                    redisTemplate.convertAndSend("monitor:metrics:realtime", 
                        objectMapper.writeValueAsString(Map.of(
                            "clientId", "perf-test-client",
                            "data", metricsData
                        )));
                    
                    Thread.sleep(1); // 1ms间隔
                } catch (Exception e) {
                    System.err.println("发送数据失败: " + e.getMessage());
                }
            }
        });

        // 等待所有消息接收
        assertTrue(messageLatch.await(60, TimeUnit.SECONDS));
        long totalTime = System.currentTimeMillis() - startTime;

        System.out.println("数据吞吐量测试结果:");
        System.out.println("消息数量: " + messageCount);
        System.out.println("总时间: " + totalTime + "ms");
        System.out.println("吞吐量: " + (messageCount * 1000.0 / totalTime) + " 消息/秒");

        session.close();
        executor.shutdown();
    }

    @Test
    void testLatency() throws Exception {
        int testCount = 100;
        CountDownLatch connectionLatch = new CountDownLatch(1);
        
        LatencyTestHandler handler = new LatencyTestHandler(connectionLatch);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/metrics?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url))
            .get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        
        List<Long> latencies = new ArrayList<>();

        for (int i = 0; i < testCount; i++) {
            long startTime = System.nanoTime();
            
            // 发送ping消息
            Map<String, Object> pingMessage = Map.of(
                "type", "ping",
                "timestamp", startTime
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pingMessage)));
            
            // 等待pong响应
            long responseTime = handler.waitForPong(5000);
            if (responseTime > 0) {
                long latency = (responseTime - startTime) / 1_000_000; // 转换为毫秒
                latencies.add(latency);
            }
            
            Thread.sleep(10); // 10ms间隔
        }

        // 计算延迟统计
        double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        long minLatency = latencies.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxLatency = latencies.stream().mapToLong(Long::longValue).max().orElse(0);

        System.out.println("延迟测试结果:");
        System.out.println("测试次数: " + testCount);
        System.out.println("成功次数: " + latencies.size());
        System.out.println("平均延迟: " + String.format("%.2f", avgLatency) + "ms");
        System.out.println("最小延迟: " + minLatency + "ms");
        System.out.println("最大延迟: " + maxLatency + "ms");

        // 验证延迟要求（<5秒，实际应该远小于这个值）
        assertTrue(avgLatency < 5000, "平均延迟应该小于5秒");
        assertTrue(maxLatency < 5000, "最大延迟应该小于5秒");

        session.close();
    }

    @Test
    void testConnectionStability() throws Exception {
        int connectionCount = 20;
        int testDuration = 30; // 30秒
        
        CountDownLatch connectionLatch = new CountDownLatch(connectionCount);
        List<WebSocketSession> sessions = new ArrayList<>();
        AtomicInteger messageCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // 建立多个连接
        for (int i = 0; i < connectionCount; i++) {
            TestWebSocketHandler handler = new TestWebSocketHandler(connectionLatch, null) {
                @Override
                public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
                    super.handleMessage(session, message);
                    messageCount.incrementAndGet();
                }

                @Override
                public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                    super.handleTransportError(session, exception);
                    errorCount.incrementAndGet();
                }
            };
            
            StandardWebSocketClient client = new StandardWebSocketClient();
            String url = "ws://localhost:" + port + "/ws/metrics?token=" + validToken;
            WebSocketSession session = client.doHandshake(handler, null, URI.create(url))
                .get(5, TimeUnit.SECONDS);
            sessions.add(session);
        }

        // 等待所有连接建立
        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS));

        long startTime = System.currentTimeMillis();
        
        // 持续发送数据
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            while (System.currentTimeMillis() - startTime < testDuration * 1000) {
                try {
                    Map<String, Object> metricsData = Map.of(
                        "cpu_usage", Math.random() * 100,
                        "timestamp", System.currentTimeMillis()
                    );
                    redisTemplate.convertAndSend("monitor:metrics:realtime", 
                        objectMapper.writeValueAsString(Map.of(
                            "clientId", "stability-test-client",
                            "data", metricsData
                        )));
                    Thread.sleep(100); // 100ms间隔
                } catch (Exception e) {
                    System.err.println("发送数据失败: " + e.getMessage());
                }
            }
        });

        // 等待测试完成
        Thread.sleep(testDuration * 1000);

        System.out.println("连接稳定性测试结果:");
        System.out.println("测试时长: " + testDuration + "秒");
        System.out.println("连接数: " + connectionCount);
        System.out.println("活跃连接: " + connectionManager.getActiveConnectionCount());
        System.out.println("接收消息数: " + messageCount.get());
        System.out.println("错误数: " + errorCount.get());

        // 验证连接稳定性
        assertTrue(errorCount.get() < connectionCount * 0.1, "错误率应该小于10%");

        // 清理连接
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                session.close();
            }
        }

        executor.shutdown();
    }

    /**
     * 测试用WebSocket处理器
     */
    private static class TestWebSocketHandler implements WebSocketHandler {
        protected final CountDownLatch connectionLatch;
        protected final CountDownLatch messageLatch;
        protected final ObjectMapper objectMapper = new ObjectMapper();

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
            if (messageLatch != null) {
                messageLatch.countDown();
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

    /**
     * 延迟测试处理器
     */
    private static class LatencyTestHandler extends TestWebSocketHandler {
        private final AtomicLong lastPongTime = new AtomicLong(0);

        public LatencyTestHandler(CountDownLatch connectionLatch) {
            super(connectionLatch, null);
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
            if (message instanceof TextMessage) {
                String payload = ((TextMessage) message).getPayload();
                @SuppressWarnings("unchecked")
                Map<String, Object> messageData = objectMapper.readValue(payload, Map.class);
                
                if ("pong".equals(messageData.get("type"))) {
                    lastPongTime.set(System.nanoTime());
                }
            }
        }

        public long waitForPong(long timeoutMs) throws InterruptedException {
            long startTime = System.currentTimeMillis();
            long lastTime = lastPongTime.get();
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                long currentTime = lastPongTime.get();
                if (currentTime > lastTime) {
                    return currentTime;
                }
                Thread.sleep(1);
            }
            
            return -1; // 超时
        }
    }
}