package cn.flying.monitor.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 监控系统通知服务启动类
 */
@SpringBootApplication(scanBasePackages = "cn.flying.monitor")
@EnableDiscoveryClient
public class MonitorNotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorNotificationServiceApplication.class, args);
    }
}