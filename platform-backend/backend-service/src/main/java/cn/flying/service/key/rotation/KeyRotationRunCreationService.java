package cn.flying.service.key.rotation;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.KeyRotationPolicy;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import cn.flying.dao.mapper.KeyRotationPolicyMapper;
import cn.flying.dao.mapper.KeyRotationRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;

/**
 * Serializes manual and scheduled triggers into immutable run snapshots.
 */
@Service
@RequiredArgsConstructor
public class KeyRotationRunCreationService {

    private final KeyRotationPolicyMapper policyMapper;
    private final KeyRotationRunMapper runMapper;
    private final FileKeyEnvelopeMapper envelopeMapper;
    private final KeyRotationAuditService auditService;

    /**
     * Starts or idempotently returns a manual run identified by a caller request key.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public KeyRotationRun startManual(Long tenantId,
                                      Long actorId,
                                      String mode,
                                      String requestId,
                                      Instant now) {
        validateMode(mode);
        if (actorId == null || actorId <= 0 || requestId == null
                || !requestId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "轮换请求标识无效");
        }
        KeyRotationPolicy policy = requireActivePolicyForUpdate(tenantId);
        return createOrGet(policy, actorId, "MANUAL", "manual:" + requestId, mode, now);
    }

    /**
     * Creates exactly one due scheduled run and advances the durable next-run boundary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public KeyRotationRun startScheduledIfDue(Long tenantId, Instant now) {
        KeyRotationPolicy policy = policyMapper.selectTenantPolicyForUpdate(tenantId);
        if (policy == null || !KeyRotationStates.POLICY_ACTIVE.equals(policy.getStatus())
                || !Integer.valueOf(1).equals(policy.getScheduleEnabled())
                || policy.getNextRunAt() == null || policy.getNextRunAt().toInstant().isAfter(now)) {
            return null;
        }
        Instant due = policy.getNextRunAt().toInstant();
        String triggerKey = "scheduled:" + due.getEpochSecond();
        policy.setNextRunAt(Date.from(nextDue(due, policy.getScheduleIntervalSeconds(), now)));
        KeyRotationRun activeRun = findActiveRun(policy);
        if (activeRun != null) {
            policyMapper.updateById(policy);
            return activeRun;
        }
        KeyRotationRun run = createOrGet(
                policy, 0L, "SCHEDULED", triggerKey, KeyRotationStates.MODE_APPLY, now);
        policyMapper.updateById(policy);
        return run;
    }

    /**
     * Creates the frozen run and resets retirement readiness for a new cycle.
     */
    private KeyRotationRun createOrGet(KeyRotationPolicy policy,
                                       Long actorId,
                                       String triggerType,
                                       String triggerKey,
                                       String mode,
                                       Instant now) {
        KeyRotationRun existing = runMapper.selectOne(new LambdaQueryWrapper<KeyRotationRun>()
                .eq(KeyRotationRun::getTenantId, policy.getTenantId())
                .eq(KeyRotationRun::getPolicyId, policy.getId())
                .eq(KeyRotationRun::getTriggerKey, triggerKey)
                .eq(KeyRotationRun::getDeleted, 0));
        if (existing != null) {
            if (!mode.equals(existing.getMode())
                    || !policy.getPolicyVersion().equals(existing.getPolicyVersion())) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "轮换请求标识已用于不同执行参数");
            }
            return existing;
        }
        if (findActiveRun(policy) != null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "已有未结束的密钥轮换任务");
        }
        Long maxEnvelopeId = envelopeMapper.selectMaxEnvelopeId(policy.getTenantId());
        KeyRotationRun run = new KeyRotationRun()
                .setId(IdWorker.getId())
                .setTenantId(policy.getTenantId())
                .setPolicyId(policy.getId())
                .setPolicyVersion(policy.getPolicyVersion())
                .setTriggerType(triggerType)
                .setTriggerKey(triggerKey)
                .setMode(mode)
                .setStatus(KeyRotationStates.RUN_PLANNED)
                .setTargetProvider(policy.getTargetProvider())
                .setTargetProviderContract(policy.getTargetProviderContract())
                .setTargetKeyId(policy.getTargetKeyId())
                .setTargetProviderKeyVersion(policy.getTargetProviderKeyVersion())
                .setTargetWrappingAlgorithm(policy.getTargetWrappingAlgorithm())
                .setTargetContextSchema(policy.getTargetContextSchema())
                .setTargetLogicalKeyVersion(policy.getTargetLogicalKeyVersion())
                .setBatchSize(policy.getBatchSize())
                .setMaxItemsPerMinute(policy.getMaxItemsPerMinute())
                .setMaxAttempts(policy.getMaxAttempts())
                .setInitialBackoffSeconds(policy.getInitialBackoffSeconds())
                .setMaxBackoffSeconds(policy.getMaxBackoffSeconds())
                .setLeaseSeconds(policy.getLeaseSeconds())
                .setGracePeriodSeconds(policy.getGracePeriodSeconds())
                .setSnapshotMaxEnvelopeId(maxEnvelopeId == null ? 0L : maxEnvelopeId)
                .setScanCursorId(0L)
                .setDiscoveryComplete(0)
                .setTotalCount(0L)
                .setPendingCount(0L)
                .setRunningCount(0L)
                .setSucceededCount(0L)
                .setSkippedCount(0L)
                .setFailedCount(0L)
                .setRemainingCount(0L)
                .setRateWindowStartedAt(Date.from(now))
                .setRateWindowCount(0)
                .setCreatedBy(actorId)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY)
                .setDeleted(0);
        runMapper.insert(run);
        if (KeyRotationStates.MODE_APPLY.equals(mode)) {
            policy.setLastRunId(run.getId())
                    .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY)
                    .setRetirementEligibleAt(null)
                    .setRetirementAcknowledgedAt(null);
            policyMapper.updateById(policy);
        }
        auditService.record(run, null, actorId, "START", "SUCCESS", null);
        return run;
    }

    /**
     * Returns the oldest unfinished run while the tenant policy row serializes competing triggers.
     */
    private KeyRotationRun findActiveRun(KeyRotationPolicy policy) {
        return runMapper.selectOne(new LambdaQueryWrapper<KeyRotationRun>()
                .eq(KeyRotationRun::getTenantId, policy.getTenantId())
                .eq(KeyRotationRun::getPolicyId, policy.getId())
                .in(KeyRotationRun::getStatus,
                        KeyRotationStates.RUN_PLANNED,
                        KeyRotationStates.RUN_RUNNING,
                        KeyRotationStates.RUN_PAUSED)
                .eq(KeyRotationRun::getDeleted, 0)
                .orderByAsc(KeyRotationRun::getId)
                .last("LIMIT 1"));
    }

    /**
     * Locks and requires an enabled tenant policy.
     */
    private KeyRotationPolicy requireActivePolicyForUpdate(Long tenantId) {
        KeyRotationPolicy policy = policyMapper.selectTenantPolicyForUpdate(tenantId);
        if (policy == null || !KeyRotationStates.POLICY_ACTIVE.equals(policy.getStatus())) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换策略未启用");
        }
        return policy;
    }

    /**
     * Advances a fixed interval until its timestamp is strictly in the future.
     */
    private Instant nextDue(Instant due, Long intervalSeconds, Instant now) {
        if (intervalSeconds == null || intervalSeconds < 60L) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换调度周期无效");
        }
        long missed = Math.max(1L, Math.floorDiv(now.getEpochSecond() - due.getEpochSecond(), intervalSeconds) + 1L);
        try {
            return due.plusSeconds(Math.multiplyExact(missed, intervalSeconds));
        } catch (ArithmeticException overflow) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换调度时间溢出");
        }
    }

    /**
     * Rejects unknown modes before touching policy state.
     */
    private void validateMode(String mode) {
        if (!KeyRotationStates.MODE_DRY_RUN.equals(mode) && !KeyRotationStates.MODE_APPLY.equals(mode)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换模式无效");
        }
    }
}
