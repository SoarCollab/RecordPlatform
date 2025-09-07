package cn.flying.dao.vo.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 审计日志查询参数VO
 */
@Data
@Schema(description = "审计日志查询参数VO")
public class AuditLogQueryVO {
    
    /**
     * 当前页码
     */
    @Schema(description = "当前页码")
    private Integer pageNum = 1;
    
    /**
     * 每页记录数
     */
    @Schema(description = "每页记录数")
    private Integer pageSize = 10;
    
    /**
     * 用户ID
     */
    @Schema(description = "用户ID")
    private String userId;
    
    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String username;
    
    /**
     * 操作模块
     */
    @Schema(description = "操作模块")
    private String module;
    
    /**
     * 操作类型
     */
    @Schema(description = "操作类型")
    private String operationType;
    
    /**
     * 操作状态（0正常 1异常）
     */
    @Schema(description = "操作状态（0正常 1异常）")
    private Integer status;
    
    /**
     * 请求IP
     */
    @Schema(description = "请求IP")
    private String requestIp;
    
    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private String startTime;
    
    /**
     * 结束时间
     */
    @Schema(description = "结束时间")
    private String endTime;
} 