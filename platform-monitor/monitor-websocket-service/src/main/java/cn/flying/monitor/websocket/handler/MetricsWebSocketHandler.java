package cn.flying.monitor.websocket.handler;

import cn.flying.monitor.websocket.service.MetricsStreamingService;
import cn.flying.monitor.websocket.service.WebSocketConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时指标数据WebSocket处理器
 * 处理客户端连接和实时数据推送
 */
@Slf4j
@Component
public class MetricsWebSocketHandler implements WebSocketHandler {

    private final WebSocketConnectionManager connectionManager;
    private final MetricsStreamingService streamingService;
    private final ObjectMapper objectMapper;

    public MetricsWebSocketHandler(WebSocketConnectionManager connectionManager,
                                 MetricsStreamingService streamingService,
                                 ObjectMapper objectMapper) {
        this.connectionManager = connectionManager;
        this.streamingService = streamingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String username = (String) session.getAttributes().get("username");
        log.info("WebSocket连接已建立，用户: {}, 会话ID: {}", username, session.getId());
        
        connectionManager.addConnection(session);
        
        // 发送连接确认消息
        sendMessage(session, Map.of(
            "type", "connection_established",
            "message", "实时数据流连接已建立",
            "sessionId", session.getId()
        ));
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        if (message instanceof TextMessage) {
            String payload = ((TextMessage) message).getPayload();
            log.debug("收到WebSocket消息，会话ID: {}, 消息: {}", session.getId(), payload);
            
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> messageData = objectMapper.readValue(payload, Map.class);
                handleClientMessage(session, messageData);
            } catch (Exception e) {
                log.error("处理WebSocket消息失败，会话ID: {}", session.getId(), e);
                sendErrorMessage(session, "消息格式错误");
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String username = (String) session.getAttributes().get("username");
        log.error("WebSocket传输错误，用户: {}, 会话ID: {}", username, session.getId(), exception);
        
        connectionManager.removeConnection(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        String username = (String) session.getAttributes().get("username");
        log.info("WebSocket连接已关闭，用户: {}, 会话ID: {}, 状态: {}", 
                username, session.getId(), closeStatus);
        
        connectionManager.removeConnection(session.getId());
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * 处理客户端消息
     */
    private void handleClientMessage(WebSocketSession session, Map<String, Object> messageData) {
        String type = (String) messageData.get("type");
        
        switch (type) {
            case "subscribe":
                handleSubscription(session, messageData);
                break;
            case "unsubscribe":
                handleUnsubscription(session, messageData);
                break;
            case "ping":
                handlePing(session);
                break;
            default:
                log.warn("未知的消息类型: {}, 会话ID: {}", type, session.getId());
                sendErrorMessage(session, "未知的消息类型: " + type);
        }
    }

    /**
     * 处理订阅请求
     */
    private void handleSubscription(WebSocketSession session, Map<String, Object> messageData) {
        @SuppressWarnings("unchecked")
        Map<String, Object> subscription = (Map<String, Object>) messageData.get("subscription");
        
        if (subscription == null) {
            sendErrorMessage(session, "缺少订阅信息");
            return;
        }

        String clientId = (String) subscription.get("clientId");
        @SuppressWarnings("unchecked")
        java.util.List<String> metrics = (java.util.List<String>) subscription.get("metrics");
        Integer interval = (Integer) subscription.get("interval");

        if (interval == null || interval < 1) {
            interval = 5; // 默认5秒间隔
        }

        connectionManager.addSubscription(session.getId(), clientId, metrics, interval);
        
        sendMessage(session, Map.of(
            "type", "subscription_confirmed",
            "clientId", clientId != null ? clientId : "all",
            "metrics", metrics != null ? metrics : java.util.List.of("all"),
            "interval", interval
        ));
        
        log.info("添加订阅，会话ID: {}, 客户端: {}, 指标: {}, 间隔: {}秒", 
                session.getId(), clientId, metrics, interval);
    }

    /**
     * 处理取消订阅请求
     */
    private void handleUnsubscription(WebSocketSession session, Map<String, Object> messageData) {
        String clientId = (String) messageData.get("clientId");
        connectionManager.removeSubscription(session.getId(), clientId);
        
        sendMessage(session, Map.of(
            "type", "unsubscription_confirmed",
            "clientId", clientId != null ? clientId : "all"
        ));
        
        log.info("取消订阅，会话ID: {}, 客户端: {}", session.getId(), clientId);
    }

    /**
     * 处理心跳请求
     */
    private void handlePing(WebSocketSession session) {
        sendMessage(session, Map.of(
            "type", "pong",
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 发送消息到客户端
     */
    private void sendMessage(WebSocketSession session, Object message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("发送WebSocket消息失败，会话ID: {}", session.getId(), e);
        }
    }

    /**
     * 发送错误消息
     */
    private void sendErrorMessage(WebSocketSession session, String error) {
        sendMessage(session, Map.of(
            "type", "error",
            "message", error,
            "timestamp", System.currentTimeMillis()
        ));
    }
}