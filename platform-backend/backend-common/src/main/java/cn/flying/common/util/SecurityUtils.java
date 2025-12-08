package cn.flying.common.util;

import cn.flying.common.constant.UserRole;
import cn.flying.common.tenant.TenantContext;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;

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
