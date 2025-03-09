package cn.flying.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
    public RestTemplate restTemplate(){
        return new RestTemplate();
    }
}
