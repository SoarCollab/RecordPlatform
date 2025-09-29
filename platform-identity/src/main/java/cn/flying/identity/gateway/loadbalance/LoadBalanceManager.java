package cn.flying.identity.gateway.loadbalance;

import cn.flying.identity.gateway.discovery.NacosServiceDiscovery;
import cn.flying.identity.gateway.loadbalance.impl.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 负载均衡管理器
 * 管理所有负载均衡策略，提供统一的调用接口
 * <p>
 * 核心功能：
 * 1. 策略注册和管理
 * 2. 动态切换策略
 * 3. 服务实例健康管理
 * 4. 负载均衡统计
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component
public class LoadBalanceManager {

    /**
     * 默认策略
     */
    private static final String DEFAULT_STRATEGY = "roundRobin";

    /**
     * 策略映射
     */
    private final Map<String, LoadBalanceStrategy> strategyMap = new HashMap<>();

    /**
     * 服务实例缓存
     */
    private final Map<String, List<LoadBalanceStrategy.ServiceInstance>> serviceInstanceCache = new ConcurrentHashMap<>();

    @Resource
    private RoundRobinStrategy roundRobinStrategy;

    @Resource
    private RandomStrategy randomStrategy;

    @Resource
    private LeastConnectionStrategy leastConnectionStrategy;

    @Resource
    private ConsistentHashStrategy consistentHashStrategy;

    @Resource
    private ResponseTimeStrategy responseTimeStrategy;

    @Resource
    private NacosServiceDiscovery nacosServiceDiscovery;

    /**
     * 初始化策略映射
     */
    @PostConstruct
    public void init() {
        // 注册所有策略
        registerStrategy("roundRobin", roundRobinStrategy);
        registerStrategy("random", randomStrategy);
        registerStrategy("leastConnection", leastConnectionStrategy);
        registerStrategy("consistentHash", consistentHashStrategy);
        registerStrategy("responseTime", responseTimeStrategy);

        log.info("负载均衡管理器初始化完成，注册策略数量: {}", strategyMap.size());
    }

    /**
     * 注册负载均衡策略
     *
     * @param name     策略名称
     * @param strategy 策略实现
     */
    public void registerStrategy(String name, LoadBalanceStrategy strategy) {
        strategyMap.put(name, strategy);
        log.info("注册负载均衡策略: {} -> {}", name, strategy.getStrategyName());
    }

    /**
     * 选择服务实例
     *
     * @param serviceName  服务名称
     * @param strategyName 策略名称
     * @param requestKey   请求标识（用于一致性哈希）
     * @return 选中的服务实例
     */
    public LoadBalanceStrategy.ServiceInstance selectInstance(String serviceName, String strategyName, String requestKey) {
        // 获取策略
        LoadBalanceStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            log.warn("未找到负载均衡策略: {}，使用默认策略: {}", strategyName, DEFAULT_STRATEGY);
            strategy = strategyMap.get(DEFAULT_STRATEGY);
        }

        // 获取服务实例列表
        List<LoadBalanceStrategy.ServiceInstance> instances = getServiceInstances(serviceName);
        if (instances == null || instances.isEmpty()) {
            log.warn("服务 {} 没有可用的实例", serviceName);
            return null;
        }

        // 选择实例
        LoadBalanceStrategy.ServiceInstance selected = strategy.select(instances, requestKey);

        // 更新统计信息
        if (selected != null) {
            updateStatistics(serviceName, selected, strategyName);
        }

