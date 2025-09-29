package cn.flying.identity.service.apigateway;

import cn.flying.identity.dto.apigateway.ApiCallLog;
import cn.flying.platformapi.constant.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * API调用日志服务接口
 * 提供API调用日志的记录、查询和统计功能
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public interface ApiCallLogService {

    /**
     * 记录API调用日志
     *
     * @param log 调用日志
     * @return 操作结果
     */
    Result<Void> recordCallLog(ApiCallLog log);

    /**
     * 异步记录API调用日志
     *
     * @param log 调用日志
     */
    void recordCallLogAsync(ApiCallLog log);

    /**
     * 根据ID查询调用日志
     *
     * @param logId 日志ID
     * @return 调用日志
     */
    Result<ApiCallLog> getCallLogById(Long logId);

    /**
     * 分页查询调用日志
     *
     * @param pageNum      页码
     * @param pageSize     每页大小
     * @param appId        应用ID（可选）
     * @param apiKey       API密钥（可选）
     * @param startTime    开始时间（可选）
     * @param endTime      结束时间（可选）
     * @param responseCode 响应状态码（可选）
     * @return 分页结果
     */
    Result<Page<ApiCallLog>> getCallLogsPage(int pageNum, int pageSize,
                                             Long appId, String apiKey,
                                             LocalDateTime startTime, LocalDateTime endTime,
                                             Integer responseCode);

    /**
     * 根据应用ID查询调用日志
     *
     * @param appId 应用ID
     * @param days  查询天数
     * @return 调用日志列表
     */
    Result<Page<ApiCallLog>> getCallLogsByAppId(Long appId, int days, int pageNum, int pageSize);

    /**
     * 根据API密钥查询调用日志
     *
     * @param apiKey API密钥
     * @param days   查询天数
     * @return 调用日志列表
     */
    Result<Page<ApiCallLog>> getCallLogsByApiKey(String apiKey, int days, int pageNum, int pageSize);

    /**
     * 获取应用的调用统计
     *
     * @param appId 应用ID
     * @param days  统计天数
     * @return 统计数据
     */
    Result<Map<String, Object>> getAppCallStatistics(Long appId, int days);

    /**
     * 获取API密钥的调用统计
     *
     * @param apiKey API密钥
     * @param days   统计天数
     * @return 统计数据
     */
    Result<Map<String, Object>> getApiKeyCallStatistics(String apiKey, int days);

    /**
     * 获取接口的调用统计
     *
     * @param interfaceId 接口ID
     * @param days        统计天数
     * @return 统计数据
     */
    Result<Map<String, Object>> getInterfaceCallStatistics(Long interfaceId, int days);

    /**
     * 清理过期的调用日志
     *
     * @param days 保留天数
     * @return 清理数量
     */
    Result<Integer> cleanExpiredLogs(int days);

    /**
     * 获取实时调用统计
     *
     * @return 实时统计数据
     */
    Result<Map<String, Object>> getRealtimeStatistics();

    /**
     * 获取错误调用日志
     *
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @param hours    查询小时数
     * @return 错误日志列表
     */
    Result<Page<ApiCallLog>> getErrorLogs(int pageNum, int pageSize, int hours);
}
