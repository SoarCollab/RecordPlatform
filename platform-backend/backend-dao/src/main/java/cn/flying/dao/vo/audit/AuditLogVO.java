package cn.flying.dao.vo.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 审计日志VO - 前端展示用
 */
@Data
@Builder
@Schema(description = "审计日志VO")
public class AuditLogVO {

    @Schema(description = "日志ID")
    private String id;

    @Schema(description = "用户ID")
    private String userId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "操作类型")
    private String action;

    @Schema(description = "操作模块")
    private String module;

    @Schema(description = "目标ID")
    private String targetId;

    @Schema(description = "目标类型")
    private String targetType;

    @Schema(description = "详情")
    private String detail;

    @Schema(description = "IP地址")
    private String ip;

    @Schema(description = "User-Agent")
    private String userAgent;

    @Schema(description = "状态 (0=成功, 1=失败)")
    private Integer status;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "执行时长(ms)")
    private Long duration;

    @Schema(description = "创建时间")
    private String createTime;
}
