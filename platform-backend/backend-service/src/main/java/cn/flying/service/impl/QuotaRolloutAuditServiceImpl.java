package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.QuotaRolloutAudit;
import cn.flying.dao.mapper.QuotaRolloutAuditMapper;
import cn.flying.dao.vo.file.QuotaRolloutAuditUpsertVO;
import cn.flying.dao.vo.file.QuotaRolloutAuditVO;
import cn.flying.service.QuotaRolloutAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;

/**
 * 配额灰度扩容审计服务实现。
 * 负责审计写入校验、数据持久化与响应转换。
 */
@Service
@RequiredArgsConstructor
public class QuotaRolloutAuditServiceImpl implements QuotaRolloutAuditService {

    private static final String DECISION_KEEP_ENFORCE = "KEEP_ENFORCE";
    private static final String DECISION_FORCE_SHADOW = "FORCE_SHADOW";
    private static final String DECISION_EXTEND_OBSERVATION = "EXTEND_OBSERVATION";

    private final QuotaRolloutAuditMapper quotaRolloutAuditMapper;

    /**
     * 写入或更新灰度扩容审计记录。
     *
     * @param operatorId 操作人ID
     * @param request 审计写入请求
     * @return 最新审计记录
     */
    @Override
    public QuotaRolloutAuditVO upsertAudit(Long operatorId, QuotaRolloutAuditUpsertVO request) {
        validateRequest(request);

        QuotaRolloutAudit audit = new QuotaRolloutAudit()
                .setId(IdUtils.nextEntityId())
                .setBatchId(request.batchId().trim())
                .setTenantId(request.tenantId())
                .setObservationStartTime(toDate(request.observationStartTime()))
                .setObservationEndTime(toDate(request.observationEndTime()))
                .setSampledRequestCount(request.sampledRequestCount())
                .setExceededRequestCount(request.exceededRequestCount())
                .setFalsePositiveCount(request.falsePositiveCount())
                .setRollbackDecision(normalizeRollbackDecision(request.rollbackDecision()))
                .setRollbackReason(trimToNull(request.rollbackReason()))
                .setEvidenceLink(trimToNull(request.evidenceLink()))
                .setOperatorName(resolveOperatorName(operatorId));

        quotaRolloutAuditMapper.upsertAudit(audit);
        QuotaRolloutAudit latest = quotaRolloutAuditMapper.selectByBatchAndTenant(audit.getBatchId(), audit.getTenantId());
        if (latest == null) {
            throw new GeneralException(ResultEnum.FAIL, "灰度审计写入后查询失败");
        }
        return toView(latest);
    }

