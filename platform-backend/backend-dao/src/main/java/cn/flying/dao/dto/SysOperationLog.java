package cn.flying.dao.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统操作日志实体类
 */
@Data
@TableName("sys_operation_log")
public class SysOperationLog {
    
    /**
     * 日志ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /**
     * 操作模块
     */
    private String module;
    
    /**
     * 操作类型
     */
    private String operationType;
    
    /**
     * 操作描述
     */
    private String description;
    
    /**
     * 请求方法
     */
    private String method;
    
    /**
     * 请求URL
     */
    private String requestUrl;
    
    /**
     * 请求方式
     */
    private String requestMethod;
    
    /**
     * 请求IP
     */
    private String requestIp;
    
    /**
     * 请求参数
     */
    private String requestParam;
    
    /**
     * 响应结果
     */
    private String responseResult;
    
    /**
     * 操作状态（0正常 1异常）
     */
    private Integer status;
    
    /**
     * 错误信息
     */
    private String errorMsg;
    
    /**
     * 操作用户ID
     */
    private String userId;
    
    /**
     * 操作用户名
     */
    private String username;
    
    /**
     * 操作时间
     */
    private LocalDateTime operationTime;
    
    /**
     * 执行时长（毫秒）
     */
    private Long executionTime;
} 