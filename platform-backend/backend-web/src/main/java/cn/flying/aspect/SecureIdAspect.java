package cn.flying.aspect;

import cn.flying.common.annotation.SecureId;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/**
 * ID安全切面，用于处理带有@SecureId注解的方法返回值
 * 将内部ID转换为混淆后的外部ID
 */
@Slf4j
@Aspect
@Component
public class SecureIdAspect {

    /**
     * 环绕通知，拦截带有@SecureId注解的方法
     *
     * @param joinPoint 切点
     * @param secureId  注解
     * @return 处理后的返回值
     * @throws Throwable 可能抛出的异常
     */
    @Around("@annotation(secureId)")
    public Object aroundSecureId(ProceedingJoinPoint joinPoint, SecureId secureId) throws Throwable {
        if (log.isDebugEnabled()) {
            log.debug("执行SecureId切面, hideOriginalId={}", secureId.hideOriginalId());
        }

        // 执行原方法
        Object result = joinPoint.proceed();

        // 如果注解禁用或结果为null，直接返回
        if (!secureId.value() || result == null) {
            if (log.isDebugEnabled()) {
                log.debug("SecureId切面未执行: enable={}, result=null? {}", secureId.value(), result == null);
            }
            return result;
        }

        try {
            // 处理返回值中的ID
            if (log.isDebugEnabled()) {
                log.debug("处理返回值类型: {}", result.getClass().getName());
            }

            switch (result) {
                case Result<?> result1 -> {
                    log.debug("处理Result包装类");
                    return processResultWrapper(result1, secureId.field(), secureId);
                }
                case Collection<?> objects -> {
                    log.debug("处理Collection集合");
                    for (Object item : objects) {
                        processObject(item, secureId.field(), secureId.hideOriginalId());
                    }
                    return result;
                }
                case Map<?, ?> ignored -> {
                    log.debug("跳过Map类型处理");
                    // 不处理Map，因为Map的结构不确定
                    return result;
                }
                default -> {
                    log.debug("处理单个对象: {}", result.getClass().getName());
                    // 处理单个对象
                    processObject(result, secureId.field(), secureId.hideOriginalId());
                    return result;
                }
            }
        } catch (Exception e) {
            // 使用ERROR级别记录异常，确保在任何环境都能看到
            log.error("处理安全ID时发生错误", e);
            // 发生错误时返回原结果，确保API能够正常工作
            return result;
        }
    }

