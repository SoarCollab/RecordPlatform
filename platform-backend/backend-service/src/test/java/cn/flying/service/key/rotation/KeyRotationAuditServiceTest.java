package cn.flying.service.key.rotation;

import cn.flying.dao.entity.KeyRotationAuditLog;
import cn.flying.dao.entity.KeyRotationPolicy;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.mapper.KeyRotationAuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies audit persistence remains useful for correlation without storing raw provider key identifiers.
 */
@ExtendWith(MockitoExtension.class)
class KeyRotationAuditServiceTest {

    @Mock
    private KeyRotationAuditLogMapper auditLogMapper;

    private KeyRotationAuditService service;

    /**
     * Creates the audit service around an isolated persistence boundary.
     */
    @BeforeEach
    void setUp() {
        service = new KeyRotationAuditService(auditLogMapper);
    }

    /**
     * Proves incomplete objects cannot create unscoped governance evidence.
     */
    @Test
    void shouldIgnoreMissingTenantContext() {
        service.record(null, null, 51L, "COMPLETE", "SUCCESS", null);
        service.record(new KeyRotationRun(), null, 51L, "COMPLETE", "SUCCESS", null);
        service.recordPolicy(null, 51L, "POLICY_SAVE", "SUCCESS");
        service.recordPolicy(new KeyRotationPolicy(), 51L, "POLICY_SAVE", "SUCCESS");

        verify(auditLogMapper, never()).insert(org.mockito.ArgumentMatchers.any(KeyRotationAuditLog.class));
    }

    /**
     * Proves run audit records retain stable facts and only a SHA-256 fingerprint of the provider key ID.
     */
    @Test
    void shouldFingerprintRunTargetBeforePersistence() {
        KeyRotationRun run = new KeyRotationRun()
                .setId(101L)
                .setTenantId(11L)
                .setPolicyId(71L)
                .setRemainingCount(3L)
                .setTargetProvider("vault-transit")
                .setTargetProviderContract(1)
                .setTargetLogicalKeyVersion(2)
                .setTargetKeyId("tenant-key");
        ArgumentCaptor<KeyRotationAuditLog> inserted = ArgumentCaptor.forClass(KeyRotationAuditLog.class);

        service.record(run, 201L, 51L, "ITEM_FAILURE", "FAILURE", "TIMEOUT");

        verify(auditLogMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTenantId()).isEqualTo(11L);
        assertThat(inserted.getValue().getRunId()).isEqualTo(101L);
        assertThat(inserted.getValue().getFailureCategory()).isEqualTo("TIMEOUT");
        assertThat(inserted.getValue().getTargetKeyFingerprint())
                .isEqualTo("5c3c107fd162b818601ac73cf4ac41d98bcf1c4c77f844a1f7877cbb7ee8bcdd")
                .doesNotContain("tenant-key");
    }

    /**
     * Proves policy audit records preserve lifecycle evidence and accept an intentionally absent key ID.
     */
    @Test
    void shouldRecordPolicyWithoutInventingKeyFingerprint() {
        KeyRotationPolicy policy = new KeyRotationPolicy()
                .setId(71L)
                .setTenantId(11L)
                .setLastRunId(101L)
                .setTargetProvider("vault-transit")
                .setTargetProviderContract(1)
                .setTargetLogicalKeyVersion(2)
                .setTargetKeyId(null);
        ArgumentCaptor<KeyRotationAuditLog> inserted = ArgumentCaptor.forClass(KeyRotationAuditLog.class);

        service.recordPolicy(policy, 51L, "POLICY_PAUSED", "SUCCESS");

        verify(auditLogMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getPolicyId()).isEqualTo(71L);
        assertThat(inserted.getValue().getRunId()).isEqualTo(101L);
        assertThat(inserted.getValue().getAction()).isEqualTo("POLICY_PAUSED");
        assertThat(inserted.getValue().getTargetKeyFingerprint()).isNull();
    }
}