        return selected;
    }

    /**
     * 获取服务实例列表
     *
     * @param serviceName 服务名称
     * @return 实例列表
     */
    private List<LoadBalanceStrategy.ServiceInstance> getServiceInstances(String serviceName) {
        // 从缓存获取
        List<LoadBalanceStrategy.ServiceInstance> instances = serviceInstanceCache.get(serviceName);

        // 如果缓存为空，从Nacos注册中心获取
        if (instances == null || instances.isEmpty()) {
            log.debug("缓存中没有服务实例: {}，从Nacos注册中心获取", serviceName);

            try {
                // 从Nacos获取服务实例
                List<NacosServiceDiscovery.ServiceInstance> nacosInstances =
                        nacosServiceDiscovery.getServiceInstances(serviceName);

                if (nacosInstances != null && !nacosInstances.isEmpty()) {
                    // 转换为负载均衡实例格式
                    instances = nacosInstances.stream()
                            .filter(NacosServiceDiscovery.ServiceInstance::isHealthy)
                            .map(this::convertToLoadBalanceInstance)
                            .collect(Collectors.toList());

                    // 更新缓存
                    if (!instances.isEmpty()) {
                        serviceInstanceCache.put(serviceName, instances);
                        log.info("从Nacos获取服务实例成功: {}, 数量: {}", serviceName, instances.size());
                    }
                }
            } catch (Exception e) {
                log.error("从Nacos获取服务实例失败: {}", serviceName, e);
            }
        }

        return instances;
    }

    /**
     * 更新统计信息
     *
     * @param serviceName  服务名称
     * @param instance     选中的实例
     * @param strategyName 使用的策略
     */
    private void updateStatistics(String serviceName, LoadBalanceStrategy.ServiceInstance instance, String strategyName) {
        // 增加活跃连接数
        instance.setActiveConnections(instance.getActiveConnections() + 1);

        log.debug("负载均衡选择: service={}, instance={}, strategy={}, activeConnections={}",
                serviceName, instance.getInstanceId(), strategyName, instance.getActiveConnections());
    }

    /**
     * 转换Nacos实例为负载均衡实例
     *
     * @param nacosInstance Nacos实例
     * @return 负载均衡实例
     */
    private LoadBalanceStrategy.ServiceInstance convertToLoadBalanceInstance(
            NacosServiceDiscovery.ServiceInstance nacosInstance) {
        LoadBalanceStrategy.ServiceInstance lbInstance = new LoadBalanceStrategy.ServiceInstance();
        lbInstance.setInstanceId(nacosInstance.getInstanceId());
        lbInstance.setHost(nacosInstance.getHost());
        lbInstance.setPort(nacosInstance.getPort());
        lbInstance.setWeight((int) nacosInstance.getWeight());
        lbInstance.setHealthy(nacosInstance.isHealthy());
        lbInstance.setMetadata(nacosInstance.getMetadata());
        lbInstance.setActiveConnections(0);
        lbInstance.setAvgResponseTime(0.0);
        lbInstance.setLastUpdateTime(System.currentTimeMillis());
        return lbInstance;
    }

    /**
     * 根据负载均衡类型码选择策略
     *
     * @param loadBalanceType 负载均衡类型：1-轮询，2-随机，3-最少连接，4-一致性哈希，5-响应时间
     * @return 策略名称
     */
    public String getStrategyByType(Integer loadBalanceType) {
        if (loadBalanceType == null) {
            return DEFAULT_STRATEGY;
        }

        return switch (loadBalanceType) {
            case 1 -> "roundRobin";
            case 2 -> "random";
            case 3 -> "leastConnection";
            case 4 -> "consistentHash";
            case 5 -> "responseTime";
            default -> DEFAULT_STRATEGY;
        };
    }

    /**
     * 更新服务实例列表
     *
     * @param serviceName 服务名称
     * @param instances   实例列表
     */
    public void updateServiceInstances(String serviceName, List<LoadBalanceStrategy.ServiceInstance> instances) {
        serviceInstanceCache.put(serviceName, instances);
        log.debug("更新服务实例: {}, 数量: {}", serviceName, instances.size());
    }

    /**
     * 标记服务实例为不健康
     * 使用线程安全的方式更新实例状态
     *
     * @param serviceName 服务名称
     * @param instanceId  实例ID
     */
    public void markInstanceUnhealthy(String serviceName, String instanceId) {
        serviceInstanceCache.compute(serviceName, (k, instances) -> {
            if (instances == null) {
                return null;
            }
            // 创建新的列表，避免并发修改
            List<LoadBalanceStrategy.ServiceInstance> newInstances = new CopyOnWriteArrayList<>();
            for (LoadBalanceStrategy.ServiceInstance instance : instances) {
                if (instance.getInstanceId().equals(instanceId)) {
                    // 创建新的实例对象，避免直接修改共享对象
                    LoadBalanceStrategy.ServiceInstance newInstance = cloneInstance(instance);
                    newInstance.setHealthy(false);
                    newInstances.add(newInstance);
                    log.warn("标记服务实例为不健康: {} -> {}", serviceName, instanceId);
                } else {
                    newInstances.add(instance);
                }
            }
            return newInstances;
        });
    }

    /**
     * 克隆服务实例
     * 创建实例的深拷贝，避免并发修改问题
     *
     * @param instance 原始实例
     * @return 克隆的实例
     */
    private LoadBalanceStrategy.ServiceInstance cloneInstance(LoadBalanceStrategy.ServiceInstance instance) {
        LoadBalanceStrategy.ServiceInstance newInstance = new LoadBalanceStrategy.ServiceInstance();
        newInstance.setInstanceId(instance.getInstanceId());
        newInstance.setHost(instance.getHost());
        newInstance.setPort(instance.getPort());
        newInstance.setWeight(instance.getWeight());
        newInstance.setHealthy(instance.isHealthy());
        newInstance.setMetadata(new HashMap<>(instance.getMetadata() != null ? instance.getMetadata() : new HashMap<>()));
        newInstance.setActiveConnections(instance.getActiveConnections());
        newInstance.setAvgResponseTime(instance.getAvgResponseTime());
        newInstance.setLastUpdateTime(instance.getLastUpdateTime());
        return newInstance;
    }

    /**
     * 标记服务实例为健康
     * 使用线程安全的方式更新实例状态
     *
     * @param serviceName 服务名称
     * @param instanceId  实例ID
     */
    public void markInstanceHealthy(String serviceName, String instanceId) {
        serviceInstanceCache.compute(serviceName, (k, instances) -> {
            if (instances == null) {
                return null;
            }
            // 创建新的列表，避免并发修改
            List<LoadBalanceStrategy.ServiceInstance> newInstances = new CopyOnWriteArrayList<>();
            for (LoadBalanceStrategy.ServiceInstance instance : instances) {
                if (instance.getInstanceId().equals(instanceId)) {
                    // 创建新的实例对象，避免直接修改共享对象
                    LoadBalanceStrategy.ServiceInstance newInstance = cloneInstance(instance);
                    newInstance.setHealthy(true);
                    newInstances.add(newInstance);
                    log.info("标记服务实例为健康: {} -> {}", serviceName, instanceId);
                } else {
                    newInstances.add(instance);
                }
            }
            return newInstances;
        });
    }

    /**
     * 更新实例统计信息
     * 使用线程安全的方式更新统计数据
     *
     * @param serviceName  服务名称
     * @param instanceId   实例ID
     * @param responseTime 响应时间
     * @param success      是否成功
     */
    public void updateInstanceStats(String serviceName, String instanceId, long responseTime, boolean success) {
        serviceInstanceCache.compute(serviceName, (k, instances) -> {
            if (instances == null) {
                return null;
            }
            // 创建新的列表，避免并发修改
            List<LoadBalanceStrategy.ServiceInstance> newInstances = new CopyOnWriteArrayList<>();
            for (LoadBalanceStrategy.ServiceInstance instance : instances) {
                if (instance.getInstanceId().equals(instanceId)) {
                    // 创建新的实例对象，避免直接修改共享对象
                    LoadBalanceStrategy.ServiceInstance newInstance = cloneInstance(instance);

                    // 更新平均响应时间（使用滑动窗口算法）
                    double currentAvg = newInstance.getAvgResponseTime();
                    double newAvg = (currentAvg * 0.9) + (responseTime * 0.1);
                    newInstance.setAvgResponseTime(newAvg);

                    // 更新活跃连接数
                    if (success) {
                        long activeConnections = Math.max(0, newInstance.getActiveConnections() - 1);
                        newInstance.setActiveConnections(activeConnections);
                    }

                    newInstance.setLastUpdateTime(System.currentTimeMillis());
                    newInstances.add(newInstance);
                } else {
                    newInstances.add(instance);
                }
            }
            return newInstances;
        });
    }

    /**
     * 获取所有可用的策略名称
     *
     * @return 策略名称列表
     */
    public List<String> getAvailableStrategies() {
        return strategyMap.keySet().stream().toList();
    }

    /**
     * 获取服务实例统计信息
     *
     * @param serviceName 服务名称
     * @return 统计信息
     */
    public Map<String, Object> getServiceStatistics(String serviceName) {
        Map<String, Object> stats = new HashMap<>();
        List<LoadBalanceStrategy.ServiceInstance> instances = serviceInstanceCache.get(serviceName);

        if (instances != null) {
            stats.put("totalInstances", instances.size());
            stats.put("healthyInstances", instances.stream().filter(LoadBalanceStrategy.ServiceInstance::isHealthy).count());
            stats.put("totalActiveConnections", instances.stream().mapToLong(LoadBalanceStrategy.ServiceInstance::getActiveConnections).sum());
            stats.put("avgResponseTime", instances.stream().mapToDouble(LoadBalanceStrategy.ServiceInstance::getAvgResponseTime).average().orElse(0));
        }

        return stats;
    }
}