package cn.flying.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * HTTPS 强制配置。
 * 生产环境下自动将 HTTP 请求重定向到 HTTPS。
 * <p>
 * 实现方式：
 * 1. 创建额外的 HTTP Connector 监听 80 端口
 * 2. 配置 Security Constraint 强制使用 CONFIDENTIAL 传输保证
 * 3. 所有 HTTP 请求自动 301 重定向到 HTTPS
 */
@Slf4j
@Configuration
@Profile("prod")
@ConditionalOnProperty(name = "security.require-ssl", havingValue = "true")
public class HttpsRedirectConfiguration {

    @Value("${server.port:443}")
    private int httpsPort;

    @Value("${security.http-redirect-port:80}")
    private int httpPort;

    @Bean
    public ServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory() {
            @Override
            protected void postProcessContext(Context context) {
                // 配置安全约束：所有请求必须使用 HTTPS
                SecurityConstraint securityConstraint = new SecurityConstraint();
                securityConstraint.setUserConstraint("CONFIDENTIAL");

                SecurityCollection collection = new SecurityCollection();
                collection.addPattern("/*");
                securityConstraint.addCollection(collection);

                context.addConstraint(securityConstraint);
            }
        };

        // 添加 HTTP Connector 用于重定向
        tomcat.addAdditionalTomcatConnectors(createHttpConnector());

        log.info("HTTPS redirect enabled: HTTP {} -> HTTPS {}", httpPort, httpsPort);
        return tomcat;
    }

    /**
     * 创建 HTTP Connector，接收 80 端口请求并重定向到 HTTPS。
     */
    private Connector createHttpConnector() {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setScheme("http");
        connector.setPort(httpPort);
        connector.setSecure(false);
        // 设置重定向端口为 HTTPS 端口
        connector.setRedirectPort(httpsPort);
        return connector;
    }
}
