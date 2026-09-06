package cn.flying.service.platform;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.Tenant;
import cn.flying.dao.entity.platform.PlatformTenantRow;
import cn.flying.dao.entity.platform.PlatformUserRow;
import cn.flying.dao.mapper.platform.PlatformOverviewMapper;
import cn.flying.dao.mapper.platform.PlatformQuotaMapper;
import cn.flying.dao.mapper.platform.PlatformTenantMapper;
import cn.flying.dao.mapper.platform.PlatformUserMapper;
import cn.flying.dao.vo.admin.ChangeTenantMemberRoleRequest;
import cn.flying.dao.vo.admin.ChangeTenantMemberStatusRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantInvitationVO;
import cn.flying.dao.vo.admin.TenantMemberReasonRequest;
import cn.flying.dao.vo.admin.TenantMemberVO;
import cn.flying.service.QuotaService;
import cn.flying.service.admin.TenantInvitationService;
import cn.flying.service.admin.TenantMemberCommandService;
import cn.flying.service.admin.TenantMemberQueryService;
import cn.flying.service.auth.TenantSessionRevocationService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static cn.flying.service.platform.PlatformServiceTestSupport.isolated;
import static cn.flying.service.platform.PlatformServiceTestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Focused orchestration fixture; existing member behavior and real SQL are covered by their own contract/IT suites. */
class PlatformUserServiceTest {

    private static final String KEY = "11111111-2222-3333-4444-555555555555";
    private PlatformServiceTestSupport fixture;
    private PlatformUserMapper mapper;
    private PlatformTenantMapper tenants;
    private TenantMemberQueryService members;
    private TenantMemberCommandService commands;
    private TenantInvitationService invitations;
    private PlatformUserService service;
    private Tenant tenantState;
    private AtomicReference<TenantMemberVO> memberState;

    /** Keeps platform guards, target resolution, locking orchestration and operation execution real. */
    @BeforeEach
    void setUp() {
        fixture = new PlatformServiceTestSupport();
        mapper = mock(PlatformUserMapper.class);
        tenants = mock(PlatformTenantMapper.class);
        members = mock(TenantMemberQueryService.class);
        commands = mock(TenantMemberCommandService.class);
        invitations = mock(TenantInvitationService.class);
        PlatformQuotaMapper quotaMapper = mock(PlatformQuotaMapper.class);
        QuotaService quotaService = mock(QuotaService.class);
        PlatformTenantQueryService tenantQueries = new PlatformTenantQueryService(tenants, quotaMapper, mapper,
                mock(PlatformOverviewMapper.class), quotaService, fixture.sanitizer);
        PlatformTenantCommandService tenantCommands = new PlatformTenantCommandService(tenants, quotaMapper,
                quotaService, mock(TenantSessionRevocationService.class), fixture.sanitizer, fixture.executor);
        service = new PlatformUserService(mapper, tenantQueries, tenantCommands, members, commands, invitations, fixture.executor);
        PlatformTenantRow metadata = new PlatformTenantRow();
        metadata.setId(42L).setStatus(1).setVersion(0L);
        when(tenants.selectMetadata(42L)).thenReturn(metadata);
        tenantState = new Tenant().setId(42L).setVersion(0L).setStatus(1);
        when(tenants.lockTenant(42L)).thenAnswer(invocation -> {
            isolated(0);
            return tenantState;
        });
        memberState = new AtomicReference<>(member("user", 1));
        when(members.get(42L, 9L)).thenAnswer(invocation -> {
            isolated(42);
            return memberState.get();
        });
    }

    /** Clears all per-test identity and static ID substitutions. */
    @AfterEach
    void tearDown() {
        fixture.close();
    }

    /** Global pages return only approved metadata and preserve user/entity ID types. */
    @Test
    void mapsBoundedGlobalMemberMetadata() throws Exception {
        PlatformUserRow row = new PlatformUserRow().setId(9L).setTenantId(42L)
                .setUsername("member").setNickname("Display name").setRole("user").setStatus(1);
        when(mapper.countPage("member", "user", 1, 42L)).thenReturn(21L);
        when(mapper.selectPage(20, 20, "member", "user", 1, 42L)).thenReturn(List.of(row));

        var page = service.list(2, 20, " member ", "user", 1, 42L);

        assertThat(page.getTotal()).isEqualTo(21);
        assertThat(page.getRecords().getFirst().id()).isEqualTo("U9");
        assertThat(page.getRecords().getFirst().tenantId()).isEqualTo("E42");
        assertThat(fixture.json.writeValueAsString(page.getRecords())).doesNotContain(
                "password", "authVersion", "token", "email-private");
        isolated(0);
    }

