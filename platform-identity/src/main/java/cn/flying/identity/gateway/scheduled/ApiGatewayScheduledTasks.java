package cn.flying.identity.gateway.scheduled;

import cn.flying.identity.dto.apigateway.ApiKey;
import cn.flying.identity.dto.apigateway.ApiQuota;
import cn.flying.identity.gateway.alert.AlertService;
import cn.flying.identity.mapper.apigateway.ApiKeyMapper;
import cn.flying.identity.service.apigateway.ApiCallLogService;
import cn.flying.identity.service.apigateway.ApiQuotaService;
import cn.flying.platformapi.constant.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * API网关定时任务
 * 负责定期执行清理、统计、告警等后台任务
 * <p>
 * 核心功能：
 * 1. 定期清理过期的API调用日志
 * 2. 定期重置过期的配额计数
 * 3. 定期同步Redis中的密钥使用统计到数据库
 * 4. 定期检查即将过期的API密钥并告警
 * 5. 定期检查配额使用情况并告警
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component
public class ApiGatewayScheduledTasks {

    @Resource
    private ApiCallLogService apiCallLogService;

    @Resource
    private ApiQuotaService apiQuotaService;

    @Resource
    private ApiKeyMapper apiKeyMapper;

    @Resource
    private AlertService alertService;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    /**
     * 定时清理过期的API调用日志
     * 执行时间：每天凌晨3点
     * <p>
     * 清理策略：删除90天前的历史日志
     * 清理原因：防止日志表无限增长，影响查询性能
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanExpiredApiCallLogs() {
        log.info("开始定时清理过期API调用日志");
        long startTime = System.currentTimeMillis();

        try {
            // 清理90天前的日志
            int retentionDays = 90;
            Result<Integer> result = apiCallLogService.cleanExpiredLogs(retentionDays);

            if (result.isSuccess()) {
                int deletedCount = result.getData();
                long duration = System.currentTimeMillis() - startTime;

                log.info("清理过期API调用日志完成: 删除{}条记录, 耗时{}ms", deletedCount, duration);

                // 如果删除数量过多，发送告警
                if (deletedCount > 100000) {
                    alertService.sendAlert("LOG_CLEANUP",
                            String.format("API调用日志清理删除了大量数据: %d条记录", deletedCount),
                            "MEDIUM");
                }
            } else {
                log.error("清理过期API调用日志失败: {}", result.getMessage());
                alertService.sendAlert("LOG_CLEANUP_FAILED",
                        "API调用日志清理任务失败: " + result.getMessage(),
                        "HIGH");
            }
        } catch (Exception e) {
            log.error("定时清理API调用日志异常", e);
            alertService.sendAlert("LOG_CLEANUP_ERROR",
                    "API调用日志清理任务异常: " + e.getMessage(),
                    "HIGH");
        }
    }

    /**
     * 定时重置过期的配额计数
     * 执行时间：每5分钟一次
     * <p>
     * 重置策略：根据配额类型自动计算是否需要重置
     * - 分钟配额：每分钟重置
     * - 小时配额：每小时重置
     * - 天配额：每天重置
     * - 月配额：每月重置
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void resetExpiredQuotas() {
        log.debug("开始定时重置过期配额");
        long startTime = System.currentTimeMillis();

        try {
            Result<Integer> result = apiQuotaService.resetExpiredQuotas();

            if (result.isSuccess()) {
                int resetCount = result.getData();
                long duration = System.currentTimeMillis() - startTime;

                if (resetCount > 0) {
                    log.info("重置过期配额完成: 重置{}个配额, 耗时{}ms", resetCount, duration);
                } else {
                    log.debug("本次无需重置配额");
                }
            } else {
                log.error("重置过期配额失败: {}", result.getMessage());
            }
        } catch (Exception e) {
            log.error("定时重置配额异常", e);
            // 不发送告警，因为这个任务执行频繁，避免告警轰炸
        }
    }

    /**
     * 定时同步API密钥使用统计
     * 执行时间：每小时执行一次
     * <p>
     * 同步策略：将Redis中的使用计数器同步到数据库
     * 原因：避免频繁更新数据库，使用Redis做缓冲
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void syncApiKeyUsageStats() {
        log.info("开始定时同步API密钥使用统计");
        long startTime = System.currentTimeMillis();
        int syncCount = 0;

        try {
            // 查询所有的使用统计键
            String pattern = "api:key:usage:*";
            Set<String> keys = redisTemplate.keys(pattern);

            if (keys.isEmpty()) {
                log.debug("没有需要同步的密钥使用统计");
                return;
            }

            // 遍历每个密钥，同步统计数据
            for (String key : keys) {
                try {
                    // 提取密钥ID
                    String keyIdStr = key.replace("api:key:usage:", "");
                    Long keyId = Long.parseLong(keyIdStr);

                    // 获取Redis中的使用次数
                    String usageStr = redisTemplate.opsForValue().get(key);
                    if (usageStr != null) {
                        long usage = Long.parseLong(usageStr);

                        // 更新数据库中的使用次数
                        ApiKey apiKey = apiKeyMapper.selectById(keyId);
                        if (apiKey != null) {
                            long currentUsedCount = apiKey.getUsedCount() != null ? apiKey.getUsedCount() : 0L;
                            apiKey.setUsedCount(currentUsedCount + usage);
                            apiKey.setLastUsedTime(LocalDateTime.now());
                            apiKeyMapper.updateById(apiKey);

                            // 清除Redis中的计数器
                            redisTemplate.delete(key);
                            syncCount++;

                            log.debug("同步密钥使用统计: keyId={}, usage={}", keyId, usage);
                        }
                    }
                } catch (Exception e) {
                    log.error("同步单个密钥使用统计失败: key={}", key, e);
                    // 继续处理下一个
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("同步API密钥使用统计完成: 同步{}个密钥, 耗时{}ms", syncCount, duration);

        } catch (Exception e) {
            log.error("定时同步API密钥使用统计异常", e);
            alertService.sendAlert("SYNC_KEY_USAGE_ERROR",
                    "同步API密钥使用统计异常: " + e.getMessage(),
                    "MEDIUM");
        }
    }

    /**
     * 定时检查即将过期的API密钥
     * 执行时间：每天上午9点
     * <p>
     * 检查策略：检查7天内即将过期的密钥
     * 告警目的：提前通知管理员进行密钥轮换
     */
    @Scheduled(cron = "0 0 9 * * ?")
    public void checkExpiringApiKeys() {
        log.info("开始定时检查即将过期的API密钥");
        long startTime = System.currentTimeMillis();

        try {
            // 检查7天内即将过期的密钥
            int checkDays = 7;
            LocalDateTime threshold = LocalDateTime.now().plusDays(checkDays);

            LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiKey::getKeyStatus, 1) // 仅检查启用状态
                    .isNotNull(ApiKey::getExpireTime)
                    .le(ApiKey::getExpireTime, threshold)
                    .ge(ApiKey::getExpireTime, LocalDateTime.now()); // 排除已过期

            List<ApiKey> expiringKeys = apiKeyMapper.selectList(wrapper);

            if (expiringKeys != null && !expiringKeys.isEmpty()) {
                log.warn("发现{}个即将过期的API密钥", expiringKeys.size());

                // 构建告警消息
                StringBuilder alertMessage = new StringBuilder();
                alertMessage.append(String.format("发现%d个API密钥将在%d天内过期:\n",
                        expiringKeys.size(), checkDays));

                for (ApiKey key : expiringKeys) {
                    alertMessage.append(String.format("- KeyID: %d, AppID: %d, 名称: %s, 过期时间: %s\n",
                            key.getId(), key.getAppId(), key.getKeyName(), key.getExpireTime()));
                }

                // 发送告警
                alertService.sendAlert("API_KEY_EXPIRING",
                        alertMessage.toString(),
                        "MEDIUM");
            } else {
                log.info("当前没有即将过期的API密钥");
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("检查即将过期的API密钥完成: 耗时{}ms", duration);

        } catch (Exception e) {
            log.error("定时检查即将过期API密钥异常", e);
            alertService.sendAlert("CHECK_EXPIRING_KEYS_ERROR",
                    "检查即将过期API密钥异常: " + e.getMessage(),
                    "MEDIUM");
        }
    }

