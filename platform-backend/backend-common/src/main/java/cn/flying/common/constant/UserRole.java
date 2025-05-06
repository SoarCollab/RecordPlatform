package cn.flying.common.constant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * @program: RecordPlatform
 * @description: 用户权限
 * @author: 王贝强
 * @create: 2025-05-06 20:54
 */
@Getter
@Schema(description = "用户权限枚举")
public enum UserRole {
    ROLE_DEFAULT("user", "普通用户"),
    ROLE_ADMINISTER("admin", "超级管理员"),
    ROLE_MONITOR("monitor", "系统监控员"),
    ROLE_NOOP("noop", "未登录用户");

    @Schema(description = "角色")
    private final String role;
    @Schema(description = "描述")
    private final String description;

    UserRole(String role, String description) {
        this.role = role;
        this.description = description;
    }

    /**
     * 根据角色获取枚举
     * @param role
     * @return
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
