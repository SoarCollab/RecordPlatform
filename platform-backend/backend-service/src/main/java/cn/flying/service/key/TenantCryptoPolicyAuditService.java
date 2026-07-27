package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.TenantCryptoPolicyAudit;
import cn.flying.dao.mapper.TenantCryptoPolicyAuditMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists sanitized policy failure evidence independently from the rejected policy transaction.
 */
@Service
@RequiredArgsConstructor
public class TenantCryptoPolicyAuditService {

    private final TenantCryptoPolicyAuditMapper auditMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * Records a stable failure category without retaining request fields or provider secrets.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailure(Long tenantId,
                              Long policyVersion,
                              Long actorId,
                              String action,
                              String oldPolicyFingerprint,
                              String newPolicyFingerprint,
                              CryptoSuiteFailureReason reason) {
        TenantCryptoPolicyAudit audit = new TenantCryptoPolicyAudit()
                .setId(snowflakeIdGenerator.nextId())
                .setTenantId(tenantId)
                .setPolicyVersion(policyVersion)
                .setActorId(actorId)
                .setAction(action)
                .setOutcome("FAILURE")
                .setOldPolicyFingerprint(oldPolicyFingerprint)
                .setNewPolicyFingerprint(newPolicyFingerprint)
                .setFailureReason(reason.name())
                .setDeleted(0);
        if (auditMapper.insert(audit) != 1) {
            throw new GeneralException(ResultEnum.FAIL, "crypto policy failure audit persistence failed");
        }
    }
}
