package cn.flying.identity.gateway.loadbalance;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 负载均衡策略接口
 * 定义负载均衡算法的标准接口
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public interface LoadBalanceStrategy {

    /**
     * 选择目标服务实例
     *
     * @param serviceInstances 可用的服务实例列表
     * @param requestKey       请求标识（用于一致性哈希等算法）
     * @return 选中的服务实例
     */
    ServiceInstance select(List<ServiceInstance> serviceInstances, String requestKey);

    /**
     * 获取策略名称
     *
     * @return 策略名称
     */
    String getStrategyName();

    /**
     * 服务实例定义
     */
    @Setter
    @Getter
    class ServiceInstance {
        // Getters and Setters
        private String instanceId;
        private String host;
        private int port;
        private int weight;  // 权重
        private boolean healthy;  // 健康状态
        private long activeConnections;  // 活跃连接数
        private double avgResponseTime;  // 平均响应时间
        private long lastUpdateTime;  // 最后更新时间
        private java.util.Map<String, String> metadata;  // 元数据

        // 无参构造函数
        public ServiceInstance() {
            this.weight = 100;  // 默认权重
            this.healthy = true;
            this.activeConnections = 0;
            this.avgResponseTime = 0;
            this.lastUpdateTime = System.currentTimeMillis();
            this.metadata = new java.util.HashMap<>();
        }

        // 带参构造函数
        public ServiceInstance(String instanceId, String host, int port) {
            this();
            this.instanceId = instanceId;
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return String.format("ServiceInstance{id='%s', url='%s', weight=%d, healthy=%s}",
                    instanceId, getUrl(), weight, healthy);
        }

        public String getUrl() {
            return "http://" + host + ":" + port;
        }
    }
}