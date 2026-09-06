package cn.flying.service.platform;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.QuotaPolicy;
import cn.flying.dao.entity.Tenant;
import cn.flying.dao.mapper.platform.PlatformQuotaMapper;
import cn.flying.dao.mapper.platform.PlatformTenantMapper;
import cn.flying.dao.vo.file.QuotaStatusVO;
import cn.flying.dao.vo.platform.ChangePlatformTenantStatusRequest;
import cn.flying.dao.vo.platform.CreatePlatformTenantRequest;
import cn.flying.dao.vo.platform.UpdatePlatformQuotaRequest;
import cn.flying.dao.vo.platform.UpdatePlatformTenantRequest;
import cn.flying.service.QuotaService;
import cn.flying.service.auth.TenantSessionRevocationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static cn.flying.service.platform.PlatformServiceTestSupport.isolated;
import static cn.flying.service.platform.PlatformServiceTestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Verifies tenant command decisions and isolated side-effect boundaries with the real durable executor. */
class PlatformTenantCommandServiceTest {

    private static final String KEY = "11111111-2222-3333-4444-555555555555";
    private PlatformServiceTestSupport fixture;
    private PlatformTenantMapper tenants;
    private PlatformQuotaMapper quotas;
    private QuotaService quotaService;
    private TenantSessionRevocationService sessions;
    private PlatformTenantCommandService service;
    private Tenant target;

    /** Composes one current target and checks that each target operation receives forced isolation. */
    @BeforeEach
    void setUp() {
        fixture = new PlatformServiceTestSupport();
        tenants = mock(PlatformTenantMapper.class);
        quotas = mock(PlatformQuotaMapper.class);
        quotaService = mock(QuotaService.class);
        sessions = mock(TenantSessionRevocationService.class);
        target = new Tenant().setId(42L).setStatus(1).setVersion(3L).setDeleted(0);
        when(tenants.lockTenant(anyLong())).thenAnswer(invocation -> {
            isolated(0);
            return target;
        });
        when(tenants.insertTenant(any())).thenReturn(1);
        when(quotas.insertOverride(any())).thenAnswer(invocation -> {
            QuotaPolicy policy = invocation.getArgument(0);
            isolated(policy.getTenantId());
            return 1;
        });
        when(quotaService.getCurrentQuotaStatus(anyLong(), eq(0L))).thenAnswer(invocation -> {
            Long tenantId = invocation.getArgument(0);
            isolated(tenantId);
            return new QuotaStatusVO(tenantId, 0L, "SHADOW", 0L, 10L, 0L, 10L, 0L, 1024L, 0L, 50L);
        });
        service = new PlatformTenantCommandService(tenants, quotas, quotaService, sessions, fixture.sanitizer, fixture.executor);
    }

    /** Restores all per-test security and ID state. */
    @AfterEach
    void tearDown() {
        fixture.close();
    }

    /** Tenant creation obtains existing quota defaults and never provisions a default account. */
    @Test
    void createsTenantAndInitialQuotaWithoutCredentials() {
        var result = service.create(KEY, new CreatePlatformTenantRequest("tenant-test", " New Tenant ", null, null, "approved"));

        ArgumentCaptor<Tenant> created = ArgumentCaptor.forClass(Tenant.class);
        verify(tenants).insertTenant(created.capture());
        assertThat(created.getValue().getName()).isEqualTo("New Tenant");
        assertThat(created.getValue().getCode()).isEqualTo("tenant-test");
        ArgumentCaptor<QuotaPolicy> policy = ArgumentCaptor.forClass(QuotaPolicy.class);
        verify(quotas).insertOverride(policy.capture());
        assertThat(policy.getValue().getTenantId()).isEqualTo(created.getValue().getId());
        assertThat(policy.getValue().getScopeId()).isEqualTo(created.getValue().getId());
        assertThat(policy.getValue().getScopeType()).isEqualTo("TENANT");
        assertThat(policy.getValue().getMaxStorageBytes()).isEqualTo(1024L);
        assertThat(policy.getValue().getMaxFileCount()).isEqualTo(50L);
        assertThat(policy.getValue().getVersion()).isZero();
        assertThat(result.resourceId()).isEqualTo("E" + created.getValue().getId());
        assertThat(result.version()).isZero();
        isolated(0);
    }

