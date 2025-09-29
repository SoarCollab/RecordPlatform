package cn.flying.identity.dto.apigateway;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API接口定义实体类
 * 定义系统中所有可供开放的API接口
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Data
@TableName("api_interface")
public class ApiInterface {

    /**
     * 接口ID(主键)
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 接口名称
     */
    @TableField("interface_name")
    private String interfaceName;

    /**
     * 接口标识码(唯一)
     */
    @TableField("interface_code")
    private String interfaceCode;

    /**
     * 接口路径
     */
    @TableField("interface_path")
    private String interfacePath;

    /**
     * HTTP方法:GET,POST,PUT,DELETE等
     */
    @TableField("interface_method")
    private String interfaceMethod;

    /**
     * 接口描述
     */
    @TableField("interface_description")
    private String interfaceDescription;

    /**
     * 接口分类
     */
    @TableField("interface_category")
    private String interfaceCategory;

    /**
     * 后端服务名称
     */
    @TableField("service_name")
    private String serviceName;

    /**
     * 请求参数定义(JSON)
     */
    @TableField("request_params")
    private String requestParams;

    /**
     * 响应示例(JSON)
     */
    @TableField("response_example")
    private String responseExample;

    /**
     * 是否需要认证:0-否,1-是
     */
    @TableField("is_auth_required")
    private Integer isAuthRequired;

    /**
     * 限流次数(每分钟)
     */
    @TableField("rate_limit")
    private Integer rateLimit;

    /**
     * 超时时间(毫秒)
     */
    @TableField("timeout")
    private Integer timeout;

    /**
     * 接口状态:0-已下线,1-已上线,2-维护中
     */
    @TableField("interface_status")
    private Integer interfaceStatus;

    /**
     * 接口版本
     */
    @TableField("version")
    private String version;

    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除:0-未删除,1-已删除
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
