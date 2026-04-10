package cn.flying.dao.mapper;

import cn.flying.dao.entity.QuotaRolloutAudit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 配额灰度扩容审计 Mapper。
 */
@Mapper
public interface QuotaRolloutAuditMapper extends BaseMapper<QuotaRolloutAudit> {

    /**
     * 按批次与租户查询审计记录。
     *
     * @param batchId 灰度批次ID
     * @param tenantId 租户ID
     * @return 审计记录，不存在时返回 null
     */
    @Select("SELECT id, batch_id, tenant_id, observation_start_time, observation_end_time, sampled_request_count, "
            + "exceeded_request_count, false_positive_count, rollback_decision, rollback_reason, evidence_link, "
            + "operator_name, create_time, update_time "
            + "FROM quota_rollout_audit WHERE batch_id = #{batchId} AND tenant_id = #{tenantId} LIMIT 1")
    QuotaRolloutAudit selectByBatchAndTenant(@Param("batchId") String batchId, @Param("tenantId") Long tenantId);

    /**
     * 写入或更新灰度审计记录。
     *
     * @param audit 审计实体
     * @return 影响行数
     */
    @Insert("INSERT INTO quota_rollout_audit(id, batch_id, tenant_id, observation_start_time, observation_end_time, "
            + "sampled_request_count, exceeded_request_count, false_positive_count, rollback_decision, "
            + "rollback_reason, evidence_link, operator_name, create_time, update_time) "
            + "VALUES(#{audit.id}, #{audit.batchId}, #{audit.tenantId}, #{audit.observationStartTime}, #{audit.observationEndTime}, "
            + "#{audit.sampledRequestCount}, #{audit.exceededRequestCount}, #{audit.falsePositiveCount}, "
            + "#{audit.rollbackDecision}, #{audit.rollbackReason}, #{audit.evidenceLink}, #{audit.operatorName}, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE observation_start_time = VALUES(observation_start_time), "
            + "observation_end_time = VALUES(observation_end_time), sampled_request_count = VALUES(sampled_request_count), "
            + "exceeded_request_count = VALUES(exceeded_request_count), false_positive_count = VALUES(false_positive_count), "
            + "rollback_decision = VALUES(rollback_decision), rollback_reason = VALUES(rollback_reason), "
            + "evidence_link = VALUES(evidence_link), operator_name = VALUES(operator_name), update_time = NOW()")
    int upsertAudit(@Param("audit") QuotaRolloutAudit audit);
}
