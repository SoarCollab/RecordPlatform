package cn.flying.identity.util;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.constant.UserRole;
import cn.flying.identity.dto.Account;
import cn.flying.identity.mapper.AccountMapper;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 系统安全相关工具类
 * 从 platform-backend 迁移而来，适配 SA-Token 框架
 *
 * @author 王贝强
 * @create 2025-05-06 20:51
 */
@Component
public class SecurityUtils {

    private static ApplicationContext applicationContext;

    public SecurityUtils(ApplicationContext applicationContext) {
        SecurityUtils.applicationContext = applicationContext;
    }

    /**
     * 获取 AccountMapper Bean
     */
    private static AccountMapper getAccountMapper() {
        if (applicationContext != null) {
            return applicationContext.getBean(AccountMapper.class);
        }
        return null;
    }

    /**
     * 获取当前登录用户的角色
     * 
     * @return 用户角色枚举
     */
    public static UserRole getLoginUserRole() {
        try {
            // 首先尝试从 MDC 获取（兼容原有逻辑）
            String userRole = MDC.get("userRole");
            if (userRole != null) {
                return UserRole.getRole(userRole);
            }

            // 如果 MDC 中没有，则从 SA-Token 获取
            if (StpUtil.isLogin()) {
                Long userId = Long.valueOf(StpUtil.getLoginId().toString());
                AccountMapper mapper = getAccountMapper();
                if (mapper != null) {
                    Account account = mapper.selectById(userId);
                    if (account != null) {
                        return UserRole.getRole(account.getRole());
                    }
                }
            }
        } catch (Exception e) {
            // 记录异常但不抛出，返回默认角色
            System.err.println("获取用户角色失败: " + e.getMessage());
        }
        
        return UserRole.ROLE_NOOP;
    }

    /**
     * 判断当前用户是否为管理员
     * 
     * @return true-是管理员，false-不是管理员
     */
    public static boolean isAdmin() {
        UserRole userRole = getLoginUserRole();
        return UserRole.ROLE_ADMINISTER.equals(userRole);
    }

    /**
     * 判断当前用户是否为监控员
     * 
     * @return true-是监控员，false-不是监控员
     */
    public static boolean isMonitor() {
        UserRole userRole = getLoginUserRole();
        return UserRole.ROLE_MONITOR.equals(userRole);
    }

    /**
     * 判断当前用户是否为普通用户
     * 
     * @return true-是普通用户，false-不是普通用户
     */
    public static boolean isUser() {
        UserRole userRole = getLoginUserRole();
        return UserRole.ROLE_DEFAULT.equals(userRole);
    }

    /**
     * 获取当前登录用户ID
     * 
     * @return 用户ID，未登录返回null
     */
    public static Long getLoginUserId() {
        try {
            if (StpUtil.isLogin()) {
                return Long.valueOf(StpUtil.getLoginId().toString());
            }
        } catch (Exception e) {
            System.err.println("获取用户ID失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 获取当前登录用户信息
     * 
     * @return 用户信息，未登录返回null
     */
    public static Account getLoginUser() {
        try {
            Long userId = getLoginUserId();
            if (userId != null) {
                AccountMapper mapper = getAccountMapper();
                if (mapper != null) {
                    return mapper.selectById(userId);
                }
            }
        } catch (Exception e) {
            System.err.println("获取用户信息失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 检查当前用户是否有指定权限
     * 
     * @param permission 权限标识
     * @return true-有权限，false-无权限
     */
    public static boolean hasPermission(String permission) {
        try {
            return StpUtil.hasPermission(permission);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查当前用户是否有指定角色
     * 
     * @param role 角色标识
     * @return true-有角色，false-无角色
     */
    public static boolean hasRole(String role) {
        try {
            return StpUtil.hasRole(role);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查当前用户是否已登录
     * 
     * @return true-已登录，false-未登录
     */
    public static boolean isLogin() {
        try {
            return StpUtil.isLogin();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取当前用户的Token
     * 
     * @return Token字符串，未登录返回null
     */
    public static String getToken() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getTokenValue();
            }
        } catch (Exception e) {
            System.err.println("获取Token失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 设置用户角色到 MDC（用于日志记录等）
     * 
     * @param userRole 用户角色
     */
    public static void setUserRoleToMDC(String userRole) {
        MDC.put("userRole", userRole);
    }

    /**
     * 设置用户ID到 MDC（用于日志记录等）
     * 
     * @param userId 用户ID
     */
    public static void setUserIdToMDC(Long userId) {
        if (userId != null) {
            MDC.put("userId", userId.toString());
        }
    }

    /**
     * 清除 MDC 中的用户信息
     */
    public static void clearMDC() {
        MDC.remove("userId");
        MDC.remove("userRole");
    }
}
