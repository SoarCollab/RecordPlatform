package cn.flying.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import cn.flying.filter.JwtAuthenticationFilter;
import cn.flying.filter.RequestLogFilter;
import cn.flying.filter.TenantFilter;
import cn.flying.service.AccountService;
import cn.flying.service.LoginSecurityService;
import cn.flying.service.PermissionService;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise the production security chain together with its outer servlet tenant filter. */
class PrometheusScrapeSecurityTest {

    private static final String USER = "synthetic-collector";
    private static final String PASSWORD = "SyntheticScrapePassword-OnlyForTests-9273";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final String CONTEXT = "/record-platform";
    private AnnotationConfigWebApplicationContext context;

    /** Scan with the real integration application's exclusions without creating infrastructure beans. */
    @Test
    void integrationComponentScanExcludesIsolatedSecurityConfiguration() {
        var scan = cn.flying.test.TestApplication.class
                .getAnnotation(org.springframework.context.annotation.ComponentScan.class);
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(true);
        for (var filter : scan.excludeFilters()) {
            for (Class<?> type : filter.classes()) {
                switch (filter.type()) {
                    case ANNOTATION -> scanner.addExcludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(
                            type.asSubclass(java.lang.annotation.Annotation.class)));
                    case ASSIGNABLE_TYPE -> scanner.addExcludeFilter(new org.springframework.core.type.filter.AssignableTypeFilter(type));
                    default -> fail("Update this regression to apply the integration application's new filter type");
                }
            }
        }
        var candidates = java.util.Arrays.stream(scan.basePackages())
                .flatMap(basePackage -> scanner.findCandidateComponents(basePackage).stream())
                .map(org.springframework.beans.factory.config.BeanDefinition::getBeanClassName)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(candidates.contains(SecurityConfiguration.class.getName()));
        assertTrue(candidates.contains(PrometheusScrapeSecurity.class.getName()));
        assertFalse(candidates.contains(TestConfiguration.class.getName()),
                "Isolated test beans must not enter the integration component scan");
    }

    /** Dispose each isolated credential context and detect leaked request authority. */
    @AfterEach
    void closeContext() {
        assertNull(TenantContext.getTenantId());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        SecurityContextHolder.clearContext();
        MDC.clear();
        if (context != null) {
            context.close();
        }
    }

    /** Load the real application chain without business infrastructure or global scrape providers. */
    private void start(boolean enabled, String hash) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "security.prometheus-scrape.enabled=" + enabled,
                "security.prometheus-scrape.username=" + USER,
                "security.prometheus-scrape.password-hash=" + hash);
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("jwtUtils", mock(JwtUtils.class));
            factory.registerSingleton("loginSecurityService", mock(LoginSecurityService.class));
            factory.registerSingleton("permissionService", mock(PermissionService.class));
        });
        context.register(TestConfiguration.class);
        context.refresh();
    }

    /** Build an exact context-relative request as a servlet container would expose it. */
    private MockHttpServletRequest request(String method, String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, CONTEXT + path);
        request.setContextPath(CONTEXT);
        request.setServletPath(path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    /** Execute the real security chain and existing duplicate servlet registrations once per request. */
    private MockHttpServletResponse perform(MockHttpServletRequest request, FilterChain endpoint) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        context.getBean(TenantFilter.class).doFilter(request, response, (outerRequest, outerResponse) ->
                context.getBean(FilterChainProxy.class).doFilter(outerRequest, outerResponse, (securedRequest, securedResponse) ->
                        context.getBean(RequestLogFilter.class).doFilter(securedRequest, securedResponse, (loggedRequest, loggedResponse) ->
                                context.getBean(JwtAuthenticationFilter.class).doFilter(loggedRequest, loggedResponse, endpoint))));
        assertNull(request.getSession(false));
        assertNull(response.getHeader("Set-Cookie"));
        assertNull(TenantContext.getTenantId());
        assertNull(MDC.get(Const.ATTR_TENANT_ID));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        return response;
    }

    /** Observe authorization at the endpoint rather than injecting a test SecurityContext. */
    private MockHttpServletResponse perform(MockHttpServletRequest request) throws Exception {
        return perform(request, (req, res) -> res.getWriter().write("synthetic_metric 1\n"));
    }

    /** Encode synthetic credentials without putting them in fixtures, logs or command arguments. */
    private String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Authenticate a machine identity without business roles, tenant, session or persisted credentials. */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void acceptsOnlyDedicatedAuthorityOnExactReads(String method) throws Exception {
        start(true, HASH);
        MockHttpServletResponse response = perform(request(method, "/actuator/prometheus", basic(USER, PASSWORD)), (req, res) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertEquals(USER, authentication.getName());
            assertEquals(List.of(PrometheusScrapeSecurity.AUTHORITY), authentication.getAuthorities().stream().map(Object::toString).toList());
            assertNull(authentication.getCredentials());
            assertNull(((org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal()).getPassword());
            assertNull(TenantContext.getTenantId());
            assertNull(req.getAttribute(Const.ATTR_TENANT_ID));
            assertNull(req.getAttribute(Const.ATTR_USER_ID));
            res.getWriter().write("synthetic_metric 1\n");
        });
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("synthetic_metric"));
        assertTrue(context.getBeansOfType(BasicAuthenticationFilter.class).isEmpty());
        assertEquals(1, context.getBeansOfType(UserDetailsService.class).size());
        var filters = context.getBean(FilterChainProxy.class).getFilterChains().getFirst().getFilters();
        assertEquals(1, filters.stream().filter(BasicAuthenticationFilter.class::isInstance).count());
        assertTrue(filters.indexOf(filters.stream().filter(BasicAuthenticationFilter.class::isInstance).findFirst().orElseThrow())
                < filters.indexOf(context.getBean(JwtAuthenticationFilter.class)));
        assertExistingFilterOrder();
    }

    /** Treat every caller tenant hint as irrelevant only on the authenticated machine route. */
    @ParameterizedTest
    @ValueSource(strings = {"", "0", "42", "-1", "bad-tenant", "9999999999999999999999999999"})
    void ignoresForgedAndDuplicateTenantHints(String tenant) throws Exception {
        start(true, HASH);
        MockHttpServletRequest request = request("GET", "/actuator/prometheus", basic(USER, PASSWORD));
        request.addHeader("X-Tenant-ID", tenant);
        request.addHeader("X-Tenant-ID", "different");
        request.addParameter("tenantId", "77");
        TenantContext.setTenantId(999L);
        request.setAttribute(Const.ATTR_TENANT_ID, 999L);
        MDC.put(Const.ATTR_TENANT_ID, "999");
        assertEquals(200, perform(request, (req, res) -> {
            assertNull(TenantContext.getTenantId());
            assertNull(req.getAttribute(Const.ATTR_TENANT_ID));
        }).getStatus());
    }

    /** Deny wrong or ambiguous credentials before any metric handler executes. */
    @Test
    void rejectsInvalidAbsentAndDuplicateCredentials() throws Exception {
        start(true, HASH);
        for (String header : List.of(basic(USER, "wrong"), basic("wrong-user", PASSWORD), "Basic", "Basic !!!",
                "Basic " + Base64.getEncoder().encodeToString("missing-colon".getBytes(StandardCharsets.UTF_8)),
                "Basic\tinvalid", " Basic " + basic(USER, PASSWORD).substring(6),
                basic(USER, PASSWORD) + ", " + basic(USER, PASSWORD), "Basic " + "x".repeat(1100))) {
            MockHttpServletResponse response = perform(request("GET", "/actuator/prometheus", header));
            assertEquals(401, response.getStatus());
            assertFalse(response.getContentAsString().contains("synthetic_metric"));
        }
        for (List<String> headers : List.of(List.of(basic(USER, PASSWORD), basic(USER, PASSWORD)),
                List.of("Bearer admin", basic(USER, PASSWORD)), List.of(basic(USER, PASSWORD), "Bearer admin"))) {
            MockHttpServletRequest request = request("GET", "/actuator/prometheus", headers.getFirst());
            request.addHeader("Authorization", headers.getLast());
            assertEquals(401, perform(request).getStatus());
        }
        assertEquals(400, perform(request("GET", "/actuator/prometheus", null)).getStatus());
        MockHttpServletRequest anonymous = request("GET", "/actuator/prometheus", null);
        anonymous.addHeader("X-Tenant-ID", "42");
        assertEquals(401, perform(anonymous).getStatus());
    }

    /** Refuse to use scrape credentials at other endpoints, descendants or write methods. */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus/child", "/actuator/prometheus/", "/actuator/prometheus-other",
            "/actuator/info", "/actuator/env", "/api/v1/files", "/api/v1/system/logs", "/api/v1/audit"})
    void rejectsOtherProtectedPaths(String path) throws Exception {
        start(true, HASH);
        MockHttpServletRequest request = request("GET", path, basic(USER, PASSWORD));
        request.addHeader("X-Tenant-ID", "42");
        assertEquals(401, perform(request).getStatus());
    }

    /** Preserve existing authentication for non-read methods even on the exact endpoint. */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "OPTIONS"})
    void rejectsWriteAndOptionsMethods(String method) throws Exception {
        start(true, HASH);
        MockHttpServletRequest request = request(method, "/actuator/prometheus", basic(USER, PASSWORD));
        assertEquals(400, perform(request).getStatus());
        request = request(method, "/actuator/prometheus", basic(USER, PASSWORD));
        request.addHeader("X-Tenant-ID", "42");
        assertEquals(401, perform(request).getStatus());
    }

    /** Keep Bearer operator access and reject ordinary users and cross-tenant JWTs. */
    @Test
    void preservesJwtRolesAndTenantValidation() throws Exception {
        start(true, HASH);
        configureJwt("admin");
        configureJwt("monitor");
        configureJwt("user");
        for (String role : List.of("admin", "monitor", "user")) {
            MockHttpServletRequest request = request("GET", "/actuator/prometheus", "Bearer " + role);
            request.addHeader("X-Tenant-ID", "42");
            assertEquals("user".equals(role) ? 403 : 200, perform(request).getStatus());
        }
        MockHttpServletRequest crossTenant = request("GET", "/actuator/prometheus", "Bearer admin");
        crossTenant.addHeader("X-Tenant-ID", "43");
        assertEquals(403, perform(crossTenant).getStatus());
        assertEquals(400, perform(request("GET", "/actuator/prometheus", "Bearer admin")).getStatus());
        JwtUtils jwt = context.getBean(JwtUtils.class);
        DecodedJWT decoded = jwt.resolveJwt("Bearer admin");
        String longBearer = "Bearer " + "synthetic-jwt-segment".repeat(100);
        when(jwt.resolveJwt(longBearer)).thenReturn(decoded);
        MockHttpServletRequest longRequest = request("GET", "/actuator/prometheus", longBearer);
        longRequest.addHeader("X-Tenant-ID", "42");
        assertEquals(200, perform(longRequest).getStatus());
        verify(jwt).resolveJwt(longBearer);
    }

    /** Disable the new feature without changing the prior operator-only endpoint behavior. */
    @Test
    void disabledFeatureKeepsExistingAuthorization() throws Exception {
        start(false, "not-a-hash");
        assertExistingFilterOrder();
        assertEquals(0, context.getBean(FilterChainProxy.class).getFilterChains().getFirst().getFilters().stream()
                .filter(BasicAuthenticationFilter.class::isInstance).count());
        assertEquals(400, perform(request("GET", "/actuator/prometheus", basic(USER, PASSWORD))).getStatus());
        MockHttpServletRequest request = request("GET", "/actuator/prometheus", basic(USER, PASSWORD));
        request.addHeader("X-Tenant-ID", "42");
        assertEquals(401, perform(request).getStatus());
        configureJwt("monitor");
        request = request("GET", "/actuator/prometheus", "Bearer monitor");
        request.addHeader("X-Tenant-ID", "42");
        assertEquals(200, perform(request).getStatus());
        assertThrows(IllegalStateException.class, () -> context.getBean(PrometheusScrapeSecurity.class).createFilter());
    }

    /** Preserve the original custom JWT, request-log and login order without duplicate registration. */
    private void assertExistingFilterOrder() {
        var filters = context.getBean(FilterChainProxy.class).getFilterChains().getFirst().getFilters();
        assertEquals(1, filters.stream().filter(JwtAuthenticationFilter.class::isInstance).count());
        assertEquals(1, filters.stream().filter(RequestLogFilter.class::isInstance).count());
        assertEquals(1, filters.stream().filter(cn.flying.filter.JsonUsernamePasswordAuthenticationFilter.class::isInstance).count());
        int jwt = filters.indexOf(context.getBean(JwtAuthenticationFilter.class));
        int requestLog = filters.indexOf(context.getBean(RequestLogFilter.class));
        int login = filters.indexOf(filters.stream()
                .filter(cn.flying.filter.JsonUsernamePasswordAuthenticationFilter.class::isInstance).findFirst().orElseThrow());
        assertTrue(jwt < requestLog);
        assertTrue(requestLog < login);
    }

    /** Prove the global manager continues to call AccountService for JSON and form logins. */
    @Test
    void preservesApplicationLoginManagerAndLogout() throws Exception {
        start(true, HASH);
        AccountService accounts = context.getBean(AccountService.class);
        when(accounts.loadUserByUsername("business-user")).thenAnswer(invocation -> User.withUsername("business-user")
                .password(new BCryptPasswordEncoder(4).encode("business-password")).roles("user").build());
        when(accounts.loadUserByUsername(USER)).thenThrow(new UsernameNotFoundException("No business account"));
        // A null JWT is an existing handled login-success branch, avoiding irrelevant DTO serialization.
        cn.flying.dao.dto.Account account = new cn.flying.dao.dto.Account();
        account.setId(7L);
        account.setTenantId(42L);
        account.setUsername("business-user");
        when(accounts.findAccountByNameOrEmail("business-user")).thenReturn(account);
        for (boolean json : List.of(true, false)) {
            MockHttpServletRequest request = request("POST", "/api/v1/auth/login", null);
            request.addHeader("X-Tenant-ID", "42");
            if (json) {
                request.setContentType("application/json");
                request.setContent("{\"username\":\"business-user\",\"password\":\"business-password\"}".getBytes(StandardCharsets.UTF_8));
            } else {
                request.setContentType("application/x-www-form-urlencoded");
                request.addParameter("username", "business-user");
                request.addParameter("password", "business-password");
            }
            assertEquals(200, perform(request).getStatus());
        }
        verify(accounts, times(2)).findAccountByNameOrEmail("business-user");
        AuthenticationManager manager = context.getBean(AuthenticationConfiguration.class).getAuthenticationManager();
        assertThrows(org.springframework.security.core.AuthenticationException.class, () -> manager.authenticate(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.unauthenticated(USER, PASSWORD)));
        verify(accounts).loadUserByUsername(USER);
        MockHttpServletRequest scrapeLogin = request("POST", "/api/v1/auth/login", basic(USER, PASSWORD));
        scrapeLogin.addHeader("X-Tenant-ID", "42");
        scrapeLogin.addParameter("username", USER);
        scrapeLogin.addParameter("password", PASSWORD);
        assertEquals(401, perform(scrapeLogin).getStatus());
        MockHttpServletRequest logout = request("POST", "/api/v1/auth/logout", "Bearer logout-token");
        logout.addHeader("X-Tenant-ID", "42");
        when(context.getBean(JwtUtils.class).invalidateJwt("Bearer logout-token")).thenReturn(true);
        assertEquals(200, perform(logout).getStatus());
        verify(context.getBean(JwtUtils.class)).invalidateJwt("Bearer logout-token");
    }

    /** Clear machine authority on exceptions and do not reuse it on a later anonymous request. */
    @Test
    void clearsContextsAfterSuccessAndFailure() throws Exception {
        start(true, HASH);
        assertEquals(200, perform(request("GET", "/actuator/prometheus", basic(USER, PASSWORD))).getStatus());
        AtomicBoolean entered = new AtomicBoolean();
        assertThrows(ServletException.class, () -> perform(request("GET", "/actuator/prometheus", basic(USER, PASSWORD)), (req, res) -> {
            entered.set(true);
            throw new ServletException("synthetic endpoint failure");
        }));
        assertTrue(entered.get());
        assertNull(TenantContext.getTenantId());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(400, perform(request("GET", "/actuator/prometheus", null)).getStatus());
    }

    /** Apply credential rotation only through fresh backend configuration and reject the old password. */
    @Test
    void rotationInvalidatesOldCredential() throws Exception {
        start(true, HASH);
        assertEquals(200, perform(request("GET", "/actuator/prometheus", basic(USER, PASSWORD))).getStatus());
        context.close();
        String replacement = "SyntheticRotatedPassword-81594";
        start(true, new BCryptPasswordEncoder(4).encode(replacement));
        assertEquals(401, perform(request("GET", "/actuator/prometheus", basic(USER, PASSWORD))).getStatus());
        assertEquals(200, perform(request("GET", "/actuator/prometheus", basic(USER, replacement))).getStatus());
    }

    /** Reject incomplete/plaintext/noop startup values without echoing any supplied secret. */
    @Test
    void rejectsInvalidConfigurationWithFixedDiagnostics() {
        for (String hash : List.of("", PASSWORD, "{noop}" + PASSWORD, "$2a$99$" + "x".repeat(53))) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new PrometheusScrapeSecurity(true, USER, hash));
            assertFalse(failure.getMessage().contains(PASSWORD));
            assertNull(failure.getCause());
        }
        for (String username : List.of("", " ", "user:name", "line\nbreak")) {
            assertThrows(IllegalArgumentException.class, () -> new PrometheusScrapeSecurity(true, username, HASH));
        }
        assertThrows(IllegalArgumentException.class, () -> new PrometheusScrapeSecurity(true, null, HASH));
        assertThrows(IllegalArgumentException.class, () -> new PrometheusScrapeSecurity(true, USER, null));
        Exception startupFailure = assertThrows(Exception.class, () -> start(true, PASSWORD));
        for (Throwable cause = startupFailure; cause != null; cause = cause.getCause()) {
            assertFalse(String.valueOf(cause.getMessage()).contains(PASSWORD));
            assertFalse(String.valueOf(cause.getMessage()).contains(HASH));
        }
    }

    /** Refuse path normalization aliases and servlet-path disagreement at the policy boundary. */
    @Test
    void matcherRequiresRawExactContextRelativePath() {
        PrometheusScrapeSecurity policy = new PrometheusScrapeSecurity(true, USER, HASH);
        for (String path : List.of("/actuator/prometheus/", "/actuator/prometheus;x=1", "/actuator//prometheus",
                "/actuator/%70rometheus", "/actuator/../actuator/prometheus", "/actuator/prometheus%2fchild")) {
            MockHttpServletRequest request = request("GET", path, basic(USER, PASSWORD));
            request.setServletPath("/actuator/prometheus");
            assertFalse(policy.matches(request));
            assertFalse(policy.isScrapeAttempt(request));
        }
        assertTrue(policy.matches(request("GET", "/actuator/prometheus", basic(USER, PASSWORD))));
        MockHttpServletRequest rootContext = new MockHttpServletRequest("HEAD", "/actuator/prometheus");
        assertTrue(policy.matches(rootContext));
        assertFalse(policy.isScrapeAttempt(rootContext));
        rootContext.addHeader("Authorization", "Bearer unchanged");
        assertFalse(policy.isScrapeAttempt(rootContext));
        assertFalse(policy.isScrapeAttempt(request("GET", "/actuator/prometheus", "BasicOther token")));
    }

    /** Capture real framework/application logging, including TRACE, using synthetic secret sentinels. */
    @Test
    void neverLogsCredentialsOrUntrustedUsernames() throws Exception {
        start(true, HASH);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger filterLogger = (Logger) LoggerFactory.getLogger("cn.flying.config.PrometheusScrapeSecurity$1");
        Logger providerLogger = (Logger) LoggerFactory.getLogger(org.springframework.security.authentication.dao.DaoAuthenticationProvider.class);
        Logger requestLogger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
        Level oldFilter = filterLogger.getLevel();
        Level oldProvider = providerLogger.getLevel();
        Level oldRequest = requestLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        filterLogger.setLevel(Level.TRACE);
        providerLogger.setLevel(Level.TRACE);
        requestLogger.setLevel(Level.INFO);
        try {
            for (String header : List.of(basic(USER, PASSWORD), basic(USER, "wrong"), basic(PASSWORD, PASSWORD),
                    "Basic " + HASH, "Basic !!!")) {
                perform(request("GET", "/actuator/prometheus", header));
            }
            String logs = appender.list.stream().map(event -> event.getFormattedMessage() + " "
                    + java.util.Arrays.toString(event.getArgumentArray()) + " "
                    + (event.getThrowableProxy() == null ? "" : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy())))
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(logs.contains(PASSWORD));
            assertFalse(logs.contains(HASH));
            assertFalse(logs.contains(basic(USER, PASSWORD)));
            assertTrue(logs.contains("请求处理耗时"));
        } finally {
            filterLogger.setLevel(oldFilter);
            providerLogger.setLevel(oldProvider);
            requestLogger.setLevel(oldRequest);
            root.detachAppender(appender);
            appender.stop();
        }
    }

    /** Supply decoded tokens at the JWT boundary while exercising the production role/tenant filter. */
    private void configureJwt(String role) {
        JwtUtils jwt = context.getBean(JwtUtils.class);
        DecodedJWT token = mock(DecodedJWT.class);
        when(jwt.resolveJwt("Bearer " + role)).thenReturn(token);
        when(jwt.toId(token)).thenReturn(7L);
        when(jwt.toTenantId(token)).thenReturn(42L);
        when(jwt.toRole(token)).thenReturn(role);
        when(jwt.toUser(token)).thenReturn(User.withUsername("business-" + role).password("unused").roles(role).build());
    }

    /** Supply only application dependencies; the scrape manager must stay private to its filter. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @Import({SecurityConfiguration.class, PrometheusScrapeSecurity.class, TenantFilter.class,
            JwtAuthenticationFilter.class, RequestLogFilter.class})
    static class TestConfiguration {
        /** Provide the existing application's user lookup boundary. */
        @Bean AccountService accountService() { return mock(AccountService.class); }
        /** Use the application's existing password encoder type for global logins. */
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(4); }
        /** Support the real JSON login filter without changing its implementation. */
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
