package cn.flying.identity.service;

import cn.flying.identity.dto.TokenMonitor;
import cn.flying.platformapi.constant.Result;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Token监控服务接口
 * 提供Token监控的业务逻辑处理
 * 
 * @author flying
 * @date 2024
 */
public interface TokenMonitorService {

    /**
     * 记录Token事件
     *
     * @param tokenMonitor Token监控对象
     * @return 操作结果
     */
    Result<Void> recordTokenEvent(TokenMonitor tokenMonitor);

    /**
     * 异步记录Token事件
     * @param tokenMonitor Token监控对象
     */
    void recordTokenEventAsync(TokenMonitor tokenMonitor);

    /**
     * 记录Token创建事件
     * @param tokenId Token ID
     * @param tokenType Token类型
     * @param userId 用户ID
     * @param clientId 客户端ID
     * @param clientIp 客户端IP
     * @param userAgent 用户代理
     * @param expiresAt 过期时间
     * @return 操作结果
     */
    Result<Void> recordTokenCreation(String tokenId, String tokenType, Long userId, 
                                    String clientId, String clientIp, String userAgent, 
                                    LocalDateTime expiresAt);

    /**
     * 记录Token使用事件
     * @param tokenId Token ID
     * @param tokenType Token类型
     * @param userId 用户ID
     * @param clientId 客户端ID
     * @param clientIp 客户端IP
     * @param userAgent 用户代理
     * @param requestUrl 请求URL
     * @param requestMethod 请求方法
     * @return 操作结果
     */
    Result<Void> recordTokenUsage(String tokenId, String tokenType, Long userId, 
                                 String clientId, String clientIp, String userAgent, 
                                 String requestUrl, String requestMethod);

    /**
     * 记录Token刷新事件
     * @param oldTokenId 旧Token ID
     * @param newTokenId 新Token ID
     * @param tokenType Token类型
     * @param userId 用户ID
     * @param clientId 客户端ID
     * @param clientIp 客户端IP
     * @param userAgent 用户代理
     * @return 操作结果
     */
    Result<Void> recordTokenRefresh(String oldTokenId, String newTokenId, String tokenType, 
                                   Long userId, String clientId, String clientIp, String userAgent);

    /**
     * 记录Token撤销事件
     * @param tokenId Token ID
     * @param tokenType Token类型
     * @param userId 用户ID
     * @param clientId 客户端ID
     * @param clientIp 客户端IP
     * @param userAgent 用户代理
     * @param reason 撤销原因
     * @return 操作结果
     */
    Result<Void> recordTokenRevocation(String tokenId, String tokenType, Long userId, 
                                      String clientId, String clientIp, String userAgent, String reason);

    /**
     * 记录Token过期事件
     * @param tokenId Token ID
     * @param tokenType Token类型
     * @param userId 用户ID
     * @param clientId 客户端ID
     * @return 操作结果
     */
    Result<Void> recordTokenExpiration(String tokenId, String tokenType, Long userId, String clientId);

    /**
     * 记录Token异常事件
     * @param tokenId Token ID
     * @param tokenType Token类型
     * @param userId 用户ID
     * @param clientId 客户端ID
     * @param clientIp 客户端IP
     * @param userAgent 用户代理
     * @param abnormalType 异常类型
     * @param description 异常描述
     * @param riskScore 风险评分
     * @return 操作结果
     */
    Result<Void> recordTokenAbnormal(String tokenId, String tokenType, Long userId, String clientId, 
                                    String clientIp, String userAgent, String abnormalType, 
                                    String description, Integer riskScore);

