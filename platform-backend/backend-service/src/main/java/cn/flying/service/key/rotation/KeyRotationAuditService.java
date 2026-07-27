package cn.flying.service.key.rotation;

import cn.flying.dao.entity.KeyRotationAuditLog;
import cn.flying.dao.entity.KeyRotationPolicy;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.mapper.KeyRotationAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Persists low-detail rotation audit facts without raw provider key identifiers.
 */
@Service
@RequiredArgsConstructor
public class KeyRotationAuditService {

    private final KeyRotationAuditLogMapper auditLogMapper;

    /**
     * Records one policy, run, item, or retirement transition.
     */
    public void record(KeyRotationRun run,
                       Long itemId,
                       Long actorId,
                       String action,
                       String outcome,
                       String failureCategory) {
        if (run == null || run.getTenantId() == null) {
            return;
        }
        auditLogMapper.insert(new KeyRotationAuditLog()
                .setTenantId(run.getTenantId())
                .setPolicyId(run.getPolicyId())
                .setRunId(run.getId())
                .setItemId(itemId)
                .setActorId(actorId)
                .setAction(action)
                .setOutcome(outcome)
                .setFailureCategory(failureCategory)
                .setRemainingCount(run.getRemainingCount())
                .setTargetProvider(run.getTargetProvider())
                .setTargetProviderContract(run.getTargetProviderContract())
                .setTargetLogicalKeyVersion(run.getTargetLogicalKeyVersion())
                .setTargetKeyFingerprint(fingerprint(run.getTargetKeyId()))
                .setDeleted(0));
    }

    /**
     * Records a sanitized policy lifecycle transition without requiring a run snapshot.
     */
    public void recordPolicy(KeyRotationPolicy policy,
                             Long actorId,
                             String action,
                             String outcome) {
        if (policy == null || policy.getTenantId() == null) {
            return;
        }
        auditLogMapper.insert(new KeyRotationAuditLog()
                .setTenantId(policy.getTenantId())
                .setPolicyId(policy.getId())
                .setRunId(policy.getLastRunId())
                .setActorId(actorId)
                .setAction(action)
                .setOutcome(outcome)
                .setTargetProvider(policy.getTargetProvider())
                .setTargetProviderContract(policy.getTargetProviderContract())
                .setTargetLogicalKeyVersion(policy.getTargetLogicalKeyVersion())
                .setTargetKeyFingerprint(fingerprint(policy.getTargetKeyId()))
                .setDeleted(0));
    }

    /**
     * Produces a fixed non-secret key-reference fingerprint for audit correlation.
     */
    private String fingerprint(String keyId) {
        if (keyId == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(keyId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
