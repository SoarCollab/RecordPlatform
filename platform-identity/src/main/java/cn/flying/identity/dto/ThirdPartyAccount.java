package cn.flying.identity.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 第三方登录绑定实体类
 * 用于管理用户与第三方平台账号的绑定关系
 *
 * @author flying
 * @date 2025-01-16
 */
@Data
@TableName("third_party_account")
public class ThirdPartyAccount {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 第三方提供商
     * github-GitHub，google-Google，wechat-微信，qq-QQ
     */
    @TableField("provider")
    private String provider;

    /**
     * 第三方用户ID
     */
    @TableField("third_party_id")
    private String thirdPartyId;

    /**
     * 第三方用户名
     */
    @TableField("third_party_username")
    private String thirdPartyUsername;

    /**
     * 第三方邮箱
     */
    @TableField("third_party_email")
    private String thirdPartyEmail;

    /**
     * 第三方头像
     */
    @TableField("third_party_avatar")
    private String thirdPartyAvatar;

    /**
     * 访问令牌
     */
    @TableField("access_token")
    private String accessToken;

    /**
     * 刷新令牌
     */
    @TableField("refresh_token")
    private String refreshToken;

    /**
     * 令牌过期时间
     */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /**
     * 绑定时间
     */
    @TableField("bind_time")
    private LocalDateTime bindTime;

    /**
     * 最后登录时间
     */
    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;

    /**
     * 状态：0-禁用，1-启用
     */
    @TableField("status")
    private Integer status;

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
     * 逻辑删除：0-未删除，1-已删除
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /**
     * 第三方提供商枚举
     */
    public enum Provider {
        GITHUB("github", "GitHub"),
        GOOGLE("google", "Google"),
        WECHAT("wechat", "微信"),
        QQ("qq", "QQ"),
        WEIBO("weibo", "微博"),
        ALIPAY("alipay", "支付宝"),
        BAIDU("baidu", "百度");

        private final String code;
        private final String desc;

        Provider(String code, String desc) {
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
        public static Provider getByCode(String code) {
            for (Provider provider : values()) {
                if (provider.code.equals(code)) {
                    return provider;
                }
            }
            return null;
        }
    }

    /**
     * 状态枚举
     */
    public enum Status {
        DISABLED(0, "禁用"),
        ENABLED(1, "启用");

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
}
