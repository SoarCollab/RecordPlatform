package cn.flying.monitor.websocket.interceptor;

import cn.flying.monitor.common.security.JwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket认证拦截器
 * 在WebSocket握手阶段验证JWT令牌
 */
@Slf4j
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final JwtTokenProvider jwtTokenProvider;

    public WebSocketAuthInterceptor(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                 WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        
        String token = extractToken(request);
        if (token == null) {
            log.warn("WebSocket握手失败：缺少认证令牌，URI: {}", request.getURI());
            return false;
        }

        try {
            if (jwtTokenProvider.validateToken(token)) {
                String username = jwtTokenProvider.getUsername(token);
                attributes.put("username", username);
                attributes.put("token", token);
                log.info("WebSocket认证成功，用户: {}, URI: {}", username, request.getURI());
                return true;
            } else {
                log.warn("WebSocket握手失败：无效的认证令牌，URI: {}", request.getURI());
                return false;
            }
        } catch (Exception e) {
            log.error("WebSocket认证过程中发生异常，URI: {}", request.getURI(), e);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                             WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("WebSocket握手后发生异常，URI: {}", request.getURI(), exception);
        }
    }

    /**
     * 从请求中提取JWT令牌
     */
    private String extractToken(ServerHttpRequest request) {
        // 从查询参数中获取token
        String query = request.getURI().getQuery();
        if (query != null && query.contains("token=")) {
            String[] params = query.split("&");
            for (String param : params) {
                if (param.startsWith("token=")) {
                    return param.substring(6);
                }
            }
        }

        // 从Header中获取token
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }
}