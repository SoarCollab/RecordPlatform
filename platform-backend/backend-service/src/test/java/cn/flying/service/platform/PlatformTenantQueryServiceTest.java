package cn.flying.service.platform;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.QuotaPolicy;
import cn.flying.dao.entity.platform.PlatformTenantRow;
import cn.flying.dao.mapper.platform.PlatformOverviewMapper;
import cn.flying.dao.mapper.platform.PlatformQuotaMapper;
import cn.flying.dao.mapper.platform.PlatformTenantMapper;
import cn.flying.dao.mapper.platform.PlatformUserMapper;
import cn.flying.dao.vo.file.QuotaStatusVO;
import cn.flying.service.QuotaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static cn.flying.service.platform.PlatformServiceTestSupport.isolated;
import static cn.flying.service.platform.PlatformServiceTestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Tests safe tenant projections and forced-isolation quota/usage reads without claiming SQL proof. */
class PlatformTenantQueryServiceTest {

    private PlatformServiceTestSupport fixture;
    private PlatformTenantMapper tenants;
    private PlatformQuotaMapper quotas;
    private PlatformUserMapper users;
    private PlatformOverviewMapper overview;
    private QuotaService quotaService;
    private PlatformTenantQueryService service;
    private PlatformTenantRow target;
    private QuotaStatusVO quota;

    /** Creates a known target and observes every tenant-specific measurement boundary. */
    @BeforeEach
    void setUp() {
        fixture = new PlatformServiceTestSupport();
        tenants = mock(PlatformTenantMapper.class);
        quotas = mock(PlatformQuotaMapper.class);
        users = mock(PlatformUserMapper.class);
        overview = mock(PlatformOverviewMapper.class);
        quotaService = mock(QuotaService.class);
        target = new PlatformTenantRow();
        target.setId(42L).setCode("tenant-test").setName("Tenant").setStatus(1).setVersion(3L)
                .setDisabledReason("approved token=disable-secret").setDisabledBy(7L);
        target.setMemberCount(4L);
        quota = new QuotaStatusVO(42L, 0L, "SHADOW", 0L, 10L, 0L, 10L, 50L, 1024L, 5L, 100L);
        when(tenants.selectMetadata(42L)).thenReturn(target);
        when(quotaService.getCurrentQuotaStatus(42L, 0L)).thenAnswer(invocation -> {
            isolated(42);
            return quota;
        });
        when(users.countTenantMembers(42L)).thenAnswer(invocation -> {
            isolated(42);
            return 4L;
        });
        when(overview.countTenantAudit(42L)).thenAnswer(invocation -> {
            isolated(42);
            return 6L;
        });
        when(overview.countCompletedAttestations(42L)).thenAnswer(invocation -> {
            isolated(42);
            return 7L;
        });
        service = new PlatformTenantQueryService(tenants, quotas, users, overview, quotaService, fixture.sanitizer);
    }

    /** Clears per-test principal and tenant state. */
    @AfterEach
    void tearDown() {
        fixture.close();
    }

    /** Tenant projections encode IDs and sanitize existing disable metadata. */
    @Test
    void mapsTenantMetadataWithoutRawActorIdsOrReasons() throws Exception {
        var view = service.get(42L);

        assertThat(view.id()).isEqualTo("E42");
        assertThat(view.disabledBy()).isEqualTo("U7");
        assertThat(view.memberCount()).isEqualTo(4L);
        assertThat(fixture.json.writeValueAsString(view)).doesNotContain("disable-secret");
    }

    /** Pagination and filters reach both page and total queries with the same normalized values. */
    @Test
    void forwardsStableBoundedTenantPage() {
        when(tenants.countPage("search", 1)).thenReturn(41L);
        when(tenants.selectPage(20, 20, "search", 1)).thenReturn(List.of(target));

        var page = service.list(2, 20, " search ", 1);

        assertThat(page.getCurrent()).isEqualTo(2);
        assertThat(page.getPages()).isEqualTo(3);
        assertThat(page.getRecords()).hasSize(1);
        verify(tenants).countPage("search", 1);
        verify(tenants).selectPage(20, 20, "search", 1);
    }

    /** Invalid page values cannot reach global SQL or overflow the offset calculation. */
    @ParameterizedTest
    @CsvSource({"0,20", "1,0", "1,101", "9223372036854775807,100", "9007199254740992,1"})
    void rejectsInvalidPagination(long page, long size) {
        rejected(() -> service.list(page, size, null, null), ResultEnum.PARAM_IS_INVALID);
        verifyNoInteractions(tenants, quotas, quotaService, users, overview);
    }

