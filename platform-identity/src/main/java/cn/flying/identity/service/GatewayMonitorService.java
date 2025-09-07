package cn.flying.identity.service;

import cn.flying.platformapi.constant.Result;

import java.util.Map;

/**
 * 网关监控服务接口
 * 提供流量监控、性能统计、异常检测等功能
 * 
 * @author 王贝强
 */
public interface GatewayMonitorService {
    
    /**
     * 记录请求开始
     * 
     * @param requestId 请求ID
     * @param method HTTP方法
     * @param uri 请求URI
     * @param clientIp 客户端IP
     * @param userAgent 用户代理
     * @param userId 用户ID（可选）
     * @return 记录结果
     */
    Result<Void> recordRequestStart(String requestId, String method, String uri, 
                                   String clientIp, String userAgent, Long userId);
    
    /**
     * 记录请求结束
     * 
     * @param requestId 请求ID
     * @param statusCode 响应状态码
     * @param responseSize 响应大小（字节）
     * @param executionTime 执行时间（毫秒）
     * @param errorMessage 错误信息（可选）
     * @return 记录结果
     */
    Result<Void> recordRequestEnd(String requestId, int statusCode, long responseSize, 
                                 long executionTime, String errorMessage);
    
    /**
     * 检查流量限制
     * 
     * @param clientIp 客户端IP
     * @param userId 用户ID（可选）
     * @param uri 请求URI
     * @return 是否允许通过
     */
    Result<Boolean> checkRateLimit(String clientIp, Long userId, String uri);
    
    /**
     * 获取实时流量统计
     * 
     * @param timeRange 时间范围（分钟）
     * @return 流量统计数据
     */
    Result<Map<String, Object>> getRealTimeTrafficStats(int timeRange);
    
    /**
     * 获取API调用统计
     * 
     * @param timeRange 时间范围（分钟）
     * @param limit 返回条数限制
     * @return API调用统计
     */
    Result<Map<String, Object>> getApiCallStats(int timeRange, int limit);
    
    /**
     * 获取错误统计
     * 
     * @param timeRange 时间范围（分钟）
     * @return 错误统计数据
     */
    Result<Map<String, Object>> getErrorStats(int timeRange);
    
    /**
     * 获取性能统计
     * 
     * @param timeRange 时间范围（分钟）
     * @return 性能统计数据
     */
    Result<Map<String, Object>> getPerformanceStats(int timeRange);
    
    /**
     * 获取用户活跃度统计
     * 
     * @param timeRange 时间范围（分钟）
     * @return 用户活跃度统计
     */
    Result<Map<String, Object>> getUserActivityStats(int timeRange);
    
    /**
     * 检测异常流量
     * 
     * @param clientIp 客户端IP
     * @param userId 用户ID（可选）
     * @return 异常检测结果
     */
    Result<Map<String, Object>> detectAbnormalTraffic(String clientIp, Long userId);
    
    /**
     * 获取系统健康状态
     * 
     * @return 系统健康状态
     */
    Result<Map<String, Object>> getSystemHealth();
    
    /**
     * 清理过期的监控数据
     * 
     * @param retentionDays 保留天数
     * @return 清理结果
     */
    Result<Map<String, Object>> cleanExpiredData(int retentionDays);
    
    /**
     * 获取热点API排行
     * 
     * @param timeRange 时间范围（分钟）
     * @param limit 返回条数限制
     * @return 热点API排行
     */
    Result<Map<String, Object>> getHotApiRanking(int timeRange, int limit);
    
    /**
     * 获取慢查询统计
     * 
     * @param timeRange 时间范围（分钟）
     * @param threshold 慢查询阈值（毫秒）
     * @return 慢查询统计
     */
    Result<Map<String, Object>> getSlowQueryStats(int timeRange, long threshold);
    
    /**
     * 获取地理位置统计
     * 
     * @param timeRange 时间范围（分钟）
     * @return 地理位置统计
     */
    Result<Map<String, Object>> getGeographicStats(int timeRange);
}
