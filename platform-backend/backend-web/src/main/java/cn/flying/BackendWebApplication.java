package cn.flying;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDubbo
@EnableAsync
@EnableScheduling
public class BackendWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendWebApplication.class, args);
    }

}
