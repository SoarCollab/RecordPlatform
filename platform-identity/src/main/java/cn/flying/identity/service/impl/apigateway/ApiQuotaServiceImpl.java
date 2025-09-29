package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.dto.apigateway.ApiQuota;
import cn.flying.identity.gateway.alert.AlertService;
import cn.flying.identity.mapper.apigateway.ApiQuotaMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiQuotaService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * API配额管理服务实现类
 * 提供API调用配额的管理和检查功能
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Service
public class ApiQuotaServiceImpl extends BaseService implements ApiQuotaService {

    @Resource
    private ApiQuotaMapper quotaMapper;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Resource
    private AlertService alertService;

    /**
     * Redis配额缓存键前缀
     */
    private static final String QUOTA_KEY_PREFIX = "api:quota:";
    private static final String QUOTA_USAGE_PREFIX = "api:quota:usage:";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<ApiQuota> createQuota(Long appId, Long interfaceId, Integer quotaType,
                                        Long quotaLimit, Integer alertThreshold) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireNonNull(quotaType, "配额类型不能为空");
            requireCondition(quotaType, type -> type >= 1 && type <= 4, "配额类型必须在1-4之间");
            requireNonNull(quotaLimit, "配额限制不能为空");
            requireCondition(quotaLimit, limit -> limit > 0, "配额限制必须大于0");

