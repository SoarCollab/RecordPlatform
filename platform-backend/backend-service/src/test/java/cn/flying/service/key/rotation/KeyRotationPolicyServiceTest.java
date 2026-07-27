package cn.flying.service.key.rotation;

import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.KeyRotationPolicy;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.mapper.KeyRotationPolicyMapper;
import cn.flying.dao.mapper.KeyRotationRunMapper;
import cn.flying.service.key.KeyWrappingProviderRegistry;
import cn.flying.service.key.KeyWrappingResult;
import cn.flying.service.key.WrappingKeyReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies policy bounds, exact provider snapshots, sanitized audit, and external retirement acknowledgement.
 */
@ExtendWith(MockitoExtension.class)
class KeyRotationPolicyServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T03:00:00Z");

    @Mock
    private KeyRotationPolicyMapper policyMapper;

    @Mock
    private KeyRotationRunMapper runMapper;

    @Mock
    private KeyWrappingProviderRegistry wrappingRegistry;

    @Mock
    private KeyRotationAuditService auditService;

    private KeyRotationPolicyService service;
    private WrappingKeyReference target;

    /**
     * Creates a policy service and the exact active provider reference used by valid commands.
     */
    @BeforeEach
    void setUp() {
        service = new KeyRotationPolicyService(
                policyMapper, runMapper, wrappingRegistry, auditService);
        target = new WrappingKeyReference(
                "vault-transit", 1, "tenant-key", "7", "VAULT-TRANSIT", "external-v2");
    }

    /**
     * Proves policy persistence snapshots the registry result rather than trusting caller key routing.
     */
    @Test
    void shouldPersistExactActiveProviderSnapshot() {
        when(wrappingRegistry.activeKeyReference(2)).thenReturn(KeyWrappingResult.success(target));
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(null);
        when(policyMapper.insert(any(KeyRotationPolicy.class))).thenAnswer(invocation -> {
            ((KeyRotationPolicy) invocation.getArgument(0)).setId(71L);
            return 1;
        });
        ArgumentCaptor<KeyRotationPolicy> inserted = ArgumentCaptor.forClass(KeyRotationPolicy.class);

        KeyRotationPolicy result = service.save(11L, 51L, command());

        verify(policyMapper).insert(inserted.capture());
        assertThat(result).isSameAs(inserted.getValue());
        assertThat(result.getTargetProvider()).isEqualTo("vault-transit");
        assertThat(result.getTargetKeyId()).isEqualTo("tenant-key");
        assertThat(result.getTargetProviderKeyVersion()).isEqualTo("7");
        assertThat(result.getPolicyVersion()).isEqualTo(1L);
        verify(auditService).recordPolicy(result, 51L, "POLICY_SAVE", "SUCCESS");
    }

    /**
     * Proves a caller cannot save a policy that claims a different active provider version.
     */
    @Test
    void shouldRejectExpectedTargetMismatch() {
        when(wrappingRegistry.activeKeyReference(2)).thenReturn(KeyWrappingResult.success(target));
        KeyRotationPolicyCommand mismatched = new KeyRotationPolicyCommand(
                "vault-transit", 1, "8", 2, 25, 100,
                true, 300L, 4, 5L, 60L, 120L, 600L);

        assertThatThrownBy(() -> service.save(11L, 51L, mismatched))
                .isInstanceOf(GeneralException.class);

        verifyNoInteractions(policyMapper);
    }

    /**
     * Proves unsafe retry and scheduling bounds are rejected before provider or database access.
     */
    @Test
    void shouldRejectUnsafePolicyBounds() {
        KeyRotationPolicyCommand invalid = new KeyRotationPolicyCommand(
                null, null, null, 2, 501, 100,
                true, 30L, 0, 0L, 0L, 5L, -1L);

        assertThatThrownBy(() -> service.save(11L, 51L, invalid))
                .isInstanceOf(GeneralException.class);

        verifyNoInteractions(policyMapper, wrappingRegistry);
    }

    /**
     * Proves acknowledgement records only an external action after completed zero-remaining gates.
     */
    @Test
    void shouldAcknowledgeExternalRetirementOnlyAfterReadiness() {
        KeyRotationPolicy policy = new KeyRotationPolicy()
                .setId(71L)
                .setTenantId(11L)
                .setLastRunId(81L)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_READY)
                .setRetirementEligibleAt(Date.from(NOW.minusSeconds(1)));
        KeyRotationRun run = new KeyRotationRun()
                .setId(81L)
                .setStatus(KeyRotationStates.RUN_COMPLETED)
                .setRemainingCount(0L)
                .setFailedCount(0L);
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(policy);
        when(runMapper.selectById(81L)).thenReturn(run);

        KeyRotationPolicy result = service.acknowledgeRetirement(11L, 51L, NOW);

        assertThat(result.getRetirementStatus()).isEqualTo(KeyRotationStates.RETIREMENT_ACKNOWLEDGED);
        assertThat(result.getRetirementAcknowledgedAt().toInstant()).isEqualTo(NOW);
        verify(policyMapper).updateById(policy);
        verify(auditService).recordPolicy(
                policy, 51L, "RETIREMENT_ACKNOWLEDGE", "SUCCESS");
        verify(wrappingRegistry, never()).activeKeyReference(any());
    }

    /**
     * Builds one fully bounded, schedule-enabled policy command.
     */
    private KeyRotationPolicyCommand command() {
        return new KeyRotationPolicyCommand(
                "vault-transit", 1, "7", 2, 25, 100,
                true, 300L, 4, 5L, 60L, 120L, 600L);
    }
}
