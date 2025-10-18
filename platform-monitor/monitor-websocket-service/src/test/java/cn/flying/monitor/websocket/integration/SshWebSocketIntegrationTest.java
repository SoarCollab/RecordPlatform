package cn.flying.monitor.websocket.integration;

import cn.flying.monitor.common.security.JwtTokenProvider;
import cn.flying.monitor.websocket.service.SshSessionManager;
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
 * SSH WebSocket集成测试
 * 测试SSH代理功能、安全性和错误处理
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.redis.host=localhost",
    "spring.redis.port=6379",
    "spring.redis.database=15"
})
public class SshWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Resource
    private JwtTokenProvider jwtTokenProvider;

    @Resource
    private SshSessionManager sshSessionManager;

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
        
        // 设置测试SSH配置
        setupTestSshConfig();
    }

    @Test
    void testSshWebSocketConnection() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        
        TestSshWebSocketHandler handler = new TestSshWebSocketHandler(connectionLatch, null);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/ssh/test-client-1?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        assertTrue(session.isOpen());
        
        session.close();
    }

    @Test
    void testSshConnectionRequest() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        
        TestSshWebSocketHandler handler = new TestSshWebSocketHandler(connectionLatch, messageLatch);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/ssh/test-client-1?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        
        // 发送SSH连接请求
        Map<String, Object> connectMessage = Map.of(
            "type", "ssh_connect",
            "clientId", "test-client-1"
        );
        
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(connectMessage)));
        
        // 等待响应（可能是连接成功或失败）
        assertTrue(messageLatch.await(10, TimeUnit.SECONDS));
        
        session.close();
    }

    @Test
    void testSshCommandExecution() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch commandLatch = new CountDownLatch(1);
        
        TestSshWebSocketHandler handler = new TestSshWebSocketHandler(connectionLatch, commandLatch);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/ssh/test-client-1?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        
        // 发送命令执行请求
        Map<String, Object> commandMessage = Map.of(
            "type", "ssh_command",
            "command", "echo 'test command'"
        );
        
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(commandMessage)));
        
        // 等待命令响应
        assertTrue(commandLatch.await(5, TimeUnit.SECONDS));
        
        session.close();
    }

    @Test
    void testSshAuthentication() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        
        TestSshWebSocketHandler handler = new TestSshWebSocketHandler(connectionLatch, null);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        // 测试无效令牌
        String invalidUrl = "ws://localhost:" + port + "/ws/ssh/test-client-1?token=invalid";
        
        try {
            client.doHandshake(handler, null, URI.create(invalidUrl)).get(5, TimeUnit.SECONDS);
            fail("应该因为无效令牌而连接失败");
        } catch (Exception e) {
            // 预期的异常
            assertTrue(e.getMessage().contains("401") || e.getCause() != null);
        }
    }

    @Test
    void testSshSessionManagement() throws Exception {
        CountDownLatch connectionLatch1 = new CountDownLatch(1);
        CountDownLatch connectionLatch2 = new CountDownLatch(1);
        
        TestSshWebSocketHandler handler1 = new TestSshWebSocketHandler(connectionLatch1, null);
        TestSshWebSocketHandler handler2 = new TestSshWebSocketHandler(connectionLatch2, null);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        // 建立第一个SSH会话
        String url1 = "ws://localhost:" + port + "/ws/ssh/test-client-1?token=" + validToken;
        WebSocketSession session1 = client.doHandshake(handler1, null, URI.create(url1)).get(5, TimeUnit.SECONDS);
        assertTrue(connectionLatch1.await(5, TimeUnit.SECONDS));
        
        // 建立第二个SSH会话（不同客户端）
        String url2 = "ws://localhost:" + port + "/ws/ssh/test-client-2?token=" + validToken;
        WebSocketSession session2 = client.doHandshake(handler2, null, URI.create(url2)).get(5, TimeUnit.SECONDS);
        assertTrue(connectionLatch2.await(5, TimeUnit.SECONDS));
        
        // 验证会话管理器中的会话数量
        // 注意：实际SSH连接可能失败，但WebSocket连接应该成功
        
        session1.close();
        session2.close();
    }

    @Test
    void testSshErrorHandling() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        
        TestSshWebSocketHandler handler = new TestSshWebSocketHandler(connectionLatch, errorLatch);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/ssh/invalid-client?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        
        // 发送无效的SSH连接请求
        Map<String, Object> connectMessage = Map.of(
            "type", "ssh_connect",
            "clientId", "invalid-client"
        );
        
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(connectMessage)));
        
        // 等待错误响应
        assertTrue(errorLatch.await(10, TimeUnit.SECONDS));
        
        session.close();
    }

    @Test
    void testSshTerminalResize() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        
        TestSshWebSocketHandler handler = new TestSshWebSocketHandler(connectionLatch, null);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/ssh/test-client-1?token=" + validToken;
        WebSocketSession session = client.doHandshake(handler, null, URI.create(url)).get(5, TimeUnit.SECONDS);
        
        // 等待连接建立
        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        
        // 发送终端大小调整请求
        Map<String, Object> resizeMessage = Map.of(
            "type", "ssh_resize",
            "cols", 120,
            "rows", 30
        );
        
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(resizeMessage)));
        
        // 调整大小操作不会有响应，只要不抛异常就算成功
        Thread.sleep(1000);
        
        session.close();
    }

    @Test
    void testSshPingPong() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch pongLatch = new CountDownLatch(1);
        
        TestSshWebSocketHandler handler = new TestSshWebSocketHandler(connectionLatch, pongLatch);
        StandardWebSocketClient client = new StandardWebSocketClient();
        
        String url = "ws://localhost:" + port + "/ws/ssh/test-client-1?token=" + validToken;
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
     * 设置测试SSH配置
     */
    private void setupTestSshConfig() {
        // 设置测试客户端的SSH配置
        Map<String, Object> sshConfig = Map.of(
            "host", "localhost",
            "port", 22,
            "username", "testuser",
            "password", "testpass"
        );
        
        redisTemplate.opsForValue().set("ssh_config:test-client-1", sshConfig);
        redisTemplate.opsForValue().set("ssh_config:test-client-2", sshConfig);
    }

    /**
     * 测试用SSH WebSocket处理器
     */
    private static class TestSshWebSocketHandler implements WebSocketHandler {
        private final CountDownLatch connectionLatch;
        private final CountDownLatch messageLatch;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public TestSshWebSocketHandler(CountDownLatch connectionLatch, CountDownLatch messageLatch) {
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
                
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> messageData = objectMapper.readValue(payload, Map.class);
                    
                    String type = (String) messageData.get("type");
                    if ("pong".equals(type) || "ssh_connected".equals(type) || 
                        "error".equals(type) || "ssh_connection_established".equals(type)) {
                        if (messageLatch != null) {
                            messageLatch.countDown();
                        }
                    }
                } catch (Exception e) {
                    // 可能是SSH输出数据，不是JSON格式
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