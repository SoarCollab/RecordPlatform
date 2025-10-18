package cn.flying.monitor.websocket.unit;

import cn.flying.monitor.websocket.service.WebSocketConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * WebSocket连接管理器单元测试
 */
public class WebSocketConnectionManagerTest {

    private WebSocketConnectionManager connectionManager;

    @Mock
    private WebSocketSession mockSession1;

    @Mock
    private WebSocketSession mockSession2;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        connectionManager = new WebSocketConnectionManager();
        
        when(mockSession1.getId()).thenReturn("session1");
        when(mockSession2.getId()).thenReturn("session2");
        when(mockSession1.isOpen()).thenReturn(true);
        when(mockSession2.isOpen()).thenReturn(true);
    }

    @Test
    void testAddConnection() {
        // 添加连接
        connectionManager.addConnection(mockSession1);
        
        // 验证连接数
        assertEquals(1, connectionManager.getActiveConnectionCount());
        assertEquals(mockSession1, connectionManager.getSession("session1"));
    }

    @Test
    void testRemoveConnection() {
        // 添加连接
        connectionManager.addConnection(mockSession1);
        assertEquals(1, connectionManager.getActiveConnectionCount());
        
        // 移除连接
        connectionManager.removeConnection("session1");
        assertEquals(0, connectionManager.getActiveConnectionCount());
        assertNull(connectionManager.getSession("session1"));
    }

    @Test
    void testMultipleConnections() {
        // 添加多个连接
        connectionManager.addConnection(mockSession1);
        connectionManager.addConnection(mockSession2);
        
        // 验证连接数
        assertEquals(2, connectionManager.getActiveConnectionCount());
        
        // 验证会话ID集合
        Set<String> sessionIds = connectionManager.getActiveSessionIds();
        assertTrue(sessionIds.contains("session1"));
        assertTrue(sessionIds.contains("session2"));
    }

    @Test
    void testAddSubscription() {
        // 添加连接
        connectionManager.addConnection(mockSession1);
        
        // 添加订阅
        List<String> metrics = List.of("cpu_usage", "memory_usage");
        connectionManager.addSubscription("session1", "client1", metrics, 5);
        
        // 验证订阅
        List<WebSocketConnectionManager.Subscription> subscriptions = 
            connectionManager.getSubscriptions("session1");
        assertEquals(1, subscriptions.size());
        
        WebSocketConnectionManager.Subscription subscription = subscriptions.get(0);
        assertEquals("client1", subscription.getClientId());
        assertEquals(metrics, subscription.getMetrics());
        assertEquals(5, subscription.getInterval());
    }

    @Test
    void testRemoveSubscription() {
        // 添加连接和订阅
        connectionManager.addConnection(mockSession1);
        connectionManager.addSubscription("session1", "client1", List.of("cpu_usage"), 5);
        
        // 验证订阅存在
        assertEquals(1, connectionManager.getSubscriptions("session1").size());
        
        // 移除订阅
        connectionManager.removeSubscription("session1", "client1");
        
        // 验证订阅已移除
        assertEquals(0, connectionManager.getSubscriptions("session1").size());
    }

    @Test
    void testGetSubscribers() {
        // 添加连接
        connectionManager.addConnection(mockSession1);
        connectionManager.addConnection(mockSession2);
        
        // 添加订阅
        connectionManager.addSubscription("session1", "client1", List.of("cpu_usage"), 5);
        connectionManager.addSubscription("session2", "client1", List.of("memory_usage"), 5);
        connectionManager.addSubscription("session2", null, List.of("all"), 5); // 全局订阅
        
        // 获取client1的订阅者
        Set<String> subscribers = connectionManager.getSubscribers("client1");
        assertEquals(3, subscribers.size()); // session1, session2, session2(全局)
        assertTrue(subscribers.contains("session1"));
        assertTrue(subscribers.contains("session2"));
    }

    @Test
    void testSubscriptionUpdate() {
        // 添加连接和订阅
        connectionManager.addConnection(mockSession1);
        connectionManager.addSubscription("session1", "client1", List.of("cpu_usage"), 5);
        
        List<WebSocketConnectionManager.Subscription> subscriptions = 
            connectionManager.getSubscriptions("session1");
        WebSocketConnectionManager.Subscription subscription = subscriptions.get(0);
        
        // 测试shouldUpdate方法
        assertTrue(subscription.shouldUpdate()); // 初始状态应该需要更新
        
        // 更新时间戳
        subscription.updateLastUpdate();
        assertFalse(subscription.shouldUpdate()); // 刚更新过，不应该需要更新
    }

    @Test
    void testReplaceSubscription() {
        // 添加连接
        connectionManager.addConnection(mockSession1);
        
        // 添加初始订阅
        connectionManager.addSubscription("session1", "client1", List.of("cpu_usage"), 5);
        assertEquals(1, connectionManager.getSubscriptions("session1").size());
        
        // 添加相同客户端的新订阅（应该替换旧的）
        connectionManager.addSubscription("session1", "client1", List.of("memory_usage"), 10);
        
        List<WebSocketConnectionManager.Subscription> subscriptions = 
            connectionManager.getSubscriptions("session1");
        assertEquals(1, subscriptions.size()); // 应该只有一个订阅
        
        WebSocketConnectionManager.Subscription subscription = subscriptions.get(0);
        assertEquals("client1", subscription.getClientId());
        assertEquals(List.of("memory_usage"), subscription.getMetrics());
        assertEquals(10, subscription.getInterval());
    }

    @Test
    void testConnectionCleanup() {
        // 添加连接和订阅
        connectionManager.addConnection(mockSession1);
        connectionManager.addSubscription("session1", "client1", List.of("cpu_usage"), 5);
        
        // 验证订阅者映射
        Set<String> subscribers = connectionManager.getSubscribers("client1");
        assertTrue(subscribers.contains("session1"));
        
        // 移除连接
        connectionManager.removeConnection("session1");
        
        // 验证订阅者映射已清理
        subscribers = connectionManager.getSubscribers("client1");
        assertFalse(subscribers.contains("session1"));
    }
}