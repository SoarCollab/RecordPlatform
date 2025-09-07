package cn.flying.identity.service.impl;

import cn.flying.identity.config.TrafficMonitorConfig;
import cn.flying.identity.dto.TrafficMonitorEntity;
import cn.flying.identity.mapper.TrafficMonitorMapper;
import cn.flying.identity.service.TrafficMonitorService;
import cn.flying.identity.util.IdUtils;
import cn.flying.identity.util.IpUtils;
import cn.flying.identity.util.UserAgentUtils;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 流量监控服务实现类
 * 基于Redis和数据库的高性能流量监控和异常检测
 *
 * @author 王贝强
 */
@Slf4j
@Service
public class TrafficMonitorServiceImpl extends ServiceImpl<TrafficMonitorMapper, TrafficMonitorEntity>
        implements TrafficMonitorService {

    @Resource
    private TrafficMonitorConfig trafficMonitorConfig;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Override
    public Result<Void> recordTrafficInfo(String requestId, String clientIp, Long userId,
                                          String requestPath, String requestMethod, String userAgent) {
        try {
            if (!trafficMonitorConfig.getMonitor().isEnabled()) {
                return Result.success();
            }

            // 基于采样率决定是否记录
            if (Math.random() > trafficMonitorConfig.getMonitor().getSamplingRate()) {
                return Result.success();
            }

            // 构建流量监控实体
            TrafficMonitorEntity entity = new TrafficMonitorEntity()
                    .setId(IdUtils.nextUserId())
                    .setRequestId(requestId)
                    .setClientIp(clientIp)
                    .setUserId(userId)
                    .setRequestPath(requestPath)
                    .setRequestMethod(requestMethod)
                    .setUserAgent(userAgent)
                    .setGeoLocation(IpUtils.getIpLocation(clientIp))
                    .setDeviceFingerprint(UserAgentUtils.generateDeviceFingerprint(userAgent))
                    .setRequestTime(LocalDateTime.now())
                    .setIsAbnormal(false)
                    .setRiskScore(0)
                    .setBlockStatus(TrafficMonitorEntity.BlockStatus.NORMAL.getCode());

            // 异步记录到数据库
            if (trafficMonitorConfig.getMonitor().isAsyncEnabled()) {
                recordTrafficInfoAsync(entity);
            } else {
                this.save(entity);
            }

            // 实时统计到Redis
            updateRealTimeStats(clientIp, userId, requestPath, requestMethod);

            return Result.success();
        } catch (Exception e) {
            log.error("记录流量信息失败", e);
            return Result.error(ResultEnum.FAIL);
        }
    }

    @Override
    public Result<Void> recordResponseInfo(String requestId, Integer responseStatus, Long responseTime,
                                           Long requestSize, Long responseSize) {
        try {
            // 更新数据库记录
            TrafficMonitorEntity updateEntity = new TrafficMonitorEntity()
                    .setResponseStatus(responseStatus)
                    .setResponseTime(responseTime)
                    .setRequestSize(requestSize)
                    .setResponseSize(responseSize);

            this.lambdaUpdate()
                    .eq(TrafficMonitorEntity::getRequestId, requestId)
                    .update(updateEntity);

            // 更新响应时间统计
            updateResponseTimeStats(responseTime);

            return Result.success();
        } catch (Exception e) {
            log.error("记录响应信息失败", e);
            return Result.error(ResultEnum.FAIL);
        }
    }

    @Override
    public Result<Map<String, Object>> checkTrafficBlock(String clientIp, Long userId,
                                                         String requestPath, String userAgent) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("blocked", false);
            result.put("blockReason", "");
            result.put("blockLevel", TrafficMonitorConfig.Constants.BLOCK_LEVEL_NONE);

            // 检查白名单
            if (isInWhitelist(clientIp)) {
                return Result.success(result);
            }

            // 检查永久黑名单
            if (isInPermanentBlacklist(clientIp)) {
                result.put("blocked", true);
                result.put("blockReason", "IP in permanent blacklist");
                result.put("blockLevel", TrafficMonitorConfig.Constants.BLOCK_LEVEL_PERMANENT_BAN);
                return Result.success(result);
            }

            // 检查临时黑名单
            if (isBlacklisted(clientIp).getData()) {
                result.put("blocked", true);
                result.put("blockReason", "IP in temporary blacklist");
                result.put("blockLevel", TrafficMonitorConfig.Constants.BLOCK_LEVEL_BLACKLIST);
                return Result.success(result);
            }

            // 检查限流
            Result<Map<String, Object>> rateLimitResult = checkRateLimit(clientIp, userId, requestPath);
            if (rateLimitResult.getData().get("limited").equals(true)) {
                result.put("blocked", true);
                result.put("blockReason", "Rate limit exceeded");
                result.put("blockLevel", TrafficMonitorConfig.Constants.BLOCK_LEVEL_RATE_LIMIT);
                result.put("retryAfter", rateLimitResult.getData().get("retryAfter"));
                return Result.success(result);
            }

            // 检查异常模式
            Result<Map<String, Object>> anomalyResult = detectRealTimeAnomalies(clientIp, userId, requestPath, userAgent);
            if (anomalyResult.getData().get("isAnomalous").equals(true)) {
                int riskScore = (Integer) anomalyResult.getData().get("riskScore");
                if (riskScore >= trafficMonitorConfig.getAnomalyDetection().getRiskScoreThreshold()) {
                    result.put("blocked", true);
                    result.put("blockReason", "Anomalous traffic pattern detected");
                    result.put("blockLevel", TrafficMonitorConfig.Constants.BLOCK_LEVEL_TEMPORARY_BLOCK);
                    result.put("riskScore", riskScore);

                    // 自动添加到临时黑名单
                    if (trafficMonitorConfig.getBlocking().isAutoBlockEnabled()) {
                        addToBlacklist(clientIp, "Auto-blocked due to anomalous pattern", 1);
                    }
                }
            }

            return Result.success(result);
        } catch (Exception e) {
            log.error("检查流量拦截失败", e);
            return Result.error(ResultEnum.FAIL, null);
        }
    }

    @Override
    public Result<Map<String, Object>> detectAnomalies(String clientIp, Long userId, String requestPath,
                                                       Long responseTime, Integer responseStatus) {
        try {
            Map<String, Object> result = new HashMap<>();
            List<String> anomalies = new ArrayList<>();
            int riskScore = 0;

            // 响应时间异常检测
            if (responseTime > trafficMonitorConfig.getAnomalyDetection().getResponseTimeThreshold()) {
                anomalies.add(TrafficMonitorConfig.Constants.ANOMALY_HIGH_FREQUENCY);
                riskScore += 20;
            }

            // 错误率异常检测
            if (responseStatus >= 400) {
                double errorRate = calculateErrorRate(clientIp);
                if (errorRate > trafficMonitorConfig.getAnomalyDetection().getErrorRateThreshold()) {
                    anomalies.add("HIGH_ERROR_RATE");
                    riskScore += 30;
                }
            }

            // 地理位置异常检测
            if (trafficMonitorConfig.getAnomalyDetection().isGeoAnomalyEnabled()) {
                if (detectGeoAnomaly(clientIp, userId)) {
                    anomalies.add(TrafficMonitorConfig.Constants.ANOMALY_GEO_LOCATION);
                    riskScore += 25;
                }
            }

            // 时间模式异常检测
            if (trafficMonitorConfig.getAnomalyDetection().isTimeAnomalyEnabled()) {
                if (detectTimeAnomaly(clientIp)) {
                    anomalies.add(TrafficMonitorConfig.Constants.ANOMALY_TIME_PATTERN);
                    riskScore += 15;
                }
            }

            // DDoS攻击检测
            if (detectDDoSPattern(clientIp)) {
                anomalies.add(TrafficMonitorConfig.Constants.ANOMALY_DDOS_ATTACK);
                riskScore += 50;
            }

            result.put("isAnomalous", !anomalies.isEmpty());
            result.put("anomalies", anomalies);
            result.put("riskScore", Math.min(riskScore, 100));
            result.put("timestamp", LocalDateTime.now());

            return Result.success(result);
        } catch (Exception e) {
            log.error("异常检测失败", e);
            return Result.error(ResultEnum.FAIL, null);
        }
    }

    @Override
    public Result<Void> addToBlacklist(String clientIp, String reason, int durationHours) {
        try {
            String blacklistKey = TrafficMonitorConfig.Constants.BLACKLIST_PREFIX + clientIp;
            Map<String, String> blacklistInfo = new HashMap<>();
            blacklistInfo.put("ip", clientIp);
            blacklistInfo.put("reason", reason);
            blacklistInfo.put("addedAt", LocalDateTime.now().toString());
            blacklistInfo.put("expiresAt", LocalDateTime.now().plusHours(durationHours).toString());

            redisTemplate.opsForValue().set(blacklistKey, JSONUtil.toJsonStr(blacklistInfo),
                    durationHours, TimeUnit.HOURS);

            log.warn("IP {} 已添加到黑名单, 原因: {}, 持续时间: {}小时", clientIp, reason, durationHours);
            return Result.success();
        } catch (Exception e) {
            log.error("添加黑名单失败", e);
            return Result.error(ResultEnum.FAIL);
        }
    }

    @Override
    public Result<Void> removeFromBlacklist(String clientIp) {
        try {
            String blacklistKey = TrafficMonitorConfig.Constants.BLACKLIST_PREFIX + clientIp;
            redisTemplate.delete(blacklistKey);
            log.info("IP {} 已从黑名单移除", clientIp);
            return Result.success();
        } catch (Exception e) {
            log.error("移除黑名单失败", e);
            return Result.error(ResultEnum.FAIL);
        }
    }

    @Override
    public Result<Boolean> isBlacklisted(String clientIp) {
        try {
            String blacklistKey = TrafficMonitorConfig.Constants.BLACKLIST_PREFIX + clientIp;
            boolean exists = redisTemplate.hasKey(blacklistKey);
            return Result.success(exists);
        } catch (Exception e) {
            log.error("检查黑名单状态失败", e);
            return Result.success(false); // 默认不拦截
        }
    }

    @Override
    public Result<Map<String, Object>> getRealTimeTrafficStats(int timeRangeMinutes) {
        try {
            Map<String, Object> stats = new HashMap<>();

            // 从Redis获取实时统计数据
            String statsKey = TrafficMonitorConfig.Constants.STATS_PREFIX + "global:" +
                    (System.currentTimeMillis() / ((long) timeRangeMinutes * 60 * 1000));

            String statsJson = redisTemplate.opsForValue().get(statsKey);
            if (statsJson != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> tempStats = JSONUtil.toBean(statsJson, Map.class);
                stats = tempStats;
            } else {
                // 如果Redis中没有数据，从数据库查询
                stats = getTrafficStatsFromDB(timeRangeMinutes);
            }

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取实时流量统计失败", e);
            return Result.error(ResultEnum.FAIL, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getAnomalousTrafficStats(int timeRangeMinutes) {
        try {
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusMinutes(timeRangeMinutes);

            Map<String, Object> stats = new HashMap<>();

            // 查询异常流量数据
            List<TrafficMonitorEntity> anomalousTraffic = this.lambdaQuery()
                    .eq(TrafficMonitorEntity::getIsAbnormal, true)
                    .between(TrafficMonitorEntity::getRequestTime, startTime, endTime)
                    .list();

            // 统计异常类型分布
            Map<String, Long> anomalyTypeDistribution = new HashMap<>();
            for (TrafficMonitorEntity entity : anomalousTraffic) {
                String type = entity.getAbnormalType();
                anomalyTypeDistribution.put(type, anomalyTypeDistribution.getOrDefault(type, 0L) + 1);
            }

            stats.put("totalAnomalousRequests", anomalousTraffic.size());
            stats.put("anomalyTypeDistribution", anomalyTypeDistribution);
            stats.put("timeRange", timeRangeMinutes + " minutes");
            stats.put("calculatedAt", LocalDateTime.now());

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取异常流量统计失败", e);
            return Result.error(ResultEnum.FAIL, null);
        }
    }

    @Override
    public Result<List<Map<String, Object>>> getTopTrafficIps(int timeRangeMinutes, int limit) {
        // 实现通过mapper查询
        return Result.success(new ArrayList<>());
    }

    @Override
    public Result<List<Map<String, Object>>> getTopApis(int timeRangeMinutes, int limit) {
        // 实现通过mapper查询
        return Result.success(new ArrayList<>());
    }

    @Override
    public Result<List<Map<String, Object>>> getBlacklistInfo() {
        try {
            List<Map<String, Object>> blacklistInfo = new ArrayList<>();
            Set<String> blacklistKeys = redisTemplate.keys(TrafficMonitorConfig.Constants.BLACKLIST_PREFIX + "*");

            for (String key : blacklistKeys) {
                String infoJson = redisTemplate.opsForValue().get(key);
                if (infoJson != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> info = JSONUtil.toBean(infoJson, Map.class);
                    Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                    info.put("remainingSeconds", ttl);
                    blacklistInfo.add(info);
                }
            }

            return Result.success(blacklistInfo);
        } catch (Exception e) {
            log.error("获取黑名单信息失败", e);
            return Result.error(ResultEnum.FAIL, null);
        }
    }

    @Override
    public Result<Map<String, Object>> cleanExpiredData(int retentionDays) {
        try {
            LocalDateTime expireTime = LocalDateTime.now().minusDays(retentionDays);

            // 删除过期的流量监控数据
            int deletedCount = this.lambdaUpdate()
                    .lt(TrafficMonitorEntity::getCreateTime, expireTime)
                    .remove() ? 1 : 0;

            Map<String, Object> result = new HashMap<>();
            result.put("deletedRecords", deletedCount);
            result.put("retentionDays", retentionDays);
            result.put("cleanedAt", LocalDateTime.now());

            return Result.success(result);
        } catch (Exception e) {
            log.error("清理过期数据失败", e);
            return Result.error(ResultEnum.FAIL, null);
        }
    }

    @Override
    public Result<String> exportTrafficData(LocalDateTime startTime, LocalDateTime endTime, String clientIp) {
        // 暂不实现，返回提示信息
        return Result.error("导出功能暂未实现");
    }

    @Override
    public Result<Map<String, Object>> getTrafficDashboard() {
        try {
            Map<String, Object> dashboard = new HashMap<>();

            // 获取实时统计
            Result<Map<String, Object>> realTimeStats = getRealTimeTrafficStats(60);
            dashboard.put("realTimeStats", realTimeStats.getData());

            // 获取异常统计
            Result<Map<String, Object>> anomalousStats = getAnomalousTrafficStats(60);
            dashboard.put("anomalousStats", anomalousStats.getData());

            // 获取黑名单信息
            Result<List<Map<String, Object>>> blacklistInfo = getBlacklistInfo();
            dashboard.put("blacklistInfo", blacklistInfo.getData());

            return Result.success(dashboard);
        } catch (Exception e) {
            log.error("获取流量监控仪表板失败", e);
            return Result.error(ResultEnum.FAIL, null);
        }
    }

    @Override
    public Result<Void> updateBlockingRule(String ruleType, Object ruleValue) {
        // 暂不实现，返回成功
        return Result.success();
    }

    @Override
    public Result<Map<String, Object>> triggerAnomalyDetection(String clientIp) {
        // 暂不实现，返回空结果
        return Result.success(new HashMap<>());
    }

    @Override
    public Result<Map<String, Object>> getSystemHealthStatus() {
        try {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "healthy");
            health.put("timestamp", LocalDateTime.now());
            health.put("monitorEnabled", trafficMonitorConfig.getMonitor().isEnabled());
            health.put("rateLimitEnabled", trafficMonitorConfig.getRateLimit().isEnabled());
            health.put("anomalyDetectionEnabled", trafficMonitorConfig.getAnomalyDetection().isEnabled());

            return Result.success(health);
        } catch (Exception e) {
            log.error("获取系统健康状态失败", e);
            return Result.error(ResultEnum.FAIL, null);
        }
    }

    // ==================== 私有方法 ====================

    private Map<String, Object> getTrafficStatsFromDB(int timeRangeMinutes) {
        Map<String, Object> stats = new HashMap<>();
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusMinutes(timeRangeMinutes);

        // 从数据库查询统计数据
        long totalRequests = this.lambdaQuery()
                .between(TrafficMonitorEntity::getRequestTime, startTime, endTime)
                .count();

        stats.put("totalRequests", totalRequests);
        stats.put("timeRange", timeRangeMinutes + " minutes");
        stats.put("calculatedAt", LocalDateTime.now());

        return stats;
    }

    private double calculateErrorRate(String clientIp) {
        // 简化的错误率计算
        return 0.0;
    }

    private boolean detectGeoAnomaly(String clientIp, Long userId) {
        // 简化的地理位置异常检测
        return false;
    }

    private boolean detectTimeAnomaly(String clientIp) {
        // 简化的时间异常检测
        return false;
    }

    private boolean detectDDoSPattern(String clientIp) {
        try {
            String ddosKey = TrafficMonitorConfig.Constants.ANOMALY_PREFIX + "ddos:" + clientIp;
            Long requestCount = redisTemplate.opsForValue().increment(ddosKey);
            redisTemplate.expire(ddosKey, 1, TimeUnit.SECONDS);

            return requestCount != null && requestCount > trafficMonitorConfig.getAnomalyDetection().getDdosThreshold();
        } catch (Exception e) {
            log.error("检测DDoS模式失败", e);
            return false;
        }
    }

    private boolean isInWhitelist(String clientIp) {
        String[] whitelistIps = trafficMonitorConfig.getBlocking().getWhitelistIps();
        return Arrays.asList(whitelistIps).contains(clientIp);
    }

    private boolean isInPermanentBlacklist(String clientIp) {
        String[] permanentBlacklistIps = trafficMonitorConfig.getBlocking().getPermanentBlacklistIps();
        return Arrays.asList(permanentBlacklistIps).contains(clientIp);
    }

    private Result<Map<String, Object>> checkRateLimit(String clientIp, Long userId, String requestPath) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("limited", false);
            result.put("retryAfter", 0);

            if (!trafficMonitorConfig.getRateLimit().isEnabled()) {
                return Result.success(result);
            }

            // 检查IP限流
            String ipLimitKey = TrafficMonitorConfig.Constants.RATE_LIMIT_PREFIX + "ip:" + clientIp;
            Long currentCount = redisTemplate.opsForValue().increment(ipLimitKey);
            redisTemplate.expire(ipLimitKey, 1, TimeUnit.MINUTES);

            if (currentCount != null && currentCount > trafficMonitorConfig.getRateLimit().getIpRequestsPerMinute()) {
                result.put("limited", true);
                result.put("retryAfter", 60);
                return Result.success(result);
            }

            // 检查用户限流
            if (userId != null) {
                String userLimitKey = TrafficMonitorConfig.Constants.RATE_LIMIT_PREFIX + "user:" + userId;
                Long userCount = redisTemplate.opsForValue().increment(userLimitKey);
                redisTemplate.expire(userLimitKey, 1, TimeUnit.MINUTES);

                if (userCount != null && userCount > trafficMonitorConfig.getRateLimit().getUserRequestsPerMinute()) {
                    result.put("limited", true);
                    result.put("retryAfter", 60);
                    return Result.success(result);
                }
            }

            return Result.success(result);
        } catch (Exception e) {
            log.error("检查限流失败", e);
            // 出错时不限流
            Map<String, Object> result = new HashMap<>();
            result.put("limited", false);
            return Result.success(result);
        }
    }

    private Result<Map<String, Object>> detectRealTimeAnomalies(String clientIp, Long userId, String requestPath, String userAgent) {
        Map<String, Object> result = new HashMap<>();
        result.put("isAnomalous", false);
        result.put("riskScore", 0);

        // 简化的实时异常检测
        int riskScore = 0;

        // 检查请求频率
        if (getRecentRequestCount(clientIp) > 100) {
            riskScore += 30;
        }

        // 检查User-Agent
        if (UserAgentUtils.isBotUserAgent(userAgent)) {
            riskScore += 40;
        }

        result.put("isAnomalous", riskScore > 50);
        result.put("riskScore", riskScore);

        return Result.success(result);
    }

    private int getRecentRequestCount(String clientIp) {
        try {
            String countKey = TrafficMonitorConfig.Constants.STATS_PREFIX + "recent:" + clientIp;
            String count = redisTemplate.opsForValue().get(countKey);
            return count != null ? Integer.parseInt(count) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateResponseTimeStats(Long responseTime) {
        try {
            long currentMinute = System.currentTimeMillis() / (60 * 1000);
            String statsKey = TrafficMonitorConfig.Constants.STATS_PREFIX + "response:" + currentMinute;

            redisTemplate.opsForList().rightPush(statsKey, responseTime.toString());
            redisTemplate.expire(statsKey, trafficMonitorConfig.getMonitor().getTimeWindow(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("更新响应时间统计失败", e);
        }
    }

    @Async
    public void recordTrafficInfoAsync(TrafficMonitorEntity entity) {
        try {
            this.save(entity);
        } catch (Exception e) {
            log.error("异步记录流量信息失败", e);
        }
    }

    private void updateRealTimeStats(String clientIp, Long userId, String requestPath, String requestMethod) {
        try {
            long currentMinute = System.currentTimeMillis() / (60 * 1000);
            String statsKey = TrafficMonitorConfig.Constants.STATS_PREFIX + "global:" + currentMinute;

            // 增加请求计数
            redisTemplate.opsForHash().increment(statsKey, "totalRequests", 1);
            redisTemplate.opsForHash().increment(statsKey, "uniqueIps", clientIp.hashCode() % 1000);
            redisTemplate.expire(statsKey, trafficMonitorConfig.getMonitor().getTimeWindow(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("更新实时统计失败", e);
        }
    }
}