    /**
     * 查询指定批次与租户的审计记录。
     *
     * @param batchId 灰度批次ID
     * @param tenantId 租户ID
     * @return 审计记录
     */
    @Override
    public QuotaRolloutAuditVO getLatestAudit(String batchId, Long tenantId) {
        if (!StringUtils.hasText(batchId)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "batchId 不能为空");
        }
        if (tenantId == null || tenantId <= 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "tenantId 必须大于 0");
        }

        QuotaRolloutAudit audit = quotaRolloutAuditMapper.selectByBatchAndTenant(batchId.trim(), tenantId);
        if (audit == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE, "灰度审计记录不存在");
        }
        return toView(audit);
    }

    /**
     * 校验审计写入请求，保证计数和观察窗口口径一致。
     *
     * @param request 审计写入请求
     */
    private void validateRequest(QuotaRolloutAuditUpsertVO request) {
        if (request == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "审计请求不能为空");
        }
        if (!StringUtils.hasText(request.batchId())) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "batchId 不能为空");
        }
        if (request.tenantId() == null || request.tenantId() <= 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "tenantId 必须大于 0");
        }
        if (request.observationStartTime() == null || request.observationEndTime() == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "观察窗口不能为空");
        }
        if (request.observationStartTime().isAfter(request.observationEndTime())) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "观察窗口开始时间不能晚于结束时间");
        }

        long sampledRequestCount = nvl(request.sampledRequestCount());
        long exceededRequestCount = nvl(request.exceededRequestCount());
        long falsePositiveCount = nvl(request.falsePositiveCount());

        if (sampledRequestCount < 0 || exceededRequestCount < 0 || falsePositiveCount < 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "计数不能为负数");
        }
        if (exceededRequestCount > sampledRequestCount) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "超限命中数不能超过样本数");
        }
        if (falsePositiveCount > exceededRequestCount) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "误判数不能超过超限命中数");
        }

        String rollbackDecision = normalizeRollbackDecision(request.rollbackDecision());
        if (DECISION_FORCE_SHADOW.equals(rollbackDecision) && !StringUtils.hasText(request.rollbackReason())) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "FORCE_SHADOW 必须提供 rollbackReason");
        }
    }

    /**
     * 将回滚决策归一化为有限集合，避免脏值入库。
     *
     * @param rollbackDecision 原始回滚决策
     * @return 归一化后的回滚决策
     */
    private String normalizeRollbackDecision(String rollbackDecision) {
        if (!StringUtils.hasText(rollbackDecision)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "rollbackDecision 不能为空");
        }
        String normalized = rollbackDecision.trim().toUpperCase(Locale.ROOT);
        if (DECISION_KEEP_ENFORCE.equals(normalized)
                || DECISION_FORCE_SHADOW.equals(normalized)
                || DECISION_EXTEND_OBSERVATION.equals(normalized)) {
            return normalized;
        }
        throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "rollbackDecision 不在允许范围");
    }

    /**
     * 将数据库实体转换为响应视图。
     *
     * @param audit 审计实体
     * @return 审计响应
     */
    private QuotaRolloutAuditVO toView(QuotaRolloutAudit audit) {
        long exceededRequestCount = nvl(audit.getExceededRequestCount());
        long falsePositiveCount = nvl(audit.getFalsePositiveCount());
        Double falsePositiveRate = exceededRequestCount == 0
                ? 0D
                : falsePositiveCount * 1D / exceededRequestCount;

        return new QuotaRolloutAuditVO(
                audit.getId(),
                audit.getBatchId(),
                audit.getTenantId(),
                toLocalDateTime(audit.getObservationStartTime()),
                toLocalDateTime(audit.getObservationEndTime()),
                nvl(audit.getSampledRequestCount()),
                exceededRequestCount,
                falsePositiveCount,
                falsePositiveRate,
                audit.getRollbackDecision(),
                audit.getRollbackReason(),
                audit.getEvidenceLink(),
                audit.getOperatorName(),
                toLocalDateTime(audit.getCreateTime()),
                toLocalDateTime(audit.getUpdateTime())
        );
    }

    /**
     * 将 LocalDateTime 转换为 Date 供 Mapper 持久化。
     *
     * @param value 本地时间
     * @return Date 对象
     */
    private Date toDate(LocalDateTime value) {
        return Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 将 Date 转换为 LocalDateTime 供响应输出。
     *
     * @param value Date 对象
     * @return 本地时间
     */
    private LocalDateTime toLocalDateTime(Date value) {
        if (value == null) {
            return null;
        }
        return LocalDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault());
    }

    /**
     * 将操作人ID转换为标准化提交人标识。
     *
     * @param operatorId 操作人ID
     * @return 提交人标识
     */
    private String resolveOperatorName(Long operatorId) {
        if (operatorId == null || operatorId <= 0) {
            return "uid:unknown";
        }
        return "uid:" + operatorId;
    }

    /**
     * 规范化可选字符串，空白值统一转 null。
     *
     * @param rawValue 原始字符串
     * @return 规范化字符串
     */
    private String trimToNull(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        return rawValue.trim();
    }

    /**
     * 空值数字转 0。
     *
     * @param value 原始值
     * @return 非空 long
     */
    private long nvl(Long value) {
        return value == null ? 0L : value;
    }
}
