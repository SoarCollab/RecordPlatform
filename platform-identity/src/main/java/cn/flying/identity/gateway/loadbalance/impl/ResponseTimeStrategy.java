package cn.flying.identity.gateway.loadbalance.impl;

import cn.flying.identity.gateway.loadbalance.LoadBalanceStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 响应时间加权负载均衡策略
 * 根据服务实例的平均响应时间动态调整权重，响应时间越短权重越高
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component("responseTimeStrategy")
public class ResponseTimeStrategy implements LoadBalanceStrategy {

    /**
     * 默认响应时间（毫秒）
     */
    private static final double DEFAULT_RESPONSE_TIME = 100.0;

    /**
     * 最大响应时间（毫秒）
     */
    private static final double MAX_RESPONSE_TIME = 10000.0;

    @Override
    public ServiceInstance select(List<ServiceInstance> serviceInstances, String requestKey) {
        if (serviceInstances == null || serviceInstances.isEmpty()) {
            return null;
        }

        // 过滤健康的实例
        List<ServiceInstance> healthyInstances = serviceInstances.stream()
                .filter(ServiceInstance::isHealthy)
                .collect(Collectors.toList());

        if (healthyInstances.isEmpty()) {
            log.warn("没有健康的服务实例可用");
            return null;
        }

        // 如果只有一个实例，直接返回
        if (healthyInstances.size() == 1) {
            return healthyInstances.getFirst();
        }

        // 计算动态权重
        calculateDynamicWeights(healthyInstances);

        // 根据动态权重选择实例
        return selectByWeight(healthyInstances);
    }

    /**
     * 计算动态权重
     * 响应时间越短，权重越高
     *
     * @param instances 服务实例列表
     */
    private void calculateDynamicWeights(List<ServiceInstance> instances) {
        // 找出最小响应时间
        double minResponseTime = instances.stream()
                .mapToDouble(instance -> {
                    double rt = instance.getAvgResponseTime();
                    return rt > 0 ? rt : DEFAULT_RESPONSE_TIME;
                })
                .min()
                .orElse(DEFAULT_RESPONSE_TIME);

        // 基于响应时间计算动态权重
        for (ServiceInstance instance : instances) {
            double responseTime = instance.getAvgResponseTime();
            if (responseTime <= 0) {
                responseTime = DEFAULT_RESPONSE_TIME;
            }

            // 限制最大响应时间
            responseTime = Math.min(responseTime, MAX_RESPONSE_TIME);

            // 计算权重：响应时间越短，权重越高
            // 使用反比例函数：weight = (minResponseTime / responseTime) * baseWeight
            double dynamicWeight = (minResponseTime / responseTime) * instance.getWeight();

            // 确保权重至少为1
            int finalWeight = Math.max(1, (int) Math.round(dynamicWeight));

            log.debug("实例 {} 响应时间: {}ms, 基础权重: {}, 动态权重: {}",
                    instance.getInstanceId(), responseTime, instance.getWeight(), finalWeight);

            // 临时存储动态权重（实际应该有专门的字段）
            instance.setWeight(finalWeight);
        }
    }

    /**
     * 根据权重选择实例
     *
     * @param instances 服务实例列表
     * @return 选中的实例
     */
    private ServiceInstance selectByWeight(List<ServiceInstance> instances) {
        int totalWeight = instances.stream()
                .mapToInt(ServiceInstance::getWeight)
                .sum();

        int randomWeight = (int) (Math.random() * totalWeight);
        int weightSum = 0;

        for (ServiceInstance instance : instances) {
            weightSum += instance.getWeight();
            if (randomWeight < weightSum) {
                log.debug("响应时间加权选择服务实例: {}, 平均响应时间: {}ms",
                        instance, instance.getAvgResponseTime());
                return instance;
            }
        }

        return instances.getFirst();
    }

    @Override
    public String getStrategyName() {
        return "ResponseTime";
    }
}