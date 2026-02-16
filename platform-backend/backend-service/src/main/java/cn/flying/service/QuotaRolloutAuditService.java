package cn.flying.service;

import cn.flying.dao.vo.file.QuotaRolloutAuditUpsertVO;
import cn.flying.dao.vo.file.QuotaRolloutAuditVO;

/**
 * 配额灰度扩容审计服务。
 */
public interface QuotaRolloutAuditService {

    /**
     * 写入或更新灰度扩容审计记录。
     *
     * @param operatorId 操作人ID
     * @param request 审计写入请求
     * @return 最新审计记录
     */
    QuotaRolloutAuditVO upsertAudit(Long operatorId, QuotaRolloutAuditUpsertVO request);

    /**
     * 查询指定批次与租户的审计记录。
     *
     * @param batchId 灰度批次ID
     * @param tenantId 租户ID
     * @return 审计记录
     */
    QuotaRolloutAuditVO getLatestAudit(String batchId, Long tenantId);
}