    /**
     * 根据Token ID查询监控记录
     * @param tokenId Token ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 监控记录列表
     */
    Result<List<TokenMonitor>> getMonitorsByTokenId(String tokenId, LocalDateTime startTime, 
                                                   LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 根据用户ID查询Token监控记录
     * @param userId 用户ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 监控记录列表
     */
    Result<List<TokenMonitor>> getMonitorsByUserId(Long userId, LocalDateTime startTime, 
                                                  LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 根据客户端ID查询监控记录
     * @param clientId 客户端ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 监控记录列表
     */
    Result<List<TokenMonitor>> getMonitorsByClientId(String clientId, LocalDateTime startTime, 
                                                    LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 查询异常Token事件
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 异常事件列表
     */
    Result<List<TokenMonitor>> getAbnormalEvents(LocalDateTime startTime, LocalDateTime endTime, 
                                                int pageNum, int pageSize);

    /**
     * 根据事件类型查询监控记录
     * @param eventType 事件类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 监控记录列表
     */
    Result<List<TokenMonitor>> getMonitorsByEventType(String eventType, LocalDateTime startTime, 
                                                     LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 根据异常类型查询监控记录
     * @param abnormalType 异常类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 监控记录列表
     */
    Result<List<TokenMonitor>> getMonitorsByAbnormalType(String abnormalType, LocalDateTime startTime, 
                                                        LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 根据风险评分范围查询监控记录
     * @param minScore 最小风险评分
     * @param maxScore 最大风险评分
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 监控记录列表
     */
    Result<List<TokenMonitor>> getMonitorsByRiskScore(Integer minScore, Integer maxScore, 
                                                     LocalDateTime startTime, LocalDateTime endTime, 
                                                     int pageNum, int pageSize);

    /**
     * 查询高风险Token事件
     * @param minRiskScore 最小风险评分
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 高风险事件列表
     */
    Result<List<TokenMonitor>> getHighRiskEvents(Integer minRiskScore, LocalDateTime startTime, 
                                                LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 查询未处理的异常事件
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 未处理异常事件列表
     */
    Result<List<TokenMonitor>> getUnhandledAbnormalEvents(int pageNum, int pageSize);

    /**
     * 查询可疑的Token活动
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 可疑活动列表
     */
    Result<List<TokenMonitor>> getSuspiciousActivities(LocalDateTime startTime, LocalDateTime endTime, 
                                                      int pageNum, int pageSize);

    /**
     * 查询Token的完整生命周期
     * @param tokenId Token ID
     * @return Token生命周期记录
     */
    Result<List<TokenMonitor>> getTokenLifecycle(String tokenId);

    /**
     * 统计Token事件类型分布
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 事件类型统计
     */
    Result<List<Map<String, Object>>> getEventTypeStats(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 统计Token类型分布
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return Token类型统计
     */
    Result<List<Map<String, Object>>> getTokenTypeStats(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 统计异常类型分布
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 异常类型统计
     */
    Result<List<Map<String, Object>>> getAbnormalTypeStats(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 统计用户Token使用情况
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param limit 限制数量
     * @return 用户Token使用统计
     */
    Result<List<Map<String, Object>>> getUserTokenStats(LocalDateTime startTime, LocalDateTime endTime, Integer limit);

    /**
     * 统计客户端Token使用情况
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param limit 限制数量
     * @return 客户端Token使用统计
     */
    Result<List<Map<String, Object>>> getClientTokenStats(LocalDateTime startTime, LocalDateTime endTime, Integer limit);

    /**
     * 统计IP访问情况
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param limit 限制数量
     * @return IP访问统计
     */
    Result<List<Map<String, Object>>> getIpAccessStats(LocalDateTime startTime, LocalDateTime endTime, Integer limit);

    /**
     * 统计每日Token事件数量
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 每日事件统计
     */
    Result<List<Map<String, Object>>> getDailyEventStats(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 统计每小时Token事件数量
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 每小时事件统计
     */
    Result<List<Map<String, Object>>> getHourlyEventStats(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Token风险评估
     * @param tokenId Token ID
     * @param userId 用户ID
     * @param clientIp 客户端IP
     * @param userAgent 用户代理
     * @param requestUrl 请求URL
     * @return 风险评分
     */
    Result<Integer> assessTokenRisk(String tokenId, Long userId, String clientIp, 
                                   String userAgent, String requestUrl);

    /**
     * 检测Token异常使用模式
     * @param tokenId Token ID
     * @param timeWindow 时间窗口（分钟）
     * @return 异常检测结果
     */
    Result<Map<String, Object>> detectAbnormalUsage(String tokenId, int timeWindow);

    /**
     * 处理异常事件
     * @param eventId 事件ID
     * @param handlerId 处理人ID
     * @param handleResult 处理结果
     * @param handleRemark 处理备注
     * @return 操作结果
     */
    Result<Void> handleAbnormalEvent(Long eventId, Long handlerId, String handleResult, String handleRemark);

    /**
     * 生成Token监控报告
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param reportType 报告类型
     * @return 报告内容
     */
    Result<Map<String, Object>> generateMonitorReport(LocalDateTime startTime, LocalDateTime endTime, String reportType);

    /**
     * 实时Token告警
     * @param riskThreshold 风险阈值
     * @param timeWindow 时间窗口（分钟）
     * @return 告警信息
     */
    Result<List<Map<String, Object>>> realtimeTokenAlert(Integer riskThreshold, int timeWindow);

    /**
     * 清理过期的监控记录
     * @param retentionDays 保留天数
     * @return 清理结果
     */
    Result<Integer> cleanExpiredRecords(int retentionDays);

    /**
     * 导出Token监控数据
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param eventType 事件类型
     * @param tokenType Token类型
     * @param userId 用户ID
     * @return 导出文件路径
     */
    Result<String> exportMonitorData(LocalDateTime startTime, LocalDateTime endTime, 
                                    String eventType, String tokenType, Long userId);

    Result<Map<String, Object>> getTokenMonitorPage(int page, int size, String tokenId, String userId, String clientId, String eventType, LocalDateTime startTime, LocalDateTime endTime);
}