    /** Unfiltered global reads still remain bounded at the mapper boundary. */
    @Test
    void permitsGlobalPageWithoutTargetTenantFilter() {
        when(mapper.selectPage(0, 20, null, null, null, null)).thenReturn(List.of());
        assertThat(service.list(1, 20, " ", null, null, null).getRecords()).isEmpty();
        verify(tenants, never()).selectMetadata(anyLong());
    }

    /** Explicit member reads reuse the tenant read model inside forced isolation. */
    @Test
    void readsMembersThroughForcedTargetContext() {
        when(members.list(42L, 1L, 20L, "member", "user", 1)).thenAnswer(invocation -> {
            isolated(42);
            return new Page<TenantMemberVO>(1, 20, 1).setRecords(List.of(memberState.get()));
        });

        assertThat(service.members(42L, 1, 20, " member ", "user", 1).getRecords()).hasSize(1);

        isolated(0);
    }

    /** Invitation history receives target isolation and returns only the existing bounded metadata shape. */
    @Test
    void readsInvitationMetadataUnderTargetIsolation() {
        when(invitations.list(42L)).thenAnswer(invocation -> {
            isolated(42);
            return List.of(invitation("E13"));
        });

        assertThat(service.invitations(42L)).hasSize(1);
        isolated(0);
    }

    /** Platform orchestration forwards role changes to the reviewed command without bypassing its invariants. */
    @Test
    void delegatesRoleChangeAndRecordsOnlySafeMemberSummary() throws Exception {
        doAnswer(invocation -> {
            isolated(42);
            memberState.set(member("monitor", 1));
            return null;
        }).when(commands).changeRole(42L, 7L, 9L, "monitor", "approved token=member-secret");

        var request = new ChangeTenantMemberRoleRequest("monitor", "approved token=member-secret");
        var result = service.changeRole(42L, 9L, KEY, request);

        assertThat(result.resourceId()).isEqualTo("U9");
        assertThat(result.version()).isNull();
        assertThat(fixture.row().getBeforeSummary()).isEqualTo("role=user; status=1");
        assertThat(fixture.row().getAfterSummary()).isEqualTo("role=monitor; status=1");
        assertThat(fixture.json.writeValueAsString(fixture.row())).doesNotContain(
                "member-secret", "email-private", "authVersion");
        assertThat(service.changeRole(42L, 9L, KEY, request)).isEqualTo(result);
        verify(commands).changeRole(42L, 7L, 9L, "monitor", "approved token=member-secret");
        isolated(0);
    }

    /** Member status changes use the reviewed status command inside the same audited operation. */
    @Test
    void delegatesStatusChange() {
        doAnswer(invocation -> {
            isolated(42);
            memberState.set(member("user", 0));
            return null;
        }).when(commands).changeStatus(42L, 7L, 9L, 0, "approved");

        var result = service.changeStatus(42L, 9L, KEY, new ChangeTenantMemberStatusRequest(0, "approved"));

        assertThat(result.resourceId()).isEqualTo("U9");
        assertThat(fixture.row().getOperation()).isEqualTo("USER_STATUS_CHANGE");
        assertThat(fixture.row().getAfterSummary()).contains("status=0");
        isolated(0);
    }

    /** Explicit session revocation forwards the authenticated platform actor without disclosing internal versions. */
    @Test
    void delegatesSessionRevocation() {
        doAnswer(invocation -> {
            isolated(42);
            return null;
        }).when(commands).revokeSessions(42L, 7L, 9L, "approved");

        var result = service.revokeSessions(42L, 9L, KEY, new TenantMemberReasonRequest("approved"));

        assertThat(result.resourceId()).isEqualTo("U9");
        assertThat(result.version()).isNull();
        verify(commands).revokeSessions(42L, 7L, 9L, "approved");
        isolated(0);
    }

    /** Last-admin rejection from the reviewed service remains a failure rather than a platform bypass. */
    @Test
    void preservesLastAdministratorDenialAndRestoresContext() {
        doThrow(new GeneralException(ResultEnum.LAST_TENANT_ADMIN_REQUIRED))
                .when(commands).changeStatus(42L, 7L, 9L, 0, "approved");

        rejected(() -> service.changeStatus(42L, 9L, KEY, new ChangeTenantMemberStatusRequest(0, "approved")),
                ResultEnum.LAST_TENANT_ADMIN_REQUIRED);

        assertThat(fixture.row().getErrorCode()).isEqualTo(ResultEnum.LAST_TENANT_ADMIN_REQUIRED.getCode());
        isolated(0);
    }

