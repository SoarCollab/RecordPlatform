package cn.flying.identity.dto.apigateway;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API密钥管理实体类
 * 用于管理应用的API密钥,支持一个应用多个密钥
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Data
@TableName("api_key")
public class ApiKey {

    /**
     * 密钥ID(主键)
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 所属应用ID
     */
    @TableField("app_id")
    private Long appId;

    /**
     * API密钥(公开)
     */
    @TableField("api_key")
    private String apiKey;

    /**
     * API密钥密文(加密存储)
     */
    @TableField("api_secret")
    private String apiSecret;

    /**
     * 密钥名称
     */
    @TableField("key_name")
    private String keyName;

    /**
     * 密钥状态:0-已禁用,1-已启用,2-已过期
     */
    @TableField("key_status")
    private Integer keyStatus;

    /**
     * 密钥类型:1-正式环境,2-测试环境
     */
    @TableField("key_type")
    private Integer keyType;

    /**
     * 过期时间(NULL表示永久)
     */
    @TableField("expire_time")
    private LocalDateTime expireTime;

    /**
     * 最后使用时间
     */
    @TableField("last_used_time")
    private LocalDateTime lastUsedTime;

    /**
     * 使用次数
     */
    @TableField("used_count")
    private Long usedCount;

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
     * 逻辑删除:0-未删除,1-已删除
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
