package cn.flying.identity.service;

import cn.flying.identity.dto.TrafficMonitorEntity;
import cn.flying.platformapi.constant.Result;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流量监控服务接口
 * 提供网关流量监控、异常检测和自动拦截功能
 *
 * @author 王贝强
 */
public interface TrafficMonitorService extends IService<TrafficMonitorEntity> {

    /**
     * 记录请求流量信息
     *
     * @param requestId 请求ID
     * @param clientIp 客户端IP
     * @param userId 用户ID（可选）
     * @param requestPath 请求路径
     * @param requestMethod HTTP方法
     * @param userAgent 用户代理
     * @return 操作结果
     */
    Result<Void> recordTrafficInfo(String requestId, String clientIp, Long userId,
                                   String requestPath, String requestMethod, String userAgent);

    /**
     * 记录请求完成信息
     *
     * @param requestId 请求ID
     * @param responseStatus 响应状态码
     * @param responseTime 响应时间
     * @param requestSize 请求大小
     * @param responseSize 响应大小
     * @return 操作结果
     */
    Result<Void> recordResponseInfo(String requestId, Integer responseStatus, Long responseTime,
                                    Long requestSize, Long responseSize);

    /**
     * 检查是否需要拦截请求
     *
     * @param clientIp 客户端IP
     * @param userId 用户ID（可选）
     * @param requestPath 请求路径
     * @param userAgent 用户代理
     * @return 拦截检查结果
     */
    Result<Map<String, Object>> checkTrafficBlock(String clientIp, Long userId,
                                                  String requestPath, String userAgent);

    /**
     * 执行异常检测
     *
     * @param clientIp 客户端IP
     * @param userId 用户ID（可选）
     * @param requestPath 请求路径
     * @param responseTime 响应时间
     * @param responseStatus 响应状态
     * @return 异常检测结果
     */
    Result<Map<String, Object>> detectAnomalies(String clientIp, Long userId, String requestPath,
                                                Long responseTime, Integer responseStatus);

    /**
     * 添加IP到黑名单
     *
     * @param clientIp 客户端IP
     * @param reason 拉黑原因
     * @param durationHours 持续时间（小时）
     * @return 操作结果
     */
    Result<Void> addToBlacklist(String clientIp, String reason, int durationHours);

    /**
     * 从黑名单移除IP
     *
     * @param clientIp 客户端IP
     * @return 操作结果
     */
    Result<Void> removeFromBlacklist(String clientIp);

    /**
     * 检查IP是否在黑名单中
     *
     * @param clientIp 客户端IP
     * @return 是否在黑名单中
     */
    Result<Boolean> isBlacklisted(String clientIp);

    /**
     * 获取实时流量统计
     *
     * @param timeRangeMinutes 时间范围（分钟）
     * @return 流量统计数据
     */
    Result<Map<String, Object>> getRealTimeTrafficStats(int timeRangeMinutes);

    /**
     * 获取异常流量统计
     *
     * @param timeRangeMinutes 时间范围（分钟）
     * @return 异常流量统计
     */
    Result<Map<String, Object>> getAnomalousTrafficStats(int timeRangeMinutes);

    /**
     * 获取IP流量排行
     *
     * @param timeRangeMinutes 时间范围（分钟）
     * @param limit 返回数量限制
     * @return IP流量排行
     */
    Result<List<Map<String, Object>>> getTopTrafficIps(int timeRangeMinutes, int limit);

    /**
     * 获取API访问统计
     *
     * @param timeRangeMinutes 时间范围（分钟）
     * @param limit 返回数量限制
     * @return API访问统计
     */
    Result<List<Map<String, Object>>> getTopApis(int timeRangeMinutes, int limit);

    /**
     * 获取黑名单列表
     *
     * @return 黑名单列表
     */
    Result<List<Map<String, Object>>> getBlacklistInfo();

    /**
     * 清理过期的监控数据
     *
     * @param retentionDays 保留天数
     * @return 清理结果
     */
    Result<Map<String, Object>> cleanExpiredData(int retentionDays);

    /**
     * 导出流量监控数据
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param clientIp 客户端IP（可选）
     * @return 导出文件路径
     */
    Result<String> exportTrafficData(LocalDateTime startTime, LocalDateTime endTime, String clientIp);

    /**
     * 获取流量监控仪表板数据
     *
     * @return 仪表板数据
     */
    Result<Map<String, Object>> getTrafficDashboard();

    /**
     * 更新拦截规则
     *
     * @param ruleType 规则类型
     * @param ruleValue 规则值
     * @return 操作结果
     */
    Result<Void> updateBlockingRule(String ruleType, Object ruleValue);

    /**
     * 手动触发异常检测
     *
     * @param clientIp 客户端IP（可选）
     * @return 检测结果
     */
    Result<Map<String, Object>> triggerAnomalyDetection(String clientIp);

    /**
     * 获取系统健康状态
     *
     * @return 系统健康状态
     */
    Result<Map<String, Object>> getSystemHealthStatus();
}
