package cn.flying.identity.gateway.loadbalance.impl;

import cn.flying.identity.gateway.loadbalance.LoadBalanceStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 加权随机负载均衡策略
 * 根据服务实例的权重进行随机选择
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component("randomStrategy")
public class RandomStrategy implements LoadBalanceStrategy {

    private final Random random = new Random();

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

        // 计算总权重
        int totalWeight = healthyInstances.stream()
                .mapToInt(ServiceInstance::getWeight)
                .sum();

        // 加权随机算法
        int randomWeight = random.nextInt(totalWeight);
        int weightSum = 0;

        for (ServiceInstance instance : healthyInstances) {
            weightSum += instance.getWeight();
            if (randomWeight < weightSum) {
                log.debug("随机选择服务实例: {}", instance);
                return instance;
            }
        }

        // 兜底：返回最后一个健康实例
        return healthyInstances.getLast();
    }

    @Override
    public String getStrategyName() {
        return "Random";
    }
}