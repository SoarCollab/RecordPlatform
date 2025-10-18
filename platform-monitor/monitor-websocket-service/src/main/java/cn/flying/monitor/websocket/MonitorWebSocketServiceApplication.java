package cn.flying.monitor.websocket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 监控系统WebSocket服务启动类
 */
@SpringBootApplication(scanBasePackages = "cn.flying.monitor")
@EnableDiscoveryClient
public class MonitorWebSocketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorWebSocketServiceApplication.class, args);
    }
}