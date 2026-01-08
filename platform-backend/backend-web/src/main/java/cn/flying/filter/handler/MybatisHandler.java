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

    /**
     * 插入时字段自动填充。
     * <p>
     * 规则：
     * <ul>
     *   <li>tenantId：始终从 TenantContext 自动填充</li>
     *   <li>createTime/registerTime/updateTime：仅当调用方未显式赋值时才填充当前时间，避免覆盖测试/迁移场景的自定义时间</li>
     * </ul>
     * </p>
     *
     * @param metaObject MyBatis 元对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        log.debug("开始插入填充...");
        // 租户ID自动填充
        this.strictInsertFill(metaObject, "tenantId", Long.class, TenantContext.getTenantIdOrDefault());
        // 创建/注册/更新时间：仅在未赋值时填充，避免覆盖调用方显式设置的时间
        Date now = new Date();
        this.strictInsertFill(metaObject, "createTime", Date.class, now);
        this.strictInsertFill(metaObject, "registerTime", Date.class, now);
        this.strictInsertFill(metaObject, "updateTime", Date.class, now);
    }

    /**
     * 更新时字段自动填充。
     * <p>
     * updateTime：更新时始终刷新为当前时间。
     * </p>
     *
     * @param metaObject MyBatis 元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        log.debug("开始更新填充...");
        // 更新时间使用当前时间填充
        this.setFieldValByName("updateTime", new Date(), metaObject);
    }
}
