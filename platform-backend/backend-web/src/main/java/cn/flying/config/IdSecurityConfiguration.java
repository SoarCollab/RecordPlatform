package cn.flying.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * ID安全配置类，用于配置ID安全相关功能。
 * <p>
 * Spring Boot 4 起 JSON 映射由 Jackson 3（{@code tools.jackson}）提供，自动配置的是
 * {@code JsonMapper}；定制映射器必须通过 {@link JsonMapperBuilderCustomizer} 进行，
 * 而不是再定义一个 {@code ObjectMapper} Bean。
 */
@Slf4j
@Configuration
public class IdSecurityConfiguration {

    /**
     * 配置 Jackson 对 Long 类型的序列化处理：统一输出字符串，避免前端精度丢失。
     *
     * @return 注册 Long 序列化模块并开启 BigDecimal 明文输出的定制器
     */
    @Bean
    public JsonMapperBuilderCustomizer longAsStringJsonMapperCustomizer() {
        return builder -> builder
                .addModule(longToStringModule())
                // 禁用科学计数法
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    }

    /**
     * 构建生产环境使用的 JSON 映射器（Long 输出字符串、BigDecimal 非科学计数法）。
     * 测试可直接复用它来断言线上序列化行为。
     *
     * @return 与自动配置一致的 JsonMapper
     */
    public static JsonMapper productionJsonMapper() {
        return JsonMapper.builder()
                .addModule(longToStringModule())
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * 创建把 Long 序列化为字符串的模块，避免前端精度丢失。
     *
     * @return Long 序列化模块
     */
    public static SimpleModule longToStringModule() {
        SimpleModule simpleModule = new SimpleModule();
        simpleModule.addSerializer(Long.class, ToStringSerializer.instance);
        simpleModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return simpleModule;
    }

    /**
     * 在应用启动时记录ID生成器配置信息。
     *
     * @return 标记 Bean，仅用于触发启动日志
     */
    @Bean
    public Object logIdGeneratorInfo() {
        log.info("ID security configuration loaded");
        return new Object();
    }
}
