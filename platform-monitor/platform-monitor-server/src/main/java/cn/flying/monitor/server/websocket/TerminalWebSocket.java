package cn.flying.monitor.server.websocket;

import cn.flying.monitor.server.entity.dto.ClientSsh;
import cn.flying.monitor.server.mapper.ClientSshMapper;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import jakarta.annotation.Resource;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@ServerEndpoint("/terminal/{clientId}")
public class TerminalWebSocket {

    private static final Map<Session, Shell> sessionMap = new ConcurrentHashMap<>();
    private static ClientSshMapper sshMapper;
    private final ExecutorService service = Executors.newSingleThreadExecutor();

    @Resource
    public void setSshMapper(ClientSshMapper sshMapper) {
        TerminalWebSocket.sshMapper = sshMapper;
    }

    @OnOpen
    public void onOpen(Session session,
                       @PathParam(value = "clientId") String clientId) throws Exception {
        log.info("正在尝试建立WebSocket终端连接，客户端ID: {}, 会话ID: {}", clientId, session.getId());
        try {
            ClientSsh ssh = sshMapper.selectById(clientId);
            if (ssh == null) {
                log.error("找不到客户端ID为 {} 的SSH配置信息", clientId);
                session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "无法识别此主机"));
                return;
            }
            log.info("已找到客户端SSH配置信息：IP={}, 端口={}, 用户名={}", ssh.getIp(), ssh.getPort(), ssh.getUsername());
            if (this.createSshConnection(session, ssh, ssh.getIp())) {
                log.info("主机 {} 的SSH连接已创建", ssh.getIp());
            } else {
                log.error("与主机 {} 的SSH连接创建失败", ssh.getIp());
            }
        } catch (Exception e) {
            log.error("WebSocket连接建立过程中发生异常", e);
            throw e;
        }
    }

    private boolean createSshConnection(Session session, ClientSsh ssh, String ip) throws IOException {
        log.info("开始尝试SSH连接，用户: {}，IP: {}，端口: {}", ssh.getUsername(), ip, ssh.getPort());
        try {
            JSch jSch = new JSch();
            log.info("已创建JSch实例");
            com.jcraft.jsch.Session js = jSch.getSession(ssh.getUsername(), ip, ssh.getPort());
            log.info("已获取JSch Session，准备进行连接配置");
            js.setPassword(ssh.getPassword());
            js.setConfig("StrictHostKeyChecking", "no");
            js.setTimeout(10000);
            log.info("SSH连接参数设置完毕，尝试连接到 {}:{}", ip, ssh.getPort());
            js.connect();
            log.info("SSH连接成功，准备打开Shell通道");
            ChannelShell channel = (ChannelShell) js.openChannel("shell");
            channel.setPtyType("xterm");
            log.info("Shell通道已创建，准备连接");
            channel.connect(1000);
            log.info("Shell通道连接成功");
            sessionMap.put(session, new Shell(session, js, channel));
            return true;
        } catch (JSchException e) {
            String message = e.getMessage();
            log.error("SSH连接异常：{}", message, e);
            log.error("连接详情 - 主机: {}, 端口: {}, 用户名: {}", ip, ssh.getPort(), ssh.getUsername());
            if (message.equals("Auth fail")) {
                session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT,
                        "登录SSH失败，用户名或密码错误"));
                log.error("连接SSH失败，用户名或密码错误，登录失败");
            } else if (message.contains("Connection refused")) {
                session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT,
                        "连接被拒绝，可能是没有启动SSH服务或是放开端口"));
                log.error("连接SSH失败，连接被拒绝，可能是没有启动SSH服务或是放开端口");
            } else if (message.contains("connect timed out")) {
                session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT,
                        "连接超时，请检查网络或防火墙设置"));
                log.error("连接SSH失败，连接超时，可能是网络问题或防火墙阻止了连接");
            } else if (message.contains("UnknownHostException") || message.contains("No such host is known")) {
                session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT,
                        "无法解析主机地址，请检查IP是否正确"));
                log.error("连接SSH失败，无法解析主机地址 {}", ip);
            } else {
                session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, message));
                log.error("连接SSH时出现未知错误: {}", e.getMessage());
            }

            // 尝试执行网络诊断
            try {
                log.info("正在执行网络诊断，检查与目标主机的连接...");

                // 记录服务器信息
                String serverInfo = String.format("服务器信息 - 主机名: %s, IP: %s",
                        java.net.InetAddress.getLocalHost().getHostName(),
                        java.net.InetAddress.getLocalHost().getHostAddress());
                log.info(serverInfo);

                // 尝试执行ping测试
                boolean reachable = false;
                try {
                    log.info("正在尝试ping目标主机 {} ...", ip);
                    reachable = java.net.InetAddress.getByName(ip).isReachable(3000);
                    log.info("目标主机 {} 可达性: {}", ip, reachable);
                } catch (Exception pingEx) {
                    log.error("无法执行ping测试: {}", pingEx.getMessage(), pingEx);
                }

                // 尝试获取目标主机信息
                try {
                    java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
                    log.info("目标主机解析信息 - 主机名: {}, 规范主机名: {}, IP地址: {}",
                            addr.getHostName(), addr.getCanonicalHostName(), addr.getHostAddress());
                } catch (Exception resolveEx) {
                    log.error("无法解析目标主机信息: {}", resolveEx.getMessage());
                }

                // 尝试使用Socket直接连接SSH端口
                try {
                    log.info("尝试直接通过Socket连接 {}:{} ...", ip, ssh.getPort());
                    java.net.Socket socket = new java.net.Socket();
                    socket.connect(new java.net.InetSocketAddress(ip, ssh.getPort()), 5000);
                    log.info("Socket连接成功，端口 {} 开放", ssh.getPort());
                    socket.close();
                } catch (Exception socketEx) {
                    log.error("Socket连接失败: {}", socketEx.getMessage());
                }
            } catch (Exception diagEx) {
                log.error("执行网络诊断时出错", diagEx);
            }
        }
        return false;
    }

    @OnMessage
    public void onMessage(Session session, String message) throws IOException {
        Shell shell = sessionMap.get(session);
        OutputStream output = shell.output;
        output.write(message.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    @OnClose
    public void onClose(Session session) throws IOException {
        Shell shell = sessionMap.get(session);
        if (shell != null) {
            shell.close();
            sessionMap.remove(session);
            log.info("主机 {} 的SSH连接已断开", shell.js.getHost());
        }
    }

    @OnError
    public void onError(Session session, Throwable error) throws IOException {
        log.error("用户WebSocket连接出现错误", error);
        session.close();
    }

    private class Shell {
        private final Session session;
        private final com.jcraft.jsch.Session js;
        private final ChannelShell channel;
        private final InputStream input;
        private final OutputStream output;

        public Shell(Session session, com.jcraft.jsch.Session js, ChannelShell channel) throws IOException {
            this.js = js;
            this.session = session;
            this.channel = channel;
            this.input = channel.getInputStream();
            this.output = channel.getOutputStream();
            service.submit(this::read);
        }

        private void read() {
            try {
                byte[] buffer = new byte[1024 * 1024];
                int i;
                while ((i = input.read(buffer)) != -1) {
                    String text = new String(Arrays.copyOfRange(buffer, 0, i), StandardCharsets.UTF_8);
                    session.getBasicRemote().sendText(text);
                }
            } catch (Exception e) {
                log.error("读取SSH输入流时出现问题", e);
            }
        }

        public void close() throws IOException {
            input.close();
            output.close();
            channel.disconnect();
            js.disconnect();
            service.shutdown();
        }
    }
}