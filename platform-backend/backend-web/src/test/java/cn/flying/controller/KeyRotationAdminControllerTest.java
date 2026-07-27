package cn.flying.controller;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.dao.entity.KeyRotationItem;
import cn.flying.dao.entity.KeyRotationPolicy;
import cn.flying.dao.entity.KeyRotationRun;
import cn.flying.dao.vo.admin.KeyRotationPolicyRequest;
import cn.flying.dao.vo.admin.KeyRotationStartRequest;
import cn.flying.service.key.rotation.KeyRotationPolicyService;
import cn.flying.service.key.rotation.KeyRotationRunCreationService;
import cn.flying.service.key.rotation.KeyRotationRunService;
import cn.flying.service.key.rotation.KeyRotationStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies sanitized administrator DTO mapping and opaque identifier handling without a web container.
 */
@ExtendWith(MockitoExtension.class)
class KeyRotationAdminControllerTest {

    private static final Long TENANT_ID = 11L;
    private static final Long ACTOR_ID = 51L;
    private static final Long POLICY_ID = 71L;
    private static final Long RUN_ID = 101L;

    @Mock
    private KeyRotationPolicyService policyService;

    @Mock
    private KeyRotationRunCreationService runCreationService;

    @Mock
    private KeyRotationRunService runService;

    private KeyRotationAdminController controller;

