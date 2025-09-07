package cn.flying.identity.service.impl;

import cn.flying.identity.dto.TokenMonitor;
import cn.flying.identity.mapper.TokenMonitorMapper;
import cn.flying.identity.service.TokenMonitorService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Token监控服务实现类
 * 提供Token监控的业务逻辑处理
 *
 * @author flying
 * @date 2024
 */
@Service
public class TokenMonitorServiceImpl implements TokenMonitorService {

    @Resource
    private TokenMonitorMapper tokenMonitorMapper;

    /**
     * 记录Token事件
     *
     * @param tokenMonitor Token监控对象
     * @return 操作结果
     */
    @Override
    public Result<Void> recordTokenEvent(TokenMonitor tokenMonitor) {
        try {
            if (tokenMonitor == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID);
            }

            if (tokenMonitor.getEventTime() == null) {
                tokenMonitor.setEventTime(LocalDateTime.now());
            }

            // 计算风险评分
            Integer riskScore = calculateRiskScore(tokenMonitor);
            tokenMonitor.setRiskScore(riskScore);

            // 检测异常并设置异常类型
            detectAndSetAbnormal(tokenMonitor);

            tokenMonitorMapper.insert(tokenMonitor);
            return Result.success();
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR);
        }
    }

    /**
     * 异步记录Token事件
     *
     * @param tokenMonitor Token监控对象
     */
    @Override
    @Async
    public void recordTokenEventAsync(TokenMonitor tokenMonitor) {
        recordTokenEvent(tokenMonitor);
    }

    /**
     * 记录Token创建事件
     */
    @Override
    public Result<Void> recordTokenCreation(String tokenId, String tokenType, Long userId,
                                            String clientId, String clientIp, String userAgent,
                                            LocalDateTime expiresAt) {
        try {
            TokenMonitor monitor = new TokenMonitor();
            monitor.setTokenId(tokenId);
            monitor.setTokenType(tokenType);
            monitor.setUserId(userId);
            monitor.setClientId(clientId);
            monitor.setEventType(TokenMonitor.EventType.CREATE.getCode());
            monitor.setEventDesc("Token创建");
            monitor.setClientIp(clientIp);
            monitor.setUserAgent(userAgent);
            monitor.setTokenExpireTime(expiresAt);
            monitor.setEventTime(LocalDateTime.now());

            // 计算风险评分
            Integer riskScore = calculateRiskScore(monitor);
            monitor.setRiskScore(riskScore);

            // 检测异常并设置异常类型
            detectAndSetAbnormal(monitor);

            tokenMonitorMapper.insert(monitor);
            return Result.success();
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 记录Token使用事件
     */
    @Override
    public Result<Void> recordTokenUsage(String tokenId, String tokenType, Long userId,
                                         String clientId, String clientIp, String userAgent,
                                         String requestUrl, String requestMethod) {
        try {
            TokenMonitor monitor = new TokenMonitor();
            monitor.setTokenId(tokenId);
            monitor.setTokenType(tokenType);
            monitor.setUserId(userId);
            monitor.setClientId(clientId);
            monitor.setEventType(TokenMonitor.EventType.USE.getCode());
            monitor.setEventDesc("Token使用");
            monitor.setRequestUrl(requestUrl);
            monitor.setRequestMethod(requestMethod);
            monitor.setClientIp(clientIp);
            monitor.setUserAgent(userAgent);
            monitor.setEventTime(LocalDateTime.now());

            // 计算风险评分
            Integer riskScore = calculateRiskScore(monitor);
            monitor.setRiskScore(riskScore);

            // 检测异常并设置异常类型
            detectAndSetAbnormal(monitor);

            tokenMonitorMapper.insert(monitor);
            return Result.success();
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 记录Token刷新事件
     */
    @Override
    public Result<Void> recordTokenRefresh(String oldTokenId, String newTokenId, String tokenType,
                                           Long userId, String clientId, String clientIp, String userAgent) {
        try {
            TokenMonitor monitor = new TokenMonitor();
            monitor.setTokenId(newTokenId);
            monitor.setTokenType(tokenType);
            monitor.setUserId(userId);
            monitor.setClientId(clientId);
            monitor.setEventType(TokenMonitor.EventType.REFRESH.getCode());
            monitor.setEventDesc("Token刷新，旧Token: " + oldTokenId);
            monitor.setClientIp(clientIp);
            monitor.setUserAgent(userAgent);
            monitor.setEventTime(LocalDateTime.now());

            // 计算风险评分
            Integer riskScore = calculateRiskScore(monitor);
            monitor.setRiskScore(riskScore);

            // 检测异常并设置异常类型
            detectAndSetAbnormal(monitor);

            tokenMonitorMapper.insert(monitor);
            return Result.success();
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 记录Token撤销事件
     */
    @Override
    public Result<Void> recordTokenRevocation(String tokenId, String tokenType, Long userId,
                                              String clientId, String clientIp, String userAgent, String reason) {
        try {
            TokenMonitor monitor = new TokenMonitor();
            monitor.setTokenId(tokenId);
            monitor.setTokenType(tokenType);
            monitor.setUserId(userId);
            monitor.setClientId(clientId);
            monitor.setEventType(TokenMonitor.EventType.REVOKE.getCode());
            monitor.setEventDesc("Token撤销，原因: " + reason);
            monitor.setClientIp(clientIp);
            monitor.setUserAgent(userAgent);
            monitor.setEventTime(LocalDateTime.now());

            // 计算风险评分
            Integer riskScore = calculateRiskScore(monitor);
            monitor.setRiskScore(riskScore);

            // 检测异常并设置异常类型
            detectAndSetAbnormal(monitor);

            tokenMonitorMapper.insert(monitor);
            return Result.success();
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 分页查询Token监控记录
     */
    @Override
    public Result<Map<String, Object>> getTokenMonitorPage(int page, int size, String tokenId, String userId, String clientId, String eventType, LocalDateTime startTime, LocalDateTime endTime) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("tokenId", tokenId);
            params.put("userId", userId);
            params.put("clientId", clientId);
            params.put("eventType", eventType);
            params.put("startTime", startTime);
            params.put("endTime", endTime);

            long total = tokenMonitorMapper.countByParams(params);

            params.put("offset", (page - 1) * size);
            params.put("limit", size);

            List<TokenMonitor> records = tokenMonitorMapper.selectByParams(params);

            Map<String, Object> result = new HashMap<>();
            result.put("total", total);
            result.put("records", records);

            return Result.success(result);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 记录Token过期事件
     */
    @Override
    public Result<Void> recordTokenExpiration(String tokenId, String tokenType, Long userId, String clientId) {
        try {
            TokenMonitor monitor = new TokenMonitor();
            monitor.setTokenId(tokenId);
            monitor.setTokenType(tokenType);
            monitor.setUserId(userId);
            monitor.setClientId(clientId);
            monitor.setEventType(TokenMonitor.EventType.EXPIRE.getCode());
            monitor.setEventDesc("Token过期");
            monitor.setEventTime(LocalDateTime.now());

            // 计算风险评分
            Integer riskScore = calculateRiskScore(monitor);
            monitor.setRiskScore(riskScore);

            // 检测异常并设置异常类型
            detectAndSetAbnormal(monitor);

            tokenMonitorMapper.insert(monitor);
            return Result.success();
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 记录Token异常事件
     */
    @Override
    public Result<Void> recordTokenAbnormal(String tokenId, String tokenType, Long userId, String clientId,
                                            String clientIp, String userAgent, String abnormalType,
                                            String description, Integer riskScore) {
        try {
            TokenMonitor monitor = new TokenMonitor();
            monitor.setTokenId(tokenId);
            monitor.setTokenType(tokenType);
            monitor.setUserId(userId);
            monitor.setClientId(clientId);
            monitor.setEventType("ABNORMAL");
            monitor.setEventDesc(description);
            monitor.setClientIp(clientIp);
            monitor.setUserAgent(userAgent);
            monitor.setIsAbnormal(true);
            monitor.setAbnormalType(abnormalType);
            monitor.setRiskScore(riskScore);
            monitor.setEventTime(LocalDateTime.now());

            tokenMonitorMapper.insert(monitor);
            return Result.success();
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据Token ID查询监控记录
     */
    @Override
    public Result<List<TokenMonitor>> getMonitorsByTokenId(String tokenId, LocalDateTime startTime,
                                                           LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findByTokenIdAndTimeRange(tokenId, startTime, endTime);
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据用户ID查询监控记录
     */
    @Override
    public Result<List<TokenMonitor>> getMonitorsByUserId(Long userId, LocalDateTime startTime,
                                                          LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findByUserIdAndTimeRange(userId, startTime, endTime);
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据客户端ID查询监控记录
     */
    @Override
    public Result<List<TokenMonitor>> getMonitorsByClientId(String clientId, LocalDateTime startTime,
                                                            LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findByClientIdAndTimeRange(clientId, startTime, endTime);
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 查询异常事件
     */
    @Override
    public Result<List<TokenMonitor>> getAbnormalEvents(LocalDateTime startTime, LocalDateTime endTime,
                                                        int pageNum, int pageSize) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findAbnormalEvents(startTime, endTime);
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据事件类型查询监控记录
     */
    @Override
    public Result<List<TokenMonitor>> getMonitorsByEventType(String eventType, LocalDateTime startTime,
                                                             LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findByEventTypeAndTimeRange(eventType, startTime, endTime);
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据异常类型查询监控记录
     */
    @Override
    public Result<List<TokenMonitor>> getMonitorsByAbnormalType(String abnormalType, LocalDateTime startTime,
                                                                LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findByAbnormalTypeAndTimeRange(abnormalType, startTime, endTime);
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据风险评分查询监控记录
     */
    @Override
    public Result<List<TokenMonitor>> getMonitorsByRiskScore(Integer minScore, Integer maxScore,
                                                             LocalDateTime startTime, LocalDateTime endTime,
                                                             int pageNum, int pageSize) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findByRiskScoreRange(minScore, maxScore, startTime, endTime);
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 查询高风险事件
     */
    @Override
    public Result<List<TokenMonitor>> getHighRiskEvents(Integer minRiskScore, LocalDateTime startTime,
                                                        LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findHighRiskEvents(minRiskScore, startTime, endTime);
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 查询未处理的异常事件
     */
    @Override
    public Result<List<TokenMonitor>> getUnhandledAbnormalEvents(int pageNum, int pageSize) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findUnhandledAbnormalEvents();
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 查询可疑活动
     */
    @Override
    public Result<List<TokenMonitor>> getSuspiciousActivities(LocalDateTime startTime, LocalDateTime endTime,
                                                              int pageNum, int pageSize) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findSuspiciousActivities(startTime, endTime);
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 查询Token生命周期
     */
    @Override
    public Result<List<TokenMonitor>> getTokenLifecycle(String tokenId) {
        try {
            List<TokenMonitor> monitors = tokenMonitorMapper.findTokenLifecycle(tokenId);
            return Result.success(monitors);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取事件类型统计
     */
    @Override
    public Result<List<Map<String, Object>>> getEventTypeStats(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            List<Map<String, Object>> stats = tokenMonitorMapper.countByEventType(startTime, endTime);
            return Result.success(stats);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取Token类型统计
     */
    @Override
    public Result<List<Map<String, Object>>> getTokenTypeStats(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            List<Map<String, Object>> stats = tokenMonitorMapper.countByTokenType(startTime, endTime);
            return Result.success(stats);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取异常类型统计
     */
    @Override
    public Result<List<Map<String, Object>>> getAbnormalTypeStats(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            List<Map<String, Object>> stats = tokenMonitorMapper.countByAbnormalType(startTime, endTime);
            return Result.success(stats);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取用户Token使用统计
     */
    @Override
    public Result<List<Map<String, Object>>> getUserTokenStats(LocalDateTime startTime, LocalDateTime endTime, Integer limit) {
        try {
            List<Map<String, Object>> stats = tokenMonitorMapper.countByUser(startTime, endTime, limit);
            return Result.success(stats);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取客户端Token使用统计
     */
    @Override
    public Result<List<Map<String, Object>>> getClientTokenStats(LocalDateTime startTime, LocalDateTime endTime, Integer limit) {
        try {
            List<Map<String, Object>> stats = tokenMonitorMapper.countByClient(startTime, endTime, limit);
            return Result.success(stats);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取IP访问统计
     */
    @Override
    public Result<List<Map<String, Object>>> getIpAccessStats(LocalDateTime startTime, LocalDateTime endTime, Integer limit) {
        try {
            List<Map<String, Object>> stats = tokenMonitorMapper.countByClientIp(startTime, endTime, limit);
            return Result.success(stats);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取每日事件统计
     */
    @Override
    public Result<List<Map<String, Object>>> getDailyEventStats(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            List<Map<String, Object>> stats = tokenMonitorMapper.countByDate(startTime, endTime);
            return Result.success(stats);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取每小时事件统计
     */
    @Override
    public Result<List<Map<String, Object>>> getHourlyEventStats(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            List<Map<String, Object>> stats = tokenMonitorMapper.countByHour(startTime, endTime);
            return Result.success(stats);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 评估Token风险
     */
    @Override
    public Result<Integer> assessTokenRisk(String tokenId, Long userId, String clientIp,
                                           String userAgent, String requestUrl) {
        try {
            TokenMonitor monitor = new TokenMonitor();
            monitor.setTokenId(tokenId);
            monitor.setUserId(userId);
            monitor.setClientIp(clientIp);
            monitor.setUserAgent(userAgent);
            monitor.setRequestUrl(requestUrl);

            Integer riskScore = calculateRiskScore(monitor);
            return Result.success(riskScore);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 检测异常使用模式
     */
    @Override
    public Result<Map<String, Object>> detectAbnormalUsage(String tokenId, int timeWindow) {
        try {
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusMinutes(timeWindow);

            List<TokenMonitor> recentEvents = tokenMonitorMapper.findByTokenIdAndTimeRange(tokenId, startTime, endTime);

            Map<String, Object> result = new HashMap<>();
            result.put("tokenId", tokenId);
            result.put("timeWindow", timeWindow);
            result.put("totalEvents", recentEvents.size());

            // 检测频率异常
            boolean frequencyAbnormal = recentEvents.size() > 100; // 时间窗口内超过100次使用

            // 检测IP异常
            Set<String> uniqueIps = new HashSet<>();
            for (TokenMonitor event : recentEvents) {
                if (event.getClientIp() != null) {
                    uniqueIps.add(event.getClientIp());
                }
            }
            boolean ipAbnormal = uniqueIps.size() > 5; // 来自超过5个不同IP

            // 检测地理位置异常（简化实现）
            boolean geoAbnormal = false;

            // 检测用户代理异常
            Set<String> uniqueUserAgents = new HashSet<>();
            for (TokenMonitor event : recentEvents) {
                if (event.getUserAgent() != null) {
                    uniqueUserAgents.add(event.getUserAgent());
                }
            }
            boolean userAgentAbnormal = uniqueUserAgents.size() > 3; // 超过3种不同的用户代理

            boolean isAbnormal = frequencyAbnormal || ipAbnormal || geoAbnormal || userAgentAbnormal;

            result.put("isAbnormal", isAbnormal);
            result.put("frequencyAbnormal", frequencyAbnormal);
            result.put("ipAbnormal", ipAbnormal);
            result.put("geoAbnormal", geoAbnormal);
            result.put("userAgentAbnormal", userAgentAbnormal);
            result.put("uniqueIps", uniqueIps.size());
            result.put("uniqueUserAgents", uniqueUserAgents.size());

            return Result.success(result);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 处理异常事件
     */
    @Override
    public Result<Void> handleAbnormalEvent(Long eventId, Long handlerId, String handleResult, String handleRemark) {
        try {
            TokenMonitor monitor = tokenMonitorMapper.selectById(eventId);
            if (monitor == null) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            // 设置处理状态和结果
            // monitor.setHandleStatus("PROCESSED");
            // monitor.setHandleResult(handleResult);
            // monitor.setHandleRemark(handleRemark);
            // monitor.setHandlerId(handlerId);
            // monitor.setHandleTime(LocalDateTime.now());

            tokenMonitorMapper.updateById(monitor);
            return Result.success();
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 生成监控报告
     */
    @Override
    public Result<Map<String, Object>> generateMonitorReport(LocalDateTime startTime, LocalDateTime endTime, String reportType) {
        try {
            Map<String, Object> report = new HashMap<>();
            report.put("reportType", reportType);
            report.put("startTime", startTime);
            report.put("endTime", endTime);
            report.put("generateTime", LocalDateTime.now());

            // 基础统计
            List<Map<String, Object>> eventTypeStats = tokenMonitorMapper.countByEventType(startTime, endTime);
            List<Map<String, Object>> tokenTypeStats = tokenMonitorMapper.countByTokenType(startTime, endTime);
            List<Map<String, Object>> abnormalTypeStats = tokenMonitorMapper.countByAbnormalType(startTime, endTime);

            report.put("eventTypeStats", eventTypeStats);
            report.put("tokenTypeStats", tokenTypeStats);
            report.put("abnormalTypeStats", abnormalTypeStats);

            // 高风险事件
            List<TokenMonitor> highRiskEvents = tokenMonitorMapper.findHighRiskEvents(80, startTime, endTime);
            report.put("highRiskEvents", highRiskEvents);

            // 异常事件
            List<TokenMonitor> abnormalEvents = tokenMonitorMapper.findAbnormalEvents(startTime, endTime);
            report.put("abnormalEvents", abnormalEvents);

            return Result.success(report);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 实时告警
     */
    @Override
    public Result<List<Map<String, Object>>> realtimeTokenAlert(Integer riskThreshold, int timeWindow) {
        try {
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusMinutes(timeWindow);

            List<Map<String, Object>> alerts = new ArrayList<>();

            // 查询高风险事件
            List<TokenMonitor> highRiskEvents = tokenMonitorMapper.findHighRiskEvents(riskThreshold, startTime, endTime);
            for (TokenMonitor event : highRiskEvents) {
                Map<String, Object> alert = new HashMap<>();
                alert.put("type", "HIGH_RISK");
                alert.put("tokenId", event.getTokenId());
                alert.put("userId", event.getUserId());
                alert.put("riskScore", event.getRiskScore());
                alert.put("eventTime", event.getEventTime());
                alert.put("description", "检测到高风险Token事件");
                alerts.add(alert);
            }

            // 查询异常事件
            List<TokenMonitor> abnormalEvents = tokenMonitorMapper.findAbnormalEvents(startTime, endTime);
            for (TokenMonitor event : abnormalEvents) {
                Map<String, Object> alert = new HashMap<>();
                alert.put("type", "ABNORMAL");
                alert.put("tokenId", event.getTokenId());
                alert.put("userId", event.getUserId());
                alert.put("abnormalType", event.getAbnormalType());
                alert.put("eventTime", event.getEventTime());
                alert.put("description", "检测到异常Token事件");
                alerts.add(alert);
            }

            return Result.success(alerts);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 清理过期记录
     */
    @Override
    public Result<Integer> cleanExpiredRecords(int retentionDays) {
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
            int deletedCount = tokenMonitorMapper.deleteExpiredRecords(cutoffTime);
            return Result.success(deletedCount);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 导出监控数据
     */
    @Override
    public Result<String> exportMonitorData(LocalDateTime startTime, LocalDateTime endTime,
                                            String eventType, String tokenType, Long userId) {
        try {
            // 这里应该实现具体的导出逻辑，比如生成CSV或Excel文件
            // 简化实现，返回文件路径
            String fileName = "token_monitor_" + System.currentTimeMillis() + ".csv";
            String filePath = "/tmp/" + fileName;

            // 实际实现中应该查询数据并写入文件
            // List<TokenMonitor> data = tokenMonitorMapper.findForExport(startTime, endTime, eventType, tokenType, userId);
            // 写入CSV文件...

            return Result.success(filePath);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 计算风险评分
     */
    private Integer calculateRiskScore(TokenMonitor monitor) {
        int score = 0;

        // 基础分数
        score += 10;

        // 根据事件类型调整分数
        if (monitor.getEventType() != null) {
            switch (monitor.getEventType()) {
                case "CREATE" -> score += 5;
                case "USE" -> score += 2;
                case "REFRESH" -> score += 8;
                case "REVOKE" -> score += 15;
                case "EXPIRE" -> score += 5;
                case "ABNORMAL" -> score += 50;
            }
        }

        // 根据IP地址调整分数
        if (monitor.getClientIp() != null) {
            if (isInternalIp(monitor.getClientIp())) {
                score += 10; // 外部IP增加风险
            }
        }

        // 根据用户代理调整分数
        if (monitor.getUserAgent() != null) {
            String userAgent = monitor.getUserAgent().toLowerCase();
            if (userAgent.contains("bot") || userAgent.contains("crawler")) {
                score += 20; // 机器人或爬虫增加风险
            }
        }

        // 根据请求URL调整分数
        if (monitor.getRequestUrl() != null) {
            String url = monitor.getRequestUrl().toLowerCase();
            if (url.contains("admin") || url.contains("sensitive")) {
                score += 15; // 敏感路径增加风险
            }
        }

        return Math.min(score, 100); // 最高100分
    }

    /**
     * 检测并设置异常信息
     */
    private void detectAndSetAbnormal(TokenMonitor monitor) {
        boolean isAbnormal = false;
        String abnormalType = null;

        // 检测高风险评分
        if (monitor.getRiskScore() != null && monitor.getRiskScore() > 80) {
            isAbnormal = true;
            abnormalType = "HIGH_RISK";
        }

        // 检测可疑IP
        if (monitor.getClientIp() != null && isInternalIp(monitor.getClientIp())) {
            // 这里可以添加更复杂的IP检测逻辑
            // 比如检查IP黑名单、地理位置等
        }

        monitor.setIsAbnormal(isAbnormal);
        if (abnormalType != null) {
            monitor.setAbnormalType(abnormalType);
        }
    }

    /**
     * 判断是否为内网IP
     */
    private boolean isInternalIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return true;
        }

        // 简化的内网IP判断
        return !ip.startsWith("192.168.") &&
                !ip.startsWith("10.") &&
                !ip.startsWith("172.16.") &&
                !ip.equals("127.0.0.1") &&
                !ip.equals("localhost");
    }
}