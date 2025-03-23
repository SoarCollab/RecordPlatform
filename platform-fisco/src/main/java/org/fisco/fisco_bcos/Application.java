package org.fisco.fisco_bcos;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class, args);
    }
}