    /**
     * Creates the controller around isolated governance services.
     */
    @BeforeEach
    void setUp() {
        new IdUtils(null,
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234"));
        controller = new KeyRotationAdminController(policyService, runCreationService, runService);
    }

    /**
     * Covers policy create, read, lifecycle, and retirement mappings while proving the raw key ID is absent.
     */
    @Test
    void shouldMapEveryPolicyControlWithoutRawProviderKeyId() {
        KeyRotationPolicy policy = policy();
        KeyRotationPolicyRequest request = new KeyRotationPolicyRequest(
                "vault-transit", 1, "7", 2, 25, 100,
                true, 300L, 4, 5L, 60L, 120L, 600L);
        when(policyService.save(any(), any(), any())).thenReturn(policy);
        when(policyService.get(TENANT_ID)).thenReturn(policy);
        when(policyService.changeStatus(any(), any(), any())).thenReturn(policy);
        when(policyService.acknowledgeRetirement(any(), any(), any())).thenReturn(policy);

        assertThat(controller.savePolicy(ACTOR_ID, TENANT_ID, request).getData().id())
                .isEqualTo(IdUtils.toExternalId(POLICY_ID));
        assertThat(controller.getPolicy(TENANT_ID).getData().targetProviderContract()).isZero();
        assertThat(controller.pausePolicy(ACTOR_ID, TENANT_ID).getData().targetLogicalKeyVersion()).isZero();
        assertThat(controller.resumePolicy(ACTOR_ID, TENANT_ID).getData().batchSize()).isZero();
        assertThat(controller.disablePolicy(ACTOR_ID, TENANT_ID).getData().policyVersion()).isZero();
        assertThat(controller.acknowledgeRetirement(ACTOR_ID, TENANT_ID).getData().lastRunId()).isNull();
        assertThat(controller.getPolicy(TENANT_ID).getData().toString()).doesNotContain("raw-provider-key");
    }

    /**
     * Covers run creation, lookup, lifecycle controls, and positive boundary mapping through opaque IDs.
     */
    @Test
    void shouldMapEveryRunControlThroughOpaqueIdentifiers() {
        KeyRotationRun run = run().setSnapshotMaxEnvelopeId(999L);
        String externalRunId = IdUtils.toExternalId(RUN_ID);
        when(runCreationService.startManual(any(), any(), any(), any(), any())).thenReturn(run);
        when(runService.listRuns(TENANT_ID, 20)).thenReturn(List.of(run));
        when(runService.getRun(TENANT_ID, RUN_ID)).thenReturn(run);
        when(runService.pause(TENANT_ID, ACTOR_ID, RUN_ID)).thenReturn(run);
        when(runService.resume(TENANT_ID, ACTOR_ID, RUN_ID)).thenReturn(run);
        when(runService.cancel(TENANT_ID, ACTOR_ID, RUN_ID)).thenReturn(run);
        when(runService.retry(TENANT_ID, ACTOR_ID, RUN_ID)).thenReturn(run);

        assertThat(controller.startRun(ACTOR_ID, TENANT_ID,
                new KeyRotationStartRequest(KeyRotationStates.MODE_APPLY, "request-1")).getData().id())
                .isEqualTo(externalRunId);
        assertThat(controller.listRuns(TENANT_ID, 20).getData()).hasSize(1);
        assertThat(controller.getRun(TENANT_ID, externalRunId).getData().snapshotBoundary())
                .isEqualTo(IdUtils.toExternalId(999L));
        assertThat(controller.pauseRun(ACTOR_ID, TENANT_ID, externalRunId).getData().discoveryComplete())
                .isFalse();
        assertThat(controller.resumeRun(ACTOR_ID, TENANT_ID, externalRunId).getData().remainingCount())
                .isZero();
        assertThat(controller.cancelRun(ACTOR_ID, TENANT_ID, externalRunId).getData().policyId())
                .isEqualTo(IdUtils.toExternalId(POLICY_ID));
        assertThat(controller.retryRun(ACTOR_ID, TENANT_ID, externalRunId).getData().toString())
                .doesNotContain("raw-provider-key");
    }

    /**
     * Covers sanitized item mapping, bounded next-cursor generation, and decoded input cursors.
     */
    @Test
    void shouldPageSanitizedItemsWithOpaqueCursor() {
        String externalRunId = IdUtils.toExternalId(RUN_ID);
        String inputCursor = IdUtils.toExternalId(200L);
        KeyRotationItem first = item(201L).setAttemptCount(null).setRetryable(null);
        KeyRotationItem second = item(202L).setAttemptCount(3).setRetryable(1);
        when(runService.listItems(TENANT_ID, RUN_ID, 200L, 2)).thenReturn(List.of(first, second));

        var page = controller.listItems(TENANT_ID, externalRunId, inputCursor, 2).getData();

        assertThat(page.records()).hasSize(2);
        assertThat(page.records().getFirst().attemptCount()).isZero();
        assertThat(page.records().getFirst().retryable()).isFalse();
        assertThat(page.records().getLast().retryable()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(IdUtils.toExternalId(202L));
        assertThat(page.toString()).doesNotContain("301", "401", "raw-provider-key");
        verify(runService).listItems(TENANT_ID, RUN_ID, 200L, 2);
    }

    /**
     * Covers the terminal cursor page and stable rejection of malformed public identifiers.
     */
    @Test
    void shouldReturnTerminalPageAndRejectMalformedIdentifiers() {
        String externalRunId = IdUtils.toExternalId(RUN_ID);
        when(runService.listItems(TENANT_ID, RUN_ID, null, 50)).thenReturn(List.of(item(201L)));

        assertThat(controller.listItems(TENANT_ID, externalRunId, null, 50).getData().nextCursor())
                .isNull();
        assertThatThrownBy(() -> controller.getRun(TENANT_ID, "not-an-external-id"))
                .isInstanceOf(GeneralException.class);
    }

    /**
     * Builds a policy containing a raw provider key ID that must never cross the response boundary.
     */
    private KeyRotationPolicy policy() {
        return new KeyRotationPolicy()
                .setId(POLICY_ID)
                .setTenantId(TENANT_ID)
                .setStatus(KeyRotationStates.POLICY_ACTIVE)
                .setTargetProvider("vault-transit")
                .setTargetProviderContract(null)
                .setTargetKeyId("raw-provider-key")
                .setTargetProviderKeyVersion("7")
                .setTargetLogicalKeyVersion(null)
                .setBatchSize(null)
                .setMaxItemsPerMinute(null)
                .setScheduleEnabled(null)
                .setMaxAttempts(null)
                .setInitialBackoffSeconds(null)
                .setMaxBackoffSeconds(null)
                .setLeaseSeconds(null)
                .setGracePeriodSeconds(null)
                .setPolicyVersion(null)
                .setLastRunId(null)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY)
                .setUpdateTime(new Date());
    }

    /**
     * Builds a run snapshot with null counters to verify response defaults and secret redaction.
     */
    private KeyRotationRun run() {
        return new KeyRotationRun()
                .setId(RUN_ID)
                .setTenantId(TENANT_ID)
                .setPolicyId(POLICY_ID)
                .setTriggerType("MANUAL")
                .setMode(KeyRotationStates.MODE_APPLY)
                .setStatus(KeyRotationStates.RUN_RUNNING)
                .setTargetProvider("vault-transit")
                .setTargetProviderContract(null)
                .setTargetKeyId("raw-provider-key")
                .setTargetProviderKeyVersion("7")
                .setTargetLogicalKeyVersion(null)
                .setDiscoveryComplete(null)
                .setRetirementStatus(KeyRotationStates.RETIREMENT_NOT_READY)
                .setUpdateTime(new Date());
    }

    /**
     * Builds an item with internal source, candidate, and recipient identifiers that remain server-side.
     */
    private KeyRotationItem item(Long id) {
        return new KeyRotationItem()
                .setId(id)
                .setTenantId(TENANT_ID)
                .setRunId(RUN_ID)
                .setSourceEnvelopeId(301L)
                .setCandidateEnvelopeId(401L)
                .setFileId(501L)
                .setRecipientType("OWNER")
                .setRecipientId(601L)
                .setStatus(KeyRotationStates.ITEM_FAILED)
                .setOutcome("FAILED")
                .setFailureCategory("TIMEOUT")
                .setUpdateTime(new Date());
    }
}