    /**
     * 处理Result包装类
     *
     * @param resultWrapper Result包装类对象
     * @param idField       ID字段名
     * @param secureId      方法上的SecureId注解
     * @return 处理后的Result包装类
     */
    private Result<?> processResultWrapper(Result<?> resultWrapper, String idField, SecureId secureId) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("处理Result包装类, hideOriginalId={}", secureId.hideOriginalId());
        }

        // 获取Result中的data
        Object data = getDataFromResult(resultWrapper);

        if (data == null) {
            if (log.isDebugEnabled()) {
                log.debug("Result包装类中data为空，跳过处理");
            }
            return resultWrapper;
        }

        if (log.isDebugEnabled()) {
            log.debug("Result包装类中data类型: {}", data.getClass().getName());
        }

        // 处理data对象中的ID
        if (data instanceof Collection<?>) {
            if (log.isDebugEnabled()) {
                log.debug("处理Result中的集合数据");
            }
            // 处理集合类型的data
            for (Object item : (Collection<?>) data) {
                processObject(item, idField, secureId.hideOriginalId());
            }
        } else if (!(data instanceof Map<?, ?>)) {
            if (log.isDebugEnabled()) {
                log.debug("处理Result中的单个对象");
            }
            // 处理单个对象类型的data (排除Map类型)
            processObject(data, idField, secureId.hideOriginalId());
        } else {
            if (log.isDebugEnabled()) {
                log.debug("跳过Result中的Map数据处理");
            }
        }

        return resultWrapper;
    }

    /**
     * 处理单个对象，转换ID字段
     *
     * @param obj            对象
     * @param idField        ID字段名
     * @param hideOriginalId 是否隐藏原始ID
     */
    private void processObject(Object obj, String idField, boolean hideOriginalId) throws Exception {
        if (obj == null) {
            if (log.isDebugEnabled()) {
                log.debug("对象为空，跳过处理");
            }
            return;
        }

        Class<?> clazz = obj.getClass();
        if (log.isDebugEnabled()) {
            log.debug("处理对象类型: {}, 字段: {}, 隐藏ID: {}", clazz.getName(), idField, hideOriginalId);
        }

        try {
            // 查找并处理ID字段
            Field field = clazz.getDeclaredField(idField);
            field.setAccessible(true);

            Object idValue = field.get(obj);
            if (log.isDebugEnabled()) {
                log.debug("ID字段值: {}", idValue);
            }

            if (idValue instanceof Long) {
                // 创建混淆后的外部ID
                // 可以根据对象类型判断使用不同的混淆方法
                String externalId;

                // 根据类名判断是否为用户相关对象
                String entityName = clazz.getSimpleName().toLowerCase();
                if (entityName.contains(Const.USER_ENTITY) || entityName.contains(Const.ACCOUNT_ENTITY)) {
                    externalId = IdUtils.toExternalUserId((Long) idValue);
                    if (log.isDebugEnabled()) {
                        log.debug("生成用户相关外部ID: {}", externalId);
                    }
                } else {
                    externalId = IdUtils.toExternalId((Long) idValue);
                    if (log.isDebugEnabled()) {
                        log.debug("生成普通外部ID: {}", externalId);
                    }
                }

                // 查找并设置外部ID字段
                boolean externalIdFieldFound = false;
                try {
                    Field externalIdField = clazz.getDeclaredField("externalId");
                    externalIdField.setAccessible(true);
                    externalIdField.set(obj, externalId);
                    externalIdFieldFound = true;
                    if (log.isDebugEnabled()) {
                        log.debug("设置externalId字段: {}", externalId);
                    }
                } catch (NoSuchFieldException e) {
                    log.debug("找不到externalId字段，尝试其他字段名");
                    // 如果没有externalId字段，尝试查找其他可能的字段名
                    try {
                        Field extIdField = clazz.getDeclaredField("extId");
                        extIdField.setAccessible(true);
                        extIdField.set(obj, externalId);
                        externalIdFieldFound = true;
                        if (log.isDebugEnabled()) {
                            log.debug("设置extId字段: {}", externalId);
                        }
                    } catch (NoSuchFieldException ex) {
                        // 明确标识安全风险
                        log.warn("ID混淆失败：对象 {} 缺少 externalId/extId 字段，内部ID可能泄露。" +
                                "请添加 externalId 字段以支持ID混淆", clazz.getSimpleName());
                    }
                }

                // 无论是否找到外部ID字段，都要处理 hideOriginalId 逻辑
                // 如果需要隐藏原始ID，则将原始ID设置为null
                if (hideOriginalId) {
                    field.set(obj, null);
                    if (log.isDebugEnabled()) {
                        log.debug("已隐藏对象 {} 的原始ID", clazz.getSimpleName());
                    }
                } else if (!externalIdFieldFound) {
                    // 如果未找到外部ID字段且未隐藏原始ID，记录警告
                    log.warn("安全风险：对象 {} 的内部ID {} 将被暴露给前端",
                            clazz.getSimpleName(), idValue);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("保留原始ID: {}", idValue);
                    }
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("ID字段不是Long类型，跳过处理");
                }
            }
        } catch (NoSuchFieldException e) {
            // 如果没有指定的ID字段，记录日志
            if (log.isDebugEnabled()) {
                log.debug("对象 {} 没有找到ID字段: {}", clazz.getSimpleName(), idField);
            }
        }
    }

    /**
     * 从Result中获取data字段值
     *
     * @param resultWrapper Result包装类对象
     * @return data字段值
     */
    private Object getDataFromResult(Result<?> resultWrapper) {
        try {
            // 尝试使用getData方法
            Method getDataMethod = resultWrapper.getClass().getMethod("getData");
            return getDataMethod.invoke(resultWrapper);
        } catch (Exception e) {
            try {
                // 尝试直接访问data字段
                Field dataField = resultWrapper.getClass().getDeclaredField("data");
                dataField.setAccessible(true);
                return dataField.get(resultWrapper);
            } catch (Exception ex) {
                log.warn("无法获取Result中的data字段: {}", ex.getMessage());
                return null;
            }
        }
    }

    /**
     * 处理单个对象，转换ID字段（使用注解中的hideOriginalId设置）
     *
     * @param obj     对象
     * @param idField ID字段名
     */
    private void processObject(Object obj, String idField) throws Exception {
        boolean hideOriginalId = false;

        // 1. 检查对象类上是否有SecureId注解
        if (obj != null && obj.getClass().isAnnotationPresent(SecureId.class)) {
            SecureId annotation = obj.getClass().getAnnotation(SecureId.class);
            if (annotation != null) {
                hideOriginalId = annotation.hideOriginalId();
            }
            if (log.isDebugEnabled()) {
                log.debug("从对象 {} 类注解获取hideOriginalId={}", obj.getClass().getSimpleName(), hideOriginalId);
            }
        }

        // 2. 处理对象
        processObject(obj, idField, hideOriginalId);
    }
} 