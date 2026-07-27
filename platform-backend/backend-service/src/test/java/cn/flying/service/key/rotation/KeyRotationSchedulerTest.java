package cn.flying.service.key.rotation;

import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.mapper.KeyRotationPolicyMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies bounded cross-tenant scheduling and tenant-context restoration.
 */
@ExtendWith(MockitoExtension.class)
class KeyRotationSchedulerTest {

    @Mock
    private KeyRotationPolicyMapper policyMapper;

    @Mock
    private KeyRotationRunCreationService runCreationService;

    @Mock
    private KeyRotationRunService runService;

    @Mock
    private KeyRotationWorkerService workerService;

    private KeyRotationProperties properties;
    private KeyRotationScheduler scheduler;

    /**
     * Creates a scheduler with a deliberately small database-side tenant bound.
     */
    @BeforeEach
    void setUp() {
        properties = new KeyRotationProperties();
        properties.setMaxTenantsPerPoll(2);
        scheduler = new KeyRotationScheduler(
                policyMapper, runCreationService, runService, workerService, properties);
    }

    /**
     * Clears thread-local tenant state even when an assertion fails.
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Proves selected tenants execute under strict isolation and the caller context is restored.
     */
    @Test
    void shouldRunBoundedTenantWorkAndRestoreCallerContext() {
        TenantContext.setTenantId(99L);
        TenantContext.setIgnoreIsolation(true);
        when(policyMapper.selectWorkTenantIds(any(Date.class), eq(2))).thenReturn(List.of(11L));
        when(runCreationService.startScheduledIfDue(eq(11L), any(Instant.class)))
                .thenAnswer(invocation -> {
                    assertThat(TenantContext.getTenantId()).isEqualTo(11L);
                    assertThat(TenantContext.isIgnoreIsolation()).isFalse();
                    return null;
                });

        scheduler.runScheduled();

        verify(workerService).runTenant(11L);
        verify(runService).refreshLatestRetirementReadiness(eq(11L), any(Instant.class));
        assertThat(TenantContext.getTenantId()).isEqualTo(99L);
        assertThat(TenantContext.isIgnoreIsolation()).isTrue();
    }

    /**
     * Proves one tenant failure is isolated and does not prevent a later tenant from progressing.
     */
    @Test
    void shouldContinueAfterOneTenantCycleFails() {
        when(policyMapper.selectWorkTenantIds(any(Date.class), eq(2))).thenReturn(List.of(11L, 12L));
        doThrow(new IllegalStateException("bounded-test-failure"))
                .when(workerService).runTenant(11L);

        scheduler.runScheduled();

        verify(runCreationService).startScheduledIfDue(eq(12L), any(Instant.class));
        verify(workerService).runTenant(12L);
        verify(runService).refreshLatestRetirementReadiness(eq(12L), any(Instant.class));
        verify(runService, never()).refreshLatestRetirementReadiness(eq(11L), any(Instant.class));
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.isIgnoreIsolation()).isFalse();
    }

    /**
     * Proves an empty database work page performs no tenant-scoped service call.
     */
    @Test
    void shouldStopWhenNoTenantHasDueWork() {
        when(policyMapper.selectWorkTenantIds(any(Date.class), eq(2))).thenReturn(List.of());

        scheduler.runScheduled();

        verifyNoInteractions(runCreationService, runService, workerService);
    }
}
