package cn.flying.dao.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统操作日志视图对象
 */
@Data
@Schema(description = "系统操作日志视图对象")
public class SysOperationLogVO {
    
    /**
     * 日志ID
     */
    @Schema(description = "日志ID")
    private Long id;
    
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
     * 操作描述
     */
    @Schema(description = "操作描述")
    private String description;
    
    /**
     * 请求方法
     */
    @Schema(description = "请求方法")
    private String method;
    
    /**
     * 请求URL
     */
    @Schema(description = "请求URL")
    private String requestUrl;
    
    /**
     * 请求方式
     */
    @Schema(description = "请求方式")
    private String requestMethod;
    
    /**
     * 请求IP
     */
    @Schema(description = "请求IP")
    private String requestIp;
    
    /**
     * 操作状态（0正常 1异常）
     */
    @Schema(description = "操作状态（0正常 1异常）")
    private Integer status;
    
    /**
     * 操作用户名
     */
    @Schema(description = "操作用户名")
    private String username;
    
    /**
     * 操作时间
     */
    @Schema(description = "操作时间")
    private LocalDateTime operationTime;
    
    /**
     * 执行时长（毫秒）
     */
    @Schema(description = "执行时长（毫秒）")
    private Long executionTime;
} 