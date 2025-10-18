package cn.flying.monitor.data.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Enhanced Redis caching configuration for query optimization
 */
@Configuration
@EnableCaching
public class CacheConfiguration {
    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);
    
    @Value("${spring.cache.redis.time-to-live:300000}") // Default 5 minutes
    private long defaultTtl;
    
    @Value("${spring.cache.redis.key-prefix:monitor:cache:}")
    private String keyPrefix;
    
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        
        // Create custom serializer with ObjectMapper
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        
        // Default cache configuration
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMillis(defaultTtl))
                .prefixCacheNameWith(keyPrefix)
                .serializeKeysWith(org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
                        .fromSerializer(serializer))
                .disableCachingNullValues();
        
        // Cache-specific configurations with different TTLs
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        
        // Real-time metrics cache - short TTL (30 seconds)
        cacheConfigurations.put("realtime-metrics", defaultConfig
                .entryTtl(Duration.ofSeconds(30)));
        
        // Historical metrics cache - medium TTL (5 minutes)
        cacheConfigurations.put("historical-metrics", defaultConfig
                .entryTtl(Duration.ofMinutes(5)));
        
        // Aggregation results cache - longer TTL (15 minutes)
        cacheConfigurations.put("aggregation-results", defaultConfig
                .entryTtl(Duration.ofMinutes(15)));
        
        // Statistics cache - long TTL (30 minutes)
        cacheConfigurations.put("statistics", defaultConfig
                .entryTtl(Duration.ofMinutes(30)));
        
        // Client metadata cache - very long TTL (1 hour)
        cacheConfigurations.put("client-metadata", defaultConfig
                .entryTtl(Duration.ofHours(1)));
        
        // Query performance cache - medium TTL (10 minutes)
        cacheConfigurations.put("query-performance", defaultConfig
                .entryTtl(Duration.ofMinutes(10)));
        
        log.info("Redis cache manager configured with {} cache types and default TTL: {}ms", 
                cacheConfigurations.size(), defaultTtl);
        
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
    
    @Bean
    public RedisTemplate<String, Object> enhancedRedisTemplate(RedisConnectionFactory connectionFactory, 
                                                              ObjectMapper objectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Use JSON serializer for values
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        
        template.setDefaultSerializer(serializer);
        template.afterPropertiesSet();
        
        log.info("Enhanced Redis template configured with JSON serialization");
        
        return template;
    }
}