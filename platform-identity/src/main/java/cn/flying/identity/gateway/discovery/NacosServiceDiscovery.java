package cn.flying.identity.gateway.discovery;

import cn.flying.identity.gateway.loadbalance.LoadBalanceManager;
import cn.flying.identity.gateway.loadbalance.LoadBalanceStrategy;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Nacos服务发现组件
 * 负责从Nacos注册中心发现服务实例，并更新到负载均衡管理器
 *
 * 核心功能：
 * 1. 自动发现服务实例
 * 2. 监听服务变更
 * 3. 健康检查
 * 4. 实例缓存管理
 * 5. 定期同步
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component
public class NacosServiceDiscovery {

    /**
     * Nacos服务器地址
     */
    @Value("${nacos.server-addr:localhost:8848}")
    private String nacosServerAddr;

    /**
     * Nacos命名空间
     */
    @Value("${nacos.namespace:}")
    private String nacosNamespace;

    /**
     * Nacos分组
     */
    @Value("${nacos.group:DEFAULT_GROUP}")
    private String nacosGroup;

    /**
     * 服务发现启用标志
     */
    @Value("${api.gateway.discovery.enabled:true}")
    private boolean discoveryEnabled;

    /**
     * 服务实例同步间隔（秒）
     */
    @Value("${api.gateway.discovery.sync-interval:30}")
    private int syncInterval;

    /**
     * 需要监听的服务列表（逗号分隔）
     */
    @Value("${api.gateway.discovery.services:}")
    private String monitoredServices;

    /**
     * 自动发现所有服务
     */
    @Value("${api.gateway.discovery.auto-discover:true}")
    private boolean autoDiscover;

    @Resource
    private LoadBalanceManager loadBalanceManager;

    /**
     * Nacos命名服务
     */
    private NamingService namingService;

    /**
     * 服务实例缓存
     */
    private final Map<String, List<ServiceInstance>> serviceInstanceCache = new ConcurrentHashMap<>();

    /**
     * 服务监听器映射
     */
    private final Map<String, ServiceListener> serviceListeners = new ConcurrentHashMap<>();

    /**
     * 初始化Nacos连接
     */
    @PostConstruct
    public void init() {
        if (!discoveryEnabled) {
            log.info("Nacos服务发现已禁用");
            return;
        }

        try {
            // 创建Nacos配置
            Properties properties = new Properties();
            properties.setProperty("serverAddr", nacosServerAddr);
            if (nacosNamespace != null && !nacosNamespace.isEmpty()) {
                properties.setProperty("namespace", nacosNamespace);
            }

            // 创建命名服务
            namingService = NacosFactory.createNamingService(properties);
            log.info("Nacos服务发现初始化成功，服务器地址: {}", nacosServerAddr);

            // 初始化服务监听
            initServiceListeners();

            // 立即同步一次
            syncServiceInstances();

        } catch (NacosException e) {
            log.error("Nacos服务发现初始化失败", e);
        }
    }

    /**
     * 初始化服务监听器
     */
    private void initServiceListeners() {
        if (monitoredServices == null || monitoredServices.isEmpty()) {
            if (autoDiscover) {
                log.info("自动发现模式已启用，将监听所有服务");
                discoverAllServices();
            }
        } else {
            // 解析服务列表
            String[] services = monitoredServices.split(",");
            for (String service : services) {
                String serviceName = service.trim();
                if (!serviceName.isEmpty()) {
                    subscribeService(serviceName);
                }
            }
        }
    }

    /**
     * 发现所有服务
     */
    private void discoverAllServices() {
        try {
            // 获取所有服务列表
            List<String> services = namingService.getServicesOfServer(1, Integer.MAX_VALUE).getData();
            log.info("发现 {} 个服务", services.size());

            // 订阅所有服务
            for (String serviceName : services) {
                subscribeService(serviceName);
            }
        } catch (NacosException e) {
            log.error("发现服务失败", e);
        }
    }

