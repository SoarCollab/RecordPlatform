package cn.flying.identity.dto;

import cn.hutool.core.bean.BeanUtil;

/**
 * 基础数据接口
 * 提供数据转换功能
 * 从 platform-backend 迁移而来
 *
 * @author 王贝强
 */
public interface BaseData {

    /**
     * 将当前对象转换为指定的视图对象，并执行自定义处理
     *
     * @param clazz    目标视图对象类型
     * @param consumer 自定义处理函数
     * @param <V>      视图对象类型
     * @return 转换后的视图对象
     */
    default <V> V asViewObject(Class<V> clazz, java.util.function.Consumer<V> consumer) {
        V vo = asViewObject(clazz);
        consumer.accept(vo);
        return vo;
    }

    /**
     * 将当前对象转换为指定的视图对象
     *
     * @param clazz 目标视图对象类型
     * @param <V>   视图对象类型
     * @return 转换后的视图对象
     */
    default <V> V asViewObject(Class<V> clazz) {
        return BeanUtil.copyProperties(this, clazz);
    }
}
