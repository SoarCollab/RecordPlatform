package cn.flying.identity.dto.apigateway;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 网关服务注册实体类
 * 注册后端服务实例,用于负载均衡
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Data
@TableName("gateway_service")
public class GatewayService {

    /**
     * 服务ID(主键)
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 服务名称
     */
    @TableField("service_name")
    private String serviceName;

    /**
     * 服务主机地址
     */
    @TableField("service_host")
    private String serviceHost;

    /**
     * 服务端口
     */
    @TableField("service_port")
    private Integer servicePort;

    /**
     * 服务权重(用于加权负载均衡)
     */
    @TableField("service_weight")
    private Integer serviceWeight;

    /**
     * 服务状态:0-已下线,1-已上线,2-维护中
     */
    @TableField("service_status")
    private Integer serviceStatus;

    /**
     * 健康检查URL
     */
    @TableField("health_check_url")
    private String healthCheckUrl;

    /**
     * 最后健康检查时间
     */
    @TableField("last_health_check_time")
    private LocalDateTime lastHealthCheckTime;

    /**
     * 健康检查状态:0-异常,1-正常
     */
    @TableField("health_check_status")
    private Integer healthCheckStatus;

    /**
     * 服务元数据(JSON)
     */
    @TableField("service_metadata")
    private String serviceMetadata;

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
