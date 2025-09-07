package cn.flying.identity.service;

import cn.flying.identity.dto.AuditLog;
import cn.flying.platformapi.constant.Result;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 操作审计日志服务接口
 * 提供审计日志的业务逻辑处理
 * 
 * @author flying
 * @date 2024
 */
public interface AuditLogService {

    /**
     * 记录操作日志
     * @param auditLog 审计日志对象
     * @return 操作结果
     */
    Result<String> recordLog(AuditLog auditLog);

    /**
     * 异步记录操作日志
     * @param auditLog 审计日志对象
     */
    void recordLogAsync(AuditLog auditLog);

    /**
     * 记录登录日志
     * @param userId 用户ID
     * @param username 用户名
     * @param clientIp 客户端IP
     * @param userAgent 用户代理
     * @param success 是否成功
     * @param errorMessage 错误信息
     * @return 操作结果
     */
    Result<String> recordLoginLog(Long userId, String username, String clientIp, 
                               String userAgent, boolean success, String errorMessage);

    /**
     * 记录登出日志
     * @param userId 用户ID
     * @param username 用户名
     * @param clientIp 客户端IP
     * @param userAgent 用户代理
     * @return 操作结果
     */
    Result<String> recordLogoutLog(Long userId, String username, String clientIp, String userAgent);

    /**
     * 记录权限操作日志
     * @param userId 用户ID
     * @param username 用户名
     * @param operationType 操作类型
     * @param description 操作描述
     * @param clientIp 客户端IP
     * @param userAgent 用户代理
     * @param success 是否成功
     * @return 操作结果
     */
    Result<String> recordPermissionLog(Long userId, String username, String operationType, 
                                     String description, String clientIp, String userAgent, boolean success);

    /**
     * 记录数据操作日志
     * @param userId 用户ID
     * @param username 用户名
     * @param operationType 操作类型
     * @param module 操作模块
     * @param businessId 业务ID
     * @param businessType 业务类型
     * @param description 操作描述
     * @param clientIp 客户端IP
     * @param success 是否成功
     * @return 操作结果
     */
    Result<String> recordDataLog(Long userId, String username, String operationType, String module,
                              String businessId, String businessType, String description, 
                              String clientIp, boolean success);

    /**
     * 记录系统操作日志
     * @param operationType 操作类型
     * @param module 操作模块
     * @param description 操作描述
     * @param success 是否成功
     * @param errorMessage 错误信息
     * @return 操作结果
     */
    Result<String> recordSystemLog(String operationType, String module, String description, 
                                boolean success, String errorMessage);

    /**
     * 根据用户ID查询操作日志
     * @param userId 用户ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 操作日志列表
     */
    Result<List<AuditLog>> getLogsByUserId(Long userId, LocalDateTime startTime, 
                                          LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 根据操作类型查询日志
     * @param operationType 操作类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 操作日志列表
     */
    Result<List<AuditLog>> getLogsByOperationType(String operationType, LocalDateTime startTime, 
                                                 LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 根据模块查询日志
     * @param module 操作模块
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 操作日志列表
     */
    Result<List<AuditLog>> getLogsByModule(String module, LocalDateTime startTime, 
                                          LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 根据IP地址查询日志
     * @param clientIp 客户端IP
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 操作日志列表
     */
    Result<List<AuditLog>> getLogsByClientIp(String clientIp, LocalDateTime startTime, 
                                            LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 查询失败的操作日志
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 失败操作日志列表
     */
    Result<List<AuditLog>> getFailedLogs(LocalDateTime startTime, LocalDateTime endTime, 
                                        int pageNum, int pageSize);

    /**
     * 根据风险等级查询日志
     * @param riskLevel 风险等级
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 操作日志列表
     */
    Result<List<AuditLog>> getLogsByRiskLevel(String riskLevel, LocalDateTime startTime, 
                                             LocalDateTime endTime, int pageNum, int pageSize);

    /**
     * 查询高风险操作
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 高风险操作列表
     */
    Result<List<AuditLog>> getHighRiskLogs(LocalDateTime startTime, LocalDateTime endTime, 
                                          int pageNum, int pageSize);

    /**
     * 查询异常登录记录
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 异常登录记录
     */
    Result<List<AuditLog>> getAbnormalLogins(LocalDateTime startTime, LocalDateTime endTime, 
                                            int pageNum, int pageSize);

    /**
     * 统计操作类型分布
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 操作类型统计
     */
    Result<List<Map<String, Object>>> getOperationTypeStats(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 统计用户操作次数
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param limit 限制数量
     * @return 用户操作统计
     */
    Result<List<Map<String, Object>>> getUserOperationStats(LocalDateTime startTime, LocalDateTime endTime, Integer limit);

    /**
     * 统计IP访问次数
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param limit 限制数量
     * @return IP访问统计
     */
    Result<List<Map<String, Object>>> getIpAccessStats(LocalDateTime startTime, LocalDateTime endTime, Integer limit);

    /**
     * 统计每日操作数量
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 每日操作统计
     */
    Result<List<Map<String, Object>>> getDailyOperationStats(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 统计每小时操作数量
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 每小时操作统计
     */
    Result<List<Map<String, Object>>> getHourlyOperationStats(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 导出审计日志
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param operationType 操作类型
     * @param module 操作模块
     * @param userId 用户ID
     * @return 导出文件路径
     */
    Result<String> exportLogs(LocalDateTime startTime, LocalDateTime endTime, 
                             String operationType, String module, Long userId);

    /**
     * 清理过期的审计日志
     * @param retentionDays 保留天数
     * @return 清理结果
     */
    Result<Integer> cleanExpiredLogs(int retentionDays);

    /**
     * 检测异常操作模式
     * @param userId 用户ID
     * @param timeWindow 时间窗口（分钟）
     * @return 异常检测结果
     */
    Result<Map<String, Object>> detectAbnormalPatterns(Long userId, int timeWindow);

    /**
     * 生成审计报告
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param reportType 报告类型
     * @return 报告内容
     */
    Result<Map<String, Object>> generateAuditReport(LocalDateTime startTime, LocalDateTime endTime, String reportType);

    /**
     * 实时监控告警
     * @param riskLevel 风险等级阈值
     * @param timeWindow 时间窗口（分钟）
     * @return 告警信息
     */
    Result<List<Map<String, Object>>> realtimeAlert(String riskLevel, int timeWindow);
}