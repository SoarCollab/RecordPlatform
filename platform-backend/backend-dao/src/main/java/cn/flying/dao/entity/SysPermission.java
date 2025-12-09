package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 权限定义实体
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("sys_permission")
@Schema(name = "SysPermission", description = "权限定义实体")
public class SysPermission implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "权限ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID（0表示全局权限）")
    private Long tenantId;

    @Schema(description = "权限码，格式：module:action")
    private String code;

    @Schema(description = "权限名称")
    private String name;

    @Schema(description = "模块名：file, ticket, announcement, system等")
    private String module;

    @Schema(description = "操作类型：read, write, delete, admin等")
    private String action;

    @Schema(description = "权限描述")
    private String description;

    @Schema(description = "状态：0-禁用，1-启用")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private Date updateTime;
}
