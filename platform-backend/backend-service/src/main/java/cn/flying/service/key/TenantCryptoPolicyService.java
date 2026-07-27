package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.TenantCryptoPolicy;
import cn.flying.dao.entity.TenantCryptoPolicyAudit;
import cn.flying.dao.mapper.TenantCryptoPolicyAuditMapper;
import cn.flying.dao.mapper.TenantCryptoPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Tenant-isolated administration boundary for runtime crypto policy snapshots.
 */
@Service
@RequiredArgsConstructor
public class TenantCryptoPolicyService {

    private final TenantCryptoPolicyMapper policyMapper;
    private final TenantCryptoPolicyAuditMapper auditMapper;
    private final TenantCryptoPolicyAuditService auditService;
    private final CryptoSuitePolicyService policyService;
    private final FileKeyEnvelopeProperties envelopeProperties;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * Returns the effective tenant policy, including validated defaults when no override exists.
     */
    public CryptoSuitePolicySnapshot getEffective(Long tenantId) {
        requireTenant(tenantId);
        return policyService.currentPolicy();
    }

    /**
     * Creates or updates a tenant policy under a row lock and explicit optimistic version check.
     */
    @Transactional(rollbackFor = Exception.class)
    public CryptoSuitePolicySnapshot save(Long tenantId,
                                          Long actorId,
                                          TenantCryptoPolicyCommand command) {
        requireTenant(tenantId);
        if (actorId == null || actorId <= 0 || command == null || command.expectedVersion() == null
                || command.expectedVersion() < 0) {
            throw invalid(CryptoSuiteFailureReason.POLICY_VERSION_CONFLICT);
        }
        TenantCryptoPolicy current = policyMapper.selectTenantPolicyForUpdate(tenantId);
        long currentVersion = current == null ? 0L : value(current.getPolicyVersion());
        if (currentVersion == Long.MAX_VALUE || currentVersion != command.expectedVersion()) {
            CryptoSuitePolicySnapshot currentSnapshot = current == null
                    ? policyService.currentPolicy() : toSnapshot(current);
            throw auditedFailure(
                    tenantId, currentVersion, actorId, action(current), currentSnapshot, null,
                    CryptoSuiteFailureReason.POLICY_VERSION_CONFLICT);
        }
        long nextVersion = currentVersion + 1L;
        CryptoSuitePolicySnapshot requested = toSnapshot(tenantId, nextVersion, command);
        CryptoSuitePolicySnapshot before = current == null ? policyService.currentPolicy() : toSnapshot(current);
        try {
            policyService.validateForWrite(requested);
            policyService.validateWrappingSelection(requested, envelopeProperties.getKeyVersion());
        } catch (GeneralException exception) {
            throw auditedFailure(
                    tenantId, currentVersion, actorId, action(current), before, requested,
                    failureReason(exception));
        }
        TenantCryptoPolicy target = current == null
                ? new TenantCryptoPolicy()
                .setId(snowflakeIdGenerator.nextId())
                .setTenantId(tenantId)
                .setCreatedBy(actorId)
                .setDeleted(0)
                : current;
        target.setContentEncryptionSuite(requested.contentEncryptionSuite())
                .setEnvelopeSignatureSuite(requested.envelopeSignatureSuite())
                .setKemSuite(requested.kemSuite())
                .setProofSuite(requested.proofSuite())
                .setWrappingProvider(requested.wrappingProvider())
                .setWrappingProviderContract(requested.wrappingProviderContract())
                .setSignedProofSignatureSuite(requested.signedProofSignatureSuite())
                .setSignedProofSuite(requested.signedProofSuite())
                .setSigningProvider(requested.signingProvider())
                .setSigningProviderContract(requested.signingProviderContract())
                .setPolicyVersion(nextVersion)
                .setUpdatedBy(actorId);
        int changed = current == null ? policyMapper.insert(target) : policyMapper.updateById(target);
        if (changed != 1) {
            throw auditedFailure(
                    tenantId, currentVersion, actorId, action(current), before, requested,
                    CryptoSuiteFailureReason.POLICY_VERSION_CONFLICT);
        }
        int audited = auditMapper.insert(new TenantCryptoPolicyAudit()
                .setId(snowflakeIdGenerator.nextId())
                .setTenantId(tenantId)
                .setPolicyId(target.getId())
                .setPolicyVersion(nextVersion)
                .setActorId(actorId)
                .setAction(current == null ? "CREATE" : "UPDATE")
                .setOutcome("SUCCESS")
                .setOldPolicyFingerprint(policyService.fingerprint(before))
                .setNewPolicyFingerprint(policyService.fingerprint(requested))
                .setDeleted(0));
        if (audited != 1) {
            throw new GeneralException(ResultEnum.FAIL, "crypto policy audit persistence failed");
        }
        return requested;
    }

