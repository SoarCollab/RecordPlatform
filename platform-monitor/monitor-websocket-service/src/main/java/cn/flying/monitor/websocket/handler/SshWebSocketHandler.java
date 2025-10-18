package cn.flying.monitor.websocket.handler;

import cn.flying.monitor.websocket.service.SshSessionManager;
import cn.flying.monitor.websocket.service.SshAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.util.Map;

/**
 * 增强的SSH WebSocket处理器
 * 支持多会话管理、审计日志和错误处理
 */
@Slf4j
@Component
public class SshWebSocketHandler implements WebSocketHandler {

    private final SshSessionManager sessionManager;
    private final SshAuditService auditService;
    private final ObjectMapper objectMapper;

    public SshWebSocketHandler(SshSessionManager sessionManager,
                              SshAuditService auditService,
                              ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String username = (String) session.getAttributes().get("username");
        String clientId = extractClientId(session);
        
        log.info("SSH WebSocket连接已建立，用户: {}, 客户端: {}, 会话ID: {}", 
                username, clientId, session.getId());
        
        // 记录连接审计日志
        auditService.logSshConnection(username, clientId, session.getId(), "CONNECTED");
        
        // 发送连接确认消息
        sendMessage(session, Map.of(
            "type", "ssh_connection_established",
            "message", "SSH连接已建立",
            "sessionId", session.getId(),
            "clientId", clientId
        ));
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        if (message instanceof TextMessage) {
            String payload = ((TextMessage) message).getPayload();
            
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> messageData = objectMapper.readValue(payload, Map.class);
                handleSshMessage(session, messageData);
            } catch (Exception e) {
                log.error("处理SSH WebSocket消息失败，会话ID: {}", session.getId(), e);
                sendErrorMessage(session, "消息格式错误: " + e.getMessage());
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String username = (String) session.getAttributes().get("username");
        String clientId = extractClientId(session);
        
        log.error("SSH WebSocket传输错误，用户: {}, 客户端: {}, 会话ID: {}", 
                 username, clientId, session.getId(), exception);
        
        // 记录错误审计日志
        auditService.logSshConnection(username, clientId, session.getId(), 
                                    "ERROR: " + exception.getMessage());
        
        // 清理SSH会话
        sessionManager.closeSshSession(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        String username = (String) session.getAttributes().get("username");
        String clientId = extractClientId(session);
        
        log.info("SSH WebSocket连接已关闭，用户: {}, 客户端: {}, 会话ID: {}, 状态: {}", 
                username, clientId, session.getId(), closeStatus);
        
        // 记录断开连接审计日志
        auditService.logSshConnection(username, clientId, session.getId(), 
                                    "DISCONNECTED: " + closeStatus.toString());
        
        // 清理SSH会话
        sessionManager.closeSshSession(session.getId());
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * 处理SSH相关消息
     */
    private void handleSshMessage(WebSocketSession session, Map<String, Object> messageData) {
        String type = (String) messageData.get("type");
        String username = (String) session.getAttributes().get("username");
        
        switch (type) {
            case "ssh_connect":
                handleSshConnect(session, messageData, username);
                break;
            case "ssh_command":
                handleSshCommand(session, messageData, username);
                break;
            case "ssh_resize":
                handleSshResize(session, messageData);
                break;
            case "ssh_disconnect":
                handleSshDisconnect(session, username);
                break;
            case "ping":
                handlePing(session);
                break;
            default:
                log.warn("未知的SSH消息类型: {}, 会话ID: {}", type, session.getId());
                sendErrorMessage(session, "未知的消息类型: " + type);
        }
    }

    /**
     * 处理SSH连接请求
     */
    private void handleSshConnect(WebSocketSession session, Map<String, Object> messageData, String username) {
        try {
            String clientId = (String) messageData.get("clientId");
            if (clientId == null) {
                sendErrorMessage(session, "缺少客户端ID");
                return;
            }

            // 检查用户权限
            if (!hasPermission(username, clientId)) {
                sendErrorMessage(session, "没有访问该客户端的权限");
                auditService.logSshConnection(username, clientId, session.getId(), "ACCESS_DENIED");
                return;
            }

            boolean success = sessionManager.createSshSession(session.getId(), clientId, session);
            
            if (success) {
                sendMessage(session, Map.of(
                    "type", "ssh_connected",
                    "clientId", clientId,
                    "message", "SSH连接成功"
                ));
                auditService.logSshConnection(username, clientId, session.getId(), "SSH_CONNECTED");
            } else {
                sendErrorMessage(session, "SSH连接失败");
                auditService.logSshConnection(username, clientId, session.getId(), "SSH_CONNECT_FAILED");
            }
        } catch (Exception e) {
            log.error("处理SSH连接请求失败", e);
            sendErrorMessage(session, "SSH连接失败: " + e.getMessage());
        }
    }

    /**
     * 处理SSH命令
     */
    private void handleSshCommand(WebSocketSession session, Map<String, Object> messageData, String username) {
        try {
            String command = (String) messageData.get("command");
            if (command == null) {
                return;
            }

            // 记录命令审计日志
            String clientId = sessionManager.getClientId(session.getId());
            auditService.logSshCommand(username, clientId, session.getId(), command);

            sessionManager.sendCommand(session.getId(), command);
        } catch (Exception e) {
            log.error("处理SSH命令失败，会话ID: {}", session.getId(), e);
            sendErrorMessage(session, "命令执行失败: " + e.getMessage());
        }
    }

    /**
     * 处理终端大小调整
     */
    private void handleSshResize(WebSocketSession session, Map<String, Object> messageData) {
        try {
            Integer cols = (Integer) messageData.get("cols");
            Integer rows = (Integer) messageData.get("rows");
            
            if (cols != null && rows != null) {
                sessionManager.resizeTerminal(session.getId(), cols, rows);
            }
        } catch (Exception e) {
            log.error("处理终端大小调整失败，会话ID: {}", session.getId(), e);
        }
    }

    /**
     * 处理SSH断开连接
     */
    private void handleSshDisconnect(WebSocketSession session, String username) {
        try {
            String clientId = sessionManager.getClientId(session.getId());
            sessionManager.closeSshSession(session.getId());
            
            sendMessage(session, Map.of(
                "type", "ssh_disconnected",
                "message", "SSH连接已断开"
            ));
            
            auditService.logSshConnection(username, clientId, session.getId(), "SSH_DISCONNECTED");
        } catch (Exception e) {
            log.error("处理SSH断开连接失败，会话ID: {}", session.getId(), e);
        }
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
     * 从WebSocket会话中提取客户端ID
     */
    private String extractClientId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] segments = path.split("/");
        return segments.length > 2 ? segments[segments.length - 1] : null;
    }

    /**
     * 检查用户权限
     */
    private boolean hasPermission(String username, String clientId) {
        // Basic permission check - in production this should integrate with RBAC system
        if (username == null || clientId == null) {
            return false;
        }
        
        // For now, allow access if user is authenticated
        // In production, implement proper role-based access control
        log.debug("权限检查: 用户={}, 客户端={}", username, clientId);
        return true;
    }

    /**
     * 发送消息到客户端
     */
    private void sendMessage(WebSocketSession session, Object message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("发送SSH WebSocket消息失败，会话ID: {}", session.getId(), e);
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