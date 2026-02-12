package cn.flying.service;

import cn.flying.dao.vo.file.QuotaStatusVO;
import cn.flying.service.quota.QuotaDecision;

/**
 * 配额服务。
 */
public interface QuotaService {

    /**
     * 评估一次上传请求是否会触发配额超限。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param incomingFileSizeBytes 待上传文件大小
     * @return 判定结果
     */
    QuotaDecision evaluateUploadQuota(Long tenantId, Long userId, long incomingFileSizeBytes);

    /**
     * 对上传请求执行配额校验。
     * SHADOW 模式仅记录超限；ENFORCE 模式抛出业务异常。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param incomingFileSizeBytes 待上传文件大小
     */
    void checkUploadQuota(Long tenantId, Long userId, long incomingFileSizeBytes);

    /**
     * 查询当前用户配额状态。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @return 配额状态
     */
    QuotaStatusVO getCurrentQuotaStatus(Long tenantId, Long userId);

    /**
     * 全量重算并刷新快照，用于日常对账。
     */
    void reconcileUsageSnapshots();

    /**
     * 获取当前执行模式。
     *
     * @return SHADOW 或 ENFORCE
     */
    String getEnforcementMode();
}
