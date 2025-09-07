package cn.flying.identity.constant;

import lombok.Getter;

/**
 * 用户权限枚举
 * 从 platform-backend 迁移而来，用于定义系统中的用户角色
 * 
 * @author 王贝强
 * @create 2025-05-06 20:54
 */
@Getter
public enum UserRole {
    ROLE_DEFAULT("user", "普通用户"),
    ROLE_ADMINISTER("admin", "超级管理员"),
    ROLE_MONITOR("monitor", "系统监控员"),
    ROLE_NOOP("noop", "未登录用户");

    /**
     * 角色标识
     */
    private final String role;
    
    /**
     * 角色描述
     */
    private final String description;

    UserRole(String role, String description) {
        this.role = role;
        this.description = description;
    }

    /**
     * 根据角色标识获取枚举
     * @param role 角色标识
     * @return 对应的用户角色枚举，如果未找到则返回 ROLE_NOOP
     */
    public static UserRole getRole(String role) {
        for (UserRole value : values()) {
            if (value.role.equals(role)) {
                return value;
            }
        }
        return ROLE_NOOP;
    }
}
