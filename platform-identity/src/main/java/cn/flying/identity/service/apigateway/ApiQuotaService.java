package cn.flying.identity.service.apigateway;

import cn.flying.identity.dto.apigateway.ApiQuota;
import cn.flying.platformapi.constant.Result;

import java.util.List;
import java.util.Map;

/**
 * API配额管理服务接口
 * 提供API调用配额的管理和检查功能
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public interface ApiQuotaService {

    /**
     * 创建配额限制
     *
     * @param appId 应用ID
     * @param interfaceId 接口ID（NULL表示全局配额）
     * @param quotaType 配额类型：1-每分钟,2-每小时,3-每天,4-每月
     * @param quotaLimit 配额限制（次数）
     * @param alertThreshold 告警阈值（百分比）
     * @return 操作结果
     */
    Result<ApiQuota> createQuota(Long appId, Long interfaceId, Integer quotaType,
                                 Long quotaLimit, Integer alertThreshold);

    /**
     * 更新配额限制
     *
     * @param quota 配额信息
     * @return 操作结果
     */
    Result<Void> updateQuota(ApiQuota quota);

    /**
     * 删除配额
     *
     * @param quotaId 配额ID
     * @return 操作结果
     */
    Result<Void> deleteQuota(Long quotaId);

    /**
     * 根据ID查询配额
     *
     * @param quotaId 配额ID
     * @return 配额信息
     */
    Result<ApiQuota> getQuotaById(Long quotaId);

    /**
     * 根据应用ID查询配额列表
     *
     * @param appId 应用ID
     * @return 配额列表
     */
    Result<List<ApiQuota>> getQuotasByAppId(Long appId);

    /**
     * 根据应用ID和接口ID查询配额
     *
     * @param appId 应用ID
     * @param interfaceId 接口ID
     * @param quotaType 配额类型
     * @return 配额信息
     */
    Result<ApiQuota> getQuotaByAppAndInterface(Long appId, Long interfaceId, Integer quotaType);

    /**
     * 检查是否超出配额
     *
     * @param appId 应用ID
     * @param interfaceId 接口ID（可选）
     * @return true-已超限，false-未超限
     */
    Result<Boolean> checkQuotaExceeded(Long appId, Long interfaceId);

    /**
     * 增加配额使用量
     *
     * @param appId 应用ID
     * @param interfaceId 接口ID（可选）
     * @param count 增加数量
     * @return 操作结果
     */
    Result<Void> incrementQuotaUsage(Long appId, Long interfaceId, int count);

    /**
     * 重置配额使用量
     *
     * @param quotaId 配额ID
     * @return 操作结果
     */
    Result<Void> resetQuota(Long quotaId);

    /**
     * 重置所有过期的配额
     *
     * @return 重置数量
     */
    Result<Integer> resetExpiredQuotas();

    /**
     * 获取配额使用统计
     *
     * @param appId 应用ID
     * @return 统计信息
     */
    Result<Map<String, Object>> getQuotaStatistics(Long appId);

    /**
     * 检查并发送配额告警
     *
     * @param quotaId 配额ID
     * @return 操作结果
     */
    Result<Void> checkAndSendAlert(Long quotaId);

    /**
     * 获取即将超限的配额列表
     *
     * @param threshold 阈值百分比
     * @return 配额列表
     */
    Result<List<ApiQuota>> getQuotasNearLimit(int threshold);

    /**
     * 获取所有启用的配额列表
     * 用于定时任务检查配额告警
     *
     * @return 配额列表
     */
    Result<List<ApiQuota>> getAllActiveQuotas();
}
