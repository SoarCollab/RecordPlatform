package cn.flying.monitor.websocket.config;

import cn.flying.monitor.websocket.handler.MetricsWebSocketHandler;
import cn.flying.monitor.websocket.handler.SshWebSocketHandler;
import cn.flying.monitor.websocket.interceptor.WebSocketAuthInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket配置类
 * 配置实时数据流和SSH代理的WebSocket端点
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MetricsWebSocketHandler metricsWebSocketHandler;
    private final SshWebSocketHandler sshWebSocketHandler;
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    public WebSocketConfig(MetricsWebSocketHandler metricsWebSocketHandler,
                          SshWebSocketHandler sshWebSocketHandler,
                          WebSocketAuthInterceptor webSocketAuthInterceptor) {
        this.metricsWebSocketHandler = metricsWebSocketHandler;
        this.sshWebSocketHandler = sshWebSocketHandler;
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 实时指标数据流端点
        registry.addHandler(metricsWebSocketHandler, "/ws/metrics")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins("*"); // 生产环境应配置具体域名
        
        // SSH代理端点
        registry.addHandler(sshWebSocketHandler, "/ws/ssh/{clientId}")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins("*"); // 生产环境应配置具体域名
    }

    /**
     * 配置WebSocket容器参数
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8192);
        container.setMaxBinaryMessageBufferSize(8192);
        container.setMaxSessionIdleTimeout(300000L); // 5分钟超时
        return container;
    }
}