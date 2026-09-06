package cn.flying.controller.platform;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.config.IdSecurityConfiguration;
import cn.flying.dao.vo.admin.ChangeTenantMemberRoleRequest;
import cn.flying.dao.vo.admin.ChangeTenantMemberStatusRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantInvitationVO;
import cn.flying.dao.vo.admin.TenantMemberReasonRequest;
import cn.flying.dao.vo.admin.TenantMemberVO;
import cn.flying.dao.vo.platform.ChangePlatformTenantStatusRequest;
import cn.flying.dao.vo.platform.CreatePlatformTenantRequest;
import cn.flying.dao.vo.platform.PlatformAuditVO;
import cn.flying.dao.vo.platform.PlatformConfigurationVO;
import cn.flying.dao.vo.platform.PlatformHealthVO;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.PlatformOverviewVO;
import cn.flying.dao.vo.platform.PlatformPage;
import cn.flying.dao.vo.platform.PlatformQuotaVO;
import cn.flying.dao.vo.platform.PlatformSessionVO;
import cn.flying.dao.vo.platform.PlatformTenantVO;
import cn.flying.dao.vo.platform.PlatformUsageVO;
import cn.flying.dao.vo.platform.PlatformUserVO;
import cn.flying.dao.vo.platform.UpdatePlatformConfigurationRequest;
import cn.flying.dao.vo.platform.UpdatePlatformQuotaRequest;
import cn.flying.dao.vo.platform.UpdatePlatformTenantRequest;
import cn.flying.filter.handler.GlobalExceptionHandler;
import cn.flying.security.CustomMethodSecurityExpressionHandler;
import cn.flying.service.PermissionService;
import cn.flying.service.platform.PlatformAuditService;
import cn.flying.service.platform.PlatformConfigurationService;
import cn.flying.service.platform.PlatformQueryService;
import cn.flying.service.platform.PlatformTenantCommandService;
import cn.flying.service.platform.PlatformTenantQueryService;
import cn.flying.service.platform.PlatformUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests real HTTP binding and method security; SQL, Redis, and transaction evidence live in the MySQL suite. */
@SpringJUnitConfig(PlatformControlPlaneMvcTest.Config.class)
class PlatformControlPlaneMvcTest {

    private static final String ROOT = "/api/v1/platform";
    private static final long TENANT = 92301L;
    private static final long USER = 9230101L;
    private static final long INVITATION = 9230102L;
    private static final long OPERATION = 9230103L;
    private static final long ACTOR = 9230001L;
    private static final String KEY = "91452768-6843-4de6-b061-91c4f28c2a0e";
    private static final String CONFIG_KEY = "HIGH_FREQ_THRESHOLD";
    private static final String REASON = "Approved platform maintenance";
    private static final SecureIdCodec CODEC = new SecureIdCodec(
            "SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234");

    @Autowired private PlatformOverviewController overviewController;
    @Autowired private PlatformTenantController tenantController;
    @Autowired private PlatformUserController userController;
    @Autowired private PlatformConfigurationController configurationController;
    @Autowired private PlatformAuditController auditController;
    @Autowired private PlatformQueryService queries;
    @Autowired private PlatformTenantQueryService tenants;
    @Autowired private PlatformTenantCommandService commands;
    @Autowired private PlatformUserService users;
    @Autowired private PlatformConfigurationService configurations;
    @Autowired private PlatformAuditService audits;
    @Autowired private PermissionService permissions;
    @Autowired private LocalValidatorFactoryBean validator;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ObjectMapper productionJson = new IdSecurityConfiguration()
            .objectMapper(Jackson2ObjectMapperBuilder.json());
    private MockMvc mvc;
    private Object previousCodec;