    /**
     * 订阅服务变更
     *
     * @param serviceName 服务名称
     */
    public void subscribeService(String serviceName) {
        try {
            if (serviceListeners.containsKey(serviceName)) {
                log.debug("服务 {} 已订阅", serviceName);
                return;
            }

            // 创建服务监听器
            ServiceListener listener = new ServiceListener(serviceName);
            serviceListeners.put(serviceName, listener);

            // 订阅服务
            namingService.subscribe(serviceName, nacosGroup, listener);
            log.info("订阅服务: {}", serviceName);

            // 立即获取服务实例
            updateServiceInstances(serviceName);

        } catch (NacosException e) {
            log.error("订阅服务 {} 失败", serviceName, e);
        }
    }

    /**
     * 取消订阅服务
     *
     * @param serviceName 服务名称
     */
    public void unsubscribeService(String serviceName) {
        try {
            ServiceListener listener = serviceListeners.remove(serviceName);
            if (listener != null) {
                namingService.unsubscribe(serviceName, nacosGroup, listener);
                log.info("取消订阅服务: {}", serviceName);
            }

            // 清理缓存
            serviceInstanceCache.remove(serviceName);
            loadBalanceManager.updateServiceInstances(serviceName, Collections.emptyList());

        } catch (NacosException e) {
            log.error("取消订阅服务 {} 失败", serviceName, e);
        }
    }

    /**
     * 更新服务实例
     *
     * @param serviceName 服务名称
     */
    private void updateServiceInstances(String serviceName) {
        try {
            // 获取健康的服务实例
            List<Instance> instances = namingService.selectInstances(serviceName, nacosGroup, true);

            // 转换为内部服务实例格式
            List<ServiceInstance> serviceInstances = convertInstances(serviceName, instances);

            // 更新缓存
            serviceInstanceCache.put(serviceName, serviceInstances);

            // 更新负载均衡管理器
            List<LoadBalanceStrategy.ServiceInstance> lbInstances = serviceInstances.stream()
                    .map(this::convertToLoadBalanceInstance)
                    .collect(Collectors.toList());
            loadBalanceManager.updateServiceInstances(serviceName, lbInstances);

            log.info("更新服务 {} 实例，数量: {}", serviceName, serviceInstances.size());

        } catch (NacosException e) {
            log.error("更新服务 {} 实例失败", serviceName, e);
        }
    }

    /**
     * 转换Nacos实例为内部格式
     */
    private List<ServiceInstance> convertInstances(String serviceName, List<Instance> instances) {
        return instances.stream()
                .map(instance -> {
                    ServiceInstance serviceInstance = new ServiceInstance();
                    serviceInstance.setServiceName(serviceName);
                    serviceInstance.setInstanceId(instance.getInstanceId());
                    serviceInstance.setHost(instance.getIp());
                    serviceInstance.setPort(instance.getPort());
                    serviceInstance.setHealthy(instance.isHealthy());
                    serviceInstance.setWeight(instance.getWeight());
                    serviceInstance.setMetadata(instance.getMetadata());
                    serviceInstance.setClusterName(instance.getClusterName());
                    serviceInstance.setEnabled(instance.isEnabled());
                    return serviceInstance;
                })
                .collect(Collectors.toList());
    }

    /**
     * 转换为负载均衡实例
     */
    private LoadBalanceStrategy.ServiceInstance convertToLoadBalanceInstance(ServiceInstance instance) {
        LoadBalanceStrategy.ServiceInstance lbInstance = new LoadBalanceStrategy.ServiceInstance();
        lbInstance.setInstanceId(instance.getInstanceId());
        lbInstance.setHost(instance.getHost());
        lbInstance.setPort(instance.getPort());
        lbInstance.setWeight((int) instance.getWeight());
        lbInstance.setHealthy(instance.isHealthy());
        lbInstance.setMetadata(instance.getMetadata());
        return lbInstance;
    }

    /**
     * 定期同步服务实例
     */
    @Scheduled(fixedDelayString = "${api.gateway.discovery.sync-interval:30}000")
    public void syncServiceInstances() {
        if (!discoveryEnabled || namingService == null) {
            return;
        }

        log.debug("开始同步服务实例");

        // 如果启用自动发现，先发现新服务
        if (autoDiscover) {
            discoverAllServices();
        }

        // 更新所有已订阅的服务
        for (String serviceName : serviceListeners.keySet()) {
            updateServiceInstances(serviceName);
        }

        log.debug("服务实例同步完成");
    }

