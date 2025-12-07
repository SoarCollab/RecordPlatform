package cn.flying.filter.handler;

import cn.flying.common.tenant.TenantContext;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MyBatis-Plus 字段自动填充处理器
 * 自动填充 createTime、updateTime、registerTime 和 tenantId
 */
@Slf4j
@Component
public class MybatisHandler implements MetaObjectHandler {
     @Override
    public void insertFill(MetaObject metaObject) {
        log.debug("开始插入填充...");
        // 租户ID自动填充
        this.strictInsertFill(metaObject, "tenantId", Long.class, TenantContext.getTenantIdOrDefault());
        // 创建时间使用当前时间填充
        this.setFieldValByName("createTime", new Date(), metaObject);
        // 注册时间使用当前时间填充
        this.setFieldValByName("registerTime", new Date(), metaObject);
        // 更新时间使用当前时间填充
        this.setFieldValByName("updateTime", new Date(), metaObject);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        log.debug("开始更新填充...");
        // 更新时间使用当前时间填充
        this.setFieldValByName("updateTime", new Date(), metaObject);
    }
}
