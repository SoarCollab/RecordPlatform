package cn.flying.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Param: RecordPlatform
 * @description: Web配置类
 * @Author: flyingcoding
 * @Create: 2025-01-16 11:37
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    /**
     * 密码加密
     * */
    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }
    /**
     * RestTemplate
     * */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService healthIndicatorExecutor() {
        AtomicInteger idx = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("health-indicator-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(2, factory);
    }
}
