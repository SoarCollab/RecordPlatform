package cn.flying.monitor.websocket.service;

public interface SshAuditService {
    void logSshConnection(String username, String clientId, String sessionId, String status);
    void logSshCommand(String username, String clientId, String sessionId, String command);
}
