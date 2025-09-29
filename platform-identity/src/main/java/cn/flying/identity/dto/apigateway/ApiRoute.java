package cn.flying.identity.dto.apigateway;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API路由配置实体
 * 用于动态路由管理
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Data
@TableName("api_route")
public class ApiRoute {

    /**
     * 路由ID
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 路由名称
     */
    private String routeName;

    /**
     * 路由路径（前端请求路径）
     */
    private String routePath;

    /**
     * 目标服务名称
     */
    private String targetService;

    /**
     * 服务名称（与targetService相同，提供别名以兼容）
     */
    public String getServiceName() {
        return targetService;
    }

    /**
     * 设置服务名称
     */
    public void setServiceName(String serviceName) {
        this.targetService = serviceName;
    }

    /**
     * 目标路径（转发到的实际路径）
     */
    private String targetPath;

    /**
     * 路由类型：1-精确匹配，2-前缀匹配，3-正则匹配
     */
    private Integer routeType;

    /**
     * HTTP方法（GET,POST,PUT,DELETE,*表示所有）
     */
    private String httpMethod;

    /**
     * 路由优先级（数值越小优先级越高）
     */
    private Integer priority;

    /**
     * 是否启用：0-禁用，1-启用
     */
    private Integer routeStatus;

    /**
     * 是否需要认证：0-否，1-是
     */
    private Integer requireAuth;

    /**
     * 是否启用限流：0-否，1-是
     */
    private Integer enableRateLimit;

    /**
     * 限流配置（QPS）
     */
    private Integer rateLimit;

    /**
     * 负载均衡策略：1-轮询，2-随机，3-最少连接，4-一致性哈希
     */
    private Integer loadBalance;

    /**
     * 超时时间（毫秒）
     */
    private Integer timeout;

    /**
     * 重试次数
     */
    private Integer retryTimes;

    /**
     * 路由元数据（JSON格式）
     */
    private String metadata;

    /**
     * 路由描述
     */
    private String routeDescription;

    /**
     * 获取描述（提供description别名）
     */
    public String getDescription() {
        return routeDescription;
    }

    /**
     * 设置描述
     */
    public void setDescription(String description) {
        this.routeDescription = description;
    }

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}