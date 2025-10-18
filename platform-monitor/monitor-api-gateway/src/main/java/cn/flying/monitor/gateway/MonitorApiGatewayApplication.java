package cn.flying.monitor.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 监控系统API网关启动类
 */
@SpringBootApplication(scanBasePackages = "cn.flying.monitor")
@EnableDiscoveryClient
public class MonitorApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorApiGatewayApplication.class, args);
    }
}