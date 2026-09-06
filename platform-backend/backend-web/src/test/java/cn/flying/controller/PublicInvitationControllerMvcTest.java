package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.config.PrometheusScrapeSecurity;
import cn.flying.dao.vo.admin.AcceptTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantMemberVO;
import cn.flying.filter.TenantFilter;
import cn.flying.filter.handler.GlobalExceptionHandler;
import cn.flying.service.admin.TenantInvitationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests anonymous HTTP acceptance with the real tenant filter, validation, and exception mapping. */
@ExtendWith(MockitoExtension.class)
class PublicInvitationControllerMvcTest {

    private static final String ACCEPT_PATH = "/api/v1/public/invitations/accept";
    private static final String TOKEN = "a".repeat(43);
    private static final String PASSWORD = "MemberPass123!";
    private static final SecureIdCodec ID_CODEC = new SecureIdCodec(
            "SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234");

    @Mock private TenantInvitationService invitationService;

    private final ObjectMapper json = new ObjectMapper();
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    /** Creates an anonymous MVC boundary without database, mail, or authentication service substitutes. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new PublicInvitationController(invitationService))
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TenantFilter(new PrometheusScrapeSecurity(false, "", "")))
                .build();
    }

    /** Releases validator resources and clears thread-local identity after success and failures. */
    @AfterEach
    void tearDown() {
        validator.close();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    /** Ignores every caller tenant hint before handing the opaque acceptance payload to its owner resolver. */
    @ParameterizedTest
    @MethodSource("untrustedTenantHeaders")
    void shouldAcceptWithoutAuthenticationOrCallerTenantAuthority(List<String> tenantHeaders) throws Exception {
        AcceptTenantInvitationRequest body = acceptanceRequest("New Member");
        TenantContext.setTenantId(999L);
        when(invitationService.accept(body)).thenAnswer(invocation -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(TenantContext.getTenantId()).isNull();
            return member("New Member");
        });
        MockHttpServletRequestBuilder request = acceptancePost(body).param("tenantId", "888");
        if (!tenantHeaders.isEmpty()) {
            request.header("X-Tenant-ID", tenantHeaders.toArray());
        }

        var result = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(ID_CODEC.toExternalUserId(82L)))
                .andExpect(jsonPath("$.data.username").value("new.member"))
                .andExpect(jsonPath("$.data.email").value("invited@example.test"))
                .andExpect(jsonPath("$.data.role").value("monitor"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.tokenHash").doesNotExist())
                .andExpect(jsonPath("$.data.tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.authVersion").doesNotExist())
                .andReturn();

        verify(invitationService).accept(body);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN, PASSWORD);
        assertThat(result.getRequest().getAttribute(Const.ATTR_TENANT_ID)).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /** Keeps nickname optional while requiring the token and account setup fields in the POST body. */
    @Test
    void shouldAcceptWithoutOptionalNickname() throws Exception {
        AcceptTenantInvitationRequest body = acceptanceRequest(null);
        when(invitationService.accept(body)).thenReturn(member(null));

        mvc.perform(acceptancePost(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(ID_CODEC.toExternalUserId(82L)))
                .andExpect(jsonPath("$.data.nickname").doesNotExist());

        verify(invitationService).accept(body);
    }

    /** Rejects malformed acceptance fields before capability lookup and does not echo submitted secrets. */
    @ParameterizedTest
    @MethodSource("invalidBodies")
    void shouldValidateAcceptanceBody(InvalidBody invalid) throws Exception {
        var result = mvc.perform(acceptancePost(invalid.body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()))
                .andExpect(jsonPath("$.data.detail", containsString(invalid.field())))
                .andReturn();

        verifyNoInteractions(invitationService);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN, PASSWORD);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    /** Returns stable expired/replayed/conflicting invitation errors without returning capability material. */
    @ParameterizedTest
    @EnumSource(value = ResultEnum.class, names = {"INVITATION_INVALID", "INVITATION_ACCOUNT_CONFLICT"})
    void shouldMapAcceptanceBusinessFailures(ResultEnum failure) throws Exception {
        AcceptTenantInvitationRequest body = acceptanceRequest("New Member");
        when(invitationService.accept(body)).thenThrow(new GeneralException(failure));

        var result = mvc.perform(acceptancePost(body).header("X-Tenant-ID", "11", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(failure.getCode()))
                .andExpect(jsonPath("$.message").value(failure.getMessage()))
                .andExpect(jsonPath("$.data.detail").value(failure.getMessage()))
                .andReturn();

        verify(invitationService).accept(body);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN, PASSWORD);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    /** Keeps the tenant-optional HTTP surface limited to the exact acceptance POST route. */
    @ParameterizedTest
    @CsvSource({"GET,/api/v1/public/invitations/accept", "PUT,/api/v1/public/invitations/accept",
            "DELETE,/api/v1/public/invitations/accept", "POST,/api/v1/public/invitations/accept/extra",
            "POST,/api/v1/public/invitations/accept-extra"})
    void shouldRejectOtherAnonymousMethodsAndPaths(String method, String path) throws Exception {
        mvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(acceptanceRequest("New Member"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        verifyNoInteractions(invitationService);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    /** Retains audit metadata while forbidding persistence of token-bearing request and response bodies. */
    @Test
    void shouldDisableAcceptanceAuditPayloads() throws NoSuchMethodException {
        OperationLog audit = PublicInvitationController.class
                .getMethod("accept", AcceptTenantInvitationRequest.class).getAnnotation(OperationLog.class);

        assertThat(audit).isNotNull();
        assertThat(audit.saveRequestData()).isFalse();
        assertThat(audit.saveResponseData()).isFalse();
    }

    /** Serializes the capability only into the acceptance POST body. */
    private MockHttpServletRequestBuilder acceptancePost(AcceptTenantInvitationRequest body) throws Exception {
        return MockMvcRequestBuilders.post(ACCEPT_PATH).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    /** Supplies deterministic account setup fields for exact HTTP-to-service payload comparison. */
    private static AcceptTenantInvitationRequest acceptanceRequest(String nickname) {
        return new AcceptTenantInvitationRequest(TOKEN, "new.member", nickname, PASSWORD);
    }

    /** Represents a member resolved by the invitation service rather than by caller-supplied tenancy. */
    private TenantMemberVO member(String nickname) {
        return new TenantMemberVO(ID_CODEC.toExternalUserId(82L), "new.member", "invited@example.test",
                nickname, "monitor", 1, new Date(1_700_000_000_000L), null);
    }

    /** Includes absent, default, foreign, empty, malformed, and duplicate tenant header variants. */
    private static Stream<List<String>> untrustedTenantHeaders() {
        return Stream.of(List.of(), List.of("0"), List.of("999"), List.of(""),
                List.of("not-a-tenant"), List.of("11", "999"));
    }

    /** Covers required fields and the token, username, nickname, and password length boundaries. */
    private static Stream<InvalidBody> invalidBodies() {
        return Stream.of(
                new InvalidBody(new AcceptTenantInvitationRequest(null, "new.member", null, PASSWORD), "token"),
                new InvalidBody(new AcceptTenantInvitationRequest(" ".repeat(40), "new.member", null, PASSWORD), "token"),
                new InvalidBody(new AcceptTenantInvitationRequest("a".repeat(39), "new.member", null, PASSWORD), "token"),
                new InvalidBody(new AcceptTenantInvitationRequest("a".repeat(129), "new.member", null, PASSWORD), "token"),
                new InvalidBody(new AcceptTenantInvitationRequest(TOKEN, "ab", null, PASSWORD), "username"),
                new InvalidBody(new AcceptTenantInvitationRequest(TOKEN, "a".repeat(51), null, PASSWORD), "username"),
                new InvalidBody(new AcceptTenantInvitationRequest(TOKEN, "bad name", null, PASSWORD), "username"),
                new InvalidBody(new AcceptTenantInvitationRequest(TOKEN, null, null, PASSWORD), "username"),
                new InvalidBody(new AcceptTenantInvitationRequest(TOKEN, "new.member", "n".repeat(51), PASSWORD), "nickname"),
                new InvalidBody(new AcceptTenantInvitationRequest(TOKEN, "new.member", null, "p".repeat(7)), "password"),
                new InvalidBody(new AcceptTenantInvitationRequest(TOKEN, "new.member", null, "p".repeat(73)), "password"),
                new InvalidBody(new AcceptTenantInvitationRequest(TOKEN, "new.member", null, " ".repeat(8)), "password"),
                new InvalidBody(new AcceptTenantInvitationRequest(TOKEN, "new.member", null, null), "password"));
    }

    private record InvalidBody(AcceptTenantInvitationRequest body, String field) {
        /** Prevents parameterized test reports from rendering token or password fixtures. */
        @Override
        public String toString() {
            return "invalid " + field;
        }
    }
}
