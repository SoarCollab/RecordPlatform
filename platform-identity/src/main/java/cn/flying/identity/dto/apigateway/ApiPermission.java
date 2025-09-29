package cn.flying.identity.dto.apigateway;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API权限配置实体类
 * 管理应用对API接口的访问权限
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Data
@TableName("api_permission")
public class ApiPermission {

    /**
     * 权限ID(主键)
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 应用ID
     */
    @TableField("app_id")
    private Long appId;

    /**
     * 接口ID
     */
    @TableField("interface_id")
    private Long interfaceId;

    /**
     * 权限状态:0-已禁用,1-已启用
     */
    @TableField("permission_status")
    private Integer permissionStatus;

    /**
     * 权限过期时间(NULL表示永久)
     */
    @TableField("expire_time")
    private LocalDateTime expireTime;

    /**
     * 授权时间
     */
    @TableField("grant_time")
    private LocalDateTime grantTime;

    /**
     * 授权人ID
     */
    @TableField("grant_by")
    private Long grantBy;

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