            // 检查是否已存在相同的配额设置
            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiQuota::getAppId, appId)
                    .eq(ApiQuota::getQuotaType, quotaType);
            if (interfaceId != null) {
                wrapper.eq(ApiQuota::getInterfaceId, interfaceId);
            } else {
                wrapper.isNull(ApiQuota::getInterfaceId);
            }

            ApiQuota existingQuota = quotaMapper.selectOne(wrapper);
            if (existingQuota != null) {
                throw new RuntimeException("该应用的配额设置已存在");
            }

            // 创建新配额
            ApiQuota quota = new ApiQuota();
            quota.setId(IdUtils.nextEntityId());
            quota.setAppId(appId);
            quota.setInterfaceId(interfaceId);
            quota.setQuotaType(quotaType);
            quota.setQuotaLimit(quotaLimit);
            quota.setQuotaUsed(0L);
            quota.setAlertThreshold(alertThreshold != null ? alertThreshold : 80); // 默认80%
            quota.setIsAlerted(0);
            quota.setResetTime(calculateNextResetTime(quotaType));

            int inserted = quotaMapper.insert(quota);
            requireCondition(inserted, count -> count > 0, "创建配额失败");

            // 缓存配额信息
            cacheQuota(quota);

            logInfo("创建API配额: appId={}, interfaceId={}, quotaType={}, limit={}",
                    appId, interfaceId, quotaType, quotaLimit);
            return quota;
        }, "创建配额失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateQuota(ApiQuota quota) {
        return safeExecuteAction(() -> {
            requireNonNull(quota, "配额信息不能为空");
            requireNonNull(quota.getId(), "配额ID不能为空");

            ApiQuota existingQuota = quotaMapper.selectById(quota.getId());
            requireNonNull(existingQuota, "配额不存在");

            int updated = quotaMapper.updateById(quota);
            requireCondition(updated, count -> count > 0, "更新配额失败");

            // 清除缓存
            clearQuotaCache(quota.getAppId(), quota.getInterfaceId(), quota.getQuotaType());

            logInfo("更新API配额: quotaId={}", quota.getId());
        }, "更新配额失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteQuota(Long quotaId) {
        return safeExecuteAction(() -> {
            requireNonNull(quotaId, "配额ID不能为空");

            ApiQuota quota = quotaMapper.selectById(quotaId);
            requireNonNull(quota, "配额不存在");

            int deleted = quotaMapper.deleteById(quotaId);
            requireCondition(deleted, count -> count > 0, "删除配额失败");

            // 清除缓存
            clearQuotaCache(quota.getAppId(), quota.getInterfaceId(), quota.getQuotaType());

            logInfo("删除API配额: quotaId={}", quotaId);
        }, "删除配额失败");
    }

    @Override
    public Result<ApiQuota> getQuotaById(Long quotaId) {
        return safeExecuteData(() -> {
            requireNonNull(quotaId, "配额ID不能为空");

            ApiQuota quota = quotaMapper.selectById(quotaId);
            requireNonNull(quota, "配额不存在");

            return quota;
        }, "查询配额失败");
    }

    @Override
    public Result<List<ApiQuota>> getQuotasByAppId(Long appId) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");

            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiQuota::getAppId, appId)
                    .orderByAsc(ApiQuota::getQuotaType);

            List<ApiQuota> quotas = quotaMapper.selectList(wrapper);
            return quotas;
        }, "查询应用配额列表失败");
    }

    @Override
    public Result<ApiQuota> getQuotaByAppAndInterface(Long appId, Long interfaceId, Integer quotaType) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireNonNull(quotaType, "配额类型不能为空");

            // 先从缓存获取
            ApiQuota cachedQuota = getQuotaFromCache(appId, interfaceId, quotaType);
            if (cachedQuota != null) {
                return cachedQuota;
            }

            // 从数据库查询
            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiQuota::getAppId, appId)
                    .eq(ApiQuota::getQuotaType, quotaType);
            if (interfaceId != null) {
                wrapper.eq(ApiQuota::getInterfaceId, interfaceId);
            } else {
                wrapper.isNull(ApiQuota::getInterfaceId);
            }

            ApiQuota quota = quotaMapper.selectOne(wrapper);
            if (quota != null) {
                cacheQuota(quota);
            }

            return quota;
        }, "查询配额失败");
    }

    @Override
    public Result<Boolean> checkQuotaExceeded(Long appId, Long interfaceId) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");

            // 检查每分钟配额
            ApiQuota minuteQuota = getQuotaByAppAndInterface(appId, interfaceId, 1).getData();
            if (minuteQuota != null && minuteQuota.getQuotaUsed() >= minuteQuota.getQuotaLimit()) {
                logWarn("应用超出每分钟配额: appId={}, interfaceId={}, used={}, limit={}",
                        appId, interfaceId, minuteQuota.getQuotaUsed(), minuteQuota.getQuotaLimit());
                return true;
            }

            // 检查每小时配额
            ApiQuota hourQuota = getQuotaByAppAndInterface(appId, interfaceId, 2).getData();
            if (hourQuota != null && hourQuota.getQuotaUsed() >= hourQuota.getQuotaLimit()) {
                logWarn("应用超出每小时配额: appId={}, interfaceId={}, used={}, limit={}",
                        appId, interfaceId, hourQuota.getQuotaUsed(), hourQuota.getQuotaLimit());
                return true;
            }

            // 检查每天配额
            ApiQuota dayQuota = getQuotaByAppAndInterface(appId, interfaceId, 3).getData();
            if (dayQuota != null && dayQuota.getQuotaUsed() >= dayQuota.getQuotaLimit()) {
                logWarn("应用超出每天配额: appId={}, interfaceId={}, used={}, limit={}",
                        appId, interfaceId, dayQuota.getQuotaUsed(), dayQuota.getQuotaLimit());
                return true;
            }

            // 检查每月配额
            ApiQuota monthQuota = getQuotaByAppAndInterface(appId, interfaceId, 4).getData();
            if (monthQuota != null && monthQuota.getQuotaUsed() >= monthQuota.getQuotaLimit()) {
                logWarn("应用超出每月配额: appId={}, interfaceId={}, used={}, limit={}",
                        appId, interfaceId, monthQuota.getQuotaUsed(), monthQuota.getQuotaLimit());
                return true;
            }

            return false;
        }, "检查配额失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> incrementQuotaUsage(Long appId, Long interfaceId, int count) {
        return safeExecuteAction(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireCondition(count, c -> c > 0, "增加数量必须大于0");

            // 增加所有类型的配额使用量
            for (int quotaType = 1; quotaType <= 4; quotaType++) {
                incrementQuotaUsageByType(appId, interfaceId, quotaType, count);
            }

            logDebug("增加配额使用量: appId={}, interfaceId={}, count={}", appId, interfaceId, count);
        }, "增加配额使用量失败");
    }

    /**
     * 按类型增加配额使用量
     */
    private void incrementQuotaUsageByType(Long appId, Long interfaceId, Integer quotaType, int count) {
        LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiQuota::getAppId, appId)
                .eq(ApiQuota::getQuotaType, quotaType);
        if (interfaceId != null) {
            wrapper.eq(ApiQuota::getInterfaceId, interfaceId);
        } else {
            wrapper.isNull(ApiQuota::getInterfaceId);
        }

        ApiQuota quota = quotaMapper.selectOne(wrapper);
        if (quota != null) {
            // 使用Redis原子操作增加使用量
            String usageKey = buildQuotaUsageKey(appId, interfaceId, quotaType);
            Long newUsage = redisTemplate.opsForValue().increment(usageKey, count);

            // 定期同步到数据库（每100次）
            if (newUsage % 100 == 0) {
                LambdaUpdateWrapper<ApiQuota> updateWrapper = new LambdaUpdateWrapper<>();
                updateWrapper.eq(ApiQuota::getId, quota.getId())
                        .set(ApiQuota::getQuotaUsed, newUsage);
                quotaMapper.update(null, updateWrapper);
            }

            // 检查是否需要告警
            if (newUsage >= quota.getQuotaLimit() * quota.getAlertThreshold() / 100) {
                checkAndSendAlert(quota.getId());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> resetQuota(Long quotaId) {
        return safeExecuteAction(() -> {
            requireNonNull(quotaId, "配额ID不能为空");

            ApiQuota quota = quotaMapper.selectById(quotaId);
            requireNonNull(quota, "配额不存在");

            // 重置使用量和告警状态
            LambdaUpdateWrapper<ApiQuota> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(ApiQuota::getId, quotaId)
                    .set(ApiQuota::getQuotaUsed, 0)
                    .set(ApiQuota::getIsAlerted, 0)
                    .set(ApiQuota::getResetTime, calculateNextResetTime(quota.getQuotaType()));

            int updated = quotaMapper.update(null, wrapper);
            requireCondition(updated, count -> count > 0, "重置配额失败");

            // 清除Redis缓存
            String usageKey = buildQuotaUsageKey(quota.getAppId(), quota.getInterfaceId(), quota.getQuotaType());
            redisTemplate.delete(usageKey);
            clearQuotaCache(quota.getAppId(), quota.getInterfaceId(), quota.getQuotaType());

            logInfo("重置配额: quotaId={}", quotaId);
        }, "重置配额失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Integer> resetExpiredQuotas() {
        return safeExecuteData(() -> {
            LocalDateTime now = LocalDateTime.now();

            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            wrapper.le(ApiQuota::getResetTime, now);

            List<ApiQuota> expiredQuotas = quotaMapper.selectList(wrapper);

            int resetCount = 0;
            for (ApiQuota quota : expiredQuotas) {
                Result<Void> result = resetQuota(quota.getId());
                if (result.isSuccess()) {
                    resetCount++;
                }
            }

            logInfo("重置过期配额: 总数={}, 成功={}", expiredQuotas.size(), resetCount);
            return resetCount;
        }, "重置过期配额失败");
    }

    @Override
    public Result<Map<String, Object>> getQuotaStatistics(Long appId) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");

            Map<String, Object> stats = new HashMap<>();

            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiQuota::getAppId, appId);

            List<ApiQuota> quotas = quotaMapper.selectList(wrapper);

            stats.put("total_quotas", quotas.size());
            stats.put("quotas", quotas);

            // 计算总使用率
            if (!quotas.isEmpty()) {
                double totalUsageRate = quotas.stream()
                        .mapToDouble(q -> (double) q.getQuotaUsed() / q.getQuotaLimit() * 100)
                        .average()
                        .orElse(0.0);
                stats.put("avg_usage_rate", Math.round(totalUsageRate * 100) / 100.0);
            } else {
                stats.put("avg_usage_rate", 0.0);
            }

            stats.put("stat_time", LocalDateTime.now());

            return stats;
        }, "获取配额统计失败");
    }

    @Override
    public Result<Void> checkAndSendAlert(Long quotaId) {
        return safeExecuteAction(() -> {
            ApiQuota quota = quotaMapper.selectById(quotaId);
            if (quota == null || quota.getIsAlerted() == 1) {
                return; // 已告警过，不再重复告警
            }

            double usageRate = (double) quota.getQuotaUsed() / quota.getQuotaLimit() * 100;
            if (usageRate >= quota.getAlertThreshold()) {
                // 发送告警
                String alertMessage = String.format(
                        "应用ID %d 的API配额即将超限：已使用 %.2f%%（%d/%d）",
                        quota.getAppId(), usageRate, quota.getQuotaUsed(), quota.getQuotaLimit()
                );
                alertService.sendAlert("QUOTA_WARNING", alertMessage, "MEDIUM");

                // 标记为已告警
                LambdaUpdateWrapper<ApiQuota> wrapper = new LambdaUpdateWrapper<>();
                wrapper.eq(ApiQuota::getId, quotaId)
                        .set(ApiQuota::getIsAlerted, 1);
                quotaMapper.update(null, wrapper);

                logWarn("发送配额告警: quotaId={}, usageRate={}%", quotaId, usageRate);
            }
        }, "检查并发送告警失败");
    }

    @Override
    public Result<List<ApiQuota>> getQuotasNearLimit(int threshold) {
        return safeExecuteData(() -> {
            requireCondition(threshold, t -> t > 0 && t <= 100, "阈值必须在1-100之间");

            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            List<ApiQuota> allQuotas = quotaMapper.selectList(wrapper);

            // 过滤出超过阈值的配额
            List<ApiQuota> nearLimitQuotas = allQuotas.stream()
                    .filter(quota -> {
                        double usageRate = (double) quota.getQuotaUsed() / quota.getQuotaLimit() * 100;
                        return usageRate >= threshold;
                    })
                    .toList();

            return nearLimitQuotas;
        }, "查询即将超限的配额失败");
    }

    /**
     * 计算下次重置时间
     *
     * @param quotaType 配额类型
     * @return 重置时间
     */
    private LocalDateTime calculateNextResetTime(Integer quotaType) {
        LocalDateTime now = LocalDateTime.now();
        return switch (quotaType) {
            case 1 -> now.plusMinutes(1).withSecond(0).withNano(0); // 每分钟
            case 2 -> now.plusHours(1).withMinute(0).withSecond(0).withNano(0); // 每小时
            case 3 -> now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0); // 每天
            case 4 -> now.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0); // 每月
            default -> now.plusDays(1);
        };
    }

    /**
     * 构建配额使用量缓存键
     */
    private String buildQuotaUsageKey(Long appId, Long interfaceId, Integer quotaType) {
        return QUOTA_USAGE_PREFIX + appId + ":" +
                (interfaceId != null ? interfaceId : "global") + ":" + quotaType;
    }

    /**
     * 缓存配额信息
     */
    private void cacheQuota(ApiQuota quota) {
        try {
            String cacheKey = QUOTA_KEY_PREFIX + quota.getAppId() + ":" +
                    (quota.getInterfaceId() != null ? quota.getInterfaceId() : "global") + ":" +
                    quota.getQuotaType();

            Map<String, String> quotaInfo = new HashMap<>();
            quotaInfo.put("id", quota.getId().toString());
            quotaInfo.put("quota_limit", quota.getQuotaLimit().toString());
            quotaInfo.put("quota_used", quota.getQuotaUsed().toString());
            quotaInfo.put("alert_threshold", quota.getAlertThreshold().toString());

            redisTemplate.opsForHash().putAll(cacheKey, quotaInfo);
            redisTemplate.expire(cacheKey, 10, TimeUnit.MINUTES);
        } catch (Exception e) {
            logError("缓存配额信息失败", e);
        }
    }

    /**
     * 从缓存获取配额
     */
    private ApiQuota getQuotaFromCache(Long appId, Long interfaceId, Integer quotaType) {
        try {
            String cacheKey = QUOTA_KEY_PREFIX + appId + ":" +
                    (interfaceId != null ? interfaceId : "global") + ":" + quotaType;

            Map<Object, Object> cachedData = redisTemplate.opsForHash().entries(cacheKey);
            if (cachedData != null && !cachedData.isEmpty()) {
                ApiQuota quota = new ApiQuota();
                quota.setId(Long.parseLong((String) cachedData.get("id")));
                quota.setAppId(appId);
                quota.setInterfaceId(interfaceId);
                quota.setQuotaType(quotaType);
                quota.setQuotaLimit(Long.parseLong((String) cachedData.get("quota_limit")));
                quota.setQuotaUsed(Long.parseLong((String) cachedData.get("quota_used")));
                quota.setAlertThreshold(Integer.parseInt((String) cachedData.get("alert_threshold")));
                return quota;
            }
        } catch (Exception e) {
            logError("从缓存获取配额失败", e);
        }
        return null;
    }

    /**
     * 清除配额缓存
     */
    private void clearQuotaCache(Long appId, Long interfaceId, Integer quotaType) {
        try {
            String cacheKey = QUOTA_KEY_PREFIX + appId + ":" +
                    (interfaceId != null ? interfaceId : "global") + ":" + quotaType;
            redisTemplate.delete(cacheKey);

            String usageKey = buildQuotaUsageKey(appId, interfaceId, quotaType);
            redisTemplate.delete(usageKey);
        } catch (Exception e) {
            logError("清除配额缓存失败", e);
        }
    }

    @Override
    public Result<List<ApiQuota>> getAllActiveQuotas() {
        return safeExecuteData(() -> {
            // 查询所有配额（不过滤状态，因为ApiQuota没有status字段）
            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            wrapper.orderByAsc(ApiQuota::getAppId, ApiQuota::getQuotaType);

            List<ApiQuota> quotas = quotaMapper.selectList(wrapper);
            return quotas != null ? quotas : List.of();
        }, "获取所有启用的配额失败");
    }
}
