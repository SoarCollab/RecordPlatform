package cn.flying.identity.dto.apigateway;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API配额限制实体类
 * 管理应用的API调用配额
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Data
@TableName("api_quota")
public class ApiQuota {

    /**
     * 配额ID(主键)
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 应用ID
     */
    @TableField("app_id")
    private Long appId;

    /**
     * 接口ID(NULL表示全局配额)
     */
    @TableField("interface_id")
    private Long interfaceId;

    /**
     * 配额类型:1-每分钟,2-每小时,3-每天,4-每月
     */
    @TableField("quota_type")
    private Integer quotaType;

    /**
     * 配额限制(次数)
     */
    @TableField("quota_limit")
    private Long quotaLimit;

    /**
     * 已使用配额
     */
    @TableField("quota_used")
    private Long quotaUsed;

    /**
     * 配额重置时间
     */
    @TableField("reset_time")
    private LocalDateTime resetTime;

    /**
     * 告警阈值(百分比)
     */
    @TableField("alert_threshold")
    private Integer alertThreshold;

    /**
     * 是否已告警:0-否,1-是
     */
    @TableField("is_alerted")
    private Integer isAlerted;

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
