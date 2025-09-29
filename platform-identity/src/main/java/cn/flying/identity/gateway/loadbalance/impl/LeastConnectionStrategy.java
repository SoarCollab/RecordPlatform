package cn.flying.identity.gateway.loadbalance.impl;

import cn.flying.identity.gateway.loadbalance.LoadBalanceStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 最少连接数负载均衡策略
 * 选择当前活跃连接数最少的服务实例
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component("leastConnectionStrategy")
public class LeastConnectionStrategy implements LoadBalanceStrategy {

    @Override
    public ServiceInstance select(List<ServiceInstance> serviceInstances, String requestKey) {
        if (serviceInstances == null || serviceInstances.isEmpty()) {
            return null;
        }

        // 过滤健康的实例
        List<ServiceInstance> healthyInstances = serviceInstances.stream()
                .filter(ServiceInstance::isHealthy)
                .toList();

        if (healthyInstances.isEmpty()) {
            log.warn("没有健康的服务实例可用");
            return null;
        }

        // 如果只有一个实例，直接返回
        if (healthyInstances.size() == 1) {
            return healthyInstances.getFirst();
        }

        // 按照活跃连接数升序排序，考虑权重
        ServiceInstance selected = healthyInstances.stream()
                .min(Comparator.comparingDouble(instance ->
                    (double) instance.getActiveConnections() / instance.getWeight()))
                .orElse(healthyInstances.getFirst());

        log.debug("最少连接选择服务实例: {}, 活跃连接数: {}",
                selected, selected.getActiveConnections());

        return selected;
    }

    @Override
    public String getStrategyName() {
        return "LeastConnection";
    }
}