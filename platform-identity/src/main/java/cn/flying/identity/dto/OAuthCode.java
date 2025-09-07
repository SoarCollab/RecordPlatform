package cn.flying.identity.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OAuth2.0授权码实体类
 * 用于管理授权码的生成和验证
 */
@Data
@TableName("oauth_code")
public class OAuthCode {

    /**
     * 授权码ID（主键）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 授权码
     */
    @TableField("code")
    private String code;

    /**
     * 客户端标识符
     */
    @TableField("client_key")
    private String clientKey;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 重定向URI
     */
    @TableField("redirect_uri")
    private String redirectUri;

    /**
     * 授权范围
     */
    @TableField("scope")
    private String scope;

    /**
     * 状态参数
     */
    @TableField("state")
    private String state;

    /**
     * 授权码状态：1-有效，0-已使用，-1-已过期
     */
    @TableField("status")
    private Integer status;

    /**
     * 过期时间
     */
    @TableField("expire_time")
    private LocalDateTime expireTime;

    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 使用时间
     */
    @TableField("used_time")
    private LocalDateTime usedTime;
}