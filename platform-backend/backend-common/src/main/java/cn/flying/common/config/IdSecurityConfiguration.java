package cn.flying.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * ID安全配置类，用于配置ID安全相关功能
 */
@Slf4j
@Configuration
public class IdSecurityConfiguration {

    /**
     * 配置Jackson对Long类型的序列化处理
     */
    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper objectMapper = builder.createXmlMapper(false).build();
        
        // 配置Long类型为String序列化，防止前端精度丢失
        SimpleModule simpleModule = new SimpleModule();
        simpleModule.addSerializer(Long.class, ToStringSerializer.instance);
        simpleModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
        objectMapper.registerModule(simpleModule);
        
        // 禁用科学计数法
        objectMapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

        return objectMapper;
    }
    
    /**
     * 在应用启动时记录ID生成器配置信息
     */
    @Bean
    public Object logIdGeneratorInfo() {
        log.info("ID security configuration loaded");
        return new Object();
    }
} 