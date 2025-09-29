package cn.flying.identity.dto.apigateway;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API应用管理实体类
 * 用于管理接入API开放平台的第三方应用
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Data
@TableName("api_application")
public class ApiApplication {

    /**
     * 应用ID(主键)
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 应用名称
     */
    @TableField("app_name")
    private String appName;

    /**
     * 应用标识码(唯一)
     */
    @TableField("app_code")
    private String appCode;

    /**
     * 应用描述
     */
    @TableField("app_description")
    private String appDescription;

    /**
     * 所属开发者用户ID
     */
    @TableField("owner_id")
    private Long ownerId;

    /**
     * 应用类型:1-Web应用,2-移动应用,3-服务端应用,4-其他
     */
    @TableField("app_type")
    private Integer appType;

    /**
     * 应用状态:0-待审核,1-已启用,2-已禁用,3-已删除
     */
    @TableField("app_status")
    private Integer appStatus;

    /**
     * 应用图标URL
     */
    @TableField("app_icon")
    private String appIcon;

    /**
     * 应用官网
     */
    @TableField("app_website")
    private String appWebsite;

    /**
     * 回调URL(多个用逗号分隔)
     */
    @TableField("callback_url")
    private String callbackUrl;

    /**
     * IP白名单(JSON数组)
     */
    @TableField("ip_whitelist")
    private String ipWhitelist;

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
     * 审核通过时间
     */
    @TableField("approve_time")
    private LocalDateTime approveTime;

    /**
     * 审核人ID
     */
    @TableField("approve_by")
    private Long approveBy;

    /**
     * 逻辑删除:0-未删除,1-已删除
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
