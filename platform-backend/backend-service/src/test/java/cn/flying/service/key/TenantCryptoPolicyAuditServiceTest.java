package cn.flying.service.key;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.TenantCryptoPolicyAudit;
import cn.flying.dao.mapper.TenantCryptoPolicyAuditMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises independent, sanitized failure-audit persistence for rejected tenant policies.
 */
@ExtendWith(MockitoExtension.class)
class TenantCryptoPolicyAuditServiceTest {

    @Mock
    private TenantCryptoPolicyAuditMapper auditMapper;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * Proves the audit row retains only stable identities, fingerprints, and failure category.
     */
    @Test
    void shouldPersistSanitizedFailureEvidence() {
        when(snowflakeIdGenerator.nextId()).thenReturn(701L);
        when(auditMapper.insert(any(TenantCryptoPolicyAudit.class))).thenReturn(1);
        TenantCryptoPolicyAuditService service = new TenantCryptoPolicyAuditService(
                auditMapper, snowflakeIdGenerator);

        service.recordFailure(
                17L, 4L, 91L, "UPDATE", "a".repeat(64), "b".repeat(64),
                CryptoSuiteFailureReason.PROVIDER_MISMATCH);

        ArgumentCaptor<TenantCryptoPolicyAudit> audit = ArgumentCaptor.forClass(
                TenantCryptoPolicyAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertThat(audit.getValue().getId()).isEqualTo(701L);
        assertThat(audit.getValue().getTenantId()).isEqualTo(17L);
        assertThat(audit.getValue().getOutcome()).isEqualTo("FAILURE");
        assertThat(audit.getValue().getFailureReason()).isEqualTo("PROVIDER_MISMATCH");
        assertThat(audit.getValue().getPolicyId()).isNull();
    }

    /**
     * Proves a lost audit insert fails closed instead of silently discarding rejection evidence.
     */
    @Test
    void shouldFailClosedWhenFailureAuditIsNotInserted() {
        when(snowflakeIdGenerator.nextId()).thenReturn(701L);
        when(auditMapper.insert(any(TenantCryptoPolicyAudit.class))).thenReturn(0);
        TenantCryptoPolicyAuditService service = new TenantCryptoPolicyAuditService(
                auditMapper, snowflakeIdGenerator);

        assertThatThrownBy(() -> service.recordFailure(
                17L, 4L, 91L, "UPDATE", null, null,
                CryptoSuiteFailureReason.POLICY_VERSION_CONFLICT))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * Proves rejected-policy evidence uses an independent transaction boundary.
     */
    @Test
    void shouldDeclareRequiresNewTransactionBoundary() throws NoSuchMethodException {
        Transactional transactional = TenantCryptoPolicyAuditService.class
                .getDeclaredMethod(
                        "recordFailure", Long.class, Long.class, Long.class, String.class,
                        String.class, String.class, CryptoSuiteFailureReason.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }
}
