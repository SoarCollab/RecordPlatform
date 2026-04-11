package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * Permission view object for external API responses.
 * Excludes internal fields (tenantId).
 */
@Schema(description = "权限定义")
public record SysPermissionVO(
        @Schema(description = "权限ID（外部ID）")
        String id,
        @Schema(description = "权限码")
        String code,
        @Schema(description = "权限名称")
        String name,
        @Schema(description = "模块名")
        String module,
        @Schema(description = "操作类型")
        String action,
        @Schema(description = "权限描述")
        String description,
        @Schema(description = "状态：0-禁用，1-启用")
        Integer status,
        @Schema(description = "创建时间")
        Date createTime,
        @Schema(description = "更新时间")
        Date updateTime
) {
}
