package cn.flying.identity.dto.apigateway;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 网关路由配置实体类
 * 配置网关的动态路由规则
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Data
@TableName("gateway_route")
public class GatewayRoute {

    /**
     * 路由ID(主键)
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 路由名称
     */
    @TableField("route_name")
    private String routeName;

    /**
     * 路由标识码(唯一)
     */
    @TableField("route_code")
    private String routeCode;

    /**
     * 路由路径(支持通配符)
     */
    @TableField("route_path")
    private String routePath;

    /**
     * HTTP方法(NULL表示全部)
     */
    @TableField("route_method")
    private String routeMethod;

    /**
     * 目标服务名称
     */
    @TableField("target_service")
    private String targetService;

    /**
     * 目标路径
     */
    @TableField("target_path")
    private String targetPath;

    /**
     * 负载均衡策略:ROUND_ROBIN,WEIGHTED_ROUND_ROBIN,LEAST_CONNECTIONS,CONSISTENT_HASH
     */
    @TableField("load_balance_strategy")
    private String loadBalanceStrategy;

    /**
     * 路由优先级(数值越小优先级越高)
     */
    @TableField("route_order")
    private Integer routeOrder;

    /**
     * 是否去除前缀:0-否,1-是
     */
    @TableField("is_strip_prefix")
    private Integer isStripPrefix;

    /**
     * 超时时间(毫秒)
     */
    @TableField("timeout")
    private Integer timeout;

    /**
     * 重试次数
     */
    @TableField("retry_times")
    private Integer retryTimes;

    /**
     * 路由状态:0-已禁用,1-已启用
     */
    @TableField("route_status")
    private Integer routeStatus;

    /**
     * 路由元数据(JSON)
     */
    @TableField("route_metadata")
    private String routeMetadata;

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
