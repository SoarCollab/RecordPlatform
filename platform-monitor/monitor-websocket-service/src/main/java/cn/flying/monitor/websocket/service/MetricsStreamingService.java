package cn.flying.monitor.websocket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 实时指标数据流服务
 * 负责从Redis获取实时数据并推送给WebSocket客户端
 */
@Slf4j
@Service
public class MetricsStreamingService {

    private final WebSocketConnectionManager connectionManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisMessageListenerContainer messageListenerContainer;
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final String METRICS_CHANNEL = "monitor:metrics:realtime";
    private final String METRICS_LATEST_PREFIX = "metrics:latest:";

    public MetricsStreamingService(WebSocketConnectionManager connectionManager,
                                 RedisTemplate<String, Object> redisTemplate,
                                 ObjectMapper objectMapper,
                                 RedisMessageListenerContainer messageListenerContainer) {
        this.connectionManager = connectionManager;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.messageListenerContainer = messageListenerContainer;
    }

    @PostConstruct
    public void initialize() {
        // 订阅Redis实时数据通道
        MessageListenerAdapter listenerAdapter = new MessageListenerAdapter(this, "handleRealtimeMetrics");
        messageListenerContainer.addMessageListener(listenerAdapter, new ChannelTopic(METRICS_CHANNEL));
        
        // 启动定时推送任务
        scheduler.scheduleAtFixedRate(this::pushScheduledMetrics, 1, 1, TimeUnit.SECONDS);
        
        // 启动连接健康检查
        scheduler.scheduleAtFixedRate(this::checkConnectionHealth, 30, 30, TimeUnit.SECONDS);
        
        log.info("实时指标数据流服务已启动");
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("实时指标数据流服务已关闭");
    }

