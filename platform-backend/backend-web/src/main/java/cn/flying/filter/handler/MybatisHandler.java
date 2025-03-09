package cn.flying.filter.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * @program: MyBatisTest
 * @description:
 * @author: flyingcoding
 * @create: 2024-11-21 17:27
 */
@Slf4j
@Component
public class MybatisHandler implements MetaObjectHandler {
     @Override
    public void insertFill(MetaObject metaObject) {
        log.info("开始插入填充...");
        // 创建时间使用当前时间填充
        this.setFieldValByName("createTime", new Date(), metaObject);
         // 注册时间使用当前时间填充
         this.setFieldValByName("registerTime", new Date(), metaObject);
        // 更新时间使用当前时间填充
        this.setFieldValByName("updateTime", new Date(), metaObject);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        log.info("开始更新填充...");
       // 创建时间使用当前时间填充
        this.setFieldValByName("createTime", new Date(), metaObject);
        // 更新时间使用当前时间填充
        this.setFieldValByName("updateTime", new Date(), metaObject);
    }
}
