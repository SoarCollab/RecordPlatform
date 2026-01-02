package cn.flying.storage.service;

import cn.flying.storage.config.FaultDomainConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import cn.flying.storage.core.S3Monitor;
import cn.flying.storage.event.NodeTopologyChangeEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 备用节点池管理器
 * 监控故障域健康状态，当活跃域节点不足时自动提升备用节点
 *
 * <p>支持多活跃域配置，动态监控所有配置的活跃域
 */
@Slf4j
@Service
public class StandbyPoolManager {

    @Resource
    private FaultDomainManager faultDomainManager;

    @Resource
    private S3Monitor s3Monitor;

    @Resource
    private StorageProperties storageProperties;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    /**
     * 定期检查故障域健康状态
     * 当活跃域节点低于最低要求时，触发备用节点提升
     * 与 S3Monitor 的健康检查周期同步（30秒），但延迟启动以确保 S3Monitor 先完成首次检查
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 45000)
    public void checkDomainHealthAndPromote() {
        // 如果未启用故障域机制，跳过检查
        if (!faultDomainManager.isUsingFaultDomains()) {
            return;
        }

        // 检查备用域是否配置
        if (!faultDomainManager.isStandbyEnabled()) {
            log.debug("备用域未配置，跳过备用节点提升检查");
            return;
        }

        // 遍历所有活跃域进行健康检查
        for (String domainName : faultDomainManager.getActiveDomains()) {
            int healthyCount = faultDomainManager.countHealthyNodesInDomain(domainName);
            int minRequired = getMinNodesForDomain(domainName);

            if (healthyCount < minRequired) {
                int deficit = minRequired - healthyCount;
                log.warn("故障域 {} 健康节点不足: 当前={}, 最低要求={}，需要提升 {} 个备用节点",
                        domainName, healthyCount, minRequired, deficit);

                promoteStandbyNodes(domainName, deficit);
            }
        }
    }

    /**
     * 提升备用节点到指定故障域
     *
     * @param targetDomain 目标故障域
     * @param count        需要提升的节点数
     */
    private void promoteStandbyNodes(String targetDomain, int count) {
        List<String> healthyStandby = faultDomainManager.getHealthyStandbyNodes();

        if (healthyStandby.isEmpty()) {
            log.error("没有可用的健康备用节点来填补故障域 {} 的缺口", targetDomain);
            return;
        }

        int promotedCount = 0;
        for (String standbyNode : healthyStandby) {
            if (promotedCount >= count) {
                break;
            }

            log.info("准备提升备用节点 {} 到故障域 {}", standbyNode, targetDomain);

            // 实际执行域变更（更新内存中的哈希环和域映射）
            boolean success = faultDomainManager.changeNodeDomain(standbyNode, targetDomain);
            if (!success) {
                log.error("提升备用节点 {} 到故障域 {} 失败", standbyNode, targetDomain);
                continue;
            }

            // 发布域变更事件（通知 RebalanceService 等组件）
            eventPublisher.publishEvent(new NodeTopologyChangeEvent(
                    this,
                    standbyNode,
                    NodeTopologyChangeEvent.TopologyChangeType.NODE_DOMAIN_CHANGED,
                    targetDomain
            ));

            promotedCount++;
            log.info("成功提升备用节点 {} 到故障域 {}", standbyNode, targetDomain);
        }

        if (promotedCount > 0) {
            log.info("已成功提升 {} 个备用节点到故障域 {}", promotedCount, targetDomain);
        }
    }

    /**
     * 获取故障域的最低节点要求
     */
    private int getMinNodesForDomain(String domainName) {
        FaultDomainConfig config = faultDomainManager.getDomainConfig(domainName);
        if (config != null && config.getMinNodes() != null) {
            return config.getMinNodes();
        }
        return 1; // 默认最少 1 个节点
    }

    /**
     * 手动触发域健康检查
     */
    public void triggerHealthCheck() {
        log.info("手动触发故障域健康检查");
        checkDomainHealthAndPromote();
    }
}
