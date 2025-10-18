package cn.flying.monitor.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * 用户实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户名
     */
    @TableField("username")
    private String username;

    /**
     * 密码哈希
     */
    @TableField("password_hash")
    private String passwordHash;

    /**
     * 邮箱
     */
    @TableField("email")
    private String email;

    /**
     * 手机号
     */
    @TableField("phone")
    private String phone;

    /**
     * 真实姓名
     */
    @TableField("real_name")
    private String realName;

    /**
     * 用户状态
     */
    @TableField("status")
    private UserStatus status;

    /**
     * 是否启用MFA
     */
    @TableField("mfa_enabled")
    private Boolean mfaEnabled;

    /**
     * MFA密钥
     */
    @TableField("mfa_secret")
    private String mfaSecret;

    /**
     * 最后登录时间
     */
    @TableField("last_login_time")
    private Instant lastLoginTime;

    /**
     * 最后登录IP
     */
    @TableField("last_login_ip")
    private String lastLoginIp;

    /**
     * 密码过期时间
     */
    @TableField("password_expires_at")
    private Instant passwordExpiresAt;

    /**
     * 账户锁定时间
     */
    @TableField("locked_until")
    private Instant lockedUntil;

    /**
     * 失败登录次数
     */
    @TableField("failed_login_attempts")
    private Integer failedLoginAttempts;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    /**
     * 创建者ID
     */
    @TableField("created_by")
    private Long createdBy;

    /**
     * 更新者ID
     */
    @TableField("updated_by")
    private Long updatedBy;

    /**
     * 逻辑删除标记
     */
    @TableLogic
    @TableField("deleted")
    private Boolean deleted;

    /**
     * 用户状态枚举
     */
    public enum UserStatus {
        ACTIVE("ACTIVE", "活跃"),
        INACTIVE("INACTIVE", "非活跃"),
        LOCKED("LOCKED", "锁定"),
        SUSPENDED("SUSPENDED", "暂停"),
        PENDING_ACTIVATION("PENDING_ACTIVATION", "待激活");

        private final String code;
        private final String description;

        UserStatus(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 检查用户是否被锁定
     */
    public boolean isLocked() {
        return status == UserStatus.LOCKED || 
               (lockedUntil != null && lockedUntil.isAfter(Instant.now()));
    }

    /**
     * 检查用户是否活跃
     */
    public boolean isActive() {
        return status == UserStatus.ACTIVE && !isLocked();
    }

    /**
     * 检查密码是否过期
     */
    public boolean isPasswordExpired() {
        return passwordExpiresAt != null && passwordExpiresAt.isBefore(Instant.now());
    }

    /**
     * 检查是否需要MFA验证
     */
    public boolean requiresMfa() {
        return mfaEnabled != null && mfaEnabled;
    }

    /**
     * 检查账户是否被锁定
     */
    public boolean isAccountLocked() {
        return isLocked();
    }
}