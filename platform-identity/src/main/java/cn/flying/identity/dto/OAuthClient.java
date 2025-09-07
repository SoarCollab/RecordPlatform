package cn.flying.identity.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OAuth2.0客户端实体类
 * 用于管理第三方应用的接入信息
 */
@Data
@TableName("oauth_client")
public class OAuthClient {

    /**
     * 客户端ID（主键）
     */
    @TableId(value = "client_id", type = IdType.ASSIGN_ID)
    private Long clientId;

    /**
     * 客户端标识符
     */
    @TableField("client_key")
    private String clientKey;

    /**
     * 客户端密钥
     */
    @TableField("client_secret")
    private String clientSecret;

    /**
     * 客户端名称
     */
    @TableField("client_name")
    private String clientName;

    /**
     * 客户端描述
     */
    @TableField("description")
    private String description;

    /**
     * 重定向URI列表（JSON格式存储）
     */
    @TableField("redirect_uris")
    private String redirectUris;

    /**
     * 授权范围列表（JSON格式存储）
     */
    @TableField("scopes")
    private String scopes;

    /**
     * 支持的授权类型（JSON格式存储）
     * 如：authorization_code, refresh_token, client_credentials
     */
    @TableField("grant_types")
    private String grantTypes;

    /**
     * 访问令牌有效期（秒）
     */
    @TableField("access_token_validity")
    private Integer accessTokenValidity;

    /**
     * 刷新令牌有效期（秒）
     */
    @TableField("refresh_token_validity")
    private Integer refreshTokenValidity;

    /**
     * 客户端状态：1-启用，0-禁用
     */
    @TableField("status")
    private Integer status;

    /**
     * 是否自动授权：1-是，0-否
     */
    @TableField("auto_approve")
    private Integer autoApprove;

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
     * 逻辑删除：1-已删除，0-未删除
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}