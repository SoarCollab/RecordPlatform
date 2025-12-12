package cn.flying.aspect;

import cn.flying.common.annotation.RequireOwnership;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 资源所有权校验切面
 * 用于处理带有@RequireOwnership注解的方法
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final ApplicationContext applicationContext;

    /**
     * 前置通知，在方法执行前校验资源所有权
     * @param joinPoint 切点
     * @param requireOwnership 注解
     */
    @Before("@annotation(requireOwnership)")
    public void checkOwnership(JoinPoint joinPoint, RequireOwnership requireOwnership) {
        // 管理员绕过检查
        if (requireOwnership.adminBypass() && SecurityUtils.isAdmin()) {
            log.debug("管理员绕过资源所有权检查");
            return;
        }

        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId == null) {
            throw new GeneralException(ResultEnum.USER_NOT_LOGGED_IN);
        }

        // 获取资源ID - fail-close：无法获取时拒绝访问
        Long resourceId = extractResourceId(joinPoint, requireOwnership.resourceIdParam());
        if (resourceId == null) {
            log.warn("无法从方法参数中获取资源ID: {}，拒绝访问", requireOwnership.resourceIdParam());
            throw new GeneralException(ResultEnum.PARAM_ERROR, "资源ID参数无效");
        }

        // 获取资源所有者ID - fail-close：无法获取时拒绝访问
        Long ownerId = getResourceOwnerId(resourceId, requireOwnership);
        if (ownerId == null) {
            log.warn("无法获取资源所有者ID, resourceId={}，拒绝访问", resourceId);
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无法验证资源所有权");
        }

        // 校验所有权
        if (!currentUserId.equals(ownerId)) {
            log.warn("用户 {} 尝试访问用户 {} 的资源 {}", currentUserId, ownerId, resourceId);
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }

        log.debug("资源所有权校验通过: userId={}, resourceId={}", currentUserId, resourceId);
    }

    /**
     * 从方法参数中提取资源ID
     */
    private Long extractResourceId(JoinPoint joinPoint, String paramName) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameterNames.length; i++) {
            if (parameterNames[i].equals(paramName)) {
                Object arg = args[i];
                if (arg instanceof Long) {
                    return (Long) arg;
                } else if (arg instanceof String) {
                    try {
                        return Long.parseLong((String) arg);
                    } catch (NumberFormatException e) {
                        log.debug("参数 {} 无法解析为Long: {}", paramName, arg);
                    }
                }
                break;
            }
        }
        return null;
    }

    /**
     * 获取资源所有者ID
     * 通过资源类和资源ID查询资源实体，然后获取所有者ID字段
     */
    private Long getResourceOwnerId(Long resourceId, RequireOwnership annotation) {
        Class<?> resourceClass = annotation.resourceClass();

        // 如果没有指定资源类，跳过自动查询
        if (resourceClass == Void.class) {
            log.debug("未指定资源类，跳过自动查询所有者ID");
            return null;
        }

        try {
            // 查找对应的Mapper或Service
            Object mapper = findMapper(resourceClass);
            if (mapper == null) {
                log.warn("找不到资源类 {} 对应的Mapper", resourceClass.getSimpleName());
                return null;
            }

            // 调用selectById方法
            Object resource = invokeSelectById(mapper, resourceId);
            if (resource == null) {
                log.debug("资源不存在: {}, id={}", resourceClass.getSimpleName(), resourceId);
                return null;
            }

            // 获取所有者ID字段
            return extractOwnerId(resource, annotation.ownerIdField());

        } catch (Exception e) {
            log.error("获取资源所有者ID失败", e);
            return null;
        }
    }

    /**
     * 查找资源类对应的Mapper
     */
    private Object findMapper(Class<?> resourceClass) {
        String mapperName = resourceClass.getSimpleName() + "Mapper";
        String beanName = Character.toLowerCase(mapperName.charAt(0)) + mapperName.substring(1);

        try {
            return applicationContext.getBean(beanName);
        } catch (Exception e) {
            log.debug("无法获取Mapper: {}", beanName);
            return null;
        }
    }

    /**
     * 调用Mapper的selectById方法
     */
    private Object invokeSelectById(Object mapper, Long id) {
        try {
            Method method = mapper.getClass().getMethod("selectById", java.io.Serializable.class);
            return method.invoke(mapper, id);
        } catch (Exception e) {
            log.debug("调用selectById失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从资源对象中提取所有者ID
     */
    private Long extractOwnerId(Object resource, String ownerIdField) {
        try {
            Field field = resource.getClass().getDeclaredField(ownerIdField);
            field.setAccessible(true);
            Object value = field.get(resource);

            if (value instanceof Long) {
                return (Long) value;
            }
        } catch (NoSuchFieldException e) {
            log.debug("资源类 {} 没有字段: {}", resource.getClass().getSimpleName(), ownerIdField);
        } catch (Exception e) {
            log.error("获取所有者ID字段失败", e);
        }
        return null;
    }
}