    /**
     * Ensures the explicit request tenant matches the trusted request context.
     */
    private void requireTenant(Long tenantId) {
        Long trustedTenant = TenantContext.getTenantId();
        if (tenantId == null || tenantId <= 0 || trustedTenant == null || !trustedTenant.equals(tenantId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
    }

    /**
     * Maps an administrator command into one immutable validated snapshot.
     */
    private CryptoSuitePolicySnapshot toSnapshot(Long tenantId,
                                                 long version,
                                                 TenantCryptoPolicyCommand command) {
        return new CryptoSuitePolicySnapshot(
                tenantId, version, command.contentEncryptionSuite(), command.envelopeSignatureSuite(),
                command.kemSuite(), command.proofSuite(), command.wrappingProvider(),
                integer(command.wrappingProviderContract()), command.signedProofSignatureSuite(),
                command.signedProofSuite(), command.signingProvider(),
                integer(command.signingProviderContract()));
    }

    /**
     * Maps a persisted policy without consulting current defaults.
     */
    private CryptoSuitePolicySnapshot toSnapshot(TenantCryptoPolicy policy) {
        return new CryptoSuitePolicySnapshot(
                policy.getTenantId(), value(policy.getPolicyVersion()),
                policy.getContentEncryptionSuite(), policy.getEnvelopeSignatureSuite(),
                policy.getKemSuite(), policy.getProofSuite(), policy.getWrappingProvider(),
                integer(policy.getWrappingProviderContract()), policy.getSignedProofSignatureSuite(),
                policy.getSignedProofSuite(), policy.getSigningProvider(),
                integer(policy.getSigningProviderContract()));
    }

    /**
     * Creates a stable sanitized invalid-policy failure.
     */
    private GeneralException invalid(CryptoSuiteFailureReason reason) {
        return new GeneralException(ResultEnum.PARAM_IS_INVALID, Map.of("reason", reason.name()));
    }

    /**
     * Persists one rejected decision and returns the stable public policy failure.
     */
    private GeneralException auditedFailure(Long tenantId,
                                            long policyVersion,
                                            Long actorId,
                                            String action,
                                            CryptoSuitePolicySnapshot before,
                                            CryptoSuitePolicySnapshot requested,
                                            CryptoSuiteFailureReason reason) {
        auditService.recordFailure(
                tenantId,
                policyVersion,
                actorId,
                action,
                policyService.fingerprint(before),
                policyService.fingerprint(requested),
                reason);
        return invalid(reason);
    }

    /**
     * Resolves the stable operation name without exposing request values.
     */
    private String action(TenantCryptoPolicy current) {
        return current == null ? "CREATE" : "UPDATE";
    }

    /**
     * Extracts a closed registry reason and collapses unexpected details to capability mismatch.
     */
    private CryptoSuiteFailureReason failureReason(GeneralException exception) {
        if (exception != null && exception.getData() instanceof Map<?, ?> data) {
            Object reason = data.get("reason");
            if (reason instanceof String value) {
                try {
                    return CryptoSuiteFailureReason.valueOf(value);
                } catch (IllegalArgumentException ignored) {
                    // Fall through to the closed non-secret category.
                }
            }
        }
        return CryptoSuiteFailureReason.CAPABILITY_MISMATCH;
    }

    private int integer(Integer value) {
        return value == null ? 0 : value;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