    /**
     * 获取服务实例列表
     *
     * @param serviceName 服务名称
     * @return 服务实例列表
     */
    public List<ServiceInstance> getServiceInstances(String serviceName) {
        return serviceInstanceCache.getOrDefault(serviceName, Collections.emptyList());
    }

    /**
     * 获取所有服务名称
     *
     * @return 服务名称列表
     */
    public Set<String> getAllServices() {
        return new HashSet<>(serviceInstanceCache.keySet());
    }

    /**
     * 检查服务是否可用
     *
     * @param serviceName 服务名称
     * @return 是否可用
     */
    public boolean isServiceAvailable(String serviceName) {
        List<ServiceInstance> instances = serviceInstanceCache.get(serviceName);
        return instances != null && !instances.isEmpty() &&
                instances.stream().anyMatch(ServiceInstance::isHealthy);
    }

    /**
     * 获取服务统计信息
     *
     * @return 统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalServices", serviceInstanceCache.size());
        stats.put("totalInstances", serviceInstanceCache.values().stream()
                .mapToInt(List::size).sum());
        stats.put("healthyInstances", serviceInstanceCache.values().stream()
                .flatMap(List::stream)
                .filter(ServiceInstance::isHealthy)
                .count());
        stats.put("services", serviceInstanceCache.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            Map<String, Object> serviceStats = new HashMap<>();
                            List<ServiceInstance> instances = entry.getValue();
                            serviceStats.put("total", instances.size());
                            serviceStats.put("healthy", instances.stream()
                                    .filter(ServiceInstance::isHealthy).count());
                            return serviceStats;
                        }
                )));
        return stats;
    }

    /**
     * 关闭服务发现
     */
    @PreDestroy
    public void shutdown() {
        if (namingService != null) {
            try {
                // 取消所有订阅
                for (Map.Entry<String, ServiceListener> entry : serviceListeners.entrySet()) {
                    namingService.unsubscribe(entry.getKey(), nacosGroup, entry.getValue());
                }
                serviceListeners.clear();
                serviceInstanceCache.clear();

                // 关闭命名服务
                namingService.shutDown();
                log.info("Nacos服务发现已关闭");
            } catch (NacosException e) {
                log.error("关闭Nacos服务发现失败", e);
            }
        }
    }

    /**
     * 服务实例内部表示
     */
    public static class ServiceInstance {
        private String serviceName;
        private String instanceId;
        private String host;
        private int port;
        private boolean healthy;
        private double weight;
        private Map<String, String> metadata;
        private String clusterName;
        private boolean enabled;

        // getters and setters
        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isHealthy() {
            return healthy;
        }

        public void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, String> metadata) {
            this.metadata = metadata;
        }

        public String getClusterName() {
            return clusterName;
        }

        public void setClusterName(String clusterName) {
            this.clusterName = clusterName;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 服务监听器
     */
    private class ServiceListener implements com.alibaba.nacos.api.naming.listener.EventListener {
        private final String serviceName;

        public ServiceListener(String serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        public void onEvent(com.alibaba.nacos.api.naming.listener.Event event) {
            if (event instanceof NamingEvent) {
                NamingEvent namingEvent = (NamingEvent) event;
                log.info("服务 {} 实例变更，新实例数: {}", serviceName, namingEvent.getInstances().size());

                // 更新服务实例
                List<ServiceInstance> serviceInstances = convertInstances(serviceName, namingEvent.getInstances());
                serviceInstanceCache.put(serviceName, serviceInstances);

                // 更新负载均衡管理器
                List<LoadBalanceStrategy.ServiceInstance> lbInstances = serviceInstances.stream()
                        .map(NacosServiceDiscovery.this::convertToLoadBalanceInstance)
                        .collect(Collectors.toList());
                loadBalanceManager.updateServiceInstances(serviceName, lbInstances);
            }
        }
    }
}