    /**
     * 处理Redis实时指标数据
     */
    public void handleRealtimeMetrics(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> metricsData = objectMapper.readValue(message, Map.class);
            String clientId = (String) metricsData.get("clientId");
            
            if (clientId != null) {
                pushMetricsToSubscribers(clientId, metricsData);
            }
        } catch (Exception e) {
            log.error("处理实时指标数据失败", e);
        }
    }

    /**
     * 定时推送指标数据
     */
    private void pushScheduledMetrics() {
        try {
            Set<String> activeSessionIds = connectionManager.getActiveSessionIds();
            
            for (String sessionId : activeSessionIds) {
                List<WebSocketConnectionManager.Subscription> subscriptions = 
                    connectionManager.getSubscriptions(sessionId);
                
                for (WebSocketConnectionManager.Subscription subscription : subscriptions) {
                    if (subscription.shouldUpdate()) {
                        pushMetricsForSubscription(sessionId, subscription);
                        subscription.updateLastUpdate();
                    }
                }
            }
        } catch (Exception e) {
            log.error("定时推送指标数据失败", e);
        }
    }

    /**
     * 为特定订阅推送指标数据
     */
    private void pushMetricsForSubscription(String sessionId, WebSocketConnectionManager.Subscription subscription) {
        WebSocketSession session = connectionManager.getSession(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }

        try {
            Map<String, Object> metricsData;
            
            if (subscription.getClientId() == null) {
                // 全局订阅，获取所有客户端的最新数据
                metricsData = getAllLatestMetrics(subscription.getMetrics());
            } else {
                // 特定客户端订阅
                metricsData = getClientLatestMetrics(subscription.getClientId(), subscription.getMetrics());
            }

            if (metricsData != null && !metricsData.isEmpty()) {
                sendMetricsToSession(session, metricsData);
            }
        } catch (Exception e) {
            log.error("推送订阅指标数据失败，会话ID: {}", sessionId, e);
        }
    }

    /**
     * 推送指标数据给订阅者
     */
    private void pushMetricsToSubscribers(String clientId, Map<String, Object> metricsData) {
        Set<String> subscribers = connectionManager.getSubscribers(clientId);
        
        for (String sessionId : subscribers) {
            WebSocketSession session = connectionManager.getSession(sessionId);
            if (session != null && session.isOpen()) {
                try {
                    sendMetricsToSession(session, metricsData);
                } catch (Exception e) {
                    log.error("推送实时指标数据失败，会话ID: {}", sessionId, e);
                }
            }
        }
    }

    /**
     * 获取客户端最新指标数据
     */
    private Map<String, Object> getClientLatestMetrics(String clientId, List<String> requestedMetrics) {
        try {
            String key = METRICS_LATEST_PREFIX + clientId;
            @SuppressWarnings("unchecked")
            Map<String, Object> allMetrics = (Map<String, Object>) redisTemplate.opsForValue().get(key);
            
            if (allMetrics == null) {
                return null;
            }

            Map<String, Object> result = new HashMap<>(allMetrics);
            
            // 如果指定了特定指标，只返回请求的指标
            if (requestedMetrics != null && !requestedMetrics.isEmpty() && !requestedMetrics.contains("all")) {
                Map<String, Object> filteredMetrics = new HashMap<>();
                filteredMetrics.put("clientId", clientId);
                filteredMetrics.put("timestamp", allMetrics.get("timestamp"));
                
                for (String metric : requestedMetrics) {
                    if (allMetrics.containsKey(metric)) {
                        filteredMetrics.put(metric, allMetrics.get(metric));
                    }
                }
                result = filteredMetrics;
            }
            
            return result;
        } catch (Exception e) {
            log.error("获取客户端指标数据失败，客户端ID: {}", clientId, e);
            return null;
        }
    }

    /**
     * 获取所有客户端的最新指标数据
     */
    private Map<String, Object> getAllLatestMetrics(List<String> requestedMetrics) {
        try {
            Set<String> keys = redisTemplate.keys(METRICS_LATEST_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return null;
            }

            Map<String, Object> allClientsMetrics = new HashMap<>();
            List<Object> values = redisTemplate.opsForValue().multiGet(keys);
            
            int index = 0;
            for (String key : keys) {
                String clientId = key.substring(METRICS_LATEST_PREFIX.length());
                @SuppressWarnings("unchecked")
                Map<String, Object> clientMetrics = (Map<String, Object>) values.get(index++);
                
                if (clientMetrics != null) {
                    if (requestedMetrics != null && !requestedMetrics.isEmpty() && !requestedMetrics.contains("all")) {
                        Map<String, Object> filteredMetrics = new HashMap<>();
                        filteredMetrics.put("timestamp", clientMetrics.get("timestamp"));
                        
                        for (String metric : requestedMetrics) {
                            if (clientMetrics.containsKey(metric)) {
                                filteredMetrics.put(metric, clientMetrics.get(metric));
                            }
                        }
                        allClientsMetrics.put(clientId, filteredMetrics);
                    } else {
                        allClientsMetrics.put(clientId, clientMetrics);
                    }
                }
            }
            
            return Map.of(
                "type", "all_clients_metrics",
                "data", allClientsMetrics,
                "timestamp", System.currentTimeMillis()
            );
        } catch (Exception e) {
            log.error("获取所有客户端指标数据失败", e);
            return null;
        }
    }

    /**
     * 发送指标数据到WebSocket会话
     */
    private void sendMetricsToSession(WebSocketSession session, Map<String, Object> metricsData) throws IOException {
        Map<String, Object> message = Map.of(
            "type", "metrics_update",
            "data", metricsData,
            "timestamp", System.currentTimeMillis()
        );
        
        String json = objectMapper.writeValueAsString(message);
        session.sendMessage(new TextMessage(json));
        
        log.debug("发送指标数据到会话，会话ID: {}, 数据大小: {} bytes", 
                 session.getId(), json.length());
    }

    /**
     * 检查连接健康状态
     */
    private void checkConnectionHealth() {
        try {
            Set<String> activeSessionIds = connectionManager.getActiveSessionIds();
            log.debug("连接健康检查，活跃连接数: {}", activeSessionIds.size());
            
            for (String sessionId : activeSessionIds) {
                WebSocketSession session = connectionManager.getSession(sessionId);
                if (session == null || !session.isOpen()) {
                    log.warn("发现无效连接，移除会话ID: {}", sessionId);
                    connectionManager.removeConnection(sessionId);
                }
            }
        } catch (Exception e) {
            log.error("连接健康检查失败", e);
        }
    }

    /**
     * 发布实时指标数据到Redis通道
     */
    public void publishMetrics(String clientId, Map<String, Object> metricsData) {
        try {
            Map<String, Object> message = new HashMap<>(metricsData);
            message.put("clientId", clientId);
            message.put("timestamp", System.currentTimeMillis());
            
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(METRICS_CHANNEL, json);
            
            log.debug("发布实时指标数据，客户端ID: {}", clientId);
        } catch (Exception e) {
            log.error("发布实时指标数据失败，客户端ID: {}", clientId, e);
        }
    }
}