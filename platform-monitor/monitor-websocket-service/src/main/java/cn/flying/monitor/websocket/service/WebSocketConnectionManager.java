package cn.flying.monitor.websocket.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebSocket连接管理器
 * 管理活跃连接和订阅信息
 */
@Slf4j
@Service
public class WebSocketConnectionManager {

    // 活跃的WebSocket连接
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    
    // 订阅信息：sessionId -> 订阅列表
    private final Map<String, List<Subscription>> subscriptions = new ConcurrentHashMap<>();
    
    // 客户端订阅者映射：clientId -> sessionId列表
    private final Map<String, Set<String>> clientSubscribers = new ConcurrentHashMap<>();

    /**
     * 添加WebSocket连接
     */
    public void addConnection(WebSocketSession session) {
        activeSessions.put(session.getId(), session);
        subscriptions.put(session.getId(), new CopyOnWriteArrayList<>());
        log.info("添加WebSocket连接，会话ID: {}, 当前连接数: {}", 
                session.getId(), activeSessions.size());
    }

    /**
     * 移除WebSocket连接
     */
    public void removeConnection(String sessionId) {
        WebSocketSession session = activeSessions.remove(sessionId);
        if (session != null) {
            // 清理订阅信息
            List<Subscription> sessionSubscriptions = subscriptions.remove(sessionId);
            if (sessionSubscriptions != null) {
                for (Subscription subscription : sessionSubscriptions) {
                    removeFromClientSubscribers(subscription.getClientId(), sessionId);
                }
            }
            
            log.info("移除WebSocket连接，会话ID: {}, 当前连接数: {}", 
                    sessionId, activeSessions.size());
        }
    }

    /**
     * 添加订阅
     */
    public void addSubscription(String sessionId, String clientId, List<String> metrics, int interval) {
        List<Subscription> sessionSubscriptions = subscriptions.get(sessionId);
        if (sessionSubscriptions != null) {
            // 移除相同客户端的旧订阅
            sessionSubscriptions.removeIf(sub -> Objects.equals(sub.getClientId(), clientId));
            
            // 添加新订阅
            Subscription subscription = new Subscription(clientId, metrics, interval);
            sessionSubscriptions.add(subscription);
            
            // 更新客户端订阅者映射
            String key = clientId != null ? clientId : "all";
            clientSubscribers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
            
            log.debug("添加订阅，会话ID: {}, 客户端: {}, 指标数: {}", 
                     sessionId, key, metrics != null ? metrics.size() : 0);
        }
    }

    /**
     * 移除订阅
     */
    public void removeSubscription(String sessionId, String clientId) {
        List<Subscription> sessionSubscriptions = subscriptions.get(sessionId);
        if (sessionSubscriptions != null) {
            sessionSubscriptions.removeIf(sub -> Objects.equals(sub.getClientId(), clientId));
            removeFromClientSubscribers(clientId, sessionId);
            
            log.debug("移除订阅，会话ID: {}, 客户端: {}", sessionId, clientId);
        }
    }

    /**
     * 获取指定客户端的订阅者
     */
    public Set<String> getSubscribers(String clientId) {
        Set<String> subscribers = new HashSet<>();
        
        // 获取特定客户端的订阅者
        Set<String> clientSpecificSubscribers = clientSubscribers.get(clientId);
        if (clientSpecificSubscribers != null) {
            subscribers.addAll(clientSpecificSubscribers);
        }
        
        // 获取全局订阅者
        Set<String> globalSubscribers = clientSubscribers.get("all");
        if (globalSubscribers != null) {
            subscribers.addAll(globalSubscribers);
        }
        
        return subscribers;
    }

    /**
     * 获取WebSocket会话
     */
    public WebSocketSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    /**
     * 获取会话的订阅信息
     */
    public List<Subscription> getSubscriptions(String sessionId) {
        return subscriptions.getOrDefault(sessionId, Collections.emptyList());
    }

    /**
     * 获取活跃连接数
     */
    public int getActiveConnectionCount() {
        return activeSessions.size();
    }

    /**
     * 获取所有活跃会话ID
     */
    public Set<String> getActiveSessionIds() {
        return new HashSet<>(activeSessions.keySet());
    }

    /**
     * 从客户端订阅者映射中移除会话
     */
    private void removeFromClientSubscribers(String clientId, String sessionId) {
        String key = clientId != null ? clientId : "all";
        Set<String> subscribers = clientSubscribers.get(key);
        if (subscribers != null) {
            subscribers.remove(sessionId);
            if (subscribers.isEmpty()) {
                clientSubscribers.remove(key);
            }
        }
    }

    /**
     * 订阅信息类
     */
    public static class Subscription {
        private final String clientId;
        private final List<String> metrics;
        private final int interval;
        private long lastUpdate;

        public Subscription(String clientId, List<String> metrics, int interval) {
            this.clientId = clientId;
            this.metrics = metrics != null ? new ArrayList<>(metrics) : null;
            this.interval = interval;
            this.lastUpdate = System.currentTimeMillis();
        }

        public String getClientId() {
            return clientId;
        }

        public List<String> getMetrics() {
            return metrics;
        }

        public int getInterval() {
            return interval;
        }

        public long getLastUpdate() {
            return lastUpdate;
        }

        public void updateLastUpdate() {
            this.lastUpdate = System.currentTimeMillis();
        }

        public boolean shouldUpdate() {
            return System.currentTimeMillis() - lastUpdate >= interval * 1000L;
        }
    }
}