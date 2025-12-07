package cn.flying.dao.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import cn.flying.common.util.IdUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * MyBatis-Plus配置类
 */
@Slf4j
@Configuration
public class MybatisPlusConfiguration {

    public MybatisPlusConfiguration() {
        // 输出日志，表明使用自定义ID生成器
        log.info("MyBatis-Plus: Replace the built-in snowflake algorithm with custom IdUtils");
    }

    /**
     * 分页插件 + 多租户插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // Tenant interceptor must be added first
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandlerImpl()));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
    
    /**
     * 使用自定义雪花ID生成器替代MyBatis-Plus内置的
     */
    @Bean
    public IdentifierGenerator idGenerator() {
        return new IdentifierGenerator() {
            @Override
            public Number nextId(Object entity) {
                // 使用实体ID生成
                return IdUtils.nextEntityId();
            }
            
            @Override
            public String nextUUID(Object entity) {
                // 使用实体ID生成
                return IdUtils.nextEntityIdStr();
            }
        };
    }
} 