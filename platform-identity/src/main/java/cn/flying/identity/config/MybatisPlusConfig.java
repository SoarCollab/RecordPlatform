package cn.flying.identity.config;

import cn.flying.identity.util.IdUtils;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;

/**
 * MyBatis-Plus配置类
 * 配置自动填充处理器
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    public MybatisPlusConfig() {
        // 输出日志，表明使用自定义ID生成器
        log.info("MyBatis-Plus: Replace the built-in snowflake algorithm with custom IdUtils");
    }

    /**
     * 分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
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


    /**
     * 自动填充处理器
     * 自动填充创建时间和更新时间
     */
    @Component
    public static class MyMetaObjectHandler implements MetaObjectHandler {

        /**
         * 插入时自动填充
         *
         * @param metaObject 元对象
         */
        @Override
        public void insertFill(MetaObject metaObject) {
            this.strictInsertFill(metaObject, "registerTime", Date.class, new Date());
            this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
            this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        }

        /**
         * 更新时自动填充
         *
         * @param metaObject 元对象
         */
        @Override
        public void updateFill(MetaObject metaObject) {
            this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        }
    }
}