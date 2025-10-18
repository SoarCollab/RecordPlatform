package cn.flying.monitor.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 角色实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("roles")
public class Role {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 角色名称
     */
    @TableField("name")
    private String name;

    /**
     * 角色代码
     */
    @TableField("code")
    private String code;

    /**
     * 角色描述
     */
    @TableField("description")
    private String description;

    /**
     * 角色状态
     */
    @TableField("status")
    private RoleStatus status;

    /**
     * 是否系统内置角色
     */
    @TableField("is_system")
    private Boolean isSystem;

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
     * 角色权限列表（非数据库字段）
     */
    @TableField(exist = false)
    private List<String> permissions;

    /**
     * 角色状态枚举
     */
    public enum RoleStatus {
        ACTIVE("ACTIVE", "活跃"),
        INACTIVE("INACTIVE", "非活跃");

        private final String code;
        private final String description;

        RoleStatus(String code, String description) {
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
     * 检查角色是否活跃
     */
    public boolean isActive() {
        return status == RoleStatus.ACTIVE;
    }
}