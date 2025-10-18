package cn.flying.monitor.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 监控系统认证服务启动类
 */
@SpringBootApplication(scanBasePackages = "cn.flying.monitor")
@EnableDiscoveryClient
public class MonitorAuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorAuthServiceApplication.class, args);
    }
}