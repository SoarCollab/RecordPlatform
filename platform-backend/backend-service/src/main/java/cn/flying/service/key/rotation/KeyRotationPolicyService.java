package cn.flying.service.key.rotation;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.KeyRotationPolicy;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.mapper.KeyRotationPolicyMapper;
import cn.flying.dao.mapper.KeyRotationRunMapper;
import cn.flying.service.key.KeyWrappingProviderRegistry;
import cn.flying.service.key.WrappingKeyReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/**
 * Validates and persists one immutable-snapshot source policy per tenant.
 */
@Service
@RequiredArgsConstructor
public class KeyRotationPolicyService {

    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_ITEMS_PER_MINUTE = 60_000;
    private static final long MIN_SCHEDULE_SECONDS = 60L;
    private static final long MAX_SCHEDULE_SECONDS = 31_536_000L;
    private static final long MAX_GRACE_SECONDS = 31_536_000L;

    private final KeyRotationPolicyMapper policyMapper;
    private final KeyRotationRunMapper runMapper;
    private final KeyWrappingProviderRegistry wrappingRegistry;
    private final KeyRotationAuditService auditService;

    /**
     * Creates or replaces the tenant policy using the active provider's exact target reference.
     */
    @Transactional(rollbackFor = Exception.class)
    public KeyRotationPolicy save(Long tenantId, Long actorId, KeyRotationPolicyCommand command) {
        validateIdentity(tenantId, actorId);
        validateCommand(command);
        WrappingKeyReference target = wrappingRegistry
                .activeKeyReference(command.targetLogicalKeyVersion())
                .requireValue();
        validateExpectedTarget(command, target);

        KeyRotationPolicy policy = policyMapper.selectTenantPolicyForUpdate(tenantId);
        Date now = new Date();
        if (policy == null) {
            policy = new KeyRotationPolicy()
                    .setTenantId(tenantId)
                    .setCreatedBy(actorId)
                    .setPolicyVersion(1L)
                    .setDeleted(0);
        } else {
            policy.setPolicyVersion(value(policy.getPolicyVersion()) + 1L);
        }
        policy.setStatus(KeyRotationStates.POLICY_ACTIVE)
                .setTargetProvider(target.providerId())
                .setTargetProviderContract(target.providerContractVersion())
                .setTargetKeyId(target.keyId())
                .setTargetProviderKeyVersion(target.providerKeyVersion())
                .setTargetWrappingAlgorithm(target.wrappingAlgorithm())
                .setTargetContextSchema(target.contextSchema())
                .setTargetLogicalKeyVersion(command.targetLogicalKeyVersion())
                .setBatchSize(command.batchSize())
                .setMaxItemsPerMinute(command.maxItemsPerMinute())
                .setScheduleEnabled(Boolean.TRUE.equals(command.scheduleEnabled()) ? 1 : 0)
                .setScheduleIntervalSeconds(command.scheduleIntervalSeconds())
                .setNextRunAt(Boolean.TRUE.equals(command.scheduleEnabled())
                        ? Date.from(now.toInstant().plusSeconds(command.scheduleIntervalSeconds())) : null)
                .setMaxAttempts(command.maxAttempts())
                .setInitialBackoffSeconds(command.initialBackoffSeconds())
                .setMaxBackoffSeconds(command.maxBackoffSeconds())
                .setLeaseSeconds(command.leaseSeconds())
                .setGracePeriodSeconds(command.gracePeriodSeconds())
                .setUpdatedBy(actorId)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY)
                .setRetirementEligibleAt(null)
                .setRetirementAcknowledgedAt(null);
        if (policy.getId() == null) {
            policyMapper.insert(policy);
        } else {
            policyMapper.updateById(policy);
        }
        auditService.recordPolicy(policy, actorId, "POLICY_SAVE", "SUCCESS");
        return policy;
    }

    /**
     * Returns the tenant policy without widening tenant scope.
     */
    public KeyRotationPolicy get(Long tenantId) {
        KeyRotationPolicy policy = policyMapper.selectOne(new LambdaQueryWrapper<KeyRotationPolicy>()
                .eq(KeyRotationPolicy::getTenantId, tenantId)
                .eq(KeyRotationPolicy::getDeleted, 0));
        if (policy == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "密钥轮换策略不存在");
        }
        return policy;
    }

    /**
     * Applies an explicit ACTIVE, PAUSED, or DISABLED policy lifecycle transition.
     */
    @Transactional(rollbackFor = Exception.class)
    public KeyRotationPolicy changeStatus(Long tenantId, Long actorId, String targetStatus) {
        validateIdentity(tenantId, actorId);
        if (!KeyRotationStates.POLICY_ACTIVE.equals(targetStatus)
                && !KeyRotationStates.POLICY_PAUSED.equals(targetStatus)
                && !KeyRotationStates.POLICY_DISABLED.equals(targetStatus)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换策略状态无效");
        }
        KeyRotationPolicy policy = policyMapper.selectTenantPolicyForUpdate(tenantId);
        if (policy == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "密钥轮换策略不存在");
        }
        policy.setStatus(targetStatus)
                .setUpdatedBy(actorId)
                .setPolicyVersion(value(policy.getPolicyVersion()) + 1L);
        if (KeyRotationStates.POLICY_ACTIVE.equals(targetStatus)
                && Integer.valueOf(1).equals(policy.getScheduleEnabled())
                && policy.getNextRunAt() == null) {
            policy.setNextRunAt(Date.from(Instant.now().plusSeconds(policy.getScheduleIntervalSeconds())));
        }
        policyMapper.updateById(policy);
        auditService.recordPolicy(policy, actorId, "POLICY_" + targetStatus, "SUCCESS");
        return policy;
    }

    /**
     * Records an administrator's external retirement acknowledgement without calling a provider delete API.
     */
    @Transactional(rollbackFor = Exception.class)
    public KeyRotationPolicy acknowledgeRetirement(Long tenantId, Long actorId, Instant now) {
        validateIdentity(tenantId, actorId);
        KeyRotationPolicy policy = policyMapper.selectTenantPolicyForUpdate(tenantId);
        if (policy == null || policy.getLastRunId() == null
                || !KeyRotationStates.RETIREMENT_READY.equals(policy.getRetirementStatus())
                || policy.getRetirementEligibleAt() == null
                || policy.getRetirementEligibleAt().toInstant().isAfter(now)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "旧密钥尚未满足外部退役确认条件");
        }
        KeyRotationRun run = runMapper.selectById(policy.getLastRunId());
        if (run == null || !KeyRotationStates.RUN_COMPLETED.equals(run.getStatus())
                || value(run.getRemainingCount()) != 0L || value(run.getFailedCount()) != 0L) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "轮换仍有未完成信封");
        }
        policy.setRetirementStatus(KeyRotationStates.RETIREMENT_ACKNOWLEDGED)
                .setRetirementAcknowledgedAt(Date.from(now))
                .setUpdatedBy(actorId);
        policyMapper.updateById(policy);
        auditService.recordPolicy(policy, actorId, "RETIREMENT_ACKNOWLEDGE", "SUCCESS");
        return policy;
    }

    /**
     * Validates tenant and administrator identities before any persistence call.
     */
    private void validateIdentity(Long tenantId, Long actorId) {
        if (tenantId == null || tenantId <= 0 || actorId == null || actorId <= 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "租户或操作人无效");
        }
    }

    /**
     * Enforces bounded policy values and retry relationships.
     */
    private void validateCommand(KeyRotationPolicyCommand command) {
        if (command == null || command.targetLogicalKeyVersion() == null
                || command.targetLogicalKeyVersion() <= 0
                || command.batchSize() == null || command.batchSize() <= 0
                || command.batchSize() > MAX_BATCH_SIZE
                || command.maxItemsPerMinute() == null || command.maxItemsPerMinute() <= 0
                || command.maxItemsPerMinute() > MAX_ITEMS_PER_MINUTE
                || command.maxAttempts() == null || command.maxAttempts() < 1 || command.maxAttempts() > 20
                || command.initialBackoffSeconds() == null || command.initialBackoffSeconds() < 1
                || command.maxBackoffSeconds() == null
                || command.maxBackoffSeconds() < command.initialBackoffSeconds()
                || command.maxBackoffSeconds() > 86_400L
                || command.leaseSeconds() == null || command.leaseSeconds() < 10 || command.leaseSeconds() > 3_600L
                || command.gracePeriodSeconds() == null || command.gracePeriodSeconds() < 0
                || command.gracePeriodSeconds() > MAX_GRACE_SECONDS) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换策略参数超出安全边界");
        }
        if (Boolean.TRUE.equals(command.scheduleEnabled())
                && (command.scheduleIntervalSeconds() == null
                || command.scheduleIntervalSeconds() < MIN_SCHEDULE_SECONDS
                || command.scheduleIntervalSeconds() > MAX_SCHEDULE_SECONDS)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换调度周期无效");
        }
    }

    /**
     * Prevents a caller from claiming a target different from the active provider snapshot.
     */
    private void validateExpectedTarget(KeyRotationPolicyCommand command, WrappingKeyReference target) {
        if ((StringUtils.hasText(command.expectedProvider())
                && !Objects.equals(command.expectedProvider().trim(), target.providerId()))
                || (command.expectedProviderContract() != null
                && !Objects.equals(command.expectedProviderContract(), target.providerContractVersion()))
                || (StringUtils.hasText(command.expectedProviderKeyVersion())
                && !Objects.equals(command.expectedProviderKeyVersion().trim(), target.providerKeyVersion()))) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "密钥轮换目标与当前 provider 配置不一致");
        }
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