    /** Zero is a supported explicit quota and must not be mistaken for a missing default. */
    @Test
    void preservesExplicitZeroInitialQuota() {
        service.create(KEY, new CreatePlatformTenantRequest("tenant-test", "Tenant", 0L, 0L, "approved"));

        ArgumentCaptor<QuotaPolicy> policy = ArgumentCaptor.forClass(QuotaPolicy.class);
        verify(quotas).insertOverride(policy.capture());
        assertThat(policy.getValue().getMaxStorageBytes()).isZero();
        assertThat(policy.getValue().getMaxFileCount()).isZero();
    }

    /** The database unique constraint maps to a stable duplicate-tenant outcome. */
    @Test
    void mapsDuplicateTenantCodeWithoutCreatingQuota() {
        when(tenants.insertTenant(any())).thenThrow(new DuplicateKeyException("database secret"));
        rejected(() -> service.create(KEY,
                new CreatePlatformTenantRequest("tenant-test", "Tenant", null, null, "approved")),
                ResultEnum.PLATFORM_TENANT_CODE_EXISTS);
        verifyNoInteractions(quotas, quotaService, sessions);
        assertThat(fixture.row().getStatus()).isEqualTo("FAILURE");
    }

    /** A missing quota measurement cannot produce a tenant with fabricated zero defaults. */
    @Test
    void rejectsUnavailableInitialQuotaDefaults() {
        when(quotaService.getCurrentQuotaStatus(anyLong(), eq(0L))).thenReturn(null);
        rejected(() -> service.create(KEY,
                new CreatePlatformTenantRequest("tenant-test", "Tenant", null, null, "approved")),
                ResultEnum.SERVICE_UNAVAILABLE);
        verify(quotas, never()).insertOverride(any());
        assertThat(fixture.events).containsSubsequence("rollback", "failure-audit", "commit");
    }

    /** Metadata updates use a current lock, explicit target and matching optimistic version. */
    @Test
    void updatesMetadataUnderForcedTargetIsolation() {
        when(tenants.updateName(42L, "Renamed", 3L)).thenAnswer(invocation -> {
            isolated(42);
            return 1;
        });

        var result = service.update(42L, KEY, new UpdatePlatformTenantRequest(" Renamed ", 3L, "approved"));

        assertThat(result.version()).isEqualTo(4L);
        assertThat(fixture.row().getBeforeSummary()).isEqualTo("metadataVersion=3");
        assertThat(fixture.row().getAfterSummary()).isEqualTo("metadataVersion=4");
        isolated(0);
    }

    /** Both stale and overflowing versions are rejected before a metadata write. */
    @ParameterizedTest
    @ValueSource(longs = {2L, 9007199254740991L})
    void rejectsConflictingMetadataVersions(long version) {
        if (version == 9007199254740991L) {
            target.setVersion(version);
        }
        rejected(() -> service.update(42L, KEY, new UpdatePlatformTenantRequest("Tenant", version, "approved")),
                ResultEnum.PLATFORM_VERSION_CONFLICT);
        verify(tenants, never()).updateName(anyLong(), anyString(), anyLong());
    }

