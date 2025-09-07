package cn.flying.identity.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC配置类
 * 配置静态资源处理器，解决favicon.ico等静态资源访问问题
 *
 * @author 王贝强
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 配置静态资源处理器
     * 添加静态资源映射，确保favicon.ico等文件能够被正确访问
     *
     * @param registry 资源处理器注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 配置静态资源映射
        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/");

        // 配置docs文档资源映射
        registry.addResourceHandler("/docs/**")
                .addResourceLocations("classpath:/static/docs/");

        // 配置favicon.ico映射
        registry.addResourceHandler("/favicon.ico")
                .addResourceLocations("classpath:/static/favicon.ico");

        // 配置webjars资源映射（用于Swagger UI等）
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");
    }
}