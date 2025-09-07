package cn.flying.identity.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户会话管理实体类
 * 用于管理用户登录会话的生命周期
 *
 * @author flying
 * @date 2025-01-16
 */
@Data
@TableName("user_session")
public class UserSession {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 用户名
     */
    @TableField("username")
    private String username;

    /**
     * 客户端IP
     */
    @TableField("client_ip")
    private String clientIp;

    /**
     * 用户代理
     */
    @TableField("user_agent")
    private String userAgent;

    /**
     * 地理位置
     */
    @TableField("location")
    private String location;

    /**
     * 设备指纹
     */
    @TableField("device_fingerprint")
    private String deviceFingerprint;

    /**
     * 登录时间
     */
    @TableField("login_time")
    private LocalDateTime loginTime;

    /**
     * 最后访问时间
     */
    @TableField("last_access_time")
    private LocalDateTime lastAccessTime;

    /**
     * 过期时间
     */
    @TableField("expire_time")
    private LocalDateTime expireTime;

    /**
     * 状态：0-失效，1-有效
     */
    @TableField("status")
    private Integer status;

    /**
     * 注销原因
     */
    @TableField("logout_reason")
    private String logoutReason;

    /**
     * 注销时间
     */
    @TableField("logout_time")
    private LocalDateTime logoutTime;

    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 会话状态枚举
     */
    public enum Status {
        INVALID(0, "失效"),
        VALID(1, "有效");

        private final Integer code;
        private final String desc;

        Status(Integer code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public Integer getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }
    }

    /**
     * 注销原因枚举
     */
    public enum LogoutReason {
        USER_LOGOUT("USER_LOGOUT", "用户主动注销"),
        TIMEOUT("TIMEOUT", "会话超时"),
        FORCE_LOGOUT("FORCE_LOGOUT", "强制注销"),
        SYSTEM_LOGOUT("SYSTEM_LOGOUT", "系统注销"),
        CONCURRENT_LOGIN("CONCURRENT_LOGIN", "并发登录导致注销"),
        SECURITY_LOGOUT("SECURITY_LOGOUT", "安全原因注销");

        private final String code;
        private final String desc;

        LogoutReason(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public String getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }

        /**
         * 根据代码获取枚举
         */
        public static LogoutReason getByCode(String code) {
            for (LogoutReason reason : values()) {
                if (reason.code.equals(code)) {
                    return reason;
                }
            }
            return null;
        }
    }

    /**
     * 检查会话是否有效
     */
    public boolean isValid() {
        return Status.VALID.getCode().equals(this.status) && 
               (this.expireTime == null || this.expireTime.isAfter(LocalDateTime.now()));
    }

    /**
     * 检查会话是否过期
     */
    public boolean isExpired() {
        return this.expireTime != null && this.expireTime.isBefore(LocalDateTime.now());
    }

    /**
     * 更新最后访问时间
     */
    public void updateLastAccess() {
        this.lastAccessTime = LocalDateTime.now();
    }

    /**
     * 注销会话
     */
    public void logout(LogoutReason reason) {
        this.status = Status.INVALID.getCode();
        this.logoutReason = reason.getCode();
        this.logoutTime = LocalDateTime.now();
    }
}
