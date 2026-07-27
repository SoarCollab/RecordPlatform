package cn.flying.service.key.rotation;

import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.KeyRotationPolicy;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import cn.flying.dao.mapper.KeyRotationPolicyMapper;
import cn.flying.dao.mapper.KeyRotationRunMapper;
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
 * Verifies immutable run snapshots, trigger idempotency, and serialized schedule behavior.
 */
@ExtendWith(MockitoExtension.class)
class KeyRotationRunCreationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T02:00:00Z");

    @Mock
    private KeyRotationPolicyMapper policyMapper;

    @Mock
    private KeyRotationRunMapper runMapper;

    @Mock
    private FileKeyEnvelopeMapper envelopeMapper;

    @Mock
    private KeyRotationAuditService auditService;

    private KeyRotationRunCreationService service;

    /**
     * Creates the run factory around isolated persistence boundaries.
     */
    @BeforeEach
    void setUp() {
        service = new KeyRotationRunCreationService(
                policyMapper, runMapper, envelopeMapper, auditService);
    }

    /**
     * Proves an APPLY run freezes every provider, cursor, retry, rate, and grace policy field.
     */
    @Test
    void shouldCreateFrozenApplySnapshot() {
        KeyRotationPolicy policy = policy();
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(policy);
        when(runMapper.selectOne(any())).thenReturn(null);
        when(envelopeMapper.selectMaxEnvelopeId(11L)).thenReturn(900L);
        when(runMapper.insert(any(KeyRotationRun.class))).thenReturn(1);
        ArgumentCaptor<KeyRotationRun> inserted = ArgumentCaptor.forClass(KeyRotationRun.class);

        KeyRotationRun result = service.startManual(
                11L, 51L, KeyRotationStates.MODE_APPLY, "request-1", NOW);

        verify(runMapper).insert(inserted.capture());
        KeyRotationRun snapshot = inserted.getValue();
        assertThat(result).isSameAs(snapshot);
        assertThat(snapshot.getTriggerKey()).isEqualTo("manual:request-1");
        assertThat(snapshot.getTargetProvider()).isEqualTo("vault-transit");
        assertThat(snapshot.getTargetKeyId()).isEqualTo("tenant-key");
        assertThat(snapshot.getSnapshotMaxEnvelopeId()).isEqualTo(900L);
        assertThat(snapshot.getBatchSize()).isEqualTo(25);
        assertThat(snapshot.getMaxAttempts()).isEqualTo(4);
        assertThat(policy.getLastRunId()).isEqualTo(snapshot.getId());
        verify(policyMapper).updateById(policy);
        verify(auditService).record(snapshot, null, 51L, "START", "SUCCESS", null);
    }

    /**
     * Proves replaying the same idempotency key returns the original immutable execution.
     */
    @Test
    void shouldReturnSameRunForMatchingManualReplay() {
        KeyRotationPolicy policy = policy();
        KeyRotationRun existing = new KeyRotationRun()
                .setId(101L)
                .setPolicyVersion(policy.getPolicyVersion())
                .setMode(KeyRotationStates.MODE_APPLY);
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(policy);
        when(runMapper.selectOne(any())).thenReturn(existing);

        assertThat(service.startManual(
                11L, 51L, KeyRotationStates.MODE_APPLY, "request-1", NOW)).isSameAs(existing);

        verify(runMapper, never()).insert(any(KeyRotationRun.class));
        verifyNoInteractions(envelopeMapper);
    }

    /**
     * Proves reusing an idempotency key with changed semantics is rejected instead of silently misreporting APPLY.
     */
    @Test
    void shouldRejectConflictingIdempotencyReplay() {
        KeyRotationPolicy policy = policy();
        KeyRotationRun existing = new KeyRotationRun()
                .setId(101L)
                .setPolicyVersion(policy.getPolicyVersion())
                .setMode(KeyRotationStates.MODE_DRY_RUN);
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(policy);
        when(runMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.startManual(
                11L, 51L, KeyRotationStates.MODE_APPLY, "request-1", NOW))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * Proves a dry-run does not erase a previous APPLY run's retirement evidence.
     */
    @Test
    void shouldPreserveApplyRetirementStateForDryRun() {
        KeyRotationPolicy policy = policy()
                .setLastRunId(77L)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_READY)
                .setRetirementEligibleAt(Date.from(NOW.minusSeconds(1)));
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(policy);
        when(runMapper.selectOne(any())).thenReturn(null);
        when(envelopeMapper.selectMaxEnvelopeId(11L)).thenReturn(900L);
        when(runMapper.insert(any(KeyRotationRun.class))).thenReturn(1);

        service.startManual(11L, 51L, KeyRotationStates.MODE_DRY_RUN, "preview-1", NOW);

        assertThat(policy.getLastRunId()).isEqualTo(77L);
        assertThat(policy.getRetirementStatus()).isEqualTo(KeyRotationStates.RETIREMENT_READY);
        verify(policyMapper, never()).updateById(policy);
    }

    /**
     * Proves a due schedule advances once but never overlaps an unfinished run.
     */
    @Test
    void shouldAdvanceDueScheduleWithoutCreatingOverlappingRun() {
        KeyRotationPolicy policy = policy()
                .setScheduleEnabled(1)
                .setScheduleIntervalSeconds(300L)
                .setNextRunAt(Date.from(NOW.minusSeconds(600)));
        KeyRotationRun active = new KeyRotationRun()
                .setId(88L)
                .setStatus(KeyRotationStates.RUN_RUNNING);
        when(policyMapper.selectTenantPolicyForUpdate(11L)).thenReturn(policy);
        when(runMapper.selectOne(any())).thenReturn(active);

        assertThat(service.startScheduledIfDue(11L, NOW)).isSameAs(active);

        assertThat(policy.getNextRunAt().toInstant()).isAfter(NOW);
        verify(policyMapper).updateById(policy);
        verify(runMapper, never()).insert(any(KeyRotationRun.class));
        verifyNoInteractions(envelopeMapper);
    }

    /**
     * Builds one active tenant policy containing every immutable run input.
     */
    private KeyRotationPolicy policy() {
        return new KeyRotationPolicy()
                .setId(71L)
                .setTenantId(11L)
                .setStatus(KeyRotationStates.POLICY_ACTIVE)
                .setPolicyVersion(3L)
                .setTargetProvider("vault-transit")
                .setTargetProviderContract(1)
                .setTargetKeyId("tenant-key")
                .setTargetProviderKeyVersion("7")
                .setTargetWrappingAlgorithm("VAULT-TRANSIT")
                .setTargetContextSchema("external-v2")
                .setTargetLogicalKeyVersion(2)
                .setBatchSize(25)
                .setMaxItemsPerMinute(100)
                .setScheduleEnabled(0)
                .setMaxAttempts(4)
                .setInitialBackoffSeconds(5L)
                .setMaxBackoffSeconds(60L)
                .setLeaseSeconds(120L)
                .setGracePeriodSeconds(600L)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY)
                .setDeleted(0);
    }
}
