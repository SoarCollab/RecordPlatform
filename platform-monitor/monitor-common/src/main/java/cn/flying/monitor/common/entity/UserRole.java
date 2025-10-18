package cn.flying.monitor.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * 用户角色关联实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_roles")
public class UserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 角色ID
     */
    @TableField("role_id")
    private Long roleId;

    /**
     * 分配时间
     */
    @TableField(value = "assigned_at", fill = FieldFill.INSERT)
    private Instant assignedAt;

    /**
     * 分配者ID
     */
    @TableField("assigned_by")
    private Long assignedBy;

    /**
     * 逻辑删除标记
     */
    @TableLogic
    @TableField("deleted")
    private Boolean deleted;
}