    /** An active new tenant can invite its first administrator without a default password. */
    @Test
    void invitesFirstAdministratorWithMetadataOnlyResult() throws Exception {
        CreateTenantInvitationRequest request = new CreateTenantInvitationRequest("admin@example.test", "admin", 24, "approved");
        when(invitations.create(42L, 7L, request)).thenAnswer(invocation -> {
            isolated(42);
            return invitation("E13");
        });

        var result = service.invite(42L, KEY, request);

        assertThat(result.resourceId()).isEqualTo("E13");
        assertThat(result.version()).isNull();
        assertThat(fixture.row().getAfterSummary()).isEqualTo("status=PENDING; role=admin");
        assertThat(fixture.json.writeValueAsString(result)).doesNotContain("token", "password", "email");
        assertThat(service.invite(42L, KEY, request)).isEqualTo(result);
        verify(invitations).create(42L, 7L, request);
        isolated(0);
    }

    /** Current disabled state prevents invitation creation before the mail capability boundary is reached. */
    @Test
    void refusesInvitationsToDisabledTenant() {
        tenantState.setStatus(0);
        rejected(() -> service.invite(42L, KEY,
                new CreateTenantInvitationRequest("admin@example.test", "admin", 24, "approved")),
                ResultEnum.PLATFORM_TENANT_INACTIVE);
        verifyNoInteractions(invitations);
        isolated(0);
    }

    /** Invalid downstream invitation identity never becomes a successful platform outcome. */
    @ParameterizedTest
    @ValueSource(strings = {"U13", "invalid"})
    void refusesWrongTypedInvitationResults(String resultId) {
        when(invitations.create(eq(42L), eq(7L), any())).thenReturn(invitation(resultId));
        rejected(() -> service.invite(42L, KEY,
                new CreateTenantInvitationRequest("admin@example.test", "admin", 24, "approved")),
                ResultEnum.SERVICE_UNAVAILABLE);
        assertThat(fixture.row().getStatus()).isEqualTo("FAILURE");
    }

    /** Invitation revocation preserves target and actor bindings and records a stable pending-to-revoked transition. */
    @Test
    void delegatesInvitationRevocation() {
        doAnswer(invocation -> {
            isolated(42);
            return null;
        }).when(invitations).revoke(42L, 7L, 13L, "approved");

        var result = service.revokeInvitation(42L, 13L, KEY, new TenantMemberReasonRequest("approved"));

        assertThat(result.resourceId()).isEqualTo("E13");
        assertThat(fixture.row().getBeforeSummary()).isEqualTo("status=PENDING");
        assertThat(fixture.row().getAfterSummary()).isEqualTo("status=REVOKED");
        isolated(0);
    }

    /** Invalid search roles, statuses and pagination never reach global user metadata queries. */
    @Test
    void rejectsInvalidGlobalFilters() {
        rejected(() -> service.list(1, 20, null, "platform_admin", null, null), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.members(42L, 1, 20, null, null, 2), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.list(1, 101, null, null, null, null), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.members(42L, 1, 20, "x".repeat(101), null, null), ResultEnum.PARAM_IS_INVALID);
        verifyNoInteractions(mapper, tenants, members, commands, invitations);
    }

    /** Every public platform member entry point rejects tenant principals before downstream access. */
    @Test
    void guardsAllPublicMemberEntryPoints() {
        fixture.authorize("admin", 0L);
        rejected(() -> service.list(1, 20, null, null, null, null), ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.members(42L, 1, 20, null, null, null), ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.invitations(42L), ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.changeRole(42L, 9L, KEY, new ChangeTenantMemberRoleRequest("monitor", "approved")),
                ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.changeStatus(42L, 9L, KEY, new ChangeTenantMemberStatusRequest(0, "approved")),
                ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.revokeSessions(42L, 9L, KEY, new TenantMemberReasonRequest("approved")),
                ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.invite(42L, KEY,
                new CreateTenantInvitationRequest("admin@example.test", "admin", 24, "approved")),
                ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.revokeInvitation(42L, 13L, KEY, new TenantMemberReasonRequest("approved")),
                ResultEnum.PERMISSION_UNAUTHORIZED);
        verifyNoInteractions(mapper, tenants, members, commands, invitations, fixture.operations);
    }

    /** Builds a safe member projection while retaining private email as an audit-redaction sentinel. */
    private TenantMemberVO member(String role, int status) {
        return new TenantMemberVO("U9", "member", "email-private@example.test", "Member", role, status, null, null);
    }

    /** Builds the existing invitation metadata projection without raw token material. */
    private TenantInvitationVO invitation(String id) {
        return new TenantInvitationVO(id, "admin@example.test", "admin", "PENDING",
                LocalDateTime.now().plusDays(1), LocalDateTime.now());
    }
}
