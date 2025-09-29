package cn.flying.identity.dto.apigateway;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 网关插件配置实体类
 * 配置网关的各种插件(限流、熔断、认证等)
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Data
@TableName("gateway_plugin")
public class GatewayPlugin {

    /**
     * 插件ID(主键)
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 插件名称
     */
    @TableField("plugin_name")
    private String pluginName;

    /**
     * 插件类型:RATE_LIMIT,CIRCUIT_BREAKER,AUTH,TRANSFORM,LOG等
     */
    @TableField("plugin_type")
    private String pluginType;

    /**
     * 插件配置(JSON)
     */
    @TableField("plugin_config")
    private String pluginConfig;

    /**
     * 应用到路由(route_code,NULL表示全局)
     */
    @TableField("apply_to_route")
    private String applyToRoute;

    /**
     * 应用到服务(service_name)
     */
    @TableField("apply_to_service")
    private String applyToService;

    /**
     * 插件执行优先级
     */
    @TableField("plugin_order")
    private Integer pluginOrder;

    /**
     * 插件状态:0-已禁用,1-已启用
     */
    @TableField("plugin_status")
    private Integer pluginStatus;

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
