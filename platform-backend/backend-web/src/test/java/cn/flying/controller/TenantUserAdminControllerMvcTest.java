package cn.flying.controller;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.dao.vo.admin.ChangeTenantMemberRoleRequest;
import cn.flying.dao.vo.admin.ChangeTenantMemberStatusRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantInvitationVO;
import cn.flying.dao.vo.admin.TenantMemberReasonRequest;
import cn.flying.dao.vo.admin.TenantMemberVO;
import cn.flying.filter.handler.GlobalExceptionHandler;
import cn.flying.security.CustomMethodSecurityExpressionHandler;
import cn.flying.service.PermissionService;
import cn.flying.service.admin.TenantInvitationService;
import cn.flying.service.admin.TenantMemberCommandService;
import cn.flying.service.admin.TenantMemberQueryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises HTTP binding, validation, real method security, and controller error contracts.
 * Service substitutes observe the HTTP boundary; database isolation and transactions belong to the real IT suite.
 */
@SpringJUnitConfig(TenantUserAdminControllerMvcTest.Config.class)
class TenantUserAdminControllerMvcTest {

    private static final String ROOT = "/api/v1/admin/users";
    private static final Long TENANT_ID = 11L;
    private static final Long ACTOR_ID = 41L;
    private static final Long MEMBER_ID = 82L;
    private static final Long INVITATION_ID = 93L;
    private static final String REASON = "Membership review completed";
    private static final SecureIdCodec ID_CODEC = new SecureIdCodec(
            "SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234");

    @Autowired private TenantUserAdminController controller;
    @Autowired private TenantMemberQueryService queryService;
    @Autowired private TenantMemberCommandService commandService;
    @Autowired private TenantInvitationService invitationService;
    @Autowired private PermissionService permissionService;
    @Autowired private LocalValidatorFactoryBean validator;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private Object previousCodec;