    /** Invalid filters and oversized whitespace text fail before metadata queries. */
    @Test
    void rejectsInvalidFilters() {
        rejected(() -> service.list(1, 20, "x".repeat(101), null), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.list(1, 20, " ".repeat(101), null), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.list(1, 20, null, 2), ResultEnum.PARAM_IS_INVALID);
        verifyNoInteractions(tenants);
    }

    /** Usage uses the existing quota eligibility rules and distinctly named audit/attestation scopes. */
    @Test
    void returnsRealScopedUsageAndRestoresSystemContext() {
        var usage = service.usage(42L);

        assertThat(usage.tenantId()).isEqualTo("E42");
        assertThat(usage.users()).isEqualTo(4L);
        assertThat(usage.files()).isEqualTo(5L);
        assertThat(usage.logicalStorageBytes()).isEqualTo(50L);
        assertThat(usage.auditRecords()).isEqualTo(6L);
        assertThat(usage.completedAttestations()).isEqualTo(7L);
        assertThat(usage.auditScope()).isEqualTo("TENANT_BUSINESS_OPERATION_LOG");
        assertThat(usage.attestationScope()).isEqualTo("TENANT_COMPLETED_ATTESTATION_BATCH");
        isolated(0);
    }

    /** A missing measurement fails the request and still restores system tenant context. */
    @Test
    void rejectsUnavailableUsageInsteadOfFabricatingZero() {
        doReturn(null).when(overview).countTenantAudit(42L);
        rejected(() -> service.usage(42L), ResultEnum.SERVICE_UNAVAILABLE);
        doReturn(9007199254740992L).when(overview).countTenantAudit(42L);
        rejected(() -> service.usage(42L), ResultEnum.SERVICE_UNAVAILABLE);
        isolated(0);
    }

    /** Exact, fallback and application sources preserve the existing quota resolver's effective values. */
    @ParameterizedTest
    @CsvSource({"override,TENANT_OVERRIDE,8", "fallback,TENANT_DEFAULT,0", "default,APPLICATION_DEFAULT,0", "disabled,TENANT_DEFAULT,8"})
    void reportsEffectiveQuotaSourceAndWritableVersion(String setup, String source, long version) {
        if ("override".equals(setup) || "disabled".equals(setup)) {
            when(quotas.selectOverride(42L)).thenReturn(new QuotaPolicy().setVersion(8L)
                    .setStatus("override".equals(setup) ? 1 : 0));
        }
        if ("fallback".equals(setup) || "disabled".equals(setup)) {
            when(quotas.selectDefault(42L)).thenReturn(new QuotaPolicy().setStatus(1));
        }

        var view = service.quota(42L);

        assertThat(view.source()).isEqualTo(source);
        assertThat(view.version()).isEqualTo(version);
        assertThat(view.maxStorageBytes()).isEqualTo(1024L);
        assertThat(view.maxFileCount()).isEqualTo(100L);
        assertThat(view.enforcementMode()).isEqualTo("SHADOW");
        isolated(0);
    }

    /** Unknown enforcement modes and absent quota measurements cannot appear as healthy responses. */
    @Test
    void rejectsUnknownQuotaState() {
        quota = new QuotaStatusVO(42L, 0L, "password=mode-secret", 0L, 1L, 0L, 1L, 0L, 1L, 0L, 1L);
        rejected(() -> service.quota(42L), ResultEnum.SERVICE_UNAVAILABLE);
        quota = null;
        rejected(() -> service.quota(42L), ResultEnum.SERVICE_UNAVAILABLE);
        isolated(0);
    }

    /** Missing or malformed target metadata cannot authorize a target context switch. */
    @Test
    void rejectsMissingOrInvalidTargetState() {
        rejected(() -> service.get(43L), ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        rejected(() -> service.get(-1L), ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        target.setVersion(-1L);
        rejected(() -> service.usage(42L), ResultEnum.SERVICE_UNAVAILABLE);
        target.setVersion(9007199254740992L);
        rejected(() -> service.usage(42L), ResultEnum.SERVICE_UNAVAILABLE);
        verifyNoInteractions(quotaService, quotas, users, overview);
    }

    /** Every public tenant query guards platform permission before global or tenant metadata access. */
    @Test
    void deniesTenantRoleForAllReadMethods() {
        fixture.authorize("admin", 0L);
        rejected(() -> service.get(42L), ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.list(1, 20, null, null), ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.usage(42L), ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.quota(42L), ResultEnum.PERMISSION_UNAUTHORIZED);
        verifyNoInteractions(tenants, quotaService, quotas, users, overview);
    }
}
