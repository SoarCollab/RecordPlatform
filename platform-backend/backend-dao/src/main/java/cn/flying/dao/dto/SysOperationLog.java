package cn.flying.dao.dto;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统操作日志实体类
 */
@Data
@TableName("sys_operation_log")
@Schema(name = "sys_operation_log", description = "系统操作日志实体类")
public class SysOperationLog {
    
    /**
     * 日志ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "日志ID")
    private Long id;
    
    /**
     * 租户ID
     */
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;
    
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
     * 请求参数
     */
    @Schema(description = "请求参数")
    private String requestParam;
    
    /**
     * 响应结果
     */
    @Schema(description = "响应结果")
    private String responseResult;
    
    /**
     * 操作状态（0正常 1异常）
     */
    @Schema(description = "操作状态（0正常 1异常）")
    private Integer status;
    
    /**
     * 错误信息
     */
    @Schema(description = "错误信息")
    private String errorMsg;
    
    /**
     * 操作用户ID
     */
    @Schema(description = "操作用户ID")
    private String userId;
    
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