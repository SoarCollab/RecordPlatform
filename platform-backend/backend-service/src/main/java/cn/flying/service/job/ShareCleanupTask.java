package cn.flying.service.job;

import cn.flying.common.lock.DistributedLock;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.TenantMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时清理过期分享任务。
 * 批量更新已过期但状态仍为活跃的分享记录。
 * 使用分布式锁防止多实例重复执行。
 * 按租户分别执行，确保多租户隔离。
 */
@Component
@Slf4j
public class ShareCleanupTask {

    @Resource
    private FileShareMapper fileShareMapper;

    @Resource
    private TenantMapper tenantMapper;

    @Resource(name = "cacheManager")
    private CacheManager cacheManager;

    /**
     * 批量更新过期分享状态。
     * 默认每 5 分钟执行一次。
     * 使用分布式锁（租约 5 分钟）防止多实例重复执行。
     */
    @Scheduled(fixedDelayString = "${share.cleanup.interval:300000}")
    @DistributedLock(key = "share:cleanup:expired", leaseTime = 300000)
    public void updateExpiredShares() {
        log.debug("开始批量更新过期分享状态...");
        long startTime = System.currentTimeMillis();
        int totalUpdated = 0;

        try {
            List<Long> tenantIds = tenantMapper.selectActiveTenantIds();
            for (Long tenantId : tenantIds) {
                try {
                    TenantContext.setTenantId(tenantId);
                    int updated = fileShareMapper.updateExpiredShares(tenantId);
                    if (updated > 0) {
                        totalUpdated += updated;
                        log.info("租户 {} 更新了 {} 条过期分享", tenantId, updated);
                        // 清除 sharedFiles 缓存
                        evictSharedFilesCache();
                    }
                } catch (Exception e) {
                    log.error("租户 {} 更新过期分享失败: {}", tenantId, e.getMessage(), e);
                } finally {
                    TenantContext.clear();
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            if (totalUpdated > 0) {
                log.info("过期分享更新完成: 共更新 {} 条记录, 耗时 {}ms", totalUpdated, duration);
            } else {
                log.debug("无过期分享需要更新, 耗时 {}ms", duration);
            }
        } catch (Exception e) {
            log.error("批量更新过期分享任务执行失败", e);
        }
    }

    /**
     * 清除 sharedFiles 缓存
     * 由于无法确定具体的 shareCode，清除所有条目
     */
    private void evictSharedFilesCache() {
        Cache cache = cacheManager.getCache("sharedFiles");
        if (cache != null) {
            cache.clear();
            log.debug("已清除 sharedFiles 缓存");
        }
    }
}
