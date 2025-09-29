package cn.flying.identity.dto.apigateway;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API调用日志实体类
 * 记录所有API调用的详细日志
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Data
@TableName("api_call_log")
public class ApiCallLog {

    /**
     * 日志ID(主键)
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 请求ID(唯一)
     */
    @TableField("request_id")
    private String requestId;

    /**
     * 应用ID
     */
    @TableField("app_id")
    private Long appId;

    /**
     * API密钥
     */
    @TableField("api_key")
    private String apiKey;

    /**
     * 接口ID
     */
    @TableField("interface_id")
    private Long interfaceId;

    /**
     * 接口路径
     */
    @TableField("interface_path")
    private String interfacePath;

    /**
     * 请求方法
     */
    @TableField("request_method")
    private String requestMethod;

    /**
     * 请求参数
     */
    @TableField("request_params")
    private String requestParams;

    /**
     * 请求IP
     */
    @TableField("request_ip")
    private String requestIp;

    /**
     * 请求时间
     */
    @TableField("request_time")
    private LocalDateTime requestTime;

    /**
     * 响应状态码
     */
    @TableField("response_code")
    private Integer responseCode;

    /**
     * 响应耗时(毫秒)
     */
    @TableField("response_time")
    private Integer responseTime;

    /**
     * 响应大小(字节)
     */
    @TableField("response_size")
    private Long responseSize;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 用户代理
     */
    @TableField("user_agent")
    private String userAgent;

    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