    /** Missing targets produce a durable non-disclosing failure rather than a target-context mutation. */
    @Test
    void recordsMissingTargetFailure() {
        when(tenants.lockTenant(42L)).thenReturn(null);
        rejected(() -> service.update(42L, KEY, new UpdatePlatformTenantRequest("Tenant", 3L, "approved")),
                ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        assertThat(fixture.row().getStatus()).isEqualTo("FAILURE");
        isolated(0);
    }

    /** A lost optimistic update cannot create a false success audit. */
    @Test
    void rejectsLostMetadataUpdate() {
        rejected(() -> service.update(42L, KEY, new UpdatePlatformTenantRequest("Tenant", 3L, "approved")),
                ResultEnum.PLATFORM_VERSION_CONFLICT);
        assertThat(fixture.row().getStatus()).isEqualTo("FAILURE");
    }

    /** Disable records a sanitized reason and invalidates sessions in the same business callback. */
    @Test
    void disablesTenantAndFencesSessionsBeforeSuccessAudit() {
        when(tenants.updateStatus(eq(42L), eq(0), eq(3L), anyString(), eq(7L))).thenAnswer(invocation -> {
            isolated(42);
            assertThat((String) invocation.getArgument(3)).doesNotContain("lifecycle-secret");
            fixture.events.add("status-write");
            return 1;
        });
        doAnswer(invocation -> {
            isolated(42);
            fixture.events.add("tenant-fence");
            return null;
        }).when(sessions).invalidateAfterLifecycleChange(42L);

        var result = service.changeStatus(42L, KEY,
                new ChangePlatformTenantStatusRequest(0, 3L, "approved token=lifecycle-secret"));

        assertThat(result.version()).isEqualTo(4L);
        assertThat(fixture.events).containsSubsequence("status-write", "tenant-fence", "success-audit", "commit");
        isolated(0);
    }

    /** A disabled target can be restored by system authority without authenticating as that tenant. */
    @Test
    void restoresDisabledTenant() {
        target.setStatus(0);
        when(tenants.updateStatus(eq(42L), eq(1), eq(3L), anyString(), eq(7L))).thenReturn(1);

        var result = service.changeStatus(42L, KEY, new ChangePlatformTenantStatusRequest(1, 3L, "restored"));

        assertThat(result.version()).isEqualTo(4L);
        verify(sessions).invalidateAfterLifecycleChange(42L);
    }

    /** The system tenant is protected from disable even with a valid version and platform permission. */
    @Test
    void protectsSystemTenantFromDisable() {
        target.setId(0L);
        rejected(() -> service.changeStatus(0L, KEY, new ChangePlatformTenantStatusRequest(0, 3L, "approved")),
                ResultEnum.PLATFORM_SYSTEM_TENANT_PROTECTED);
        verifyNoInteractions(sessions);
        assertThat(fixture.row().getErrorCode()).isEqualTo(ResultEnum.PLATFORM_SYSTEM_TENANT_PROTECTED.getCode());
    }

    /** Failed authorization fencing aborts lifecycle completion and creates a failure outcome. */
    @Test
    void rejectsLifecycleSuccessWhenSessionFenceFails() {
        when(tenants.updateStatus(eq(42L), eq(0), eq(3L), anyString(), eq(7L))).thenReturn(1);
        doThrow(new IllegalStateException("redis secret")).when(sessions).invalidateAfterLifecycleChange(42L);

        rejected(() -> service.changeStatus(42L, KEY, new ChangePlatformTenantStatusRequest(0, 3L, "approved")),
                ResultEnum.SERVICE_UNAVAILABLE);
        assertThat(fixture.row().getStatus()).isEqualTo("FAILURE");
        isolated(0);
    }

    /** Exact quota replacement preserves target identity and records before/after numeric summaries. */
    @Test
    void updatesExistingQuotaVersion() {
        when(quotas.lockOverride(42L)).thenAnswer(invocation -> {
            isolated(42);
            return new QuotaPolicy().setVersion(5L).setMaxStorageBytes(100L).setMaxFileCount(10L);
        });
        when(quotas.updateOverride(42L, 200L, 20L, 5L)).thenAnswer(invocation -> {
            isolated(42);
            return 1;
        });

        var result = service.updateQuota(42L, KEY, new UpdatePlatformQuotaRequest(200L, 20L, 5L, "approved"));

        assertThat(result.version()).isEqualTo(6L);
        assertThat(fixture.row().getBeforeSummary()).contains("storageBytes=100", "fileCount=10", "version=5");
        assertThat(fixture.row().getAfterSummary()).contains("storageBytes=200", "fileCount=20", "version=6");
        isolated(0);
    }

    /** An absent exact override begins with version zero and becomes version one on its first write. */
    @Test
    void createsMissingQuotaOverride() {
        var result = service.updateQuota(42L, KEY, new UpdatePlatformQuotaRequest(200L, 20L, 0L, "approved"));

        assertThat(result.version()).isEqualTo(1L);
        ArgumentCaptor<QuotaPolicy> policy = ArgumentCaptor.forClass(QuotaPolicy.class);
        verify(quotas).insertOverride(policy.capture());
        assertThat(policy.getValue().getVersion()).isEqualTo(1L);
        assertThat(policy.getValue().getTenantId()).isEqualTo(42L);
        assertThat(policy.getValue().getScopeId()).isEqualTo(42L);
        verify(quotas, never()).updateOverride(anyLong(), anyLong(), anyLong(), anyLong());
    }

    /** Incorrect expected versions cannot implicitly create a missing override. */
    @Test
    void rejectsMissingQuotaVersionConflict() {
        rejected(() -> service.updateQuota(42L, KEY, new UpdatePlatformQuotaRequest(200L, 20L, 1L, "approved")),
                ResultEnum.PLATFORM_VERSION_CONFLICT);
        verify(quotas, never()).insertOverride(any());
    }

    /** A failed initial override insert fails the entire logical operation. */
    @Test
    void rejectsUnpersistedQuotaOverride() {
        doReturn(0).when(quotas).insertOverride(any());
        rejected(() -> service.updateQuota(42L, KEY, new UpdatePlatformQuotaRequest(200L, 20L, 0L, "approved")),
                ResultEnum.SERVICE_UNAVAILABLE);
        assertThat(fixture.row().getStatus()).isEqualTo("FAILURE");
    }

    /** Direct service invocation still enforces annotation constraints and explicit target identity. */
    @Test
    void rejectsInvalidInputsBeforeTargetMutation() {
        rejected(() -> service.create(KEY, new CreatePlatformTenantRequest("UPPER", "Tenant", null, null, "approved")),
                ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.updateQuota(42L, KEY, new UpdatePlatformQuotaRequest(-1L, 1L, 0L, "approved")),
                ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.changeStatus(42L, KEY, new ChangePlatformTenantStatusRequest(2, 3L, "approved")),
                ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.update(-1L, KEY, new UpdatePlatformTenantRequest("Tenant", 0L, "approved")),
                ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        rejected(() -> service.update(42L, KEY, new UpdatePlatformTenantRequest("Tenant", 9007199254740992L, "approved")),
                ResultEnum.PARAM_IS_INVALID);
        verifyNoInteractions(tenants, quotas, quotaService, sessions, fixture.operations);
    }

    /** Tenant principals cannot use any lifecycle or quota command regardless of target selection. */
    @Test
    void deniesTenantPrincipalAtEveryPublicCommand() {
        fixture.authorize("admin", 0L);
        rejected(() -> service.create(KEY, new CreatePlatformTenantRequest("tenant-test", "Tenant", null, null, "approved")),
                ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.update(42L, KEY, new UpdatePlatformTenantRequest("Tenant", 3L, "approved")),
                ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.changeStatus(42L, KEY, new ChangePlatformTenantStatusRequest(0, 3L, "approved")),
                ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.updateQuota(42L, KEY, new UpdatePlatformQuotaRequest(1L, 1L, 0L, "approved")),
                ResultEnum.PERMISSION_UNAUTHORIZED);
        verifyNoInteractions(tenants, quotas, quotaService, sessions, fixture.operations);
    }
}
