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
     * Proves replacing an existing policy increments its version and can explicitly disable scheduling.
     */
    @Test
    void shouldReplaceExistingPolicyWithSchedulingDisabled() {
        KeyRotationPolicy existing = new KeyRotationPolicy()
                .setId(71L)
                .setTenantId(11L)
                .setPolicyVersion(null)
                .setNextRunAt(Date.from(NOW));
        KeyRotationPolicyCommand disabled = new KeyRotationPolicyCommand(
                null, null, null, 2, 25, 100,
                false, null, 4, 5L, 60L, 120L, 600L);
        when(wrappingRegistry.activeKeyReference(2)).thenReturn(KeyWrappingResult.success(target));
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(existing);

        KeyRotationPolicy result = service.save(11L, 51L, disabled);

        assertThat(result.getPolicyVersion()).isEqualTo(1L);
        assertThat(result.getScheduleEnabled()).isZero();
        assertThat(result.getNextRunAt()).isNull();
        verify(policyMapper).updateById(existing);
        verify(policyMapper, never()).insert(any(KeyRotationPolicy.class));
    }

    /**
     * Proves tenant policy lookup returns the scoped row and uses a stable not-found error.
     */
    @Test
    void shouldGetTenantPolicyOrRejectMissingPolicy() {
        KeyRotationPolicy policy = new KeyRotationPolicy().setId(71L).setTenantId(11L);
        when(policyMapper.selectOne(any())).thenReturn(policy).thenReturn(null);

        assertThat(service.get(11L)).isSameAs(policy);
        assertThatThrownBy(() -> service.get(11L)).isInstanceOf(GeneralException.class);
    }

    /**
     * Proves status changes reject unknown states and re-arm an active scheduled policy exactly once.
     */
    @Test
    void shouldValidateStatusAndRearmActiveSchedule() {
        KeyRotationPolicy policy = new KeyRotationPolicy()
                .setId(71L)
                .setTenantId(11L)
                .setStatus(KeyRotationStates.POLICY_PAUSED)
                .setPolicyVersion(null)
                .setScheduleEnabled(1)
                .setScheduleIntervalSeconds(300L)
                .setNextRunAt(null);
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(null).thenReturn(policy);

        assertThatThrownBy(() -> service.changeStatus(11L, 51L, "UNKNOWN"))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> service.changeStatus(11L, 51L, KeyRotationStates.POLICY_PAUSED))
                .isInstanceOf(GeneralException.class);

        KeyRotationPolicy active = service.changeStatus(11L, 51L, KeyRotationStates.POLICY_ACTIVE);

        assertThat(active.getStatus()).isEqualTo(KeyRotationStates.POLICY_ACTIVE);
        assertThat(active.getPolicyVersion()).isEqualTo(1L);
        assertThat(active.getNextRunAt()).isNotNull();
        verify(policyMapper).updateById(policy);
        verify(auditService).recordPolicy(policy, 51L, "POLICY_ACTIVE", "SUCCESS");
    }

    /**
     * Proves external retirement acknowledgement rejects both incomplete policy and run gates.
     */
    @Test
    void shouldRejectRetirementWhenPolicyOrRunIsNotReady() {
        KeyRotationPolicy incomplete = new KeyRotationPolicy().setId(71L).setTenantId(11L);
        KeyRotationPolicy ready = new KeyRotationPolicy()
                .setId(71L)
                .setTenantId(11L)
                .setLastRunId(81L)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_READY)
                .setRetirementEligibleAt(Date.from(NOW.minusSeconds(1)));
        KeyRotationRun failed = new KeyRotationRun()
                .setId(81L)
                .setStatus(KeyRotationStates.RUN_COMPLETED_WITH_FAILURES)
                .setRemainingCount(1L)
                .setFailedCount(1L);
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(incomplete, ready);
        when(runMapper.selectById(81L)).thenReturn(failed);

        assertThatThrownBy(() -> service.acknowledgeRetirement(11L, 51L, NOW))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> service.acknowledgeRetirement(11L, 51L, NOW))
                .isInstanceOf(GeneralException.class);

        verify(policyMapper, never()).updateById(any(KeyRotationPolicy.class));
    }

    /**
     * Proves missing tenant or actor identities are rejected before provider and persistence access.
     */
    @Test
    void shouldRejectMissingGovernanceIdentity() {
        assertThatThrownBy(() -> service.save(null, 51L, command()))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> service.changeStatus(11L, 0L, KeyRotationStates.POLICY_ACTIVE))
                .isInstanceOf(GeneralException.class);

        verifyNoInteractions(policyMapper, wrappingRegistry);
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
