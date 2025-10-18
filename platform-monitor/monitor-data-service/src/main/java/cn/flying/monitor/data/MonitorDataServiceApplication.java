package cn.flying.monitor.data;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 监控系统数据服务启动类
 */
@SpringBootApplication(scanBasePackages = "cn.flying.monitor")
@EnableDiscoveryClient
public class MonitorDataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorDataServiceApplication.class, args);
    }
}