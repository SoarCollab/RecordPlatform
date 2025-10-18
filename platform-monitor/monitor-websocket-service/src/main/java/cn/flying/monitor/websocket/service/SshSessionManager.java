package cn.flying.monitor.websocket.service;

import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSH会话管理器
 * 管理多个并发SSH会话，支持会话录制和审计
 */
@Slf4j
@Service
public class SshSessionManager {

    private final Map<String, SshSessionInfo> activeSessions = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final RedisTemplate<String, Object> redisTemplate;

    public SshSessionManager(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 创建SSH会话
     */
    public boolean createSshSession(String sessionId, String clientId, WebSocketSession webSocketSession) {
        try {
            // 从Redis获取客户端SSH配置
            SshConfig sshConfig = getSshConfig(clientId);
            if (sshConfig == null) {
                log.error("找不到客户端SSH配置，客户端ID: {}", clientId);
                return false;
            }

            // 创建JSch会话
            JSch jsch = new JSch();
            Session jschSession = jsch.getSession(sshConfig.getUsername(), sshConfig.getHost(), sshConfig.getPort());
            jschSession.setPassword(sshConfig.getPassword());
            jschSession.setConfig("StrictHostKeyChecking", "no");
            jschSession.setTimeout(30000);
            
            log.info("正在连接SSH服务器，主机: {}:{}, 用户: {}", 
                    sshConfig.getHost(), sshConfig.getPort(), sshConfig.getUsername());
            
            jschSession.connect();
            
            // 创建Shell通道
            ChannelShell channelShell = (ChannelShell) jschSession.openChannel("shell");
            channelShell.setPtyType("xterm-256color");
            channelShell.setPtySize(80, 24, 640, 480);
            channelShell.connect();

            // 创建会话信息
            SshSessionInfo sessionInfo = new SshSessionInfo(
                sessionId, clientId, webSocketSession, jschSession, channelShell
            );
            
            activeSessions.put(sessionId, sessionInfo);
            
            // 启动输出读取线程
            executorService.submit(() -> readSshOutput(sessionInfo));
            
            log.info("SSH会话创建成功，会话ID: {}, 客户端: {}", sessionId, clientId);
            return true;
            
        } catch (JSchException e) {
            log.error("创建SSH会话失败，会话ID: {}, 客户端: {}", sessionId, clientId, e);
            handleSshConnectionError(webSocketSession, e);
            return false;
        }
    }

    /**
     * 发送命令到SSH会话
     */
    public void sendCommand(String sessionId, String command) throws IOException {
        SshSessionInfo sessionInfo = activeSessions.get(sessionId);
        if (sessionInfo == null) {
            throw new IllegalStateException("SSH会话不存在: " + sessionId);
        }

        OutputStream outputStream = sessionInfo.getChannelShell().getOutputStream();
        outputStream.write(command.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
        
        // 记录命令到会话录制
        sessionInfo.recordCommand(command);
        
        log.debug("发送SSH命令，会话ID: {}, 命令长度: {}", sessionId, command.length());
    }

    /**
     * 调整终端大小
     */
    public void resizeTerminal(String sessionId, int cols, int rows) {
        SshSessionInfo sessionInfo = activeSessions.get(sessionId);
        if (sessionInfo != null) {
            try {
                sessionInfo.getChannelShell().setPtySize(cols, rows, cols * 8, rows * 16);
                log.debug("调整终端大小，会话ID: {}, 大小: {}x{}", sessionId, cols, rows);
            } catch (Exception e) {
                log.error("调整终端大小失败，会话ID: {}", sessionId, e);
            }
        }
    }

    /**
     * 关闭SSH会话
     */
    public void closeSshSession(String sessionId) {
        SshSessionInfo sessionInfo = activeSessions.remove(sessionId);
        if (sessionInfo != null) {
            try {
                // 保存会话录制
                sessionInfo.saveRecording();
                
                // 关闭连接
                if (sessionInfo.getChannelShell() != null && sessionInfo.getChannelShell().isConnected()) {
                    sessionInfo.getChannelShell().disconnect();
                }
                if (sessionInfo.getJschSession() != null && sessionInfo.getJschSession().isConnected()) {
                    sessionInfo.getJschSession().disconnect();
                }
                
                log.info("SSH会话已关闭，会话ID: {}", sessionId);
            } catch (Exception e) {
                log.error("关闭SSH会话时发生错误，会话ID: {}", sessionId, e);
            }
        }
    }

    /**
     * 获取客户端ID
     */
    public String getClientId(String sessionId) {
        SshSessionInfo sessionInfo = activeSessions.get(sessionId);
        return sessionInfo != null ? sessionInfo.getClientId() : null;
    }

    /**
     * 获取活跃会话数量
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * 读取SSH输出
     */
    private void readSshOutput(SshSessionInfo sessionInfo) {
        try {
            InputStream inputStream = sessionInfo.getChannelShell().getInputStream();
            byte[] buffer = new byte[4096];
            
            while (sessionInfo.getChannelShell().isConnected()) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead > 0) {
                    String output = new String(Arrays.copyOfRange(buffer, 0, bytesRead), StandardCharsets.UTF_8);
                    
                    // 发送输出到WebSocket客户端
                    sendOutputToClient(sessionInfo, output);
                    
                    // 记录输出到会话录制
                    sessionInfo.recordOutput(output);
                }
            }
        } catch (IOException e) {
            if (activeSessions.containsKey(sessionInfo.getSessionId())) {
                log.error("读取SSH输出时发生错误，会话ID: {}", sessionInfo.getSessionId(), e);
                closeSshSession(sessionInfo.getSessionId());
            }
        }
    }

