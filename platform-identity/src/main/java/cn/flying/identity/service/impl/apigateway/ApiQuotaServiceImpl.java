package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.dto.apigateway.ApiQuota;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.gateway.alert.AlertService;
import cn.flying.identity.mapper.apigateway.ApiQuotaMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiQuotaService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.ResultEnum;
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
    public ApiQuota createQuota(Long appId, Long interfaceId, Integer quotaType,
                                Long quotaLimit, Integer alertThreshold) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonNull(quotaType, "配额类型不能为空");
        requireCondition(quotaType, type -> type >= 1 && type <= 4, "配额类型必须在1-4之间");
        requireNonNull(quotaLimit, "配额限制不能为空");
        requireCondition(quotaLimit, limit -> limit > 0, "配额限制必须大于0");

        try {
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
                throw businessException(ResultEnum.PARAM_IS_INVALID, "该应用的配额设置已存在");
            }

            ApiQuota quota = new ApiQuota();
            quota.setId(IdUtils.nextEntityId());
            quota.setAppId(appId);
            quota.setInterfaceId(interfaceId);
            quota.setQuotaType(quotaType);
            quota.setQuotaLimit(quotaLimit);
            quota.setQuotaUsed(0L);
            quota.setAlertThreshold(alertThreshold != null ? alertThreshold : 80);
            quota.setIsAlerted(0);
            quota.setResetTime(calculateNextResetTime(quotaType));

            int inserted = quotaMapper.insert(quota);
            requireCondition(inserted, count -> count > 0, "创建配额失败");

            cacheQuota(quota);

            logInfo("创建API配额: appId={}, interfaceId={}, quotaType={}, limit={}",
                    appId, interfaceId, quotaType, quotaLimit);
            return quota;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("创建配额失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "创建配额失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQuota(ApiQuota quota) {
        requireNonNull(quota, "配额信息不能为空");
        requireNonNull(quota.getId(), "配额ID不能为空");

        try {
            ApiQuota existingQuota = quotaMapper.selectById(quota.getId());
            requireNonNull(existingQuota, "配额不存在");

            int updated = quotaMapper.updateById(quota);
            requireCondition(updated, count -> count > 0, "更新配额失败");

            clearQuotaCache(quota.getAppId(), quota.getInterfaceId(), quota.getQuotaType());

            logInfo("更新API配额: quotaId={}", quota.getId());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("更新配额失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "更新配额失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteQuota(Long quotaId) {
        requireNonNull(quotaId, "配额ID不能为空");

        try {
            ApiQuota quota = quotaMapper.selectById(quotaId);
            requireNonNull(quota, "配额不存在");

            int deleted = quotaMapper.deleteById(quotaId);
            requireCondition(deleted, count -> count > 0, "删除配额失败");

            clearQuotaCache(quota.getAppId(), quota.getInterfaceId(), quota.getQuotaType());

            logInfo("删除API配额: quotaId={}", quotaId);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("删除配额失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "删除配额失败: " + e.getMessage());
        }
    }

    @Override
    public ApiQuota getQuotaById(Long quotaId) {
        requireNonNull(quotaId, "配额ID不能为空");

        try {
            ApiQuota quota = quotaMapper.selectById(quotaId);
            requireNonNull(quota, "配额不存在");
            return quota;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("查询配额失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "查询配额失败: " + e.getMessage());
        }
    }

    @Override
    public List<ApiQuota> getQuotasByAppId(Long appId) {
        requireNonNull(appId, "应用ID不能为空");

        try {
            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiQuota::getAppId, appId)
                    .orderByAsc(ApiQuota::getQuotaType);

            List<ApiQuota> quotas = quotaMapper.selectList(wrapper);
            return quotas != null ? quotas : List.of();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("查询应用配额列表失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "查询应用配额列表失败: " + e.getMessage());
        }
    }

    @Override
    public ApiQuota getQuotaByAppAndInterface(Long appId, Long interfaceId, Integer quotaType) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonNull(quotaType, "配额类型不能为空");

        try {
            ApiQuota cachedQuota = getQuotaFromCache(appId, interfaceId, quotaType);
            if (cachedQuota != null) {
                return cachedQuota;
            }

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
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("查询配额失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "查询配额失败: " + e.getMessage());
        }
    }

    @Override
    public boolean checkQuotaExceeded(Long appId, Long interfaceId) {
        requireNonNull(appId, "应用ID不能为空");

        try {
            ApiQuota minuteQuota = getQuotaByAppAndInterface(appId, interfaceId, 1);
            if (minuteQuota != null && quotaExceeded(minuteQuota)) {
                logWarn("应用超出每分钟配额: appId={}, interfaceId={}, used={}, limit={}",
                        appId, interfaceId, minuteQuota.getQuotaUsed(), minuteQuota.getQuotaLimit());
                return true;
            }

            ApiQuota hourQuota = getQuotaByAppAndInterface(appId, interfaceId, 2);
            if (hourQuota != null && quotaExceeded(hourQuota)) {
                logWarn("应用超出每小时配额: appId={}, interfaceId={}, used={}, limit={}",
                        appId, interfaceId, hourQuota.getQuotaUsed(), hourQuota.getQuotaLimit());
                return true;
            }

            ApiQuota dayQuota = getQuotaByAppAndInterface(appId, interfaceId, 3);
            if (dayQuota != null && quotaExceeded(dayQuota)) {
                logWarn("应用超出每天配额: appId={}, interfaceId={}, used={}, limit={}",
                        appId, interfaceId, dayQuota.getQuotaUsed(), dayQuota.getQuotaLimit());
                return true;
            }

            ApiQuota monthQuota = getQuotaByAppAndInterface(appId, interfaceId, 4);
            if (monthQuota != null && quotaExceeded(monthQuota)) {
                logWarn("应用超出每月配额: appId={}, interfaceId={}, used={}, limit={}",
                        appId, interfaceId, monthQuota.getQuotaUsed(), monthQuota.getQuotaLimit());
                return true;
            }

            return false;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("检查配额失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "检查配额失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void incrementQuotaUsage(Long appId, Long interfaceId, int count) {
        requireNonNull(appId, "应用ID不能为空");
        requireCondition(count, c -> c > 0, "增加数量必须大于0");

        try {
            for (int quotaType = 1; quotaType <= 4; quotaType++) {
                incrementQuotaUsageByType(appId, interfaceId, quotaType, count);
            }
            logDebug("增加配额使用量: appId={}, interfaceId={}, count={}", appId, interfaceId, count);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("增加配额使用量失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "增加配额使用量失败: " + e.getMessage());
        }
    }

    /**
     * 按类型增加配额使用量
     */
    private void incrementQuotaUsageByType(Long appId, Long interfaceId, Integer quotaType, int count) {
        try {
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
                String usageKey = buildQuotaUsageKey(appId, interfaceId, quotaType);
                Long newUsage = redisTemplate.opsForValue().increment(usageKey, count);

                if (newUsage != null && newUsage % 100 == 0) {
                    LambdaUpdateWrapper<ApiQuota> updateWrapper = new LambdaUpdateWrapper<>();
                    updateWrapper.eq(ApiQuota::getId, quota.getId())
                            .set(ApiQuota::getQuotaUsed, newUsage);
                    quotaMapper.update(null, updateWrapper);
                }

                if (newUsage != null
                        && quota.getQuotaLimit() != null
                        && quota.getQuotaLimit() > 0
                        && quota.getAlertThreshold() != null
                        && newUsage >= quota.getQuotaLimit() * quota.getAlertThreshold() / 100) {
                    checkAndSendAlert(quota.getId());
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("增加指定类型配额使用量失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "增加指定类型配额使用量失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetQuota(Long quotaId) {
        requireNonNull(quotaId, "配额ID不能为空");

        try {
            ApiQuota quota = quotaMapper.selectById(quotaId);
            requireNonNull(quota, "配额不存在");

            LambdaUpdateWrapper<ApiQuota> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(ApiQuota::getId, quotaId)
                    .set(ApiQuota::getQuotaUsed, 0)
                    .set(ApiQuota::getIsAlerted, 0)
                    .set(ApiQuota::getResetTime, calculateNextResetTime(quota.getQuotaType()));

            int updated = quotaMapper.update(null, wrapper);
            requireCondition(updated, count -> count > 0, "重置配额失败");

            String usageKey = buildQuotaUsageKey(quota.getAppId(), quota.getInterfaceId(), quota.getQuotaType());
            redisTemplate.delete(usageKey);
            clearQuotaCache(quota.getAppId(), quota.getInterfaceId(), quota.getQuotaType());

            logInfo("重置配额: quotaId={}", quotaId);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("重置配额失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "重置配额失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int resetExpiredQuotas() {
        try {
            LocalDateTime now = LocalDateTime.now();

            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            wrapper.le(ApiQuota::getResetTime, now);

            List<ApiQuota> expiredQuotas = quotaMapper.selectList(wrapper);
            int resetCount = 0;

            for (ApiQuota quota : expiredQuotas) {
                try {
                    resetQuota(quota.getId());
                    resetCount++;
                } catch (BusinessException ex) {
                    logWarn("重置配额失败，略过 quotaId={}: {}", quota.getId(), ex.getMessage());
                } catch (Exception inner) {
                    logError("重置配额异常: quotaId={}", quota.getId(), inner);
                }
            }

            logInfo("重置过期配额: 总数={}, 成功={}", expiredQuotas.size(), resetCount);
            return resetCount;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("重置过期配额失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "重置过期配额失败: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getQuotaStatistics(Long appId) {
        requireNonNull(appId, "应用ID不能为空");

        try {
            Map<String, Object> stats = new HashMap<>();

            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiQuota::getAppId, appId);

            List<ApiQuota> quotas = quotaMapper.selectList(wrapper);

            stats.put("total_quotas", quotas.size());
            stats.put("quotas", quotas);

            if (!quotas.isEmpty()) {
                double totalUsageRate = quotas.stream()
                        .mapToDouble(q -> {
                            Long limit = q.getQuotaLimit();
                            if (limit == null || limit <= 0) {
                                return 0.0;
                            }
                            return (double) q.getQuotaUsed() / limit * 100;
                        })
                        .average()
                        .orElse(0.0);
                stats.put("avg_usage_rate", Math.round(totalUsageRate * 100) / 100.0);
            } else {
                stats.put("avg_usage_rate", 0.0);
            }

            stats.put("stat_time", LocalDateTime.now());
            return stats;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("获取配额统计失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "获取配额统计失败: " + e.getMessage());
        }
    }

    @Override
    public void checkAndSendAlert(Long quotaId) {
        try {
            ApiQuota quota = quotaMapper.selectById(quotaId);
            if (quota == null || quota.getIsAlerted() == 1) {
                return;
            }

            Long quotaLimit = quota.getQuotaLimit();
            if (quotaLimit == null || quotaLimit <= 0) {
                return;
            }

            double usageRate = (double) quota.getQuotaUsed() / quotaLimit * 100;
            if (usageRate >= quota.getAlertThreshold()) {
                String alertMessage = String.format(
                        "应用ID %d 的API配额即将超限：已使用 %.2f%%（%d/%d）",
                        quota.getAppId(), usageRate, quota.getQuotaUsed(), quotaLimit
                );
                alertService.sendAlert("QUOTA_WARNING", alertMessage, "MEDIUM");

                LambdaUpdateWrapper<ApiQuota> wrapper = new LambdaUpdateWrapper<>();
                wrapper.eq(ApiQuota::getId, quotaId)
                        .set(ApiQuota::getIsAlerted, 1);
                quotaMapper.update(null, wrapper);

                logWarn("发送配额告警: quotaId={}, usageRate={}%", quotaId, String.format("%.2f", usageRate));
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("检查并发送告警失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "检查并发送告警失败: " + e.getMessage());
        }
    }

    @Override
    public List<ApiQuota> getQuotasNearLimit(int threshold) {
        requireCondition(threshold, t -> t > 0 && t <= 100, "阈值必须在1-100之间");

        try {
            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            List<ApiQuota> allQuotas = quotaMapper.selectList(wrapper);

            return allQuotas.stream()
                    .filter(quota -> {
                        Long limit = quota.getQuotaLimit();
                        if (limit == null || limit <= 0) {
                            return false;
                        }
                        double usageRate = (double) quota.getQuotaUsed() / limit * 100;
                        return usageRate >= threshold;
                    })
                    .toList();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("查询即将超限的配额失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "查询即将超限的配额失败: " + e.getMessage());
        }
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

    private boolean quotaExceeded(ApiQuota quota) {
        if (quota == null) {
            return false;
        }
        Long limit = quota.getQuotaLimit();
        if (limit == null || limit <= 0) {
            return false;
        }
        Long used = quota.getQuotaUsed();
        return used != null && used >= limit;
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
    public List<ApiQuota> getAllActiveQuotas() {
        try {
            LambdaQueryWrapper<ApiQuota> wrapper = new LambdaQueryWrapper<>();
            wrapper.orderByAsc(ApiQuota::getAppId, ApiQuota::getQuotaType);

            List<ApiQuota> quotas = quotaMapper.selectList(wrapper);
            return quotas != null ? quotas : List.of();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            logError("获取所有启用的配额失败", e);
            throw businessException(ResultEnum.SYSTEM_ERROR, "获取所有启用的配额失败: " + e.getMessage());
        }
    }
}