    /**
     * 定时检查配额告警
     * 执行时间：每10分钟一次
     * <p>
     * 检查策略：检查使用量超过告警阈值的配额
     * 告警目的：及时通知管理员配额使用情况
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void checkQuotaAlerts() {
        log.debug("开始定时检查配额告警");
        long startTime = System.currentTimeMillis();
        int alertCount = 0;

        try {
            // 查询所有启用的配额
            Result<List<ApiQuota>> quotaResult = apiQuotaService.getAllActiveQuotas();

            if (!quotaResult.isSuccess() || quotaResult.getData() == null) {
                log.debug("没有需要检查的配额");
                return;
            }

            List<ApiQuota> quotas = quotaResult.getData();

            // 遍历每个配额，检查是否需要告警
            for (ApiQuota quota : quotas) {
                try {
                    // 跳过没有设置告警阈值的配额
                    if (quota.getAlertThreshold() == null || quota.getAlertThreshold() <= 0) {
                        continue;
                    }

                    // 计算使用百分比
                    Long usedCount = quota.getQuotaUsed() != null ? quota.getQuotaUsed() : 0L;
                    long quotaLimit = quota.getQuotaLimit() != null ? quota.getQuotaLimit() : 0L;

                    if (quotaLimit <= 0) {
                        continue;
                    }

                    double usagePercent = (double) usedCount / quotaLimit * 100;

                    // 如果使用量超过告警阈值，发送告警
                    if (usagePercent >= quota.getAlertThreshold()) {
                        String alertMessage = String.format(
                                "配额使用量告警: AppID=%d, InterfaceID=%s, 使用量=%d/%d (%.1f%%), 阈值=%d%%",
                                quota.getAppId(),
                                quota.getInterfaceId() != null ? quota.getInterfaceId().toString() : "全局",
                                usedCount,
                                quotaLimit,
                                usagePercent,
                                quota.getAlertThreshold()
                        );

                        // 根据使用百分比决定告警级别
                        String alertLevel = usagePercent >= 95 ? "HIGH" : "MEDIUM";

                        alertService.sendAlert("QUOTA_ALERT", alertMessage, alertLevel);
                        alertCount++;

                        log.warn("配额使用量告警: quotaId={}, usage={}/{} ({}%)",
                                quota.getId(), usedCount, quotaLimit, String.format("%.1f", usagePercent));
                    }
                } catch (Exception e) {
                    log.error("检查单个配额告警失败: quotaId={}", quota.getId(), e);
                    // 继续处理下一个
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            if (alertCount > 0) {
                log.info("检查配额告警完成: 发送{}条告警, 耗时{}ms", alertCount, duration);
            } else {
                log.debug("检查配额告警完成: 无需告警, 耗时{}ms", duration);
            }

        } catch (Exception e) {
            log.error("定时检查配额告警异常", e);
            alertService.sendAlert("CHECK_QUOTA_ALERT_ERROR",
                    "检查配额告警异常: " + e.getMessage(),
                    "MEDIUM");
        }
    }

    /**
     * 定时清理不健康的服务实例缓存
     * 执行时间：每30分钟一次
     * <p>
     * 清理策略：清理长时间未更新的服务实例缓存
     * 清理原因：防止缓存中存在已下线的服务实例
     */
    @Scheduled(cron = "0 */30 * * * ?")
    public void cleanStaleServiceInstanceCache() {
        log.debug("开始定时清理不健康的服务实例缓存");
        long startTime = System.currentTimeMillis();

        try {
            // 清理负载均衡缓存中长时间未更新的实例
            String pattern = "api:lb:instance:*";
            Set<String> keys = redisTemplate.keys(pattern);

            if (keys.isEmpty()) {
                log.debug("没有需要清理的服务实例缓存");
                return;
            }

            int cleanCount = 0;
            long staleThreshold = System.currentTimeMillis() - (30 * 60 * 1000); // 30分钟前

            for (String key : keys) {
                try {
                    String lastUpdateStr = Objects.requireNonNull(redisTemplate.opsForHash().get(key, "lastUpdateTime")).toString();
                    if (lastUpdateStr != null) {
                        long lastUpdate = Long.parseLong(lastUpdateStr);

                        // 如果超过30分钟未更新，删除缓存
                        if (lastUpdate < staleThreshold) {
                            redisTemplate.delete(key);
                            cleanCount++;
                            log.debug("清理过期服务实例缓存: {}", key);
                        }
                    }
                } catch (Exception e) {
                    log.error("清理单个服务实例缓存失败: key={}", key, e);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            if (cleanCount > 0) {
                log.info("清理不健康的服务实例缓存完成: 清理{}个实例, 耗时{}ms", cleanCount, duration);
            } else {
                log.debug("清理服务实例缓存完成: 无需清理, 耗时{}ms", duration);
            }

        } catch (Exception e) {
            log.error("定时清理服务实例缓存异常", e);
        }
    }
}