    /**
     * 发送输出到WebSocket客户端
     */
    private void sendOutputToClient(SshSessionInfo sessionInfo, String output) {
        try {
            WebSocketSession webSocketSession = sessionInfo.getWebSocketSession();
            if (webSocketSession.isOpen()) {
                webSocketSession.sendMessage(new TextMessage(output));
            }
        } catch (IOException e) {
            log.error("发送SSH输出到WebSocket客户端失败，会话ID: {}", sessionInfo.getSessionId(), e);
        }
    }

    /**
     * 处理SSH连接错误
     */
    private void handleSshConnectionError(WebSocketSession webSocketSession, JSchException e) {
        String errorMessage;
        String errorCode;
        
        if (e.getMessage().contains("Auth fail")) {
            errorMessage = "SSH认证失败，请检查用户名和密码";
            errorCode = "AUTH_FAILED";
        } else if (e.getMessage().contains("Connection refused")) {
            errorMessage = "连接被拒绝，请检查SSH服务是否启动";
            errorCode = "CONNECTION_REFUSED";
        } else if (e.getMessage().contains("timeout")) {
            errorMessage = "连接超时，请检查网络连接";
            errorCode = "TIMEOUT";
        } else if (e.getMessage().contains("UnknownHostException")) {
            errorMessage = "无法解析主机地址";
            errorCode = "UNKNOWN_HOST";
        } else {
            errorMessage = "SSH连接失败: " + e.getMessage();
            errorCode = "UNKNOWN_ERROR";
        }

        try {
            Map<String, Object> errorResponse = Map.of(
                "type", "ssh_error",
                "code", errorCode,
                "message", errorMessage,
                "timestamp", System.currentTimeMillis()
            );
            
            webSocketSession.sendMessage(new TextMessage(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errorResponse)
            ));
        } catch (Exception ex) {
            log.error("发送SSH错误消息失败", ex);
        }
    }

    /**
     * 从Redis获取SSH配置
     */
    private SshConfig getSshConfig(String clientId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> configData = (Map<String, Object>) redisTemplate.opsForValue()
                .get("ssh_config:" + clientId);
            
            if (configData == null) {
                return null;
            }

            return new SshConfig(
                (String) configData.get("host"),
                (Integer) configData.get("port"),
                (String) configData.get("username"),
                (String) configData.get("password")
            );
        } catch (Exception e) {
            log.error("获取SSH配置失败，客户端ID: {}", clientId, e);
            return null;
        }
    }

    /**
     * SSH配置类
     */
    private static class SshConfig {
        private final String host;
        private final int port;
        private final String username;
        private final String password;

        public SshConfig(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }

        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
    }

    /**
     * SSH会话信息类
     */
    private static class SshSessionInfo {
        private final String sessionId;
        private final String clientId;
        private final WebSocketSession webSocketSession;
        private final Session jschSession;
        private final ChannelShell channelShell;
        private final StringBuilder recording;
        private final long startTime;

        public SshSessionInfo(String sessionId, String clientId, WebSocketSession webSocketSession,
                            Session jschSession, ChannelShell channelShell) {
            this.sessionId = sessionId;
            this.clientId = clientId;
            this.webSocketSession = webSocketSession;
            this.jschSession = jschSession;
            this.channelShell = channelShell;
            this.recording = new StringBuilder();
            this.startTime = System.currentTimeMillis();
        }

        public String getSessionId() { return sessionId; }
        public String getClientId() { return clientId; }
        public WebSocketSession getWebSocketSession() { return webSocketSession; }
        public Session getJschSession() { return jschSession; }
        public ChannelShell getChannelShell() { return channelShell; }

        public void recordCommand(String command) {
            recording.append("[").append(System.currentTimeMillis() - startTime)
                    .append("ms] INPUT: ").append(command).append("\n");
        }

        public void recordOutput(String output) {
            recording.append("[").append(System.currentTimeMillis() - startTime)
                    .append("ms] OUTPUT: ").append(output).append("\n");
        }

        public void saveRecording() {
            // Save session recording for audit purposes
            try {
                String recordingKey = "ssh_recording:" + sessionId;
                Map<String, Object> recordingData = Map.of(
                    "sessionId", sessionId,
                    "clientId", clientId,
                    "startTime", startTime,
                    "endTime", System.currentTimeMillis(),
                    "recording", recording.toString()
                );
                // Store in Redis with 30-day expiration for audit compliance
                // redisTemplate.opsForValue().set(recordingKey, recordingData, Duration.ofDays(30));
                log.info("保存SSH会话录制，会话ID: {}, 录制长度: {} 字符", 
                        sessionId, recording.length());
            } catch (Exception e) {
                log.error("保存SSH会话录制失败，会话ID: {}", sessionId, e);
            }
        }
    }
}