    /** Installs actual controllers and their validation/security proxies with deterministic encrypted IDs. */
    @BeforeEach
    void setUp() {
        reset(queries, tenants, commands, users, configurations, audits, permissions);
        previousCodec = ReflectionTestUtils.getField(IdUtils.class, "secureIdCodec");
        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", CODEC);
        TenantContext.setTenantId(0L);
        MDC.put(Const.ATTR_USER_ID, Long.toString(ACTOR));
        authenticate("platform_admin", PlatformPermissions.allCodes().toArray(String[]::new));
        mvc = MockMvcBuilders.standaloneSetup(overviewController, tenantController, userController,
                        configurationController, auditController)
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(productionJson))
                .build();
    }

    /** Prevents synthetic principals, MDC, and static codecs from leaking into unrelated tests. */
    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", previousCodec);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    /** Exercises every successful route with only its required capability and exact typed service arguments. */
    @ParameterizedTest
    @MethodSource("routes")
    void permitsEachRouteWithItsOwnCapability(Route route) throws Exception {
        authenticate("platform_admin", route.permission());
        Object expected = stubResponse(route);

        String body = mvc.perform(request(route).param("actorId", "999").header("X-Tenant-ID", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("data")).isEqualTo(json.readTree(json.writeValueAsString(expected)));
        assertThat(TenantContext.getTenantId()).isZero();
        assertThat(TenantContext.isIgnoreIsolation()).isFalse();
        verifyNoInteractions(permissions);
    }

    /** Platform roles alone cannot reach any read or write operation. */
    @ParameterizedTest
    @MethodSource("routes")
    void rejectsPlatformRoleWithoutTheRequiredCapability(Route route) throws Exception {
        authenticate("platform_admin");

        mvc.perform(request(route)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultEnum.PERMISSION_UNAUTHORIZED.getCode()));

        assertNoServiceCalls();
    }

    /** Tenant roles remain forbidden even when a synthetic principal carries every platform permission. */
    @ParameterizedTest
    @MethodSource("tenantRoleRoutes")
    void rejectsTenantRolesWithTransferablePlatformCapabilities(DeniedRoute denied) throws Exception {
        authenticate(denied.role(), PlatformPermissions.allCodes().toArray(String[]::new));

        mvc.perform(request(denied.route())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultEnum.PERMISSION_UNAUTHORIZED.getCode()));

        assertNoServiceCalls();
    }

    /** Missing idempotency keys fail as request errors before a command is accepted. */
    @ParameterizedTest
    @MethodSource("mutations")
    void requiresIdempotencyHeaderForEveryMutation(Route route) throws Exception {
        mvc.perform(request(route, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_NOT_COMPLETE.getCode()));

        assertNoServiceCalls();
    }

    /** Java's permissive shortened UUID spelling must not enter a durable claim through HTTP. */
    @ParameterizedTest
    @MethodSource("mutations")
    void rejectsNoncanonicalIdempotencyKeys(Route route) throws Exception {
        mvc.perform(request(route, "1-1-1-1-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        assertNoServiceCalls();
    }

    /** Required reasons are enforced on all mutation DTOs, including reused tenant invitation DTOs. */
    @ParameterizedTest
    @MethodSource("mutations")
    void rejectsBlankReasonForEveryMutation(Route route) throws Exception {
        var body = json.valueToTree(route.body());
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("reason", " ");

        mvc.perform(request(route).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        assertNoServiceCalls();
    }

    /** Typed identifiers reject raw IDs, malformed ciphertext, and numerically equivalent wrong ID types. */
    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void rejectsInvalidOrWrongTypeIdentifiers(InvalidIdentifier invalid) throws Exception {
        Route route = invalid.route();
        mvc.perform(request(new Route(route.name(), route.method(), invalid.path(), route.permission(), route.body())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultEnum.PLATFORM_TARGET_NOT_FOUND.getCode()));

        assertNoServiceCalls();
    }

    /** Page/filter conversion and constraints reject unbounded or unsupported global queries. */
    @ParameterizedTest
    @MethodSource("invalidQueries")
    void rejectsInvalidQueryParameters(InvalidQuery query) throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(ROOT + query.path()).param(query.name(), query.value()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        assertNoServiceCalls();
    }

    /** Required versions, numeric limits, and safe role/status values are validated at the boundary. */
    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidMutationFields(InvalidBody invalid) throws Exception {
        mvc.perform(request(invalid.route()).content(invalid.json()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        assertNoServiceCalls();
    }

    /** Query-selected tenants are decoded as resources while the trusted identity stays at tenant zero. */
    @Test
    void bindsExplicitTenantAndUserSearchFilters() throws Exception {
        when(users.list(3, 5, "review", "monitor", 0, TENANT))
                .thenReturn(new PlatformPage<PlatformUserVO>(3, 5, 0));

        mvc.perform(MockMvcRequestBuilders.get(ROOT + "/users")
                        .param("pageNum", "3").param("pageSize", "5").param("keyword", "review")
                        .param("role", "monitor").param("status", "0").param("tenantId", entity(TENANT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.current").value(3));

        verify(users).list(3, 5, "review", "monitor", 0, TENANT);
        assertThat(TenantContext.getTenantId()).isZero();
    }

    /** Audit filtering preserves the target and lifecycle rather than selecting a caller tenant. */
    @Test
    void bindsAuditTargetAndLifecycleFilters() throws Exception {
        when(audits.list(2, 10, TENANT, "FAILURE")).thenReturn(new PlatformPage<PlatformAuditVO>(2, 10, 0));

        mvc.perform(MockMvcRequestBuilders.get(ROOT + "/audit")
                        .param("pageNum", "2").param("pageSize", "10")
                        .param("tenantId", entity(TENANT)).param("status", "FAILURE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        verify(audits).list(2, 10, TENANT, "FAILURE");
    }

    /** Deterministic concurrency outcomes retain the existing HTTP-200 business-error envelope. */
    @ParameterizedTest
    @EnumSource(value = ResultEnum.class, names = {"PLATFORM_VERSION_CONFLICT", "PLATFORM_IDEMPOTENCY_CONFLICT",
            "PLATFORM_OPERATION_IN_PROGRESS", "PLATFORM_TARGET_NOT_FOUND", "PLATFORM_SYSTEM_TENANT_PROTECTED"})
    void preservesDurableCommandFailureCodes(ResultEnum failure) throws Exception {
        when(commands.changeStatus(eq(TENANT), eq(KEY), any(ChangePlatformTenantStatusRequest.class)))
                .thenThrow(new GeneralException(failure));

        mvc.perform(request(route("tenant-status")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(failure.getCode()));
    }

    /** Every public route retains audit metadata while suppressing generic raw request and response capture. */
    @Test
    void everyPlatformOperationDisablesGenericPayloadLogging() {
        var methods = Stream.of(PlatformOverviewController.class, PlatformTenantController.class,
                        PlatformUserController.class, PlatformConfigurationController.class, PlatformAuditController.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> Modifier.isPublic(method.getModifiers())).toList();

        assertThat(methods).hasSize(24).allSatisfy(method -> {
            OperationLog annotation = method.getAnnotation(OperationLog.class);
            assertThat(annotation).as(method.toString()).isNotNull();
            assertThat(annotation.saveRequestData()).isFalse();
            assertThat(annotation.saveResponseData()).isFalse();
        });
        assertThat(routes().count()).isEqualTo(methods.size());
    }

    /** Stubs only exact HTTP collaborator signatures, so wrong decoded arguments cannot yield the expected body. */
    private Object stubResponse(Route route) {
        PlatformMutationVO entityMutation = new PlatformMutationVO(entity(OPERATION), entity(TENANT), 1L);
        PlatformMutationVO userMutation = new PlatformMutationVO(entity(OPERATION), CODEC.toExternalUserId(USER), null);
        PlatformMutationVO invitationMutation = new PlatformMutationVO(entity(OPERATION), entity(INVITATION), null);
        PlatformMutationVO configMutation = new PlatformMutationVO(entity(OPERATION), CONFIG_KEY, 1L);
        PlatformTenantVO tenant = new PlatformTenantVO(entity(TENANT), "http-tenant", "HTTP Tenant", 1, 0L,
                null, null, null, new Date(0), new Date(0), 1L);
        TenantMemberVO member = new TenantMemberVO(CODEC.toExternalUserId(USER), "http.member", "http@example.test",
                "HTTP Member", "user", 1, new Date(0), null);
        TenantInvitationVO invitation = new TenantInvitationVO(entity(INVITATION), "invited@example.test", "admin",
                "PENDING", LocalDateTime.of(2030, 1, 2, 0, 0), LocalDateTime.of(2030, 1, 1, 0, 0));
        PlatformConfigurationVO config = new PlatformConfigurationVO(CONFIG_KEY, "INTEGER", 100L, 0L, 1L,
                1_000_000L, "Operations per five-minute audit window", "GLOBAL", "DATABASE", true, false, "AVAILABLE");
        PlatformAuditVO audit = new PlatformAuditVO(entity(OPERATION), CODEC.toExternalUserId(ACTOR),
                "TENANT_UPDATE", entity(TENANT), "TENANT", entity(TENANT), REASON,
                "metadataVersion=0", "metadataVersion=1", "SUCCESS", entityMutation, null,
                null, LocalDateTime.of(2030, 1, 1, 0, 0), LocalDateTime.of(2030, 1, 1, 0, 1), 60_000L);
        return switch (route.name()) {
            case "session" -> {
                var result = new PlatformSessionVO(CODEC.toExternalUserId(ACTOR), "operator", "platform", 0L,
                        List.copyOf(PlatformPermissions.allCodes()));
                when(queries.session()).thenReturn(result);
                yield result;
            }
            case "overview" -> {
                var result = new PlatformOverviewVO(5L, 4L, 1L, 12L, 10L, "AVAILABLE");
                when(queries.overview()).thenReturn(result);
                yield result;
            }
            case "health" -> {
                var result = new PlatformHealthVO("UNKNOWN", Map.of("database", "UP", "redis", "UNKNOWN"));
                when(queries.health()).thenReturn(result);
                yield result;
            }
            case "tenants" -> {
                var result = new PlatformPage<PlatformTenantVO>(1, 20, 1).setRecords(List.of(tenant));
                when(tenants.list(1, 20, null, null)).thenReturn(result);
                yield result;
            }
            case "tenant" -> {
                when(tenants.get(TENANT)).thenReturn(tenant);
                yield tenant;
            }
            case "tenant-create" -> {
                when(commands.create(KEY, (CreatePlatformTenantRequest) route.body())).thenReturn(entityMutation);
                yield entityMutation;
            }
            case "tenant-update" -> {
                when(commands.update(TENANT, KEY, (UpdatePlatformTenantRequest) route.body())).thenReturn(entityMutation);
                yield entityMutation;
            }
            case "tenant-status" -> {
                when(commands.changeStatus(TENANT, KEY, (ChangePlatformTenantStatusRequest) route.body()))
                        .thenReturn(entityMutation);
                yield entityMutation;
            }
            case "usage" -> {
                var result = new PlatformUsageVO(entity(TENANT), 2L, 3L, 1024L, 4L, 1L,
                        "TENANT_BUSINESS_OPERATION_LOG", "TENANT_COMPLETED_ATTESTATION_BATCH", "AVAILABLE");
                when(tenants.usage(TENANT)).thenReturn(result);
                yield result;
            }
            case "quota" -> {
                var result = new PlatformQuotaVO(entity(TENANT), 4096L, 20L, 1024L, 3L,
                        "TENANT_OVERRIDE", "SHADOW", 0L);
                when(tenants.quota(TENANT)).thenReturn(result);
                yield result;
            }
            case "quota-update" -> {
                when(commands.updateQuota(TENANT, KEY, (UpdatePlatformQuotaRequest) route.body())).thenReturn(entityMutation);
                yield entityMutation;
            }
            case "users" -> {
                var result = new PlatformPage<PlatformUserVO>(1, 20, 1).setRecords(List.of(new PlatformUserVO(
                        member.id(), entity(TENANT), member.username(), member.nickname(), member.role(), member.status(),
                        member.registerTime(), member.lastLoginTime())));
                when(users.list(1, 20, null, null, null, null)).thenReturn(result);
                yield result;
            }
            case "members" -> {
                var result = new PlatformPage<TenantMemberVO>(1, 20, 1).setRecords(List.of(member));
                when(users.members(TENANT, 1, 20, null, null, null)).thenReturn(result);
                yield result;
            }
            case "user-role" -> {
                when(users.changeRole(TENANT, USER, KEY, (ChangeTenantMemberRoleRequest) route.body())).thenReturn(userMutation);
                yield userMutation;
            }
            case "user-status" -> {
                when(users.changeStatus(TENANT, USER, KEY, (ChangeTenantMemberStatusRequest) route.body())).thenReturn(userMutation);
                yield userMutation;
            }
            case "user-sessions" -> {
                when(users.revokeSessions(TENANT, USER, KEY, (TenantMemberReasonRequest) route.body())).thenReturn(userMutation);
                yield userMutation;
            }
            case "invitations" -> {
                when(users.invitations(TENANT)).thenReturn(List.of(invitation));
                yield List.of(invitation);
            }
            case "invitation-create" -> {
                when(users.invite(TENANT, KEY, (CreateTenantInvitationRequest) route.body())).thenReturn(invitationMutation);
                yield invitationMutation;
            }
            case "invitation-revoke" -> {
                when(users.revokeInvitation(TENANT, INVITATION, KEY, (TenantMemberReasonRequest) route.body()))
                        .thenReturn(invitationMutation);
                yield invitationMutation;
            }
            case "configurations" -> {
                when(configurations.list()).thenReturn(List.of(config));
                yield List.of(config);
            }
            case "configuration" -> {
                when(configurations.get(CONFIG_KEY)).thenReturn(config);
                yield config;
            }
            case "configuration-update" -> {
                when(configurations.update(CONFIG_KEY, KEY, (UpdatePlatformConfigurationRequest) route.body()))
                        .thenReturn(configMutation);
                yield configMutation;
            }
            case "audits" -> {
                var result = new PlatformPage<PlatformAuditVO>(1, 20, 1).setRecords(List.of(audit));
                when(audits.list(1, 20, null, null)).thenReturn(result);
                yield result;
            }
            case "audit" -> {
                when(audits.get(OPERATION)).thenReturn(audit);
                yield audit;
            }
            default -> throw new AssertionError("Missing route fixture: " + route.name());
        };
    }

    /** Asserts boundary failures occur before any platform service can execute or read metadata. */
    private void assertNoServiceCalls() {
        verifyNoInteractions(queries, tenants, commands, users, configurations, audits);
    }

    /** Creates a Spring User principal with one role and explicitly selected code-owned capabilities. */
    private void authenticate(String role, String... capabilities) {
        var authorities = new ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        Arrays.stream(capabilities).map(SimpleGrantedAuthority::new).forEach(authorities::add);
        User principal = new User("operator", "unused", authorities);
        MDC.put(Const.ATTR_USER_ROLE, role);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
    }

    /** Builds an authenticated-shape JSON request with the standard durable-command key. */
    private MockHttpServletRequestBuilder request(Route route) throws Exception {
        return request(route, KEY);
    }

    /** Builds exact encrypted resource paths and optionally omits the idempotency header for validation tests. */
    private MockHttpServletRequestBuilder request(Route route, String key) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(route.method(), ROOT + expand(route.path()));
        if (key != null) request.header("Idempotency-Key", key);
        if (route.body() != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(route.body()));
        return request;
    }

    /** Supplies entity SecureIds while leaving user IDs in their distinct namespace. */
    private static String entity(long id) {
        return CODEC.toExternalId(id);
    }

    /** Resolves placeholders through real codecs instead of teaching the controller to accept raw fixture IDs. */
    private static String expand(String path) {
        return path.replace("{tenantId}", entity(TENANT)).replace("{userId}", CODEC.toExternalUserId(USER))
                .replace("{invitationId}", entity(INVITATION)).replace("{operationId}", entity(OPERATION));
    }

    /** Enumerates the complete route contract shared by successful and rejected requests. */
    private static Stream<Route> routes() {
        return Stream.of(
                new Route("session", HttpMethod.GET, "/session", PlatformPermissions.OVERVIEW_READ, null),
                new Route("overview", HttpMethod.GET, "/overview", PlatformPermissions.OVERVIEW_READ, null),
                new Route("health", HttpMethod.GET, "/resources/health", PlatformPermissions.RESOURCE_READ, null),
                new Route("tenants", HttpMethod.GET, "/tenants", PlatformPermissions.TENANT_READ, null),
                new Route("tenant", HttpMethod.GET, "/tenants/{tenantId}", PlatformPermissions.TENANT_READ, null),
                new Route("tenant-create", HttpMethod.POST, "/tenants", PlatformPermissions.TENANT_WRITE,
                        new CreatePlatformTenantRequest("http-tenant", "HTTP Tenant", null, null, REASON)),
                new Route("tenant-update", HttpMethod.PUT, "/tenants/{tenantId}", PlatformPermissions.TENANT_WRITE,
                        new UpdatePlatformTenantRequest("Renamed Tenant", 0L, REASON)),
                new Route("tenant-status", HttpMethod.PUT, "/tenants/{tenantId}/status", PlatformPermissions.TENANT_WRITE,
                        new ChangePlatformTenantStatusRequest(0, 0L, REASON)),
                new Route("usage", HttpMethod.GET, "/tenants/{tenantId}/usage", PlatformPermissions.TENANT_READ, null),
                new Route("quota", HttpMethod.GET, "/tenants/{tenantId}/quota", PlatformPermissions.QUOTA_READ, null),
                new Route("quota-update", HttpMethod.PUT, "/tenants/{tenantId}/quota", PlatformPermissions.QUOTA_WRITE,
                        new UpdatePlatformQuotaRequest(4096L, 20L, 0L, REASON)),
                new Route("users", HttpMethod.GET, "/users", PlatformPermissions.USER_READ, null),
                new Route("members", HttpMethod.GET, "/tenants/{tenantId}/users", PlatformPermissions.USER_READ, null),
                new Route("user-role", HttpMethod.PUT, "/tenants/{tenantId}/users/{userId}/role", PlatformPermissions.USER_WRITE,
                        new ChangeTenantMemberRoleRequest("monitor", REASON)),
                new Route("user-status", HttpMethod.PUT, "/tenants/{tenantId}/users/{userId}/status", PlatformPermissions.USER_WRITE,
                        new ChangeTenantMemberStatusRequest(0, REASON)),
                new Route("user-sessions", HttpMethod.POST, "/tenants/{tenantId}/users/{userId}/sessions/revoke", PlatformPermissions.USER_WRITE,
                        new TenantMemberReasonRequest(REASON)),
                new Route("invitations", HttpMethod.GET, "/tenants/{tenantId}/invitations", PlatformPermissions.USER_READ, null),
                new Route("invitation-create", HttpMethod.POST, "/tenants/{tenantId}/invitations", PlatformPermissions.USER_WRITE,
                        new CreateTenantInvitationRequest("invited@example.test", "admin", 24, REASON)),
                new Route("invitation-revoke", HttpMethod.DELETE, "/tenants/{tenantId}/invitations/{invitationId}", PlatformPermissions.USER_WRITE,
                        new TenantMemberReasonRequest(REASON)),
                new Route("configurations", HttpMethod.GET, "/configuration", PlatformPermissions.CONFIGURATION_READ, null),
                new Route("configuration", HttpMethod.GET, "/configuration/" + CONFIG_KEY, PlatformPermissions.CONFIGURATION_READ, null),
                new Route("configuration-update", HttpMethod.PUT, "/configuration/" + CONFIG_KEY, PlatformPermissions.CONFIGURATION_WRITE,
                        new UpdatePlatformConfigurationRequest(100L, 0L, REASON)),
                new Route("audits", HttpMethod.GET, "/audit", PlatformPermissions.AUDIT_READ, null),
                new Route("audit", HttpMethod.GET, "/audit/{operationId}", PlatformPermissions.AUDIT_READ, null));
    }

    /** Resolves a fixture by its stable human-readable route name. */
    private static Route route(String name) {
        return routes().filter(route -> route.name().equals(name)).findFirst().orElseThrow();
    }

    /** Selects all ten mutating endpoints for the shared reason/idempotency contract. */
    private static Stream<Route> mutations() {
        return routes().filter(route -> route.body() != null);
    }

    /** Covers legacy tenant-zero administrators and other tenant roles for every platform route. */
    private static Stream<DeniedRoute> tenantRoleRoutes() {
        return Stream.of("admin", "monitor", "user").flatMap(role -> routes().map(route -> new DeniedRoute(role, route)));
    }

    /** Covers every resource type and all paths accepting that identifier. */
    private static Stream<InvalidIdentifier> invalidIdentifiers() {
        return routes().flatMap(route -> Stream.of("tenantId", "userId", "invitationId", "operationId")
                .filter(name -> route.path().contains("{" + name + "}"))
                .flatMap(name -> {
                    long id = switch (name) {
                        case "tenantId" -> TENANT;
                        case "userId" -> USER;
                        case "invitationId" -> INVITATION;
                        default -> OPERATION;
                    };
                    String wrongType = name.equals("userId") ? entity(id) : CODEC.toExternalUserId(id);
                    return Stream.of(Long.toString(id), wrongType, name.equals("userId") ? "Ubroken" : "Ebroken")
                            .map(value -> new InvalidIdentifier(route,
                                    expand(route.path().replace("{" + name + "}", value)), name));
                }));
    }

    /** Supplies bounded page and filter violations without mixing expected HTTP and business failures. */
    private static Stream<InvalidQuery> invalidQueries() {
        Stream<InvalidQuery> pages = Stream.of("/tenants", "/users", "/audit", "/tenants/" + entity(TENANT) + "/users")
                .flatMap(path -> Stream.of(new InvalidQuery(path, "pageNum", "0"),
                        new InvalidQuery(path, "pageNum", "9007199254740992"),
                        new InvalidQuery(path, "pageSize", "0"), new InvalidQuery(path, "pageSize", "101"),
                        new InvalidQuery(path, "pageNum", "not-a-number")));
        return Stream.concat(pages, Stream.of(new InvalidQuery("/users", "role", "platform_admin"),
                new InvalidQuery("/users", "status", "2"), new InvalidQuery("/tenants", "status", "-1"),
                new InvalidQuery("/tenants", "keyword", "x".repeat(101)),
                new InvalidQuery("/audit", "status", "UNKNOWN")));
    }

    /** Supplies invalid fields that must never reach a platform command service. */
    private static Stream<InvalidBody> invalidBodies() {
        return Stream.of(
                new InvalidBody(route("tenant-create"), "{\"code\":\"INVALID\",\"name\":\"Tenant\",\"reason\":\"review\"}"),
                new InvalidBody(route("tenant-update"), "{\"name\":\"Tenant\",\"reason\":\"review\"}"),
                new InvalidBody(route("tenant-update"), "{\"name\":\"Tenant\",\"expectedVersion\":-1,\"reason\":\"review\"}"),
                new InvalidBody(route("tenant-status"), "{\"status\":2,\"expectedVersion\":0,\"reason\":\"review\"}"),
                new InvalidBody(route("quota-update"), "{\"maxStorageBytes\":9007199254740992,\"maxFileCount\":1,\"expectedVersion\":0,\"reason\":\"review\"}"),
                new InvalidBody(route("quota-update"), "{\"maxStorageBytes\":1,\"maxFileCount\":-1,\"expectedVersion\":0,\"reason\":\"review\"}"),
                new InvalidBody(route("configuration-update"), "{\"value\":1,\"expectedVersion\":-1,\"reason\":\"review\"}"),
                new InvalidBody(route("user-role"), "{\"role\":\"platform_admin\",\"reason\":\"review\"}"),
                new InvalidBody(route("user-status"), "{\"status\":2,\"reason\":\"review\"}"),
                new InvalidBody(route("invitation-create"), "{\"email\":\"invalid\",\"role\":\"admin\",\"expiresInHours\":24,\"reason\":\"review\"}"));
    }

    private record Route(String name, HttpMethod method, String path, String permission, Object body) {
        /** Keeps test reports free of raw request payloads. */
        @Override public String toString() { return method + " " + ROOT + path; }
    }

    private record DeniedRoute(String role, Route route) {
        /** Names the rejected role without displaying the command body. */
        @Override public String toString() { return role + " -> " + route; }
    }

    private record InvalidIdentifier(Route route, String path, String field) {
        /** Names only the route and rejected ID type. */
        @Override public String toString() { return route + ": invalid " + field; }
    }

    private record InvalidQuery(String path, String name, String value) {
        /** Omits rejected text from parameterized report names. */
        @Override public String toString() { return path + ": invalid " + name; }
    }

    private record InvalidBody(Route route, String json) {
        /** Omits request payloads from parameterized report names. */
        @Override public String toString() { return route + ": invalid body"; }
    }

    /** Loads production method-security behavior without databases or remote services. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        /** Uses the production expression handler so platform hasAuthority semantics remain unchanged. */
        @Bean static MethodSecurityExpressionHandler methodSecurityExpressionHandler(PermissionService permissions) {
            return new CustomMethodSecurityExpressionHandler(permissions);
        }
        /** Validates both JSON body records and constrained controller parameters. */
        @Bean static LocalValidatorFactoryBean validator() { return new LocalValidatorFactoryBean(); }
        /** Applies method validation to production Validated controllers. */
        @Bean static MethodValidationPostProcessor methodValidationPostProcessor(LocalValidatorFactoryBean validator) {
            var processor = new MethodValidationPostProcessor();
            processor.setValidator(validator);
            processor.setProxyTargetClass(true);
            return processor;
        }
        /** Observes accidental reliance on tenant-managed permissions. */
        @Bean PermissionService permissions() { return mock(PermissionService.class); }
        /** Supplies the HTTP-only overview collaborator. */
        @Bean PlatformQueryService queries() { return mock(PlatformQueryService.class); }
        /** Supplies the HTTP-only tenant read collaborator. */
        @Bean PlatformTenantQueryService tenants() { return mock(PlatformTenantQueryService.class); }
        /** Supplies the HTTP-only tenant mutation collaborator. */
        @Bean PlatformTenantCommandService commands() { return mock(PlatformTenantCommandService.class); }
        /** Supplies the HTTP-only member collaborator. */
        @Bean PlatformUserService users() { return mock(PlatformUserService.class); }
        /** Supplies the HTTP-only configuration collaborator. */
        @Bean PlatformConfigurationService configurations() { return mock(PlatformConfigurationService.class); }
        /** Supplies the HTTP-only audit collaborator. */
        @Bean PlatformAuditService audits() { return mock(PlatformAuditService.class); }
        /** Registers the real overview controller for validation/security advice. */
        @Bean PlatformOverviewController overviewController(PlatformQueryService queries) {
            return new PlatformOverviewController(queries);
        }
        /** Registers the real tenant controller for validation/security advice. */
        @Bean PlatformTenantController tenantController(PlatformTenantQueryService tenants, PlatformTenantCommandService commands) {
            return new PlatformTenantController(tenants, commands);
        }
        /** Registers the real member controller for validation/security advice. */
        @Bean PlatformUserController userController(PlatformUserService users) { return new PlatformUserController(users); }
        /** Registers the real configuration controller for validation/security advice. */
        @Bean PlatformConfigurationController configurationController(PlatformConfigurationService configurations) {
            return new PlatformConfigurationController(configurations);
        }
        /** Registers the real audit controller for validation/security advice. */
        @Bean PlatformAuditController auditController(PlatformAuditService audits) { return new PlatformAuditController(audits); }
    }
}
