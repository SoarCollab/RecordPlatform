package cn.flying.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis配置类
 * 配置RedisTemplate和StringRedisTemplate的Bean
 * 确保Redis相关的依赖注入能够正常工作
 *
 * @author 王贝强
 */
@Configuration
public class RedisConfig {

    /**
     * 配置StringRedisTemplate
     * 用于字符串类型的Redis操作
     *
     * @param connectionFactory Redis连接工厂
     * @return StringRedisTemplate实例
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);

        // 设置序列化器
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());

        template.afterPropertiesSet();
        return template;
    }
}