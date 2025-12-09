package cn.flying.common.util;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.UserRole;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 系统安全相关工具类
 * 提供获取当前登录用户信息的便捷方法
 */
@Component
public class SecurityUtils {

    /**
     * 获取当前登录用户角色
     * @return 用户角色枚举
     */
    public static UserRole getLoginUserRole() {
        String userRole = MDC.get(Const.ATTR_USER_ROLE);
        return UserRole.getRole(userRole);
    }

    /**
     * 判断当前用户是否为管理员
     * @return true 如果是管理员
     */
    public static boolean isAdmin() {
        UserRole userRole = getLoginUserRole();
        return UserRole.ROLE_ADMINISTER.equals(userRole);
    }

    /**
     * 判断当前用户是否具有指定角色
     * @param role 要检查的角色
     * @return true 如果具有该角色
     */
    public static boolean hasRole(UserRole role) {
        return role != null && role.equals(getLoginUserRole());
    }

    /**
     * 判断当前用户是否为系统监控员
     * @return true 如果是监控员
     */
    public static boolean isMonitor() {
        return hasRole(UserRole.ROLE_MONITOR);
    }

    /**
     * 判断当前用户是否为管理员或监控员
     * @return true 如果是管理员或监控员
     */
    public static boolean isAdminOrMonitor() {
        UserRole role = getLoginUserRole();
        return UserRole.ROLE_ADMINISTER.equals(role) || UserRole.ROLE_MONITOR.equals(role);
    }

    /**
     * 判断当前用户是否可以访问指定资源
     * 管理员可以访问所有资源，普通用户只能访问自己的资源
     * @param resourceOwnerId 资源所有者ID
     * @return true 如果可以访问
     */
    public static boolean canAccessResource(Long resourceOwnerId) {
        if (isAdmin()) {
            return true;
        }
        Long currentUserId = getUserId();
        return currentUserId != null && Objects.equals(currentUserId, resourceOwnerId);
    }

    /**
     * 要求当前用户是资源所有者，否则抛出权限异常
     * @param resourceOwnerId 资源所有者ID
     * @throws GeneralException 如果当前用户不是资源所有者
     */
    public static void requireOwnership(Long resourceOwnerId) {
        Long currentUserId = getUserId();
        if (currentUserId == null || !Objects.equals(currentUserId, resourceOwnerId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
    }

    /**
     * 要求当前用户是管理员或资源所有者，否则抛出权限异常
     * @param resourceOwnerId 资源所有者ID
     * @throws GeneralException 如果当前用户既不是管理员也不是资源所有者
     */
    public static void requireAdminOrOwnership(Long resourceOwnerId) {
        if (!canAccessResource(resourceOwnerId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
    }

    /**
     * 要求当前用户是管理员，否则抛出权限异常
     * @throws GeneralException 如果当前用户不是管理员
     */
    public static void requireAdmin() {
        if (!isAdmin()) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
    }

    /**
     * 要求当前用户是管理员或监控员，否则抛出权限异常
     * @throws GeneralException 如果当前用户既不是管理员也不是监控员
     */
    public static void requireAdminOrMonitor() {
        if (!isAdminOrMonitor()) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
    }

    /**
     * 获取当前登录用户ID
     * @return 用户ID，未登录返回null
     */
    public static Long getUserId() {
        String userId = MDC.get(Const.ATTR_USER_ID);
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取当前租户ID
     * @return 租户ID，默认返回0
     */
    public static Long getTenantId() {
        Long tenantId = TenantContext.getTenantId();
        return tenantId != null ? tenantId : 0L;
    }

    /**
     * 获取当前用户名
     * @return 用户名，未登录返回null
     */
    public static String getUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getUsername();
        }
        return null;
    }

    /**
     * 判断当前用户是否已登录
     * @return true 如果已登录
     */
    public static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof User;
    }
}
