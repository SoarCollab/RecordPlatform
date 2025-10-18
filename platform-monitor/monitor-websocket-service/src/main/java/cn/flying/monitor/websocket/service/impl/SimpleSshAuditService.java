package cn.flying.monitor.websocket.service.impl;

import cn.flying.monitor.websocket.service.SshAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SimpleSshAuditService implements SshAuditService {
    @Override
    public void logSshConnection(String username, String clientId, String sessionId, String status) {
        log.info("SSH Audit - connection: user={}, clientId={}, sessionId={}, status={}", username, clientId, sessionId, status);
    }

    @Override
    public void logSshCommand(String username, String clientId, String sessionId, String command) {
        log.info("SSH Audit - command: user={}, clientId={}, sessionId={}, command={}", username, clientId, sessionId, command);
    }
}