    /** Installs a deterministic codec and the trusted identity normally established by JWT processing. */
    @BeforeEach
    void setUp() {
        reset(queryService, commandService, invitationService, permissionService);
        previousCodec = ReflectionTestUtils.getField(IdUtils.class, "secureIdCodec");
        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", ID_CODEC);
        TenantContext.setTenantId(TENANT_ID);
        MDC.put(Const.ATTR_USER_ID, ACTOR_ID.toString());
        authenticate("admin");
        when(permissionService.hasPermission("tenant:user:admin")).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** Restores process-wide ID state and clears request identity between test cases. */
    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", previousCodec);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    /** Binds default pagination while ignoring a caller's attempt to select another tenant. */
    @Test
    void shouldListMembersUsingAuthenticatedTenantAndDefaultPage() throws Exception {
        Page<TenantMemberVO> page = new Page<TenantMemberVO>(1, 20, 1)
                .setRecords(List.of(member("user", 1)));
        when(queryService.list(TENANT_ID, 1, 20, null, null, null)).thenReturn(page);

        mvc.perform(MockMvcRequestBuilders.get(ROOT).param("tenantId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(ID_CODEC.toExternalUserId(MEMBER_ID)))
                .andExpect(jsonPath("$.data.records[0].username").value("member.one"))
                .andExpect(jsonPath("$.data.records[0].password").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].authVersion").doesNotExist());

        verify(queryService).list(TENANT_ID, 1, 20, null, null, null);
    }

    /** Preserves explicit search, role, status, and page fields across HTTP binding. */
    @Test
    void shouldBindMemberFiltersAndPageMetadata() throws Exception {
        Page<TenantMemberVO> page = new Page<TenantMemberVO>(3, 5, 11)
                .setRecords(List.of(member("monitor", 0)));
        when(queryService.list(TENANT_ID, 3, 5, "member", "monitor", 0)).thenReturn(page);

        mvc.perform(MockMvcRequestBuilders.get(ROOT)
                        .param("pageNum", "3").param("pageSize", "5")
                        .param("keyword", "member").param("role", "monitor").param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.current").value(3))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.records[0].role").value("monitor"))
                .andExpect(jsonPath("$.data.records[0].status").value(0));

        verify(queryService).list(TENANT_ID, 3, 5, "member", "monitor", 0);
    }

    /** Decodes the user SecureId before the tenant-scoped detail service receives it. */
    @Test
    void shouldResolveMemberDetailFromUserSecureId() throws Exception {
        when(queryService.get(TENANT_ID, MEMBER_ID)).thenReturn(member("user", 1));

        mvc.perform(requestFor(new AdminRequest(HttpMethod.GET, "/{userId}", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(ID_CODEC.toExternalUserId(MEMBER_ID)))
                .andExpect(jsonPath("$.data.email").value("member.one@example.test"))
                .andExpect(jsonPath("$.data.role").value("user"))
                .andExpect(jsonPath("$.data.status").value(1));

        verify(queryService).get(TENANT_ID, MEMBER_ID);
    }

    /** Carries each assignable role and the trusted actor to the command boundary. */
    @ParameterizedTest
    @ValueSource(strings = {"user", "admin", "monitor"})
    void shouldChangeRoleUsingAuthenticatedActor(String role) throws Exception {
        var body = new ChangeTenantMemberRoleRequest(role, REASON);

        mvc.perform(requestFor(new AdminRequest(HttpMethod.PUT, "/{userId}/role", body))
                        .param("tenantId", "999").param("actorId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(commandService).changeRole(TENANT_ID, ACTOR_ID, MEMBER_ID, role, REASON);
    }

    /** Maps both disable and restore requests without changing the member or actor identity. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void shouldChangeMemberStatus(int memberStatus) throws Exception {
        var body = new ChangeTenantMemberStatusRequest(memberStatus, REASON);

        mvc.perform(requestFor(new AdminRequest(HttpMethod.PUT, "/{userId}/status", body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(commandService).changeStatus(TENANT_ID, ACTOR_ID, MEMBER_ID, memberStatus, REASON);
    }

    /** Routes explicit session revocation to the requested member in the authenticated tenant. */
    @Test
    void shouldRevokeMemberSessions() throws Exception {
        mvc.perform(requestFor(new AdminRequest(HttpMethod.POST, "/{userId}/sessions/revoke",
                        new TenantMemberReasonRequest(REASON))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(commandService).revokeSessions(TENANT_ID, ACTOR_ID, MEMBER_ID, REASON);
    }

    /** Resolves the static invitations route and returns metadata without token material. */
    @Test
    void shouldListInvitationMetadataWithoutTokenMaterial() throws Exception {
        when(invitationService.list(TENANT_ID)).thenReturn(List.of(invitation("monitor")));

        mvc.perform(MockMvcRequestBuilders.get(ROOT + "/invitations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(ID_CODEC.toExternalId(INVITATION_ID)))
                .andExpect(jsonPath("$.data[0].email").value("new.member@example.test"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].token").doesNotExist())
                .andExpect(jsonPath("$.data[0].tokenHash").doesNotExist())
                .andExpect(jsonPath("$.data[0].tenantId").doesNotExist());

        verify(invitationService).list(TENANT_ID);
        verifyNoInteractions(queryService);
    }

    /** Accepts bounded expiry values and all tenant roles while returning only invitation metadata. */
    @ParameterizedTest
    @CsvSource({"user,1", "admin,168", "monitor,24"})
    void shouldCreateInvitationUsingTrustedTenantAndActor(String role, int hours) throws Exception {
        var body = new CreateTenantInvitationRequest("new.member@example.test", role, hours, REASON);
        when(invitationService.create(TENANT_ID, ACTOR_ID, body)).thenReturn(invitation(role));

        mvc.perform(requestFor(new AdminRequest(HttpMethod.POST, "/invitations", body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(ID_CODEC.toExternalId(INVITATION_ID)))
                .andExpect(jsonPath("$.data.role").value(role))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.tokenHash").doesNotExist());

        verify(invitationService).create(TENANT_ID, ACTOR_ID, body);
    }

    /** Decodes invitation IDs separately from account IDs and forwards the required reason. */
    @Test
    void shouldRevokeInvitationUsingEntitySecureId() throws Exception {
        mvc.perform(requestFor(new AdminRequest(HttpMethod.DELETE, "/invitations/{invitationId}",
                        new TenantMemberReasonRequest(REASON))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(invitationService).revoke(TENANT_ID, ACTOR_ID, INVITATION_ID, REASON);
    }

    /** Requires the explicit permission on every management route even for an administrator. */
    @ParameterizedTest
    @MethodSource("adminRequests")
    void shouldRejectAdminWithoutPermissionOnEveryRoute(AdminRequest route) throws Exception {
        when(permissionService.hasPermission("tenant:user:admin")).thenReturn(false);

        mvc.perform(requestFor(route))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultEnum.PERMISSION_UNAUTHORIZED.getCode()));

        verifyNoInteractions(queryService, commandService, invitationService);
    }

    /** Prevents transferable permission from opening any route to non-admin or platform identities. */
    @ParameterizedTest
    @ValueSource(strings = {"user", "monitor", "platform_admin"})
    void shouldRejectNonAdminPermissionHoldersOnEveryRoute(String role) throws Exception {
        authenticate(role);

        for (AdminRequest route : adminRequests().toList()) {
            mvc.perform(requestFor(route))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ResultEnum.PERMISSION_UNAUTHORIZED.getCode()));
        }

        verifyNoInteractions(queryService, commandService, invitationService);
    }

    /** Fails closed when JWT processing did not populate the actor for any mutation. */
    @ParameterizedTest
    @MethodSource("mutationRequests")
    void shouldRejectMutationWithoutTrustedActor(AdminRequest route) throws Exception {
        MDC.remove(Const.ATTR_USER_ID);

        mvc.perform(requestFor(route).param("actorId", ACTOR_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultEnum.PERMISSION_UNAUTHENTICATED.getCode()));

        verifyNoInteractions(queryService, commandService, invitationService);
    }

    /** Rejects numeric, wrong-entity, and invalid authenticated IDs before member reads or writes. */
    @ParameterizedTest
    @MethodSource("invalidUserIds")
    void shouldRejectInvalidUserIdsAcrossMemberRoutes(String externalId) throws Exception {
        for (AdminRequest route : adminRequests().filter(value -> value.path().contains("{userId}")).toList()) {
            var invalidRoute = new AdminRequest(route.method(), route.path().replace("{userId}", externalId),
                    route.body());
            mvc.perform(requestFor(invalidRoute))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.TENANT_MEMBER_NOT_FOUND.getCode()));
        }

        verifyNoInteractions(queryService, commandService, invitationService);
    }

    /** Rejects account IDs and malformed entity IDs at the invitation mutation boundary. */
    @ParameterizedTest
    @MethodSource("invalidInvitationIds")
    void shouldRejectInvalidInvitationIds(String externalId) throws Exception {
        mvc.perform(requestFor(new AdminRequest(HttpMethod.DELETE, "/invitations/" + externalId,
                        new TenantMemberReasonRequest(REASON))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultEnum.TENANT_MEMBER_NOT_FOUND.getCode()));

        verifyNoInteractions(queryService, commandService, invitationService);
    }

    /** Executes method-level constraints and MVC numeric conversion before querying member data. */
    @ParameterizedTest
    @CsvSource({"pageNum,0", "pageSize,0", "pageSize,101", "role,platform_admin",
            "status,-1", "status,2", "pageNum,not-a-number"})
    void shouldRejectInvalidListParameters(String parameter, String value) throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(ROOT).param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()))
                .andExpect(jsonPath("$.data.detail", containsString(parameter)));

        verifyNoInteractions(queryService, commandService, invitationService);
    }

    /** Rejects invalid JSON body fields through the production validation error envelope. */
    @ParameterizedTest
    @MethodSource("invalidBodies")
    void shouldValidateMutationBodies(InvalidBody invalid) throws Exception {
        mvc.perform(requestFor(invalid.route()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()))
                .andExpect(jsonPath("$.data.detail", containsString(invalid.field())));

        verifyNoInteractions(queryService, commandService, invitationService);
    }

    /** Preserves the service's non-disclosing result for any valid but unaddressable member ID. */
    @ParameterizedTest
    @ValueSource(longs = {9001L, 9002L})
    void shouldMapUnaddressableMembersToTheSameNotFoundContract(long rejectedId) throws Exception {
        when(queryService.get(TENANT_ID, rejectedId))
                .thenThrow(new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND));

        mvc.perform(MockMvcRequestBuilders.get(ROOT + "/" + ID_CODEC.toExternalUserId(rejectedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultEnum.TENANT_MEMBER_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value(ResultEnum.TENANT_MEMBER_NOT_FOUND.getMessage()))
                .andExpect(jsonPath("$.data.detail").value(ResultEnum.TENANT_MEMBER_NOT_FOUND.getMessage()));

        verify(queryService).get(TENANT_ID, rejectedId);
    }

    /** Preserves self-protection and last-administrator business failures without reporting success. */
    @ParameterizedTest
    @EnumSource(value = ResultEnum.class, names = {"TENANT_ADMIN_SELF_OPERATION_FORBIDDEN", "LAST_TENANT_ADMIN_REQUIRED"})
    void shouldMapMemberInvariantFailures(ResultEnum failure) throws Exception {
        doThrow(new GeneralException(failure)).when(commandService)
                .changeRole(TENANT_ID, ACTOR_ID, MEMBER_ID, "user", REASON);

        mvc.perform(requestFor(new AdminRequest(HttpMethod.PUT, "/{userId}/role",
                        new ChangeTenantMemberRoleRequest("user", REASON))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(failure.getCode()))
                .andExpect(jsonPath("$.message").value(failure.getMessage()))
                .andExpect(jsonPath("$.data.detail").value(failure.getMessage()));
    }

    /** Exposes deterministic invitation conflicts through the common business-error contract. */
    @ParameterizedTest
    @EnumSource(value = ResultEnum.class, names = {"INVITATION_ALREADY_EXISTS", "INVITATION_ACCOUNT_CONFLICT"})
    void shouldMapInvitationCreationConflicts(ResultEnum failure) throws Exception {
        when(invitationService.create(eq(TENANT_ID), eq(ACTOR_ID), any(CreateTenantInvitationRequest.class)))
                .thenThrow(new GeneralException(failure));

        mvc.perform(requestFor(new AdminRequest(HttpMethod.POST, "/invitations", invitationRequest(REASON))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(failure.getCode()))
                .andExpect(jsonPath("$.message").value(failure.getMessage()))
                .andExpect(jsonPath("$.data.detail").value(failure.getMessage()));
    }

    /** Builds a JSON HTTP request using actual encrypted IDs rather than numeric stand-ins. */
    private MockHttpServletRequestBuilder requestFor(AdminRequest route) throws Exception {
        String path = route.path().replace("{userId}", ID_CODEC.toExternalUserId(MEMBER_ID))
                .replace("{invitationId}", ID_CODEC.toExternalId(INVITATION_ID));
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(route.method(), ROOT + path);
        if (route.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(route.body()));
        }
        return request;
    }

    /** Changes only the authenticated role while retaining the test's trusted actor and tenant. */
    private void authenticate(String role) {
        MDC.put(Const.ATTR_USER_ROLE, role);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "operator", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    /** Supplies a public member view with no persistence or authorization secrets. */
    private TenantMemberVO member(String role, int memberStatus) {
        return new TenantMemberVO(ID_CODEC.toExternalUserId(MEMBER_ID), "member.one", "member.one@example.test",
                "Member One", role, memberStatus, new Date(1_700_000_000_000L), null);
    }

    /** Supplies stable invitation metadata for HTTP serialization checks. */
    private TenantInvitationVO invitation(String role) {
        return new TenantInvitationVO(ID_CODEC.toExternalId(INVITATION_ID), "new.member@example.test", role,
                "PENDING", LocalDateTime.of(2030, 1, 2, 12, 0), LocalDateTime.of(2030, 1, 1, 12, 0));
    }

    /** Enumerates every management route so authorization checks cannot overlook a write entry point. */
    private static Stream<AdminRequest> adminRequests() {
        return Stream.concat(Stream.of(
                new AdminRequest(HttpMethod.GET, "", null),
                new AdminRequest(HttpMethod.GET, "/{userId}", null),
                new AdminRequest(HttpMethod.GET, "/invitations", null)), mutationRequests());
    }

    /** Enumerates valid mutations shared by role, actor, and reason-boundary tests. */
    private static Stream<AdminRequest> mutationRequests() {
        return mutationsWithReason(REASON);
    }

    /** Builds each mutation with the same reason to check both required and bounded reason validation. */
    private static Stream<AdminRequest> mutationsWithReason(String reason) {
        return Stream.of(
                new AdminRequest(HttpMethod.POST, "/invitations", invitationRequest(reason)),
                new AdminRequest(HttpMethod.DELETE, "/invitations/{invitationId}", new TenantMemberReasonRequest(reason)),
                new AdminRequest(HttpMethod.PUT, "/{userId}/role", new ChangeTenantMemberRoleRequest("monitor", reason)),
                new AdminRequest(HttpMethod.PUT, "/{userId}/status", new ChangeTenantMemberStatusRequest(0, reason)),
                new AdminRequest(HttpMethod.POST, "/{userId}/sessions/revoke", new TenantMemberReasonRequest(reason)));
    }

    /** Provides a valid invitation request without any target-tenant or actor selector. */
    private static CreateTenantInvitationRequest invitationRequest(String reason) {
        return new CreateTenantInvitationRequest("new.member@example.test", "monitor", 24, reason);
    }

    /** Exercises entity type separation even when an invitation decodes to the same numeric member ID. */
    private static Stream<String> invalidUserIds() {
        return Stream.of(MEMBER_ID.toString(), ID_CODEC.toExternalId(MEMBER_ID), "Ubroken");
    }

    /** Exercises entity type separation even when an account decodes to the same numeric invitation ID. */
    private static Stream<String> invalidInvitationIds() {
        return Stream.of(INVITATION_ID.toString(), ID_CODEC.toExternalUserId(INVITATION_ID), "Ebroken");
    }

    /** Covers role/status/email/expiry validation plus blank and oversized reasons on all mutation DTOs. */
    private static Stream<InvalidBody> invalidBodies() {
        Stream<InvalidBody> reasons = Stream.of(" ", "r".repeat(256))
                .flatMap(reason -> mutationsWithReason(reason).map(route -> new InvalidBody(route, "reason")));
        Stream<InvalidBody> fields = Stream.of(
                new InvalidBody(new AdminRequest(HttpMethod.PUT, "/{userId}/role",
                        new ChangeTenantMemberRoleRequest("platform_admin", REASON)), "role"),
                new InvalidBody(new AdminRequest(HttpMethod.PUT, "/{userId}/status",
                        new ChangeTenantMemberStatusRequest(-1, REASON)), "status"),
                new InvalidBody(new AdminRequest(HttpMethod.PUT, "/{userId}/status",
                        new ChangeTenantMemberStatusRequest(2, REASON)), "status"),
                new InvalidBody(new AdminRequest(HttpMethod.PUT, "/{userId}/status",
                        new ChangeTenantMemberStatusRequest(null, REASON)), "status"),
                new InvalidBody(new AdminRequest(HttpMethod.POST, "/invitations",
                        new CreateTenantInvitationRequest("invalid-email", "user", 24, REASON)), "email"),
                new InvalidBody(new AdminRequest(HttpMethod.POST, "/invitations",
                        new CreateTenantInvitationRequest("new@example.test", "platform_admin", 24, REASON)), "role"),
                new InvalidBody(new AdminRequest(HttpMethod.POST, "/invitations",
                        new CreateTenantInvitationRequest("new@example.test", "user", 0, REASON)), "expiresInHours"),
                new InvalidBody(new AdminRequest(HttpMethod.POST, "/invitations",
                        new CreateTenantInvitationRequest("new@example.test", "user", 169, REASON)), "expiresInHours"),
                new InvalidBody(new AdminRequest(HttpMethod.POST, "/invitations",
                        new CreateTenantInvitationRequest("new@example.test", "user", null, REASON)), "expiresInHours"));
        return Stream.concat(reasons, fields);
    }

    private record AdminRequest(HttpMethod method, String path, Object body) {
        /** Keeps parameterized test names focused on the HTTP operation rather than request data. */
        @Override
        public String toString() {
            return method + " " + ROOT + path;
        }
    }

    private record InvalidBody(AdminRequest route, String field) {
        /** Identifies the rejected field without expanding the submitted body in test reports. */
        @Override
        public String toString() {
            return route + ": invalid " + field;
        }
    }

    /** Creates only the production controller advisors and replaceable HTTP collaborators. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {

        /** Registers the same permission expression handler used by application method security. */
        @Bean
        static MethodSecurityExpressionHandler methodSecurityExpressionHandler(PermissionService permissionService) {
            return new CustomMethodSecurityExpressionHandler(permissionService);
        }

        /** Supplies Jakarta Bean Validation for both HTTP bodies and constrained query arguments. */
        @Bean
        static LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }

        /** Applies the controller's existing Validated annotation to method-parameter constraints. */
        @Bean
        static MethodValidationPostProcessor methodValidationPostProcessor(LocalValidatorFactoryBean validator) {
            MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
            processor.setValidator(validator);
            processor.setProxyTargetClass(true);
            return processor;
        }

        /** Observes capability checks without loading the permission database or cache. */
        @Bean
        PermissionService permissionService() { return mock(PermissionService.class); }

        /** Observes controller query arguments without claiming SQL isolation coverage. */
        @Bean
        TenantMemberQueryService queryService() { return mock(TenantMemberQueryService.class); }

        /** Observes command identity and payload mapping without claiming transaction coverage. */
        @Bean
        TenantMemberCommandService commandService() { return mock(TenantMemberCommandService.class); }

        /** Observes invitation HTTP contracts independently of the invitation state-machine IT suite. */
        @Bean
        TenantInvitationService invitationService() { return mock(TenantInvitationService.class); }

        /** Exposes the real controller target for Spring's validation and method-security proxies. */
        @Bean
        TenantUserAdminController controller(TenantMemberQueryService queryService,
                                             TenantMemberCommandService commandService,
                                             TenantInvitationService invitationService) {
            return new TenantUserAdminController(queryService, commandService, invitationService);
        }
    }
}
