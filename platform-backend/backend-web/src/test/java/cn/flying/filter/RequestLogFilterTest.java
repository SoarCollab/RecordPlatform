package cn.flying.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("RequestLogFilter Unit Tests")
class RequestLogFilterTest {

    @InjectMocks
    private RequestLogFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain filterChain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = new MockFilterChain();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Nested
    @DisplayName("Skip Conditions Tests")
    class SkipConditionsTests {

        @Test
        @DisplayName("Should skip favicon.ico")
        void shouldSkipFavicon() throws ServletException, IOException {
            request.setServletPath("/favicon.ico");

            filter.doFilter(request, response, filterChain);

            assertThat(MDC.get(Const.ATTR_REQ_ID)).isNull();
        }

        @Test
        @DisplayName("Should skip webjars")
        void shouldSkipWebjars() throws ServletException, IOException {
            request.setServletPath("/webjars/jquery/3.0.0/jquery.min.js");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should skip swagger-ui")
        void shouldSkipSwaggerUi() throws ServletException, IOException {
            request.setServletPath("/swagger-ui/index.html");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should skip doc.html")
        void shouldSkipDocHtml() throws ServletException, IOException {
            request.setServletPath("/doc.html");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should skip api-docs")
        void shouldSkipApiDocs() throws ServletException, IOException {
            request.setServletPath("/v3/api-docs");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should skip system logs endpoint")
        void shouldSkipSystemLogs() throws ServletException, IOException {
            request.setServletPath("/api/system/logs");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("SSE Request Handling Tests")
    class SseRequestTests {

        @Test
        @DisplayName("Should detect SSE request by Accept header")
        void shouldDetectSseByAcceptHeader() throws ServletException, IOException {
            request.setServletPath("/api/v1/events");
            request.addHeader("Accept", "text/event-stream");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should detect SSE request by URL path")
        void shouldDetectSseByUrlPath() throws ServletException, IOException {
            request.setServletPath("/api/v1/sse/connect");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Regular Request Handling Tests")
    class RegularRequestTests {

        @Test
        @DisplayName("Should process regular API request")
        void shouldProcessRegularApiRequest() throws ServletException, IOException {
            request.setServletPath("/api/v1/files");
            request.setMethod("GET");
            request.setRemoteAddr("192.168.1.1");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should add request parameters to log")
        void shouldAddRequestParametersToLog() throws ServletException, IOException {
            request.setServletPath("/api/v1/files");
            request.setMethod("GET");
            request.setParameter("page", "1");
            request.setParameter("size", "10");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Sensitive Parameter Masking Tests")
    class SensitiveParameterTests {

        @Test
        @DisplayName("Should mask password parameter")
        void shouldMaskPasswordParameter() throws ServletException, IOException {
            request.setServletPath("/api/v1/auth/login");
            request.setMethod("POST");
            request.setParameter("username", "testuser");
            request.setParameter("password", "secret123");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should mask token parameter")
        void shouldMaskTokenParameter() throws ServletException, IOException {
            request.setServletPath("/api/v1/auth/tokens/refresh");
            request.setMethod("POST");
            request.setParameter("token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should mask apiKey parameter")
        void shouldMaskApiKeyParameter() throws ServletException, IOException {
            request.setServletPath("/api/v1/integration");
            request.setMethod("POST");
            request.setParameter("apiKey", "sk-test-123456");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        /**
         * 验证上传会话 clientId 参数会被敏感参数规则识别。
         */
        @Test
        @DisplayName("Should treat upload clientId parameter as sensitive")
        void shouldTreatUploadClientIdParameterAsSensitive() {
            Boolean result = ReflectionTestUtils.invokeMethod(filter, "isSensitiveParam", "clientId");

            assertThat(result).isTrue();
        }

        /**
         * 验证 grant 引用和下载会话即使被误放进查询参数也不会进入日志。
         */
        @Test
        @DisplayName("Should treat key grant reference and session as sensitive")
        void shouldTreatKeyGrantFieldsAsSensitive() {
            Boolean grantReference = ReflectionTestUtils.invokeMethod(
                    filter, "isSensitiveParam", "grantReference");
            Boolean sessionId = ReflectionTestUtils.invokeMethod(filter, "isSensitiveParam", "sessionId");

            assertThat(grantReference).isTrue();
            assertThat(sessionId).isTrue();
        }

        @Test
        @DisplayName("Should mask new_password parameter")
        void shouldMaskNewPasswordParameter() throws ServletException, IOException {
            request.setServletPath("/api/v1/users/password");
            request.setMethod("POST");
            request.setParameter("old_password", "oldpass");
            request.setParameter("new_password", "newpass");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should not mask non-sensitive parameters")
        void shouldNotMaskNonSensitiveParameters() throws ServletException, IOException {
            request.setServletPath("/api/v1/files");
            request.setMethod("GET");
            request.setParameter("filename", "document.pdf");
            request.setParameter("category", "documents");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("MDC Context Tests")
    class MdcContextTests {

        @Test
        @DisplayName("Should clear MDC after request processing")
        void shouldClearMdcAfterRequest() throws ServletException, IOException {
            request.setServletPath("/api/v1/files");
            request.setMethod("GET");

            filter.doFilter(request, response, filterChain);

            assertThat(MDC.get(Const.ATTR_REQ_ID)).isNull();
        }

        @Test
        @DisplayName("Should clear MDC even when exception occurs")
        void shouldClearMdcOnException() throws ServletException, IOException {
            request.setServletPath("/api/v1/files");
            request.setMethod("GET");
            MockFilterChain errorChain = new MockFilterChain() {
                @Override
                public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) 
                        throws ServletException {
                    throw new ServletException("Test exception");
                }
            };

            try {
                filter.doFilter(request, response, errorChain);
            } catch (ServletException e) {
            }

            assertThat(MDC.get(Const.ATTR_REQ_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("Response Logging Tests")
    class ResponseLoggingTests {

        @Test
        @DisplayName("Should wrap response for caching")
        void shouldWrapResponseForCaching() throws ServletException, IOException {
            request.setServletPath("/api/v1/conversations");
            request.setMethod("GET");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getResponse()).isInstanceOf(ContentCachingResponseWrapper.class);
        }

        @Test
        @DisplayName("Should not wrap auth login responses")
        void shouldNotWrapAuthLoginResponses() throws ServletException, IOException {
            request.setRequestURI("/api/v1/auth/login");
            request.setServletPath("/api/v1/auth/login");
            request.setMethod("POST");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getResponse()).isNotInstanceOf(ContentCachingResponseWrapper.class);
        }

        @Test
        @DisplayName("Should not wrap file API responses")
        void shouldNotWrapFileApiResponses() throws ServletException, IOException {
            request.setRequestURI("/api/v1/files/hash/test-hash/addresses");
            request.setServletPath("/api/v1/files/hash/test-hash/addresses");
            request.setMethod("GET");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getResponse()).isNotInstanceOf(ContentCachingResponseWrapper.class);
        }

        @Test
        @DisplayName("Should not wrap chunk responses")
        void shouldNotWrapChunkResponses() throws ServletException, IOException {
            request.setRequestURI("/api/v1/files/hash/test-hash/chunks");
            request.setServletPath("/api/v1/files/hash/test-hash/chunks");
            request.setMethod("GET");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getResponse()).isNotInstanceOf(ContentCachingResponseWrapper.class);
        }

        @Test
        @DisplayName("Should not wrap decrypt info responses")
        void shouldNotWrapDecryptInfoResponses() throws ServletException, IOException {
            request.setRequestURI("/api/v1/public/shares/share-code/files/test-hash/decrypt-info");
            request.setServletPath("/api/v1/public/shares/share-code/files/test-hash/decrypt-info");
            request.setMethod("GET");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getResponse()).isNotInstanceOf(ContentCachingResponseWrapper.class);
        }

        /**
         * 验证瞬时密钥消费响应永远不会进入常规响应缓存或日志预览。
         */
        @Test
        @DisplayName("Should not wrap key grant consume responses")
        void shouldNotWrapKeyGrantConsumeResponses() throws ServletException, IOException {
            request.setRequestURI("/api/v1/files/key-grants/consume");
            request.setServletPath("/api/v1/files/key-grants/consume");
            request.setMethod("POST");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getResponse()).isNotInstanceOf(ContentCachingResponseWrapper.class);
            String content = ReflectionTestUtils.invokeMethod(
                    filter,
                    "buildResponseLogContent",
                    request,
                    "application/json",
                    HttpServletResponse.SC_OK,
                    "{\"initialKey\":\"raw-secret\"}".getBytes(StandardCharsets.UTF_8)
            );
            assertThat(content).isEqualTo("<skipped>");
        }

        /**
         * 验证 manifest 治理响应中的证据摘要和对象身份不会进入常规响应日志。
         */
        @Test
        @DisplayName("Should not wrap manifest governance responses")
        void shouldNotWrapManifestGovernanceResponses() throws ServletException, IOException {
            request.setRequestURI("/api/v1/admin/manifest-backfill-runs/run-id/items");
            request.setServletPath("/api/v1/admin/manifest-backfill-runs/run-id/items");
            request.setMethod("GET");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getResponse()).isNotInstanceOf(ContentCachingResponseWrapper.class);
            String content = ReflectionTestUtils.invokeMethod(
                    filter,
                    "buildResponseLogContent",
                    request,
                    "application/json",
                    HttpServletResponse.SC_OK,
                    "{\"evidenceDigest\":\"sha256:secret\"}".getBytes(StandardCharsets.UTF_8)
            );
            assertThat(content).isEqualTo("<skipped>");
        }

        /**
         * 验证编码字面量和矩阵参数不能绕过公开分享响应体的缓存与日志禁用规则。
         */
        @Test
        @DisplayName("Should not wrap encoded public share responses")
        void shouldNotWrapEncodedPublicShareResponses() throws ServletException, IOException {
            String path = "/api/v1/public/sh%61res;x=1/%2E/share-secret/f%69les;v=2//hash-secret/chunks";
            request.setRequestURI(path);
            request.setServletPath(path);
            request.setMethod("GET");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getResponse()).isNotInstanceOf(ContentCachingResponseWrapper.class);
            String content = ReflectionTestUtils.invokeMethod(
                    filter,
                    "buildResponseLogContent",
                    request,
                    "application/json",
                    HttpServletResponse.SC_OK,
                    "chunk-response-secret".getBytes(StandardCharsets.UTF_8)
            );
            assertThat(content).isEqualTo("<skipped>");
        }

        /**
         * 验证日志路径会脱敏分享码、文件哈希和交易哈希。
         */
        @Test
        @DisplayName("Should mask sensitive identifiers in logged paths")
        void shouldMaskSensitiveIdentifiersInLoggedPaths() {
            String sanitized = ReflectionTestUtils.invokeMethod(
                    filter,
                    "sanitizePathForLog",
                    "/api/v1/public/shares/ABC123/files/hash-secret/chunks"
            );

            assertThat(sanitized).isEqualTo("/api/v1/public/shares/***/files/***/chunks");

            String fileHashRoute = ReflectionTestUtils.invokeMethod(
                    filter,
                    "sanitizePathForLog",
                    "/api/v1/files/hash/hash-secret/chunks"
            );
            assertThat(fileHashRoute).isEqualTo("/api/v1/files/hash/***/chunks");

            String uploadSessionRoute = ReflectionTestUtils.invokeMethod(
                    filter,
                    "sanitizePathForLog",
                    "/api/v1/upload-sessions/client-secret/complete"
            );
            assertThat(uploadSessionRoute).isEqualTo("/api/v1/upload-sessions/***/complete");
        }

        /**
         * 验证响应体日志预览会复用敏感字段脱敏规则，避免令牌进入日志。
         */
        @Test
        @DisplayName("Should mask sensitive JSON fields in response log content")
        void shouldMaskSensitiveJsonFieldsInResponseLogContent() {
            request.setRequestURI("/api/v1/conversations");
            byte[] body = "{\"token\":\"secret-token\",\"message\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

            String content = ReflectionTestUtils.invokeMethod(
                    filter,
                    "buildResponseLogContent",
                    request,
                    "application/json",
                    HttpServletResponse.SC_OK,
                    body
            );

            assertThat(content).contains("\"token\":\"******\"");
            assertThat(content).contains("\"message\":\"ok\"");
            assertThat(content).doesNotContain("secret-token");
        }
    }

    /** Platform traffic preserves client bytes while omitting every request parameter and response payload. */
    @ParameterizedTest(name = "platform route {0}")
    @MethodSource("platformRequests")
    void shouldOmitPlatformPayloadsWithoutWrappingResponse(String path, String contextPath) throws Exception {
        configurePlatformRequest(path, contextPath);
        byte[] requestBody = "{\"reason\":\"platform-body-sentinel\"}".getBytes(StandardCharsets.UTF_8);
        byte[] responseBody = "{\"details\":\"platform-response-sentinel\"}".getBytes(StandardCharsets.UTF_8);
        request.setContent(requestBody);
        request.setParameter("reason", "platform-query-sentinel");
        request.setParameter("platform-query-name-sentinel", "ignored");
        request.setAttribute(Const.ATTR_USER_ID, 41L);
        User principal = new User("platform-operator", "unused", List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_platform_admin")));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        MockHttpServletRequest observedRequest = spy(request);
        MDC.put("traceId", "platform-request-trace");

        try (CapturedLogs logs = new CapturedLogs()) {
            filter.doFilter(observedRequest, response, (incoming, outgoing) -> {
                assertThat(incoming).isSameAs(observedRequest);
                assertThat(incoming.getInputStream().readAllBytes()).isEqualTo(requestBody);
                assertThat(outgoing).isSameAs(response);
                assertThat(MDC.get(Const.ATTR_REQ_ID)).isNotBlank();
                HttpServletResponse clientResponse = (HttpServletResponse) outgoing;
                clientResponse.setStatus(201);
                clientResponse.setContentType("application/json");
                clientResponse.setHeader("X-Operation", "completed");
                clientResponse.getOutputStream().write(responseBody);
            });

            assertThat(response.getContentAsByteArray()).isEqualTo(responseBody);
            assertThat(response.getStatus()).isEqualTo(201);
            assertThat(response.getHeader("X-Operation")).isEqualTo("completed");
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("请求参数列表: <omitted>"))
                    .anyMatch(message -> message.contains("响应结果: <skipped>"));
            assertNoLogSentinels(logs.events(), "platform-body-sentinel", "platform-response-sentinel",
                    "platform-query-sentinel", "platform-query-name-sentinel");
        }

        verify(observedRequest, never()).getParameterMap();
        assertThat(MDC.get(Const.ATTR_REQ_ID)).isNull();
        assertThat(MDC.get("traceId")).isEqualTo("platform-request-trace");
    }

    /** Platform failures retain the original exception graph and partial client bytes without logging either. */
    @ParameterizedTest
    @MethodSource("platformFailures")
    void shouldPreservePlatformFailuresAndPartialResponses(Exception failure) throws Exception {
        configurePlatformRequest("/api/v1/platform/configuration", "");
        request.setParameter("reason", "platform-query-sentinel");
        byte[] partialBody = "platform-partial-response-sentinel".getBytes(StandardCharsets.UTF_8);
        failure.addSuppressed(new IllegalArgumentException("platform-suppressed-sentinel"));

        try (CapturedLogs logs = new CapturedLogs()) {
            assertThatThrownBy(() -> filter.doFilter(request, response, (incoming, outgoing) -> {
                assertThat(outgoing).isSameAs(response);
                outgoing.getOutputStream().write(partialBody);
                if (failure instanceof ServletException servletFailure) {
                    throw servletFailure;
                }
                throw (IOException) failure;
            })).isSameAs(failure);

            assertThat(response.getContentAsByteArray()).isEqualTo(partialBody);
            assertThat(failure.getCause()).hasMessage("platform-cause-sentinel");
            assertThat(failure.getSuppressed()).singleElement().satisfies(suppressed ->
                    assertThat(suppressed).hasMessage("platform-suppressed-sentinel"));
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("请求参数列表: <omitted>"));
            assertNoLogSentinels(logs.events(), "platform-query-sentinel", "platform-partial-response-sentinel",
                    "platform-exception-sentinel", "platform-cause-sentinel", "platform-suppressed-sentinel");
        }

        assertThat(MDC.get(Const.ATTR_REQ_ID)).isNull();
    }

    /** Requesting event-stream handling does not bypass the platform parameter omission boundary. */
    @Test
    void shouldOmitPlatformParametersWhenClientRequestsEventStream() throws Exception {
        configurePlatformRequest("/api/v1/platform/overview", "");
        request.addHeader("Accept", "text/event-stream");
        request.setParameter("reason", "platform-query-sentinel");
        byte[] body = "data: platform-stream-sentinel\n\n".getBytes(StandardCharsets.UTF_8);

        try (CapturedLogs logs = new CapturedLogs()) {
            filter.doFilter(request, response, (incoming, outgoing) -> {
                assertThat(outgoing).isSameAs(response);
                outgoing.getOutputStream().write(body);
            });

            assertThat(response.getContentAsByteArray()).isEqualTo(body);
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("请求参数列表: <omitted>"))
                    .anyMatch(message -> message.contains("SSE连接保持"));
            assertNoLogSentinels(logs.events(), "platform-query-sentinel", "platform-stream-sentinel");
        }
        assertThat(MDC.get(Const.ATTR_REQ_ID)).isNull();
    }

    /** Ordinary and similarly prefixed routes still cache, log safe data and return unmodified responses. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/platforms/summary", "/api/v1/platform-admin/overview", "/api/v1/conversations"})
    void shouldKeepOrdinaryRequestAndResponseLogging(String path) throws Exception {
        request.setRequestURI(path);
        request.setServletPath(path);
        request.setMethod("GET");
        request.setParameter("query", "ordinary-query-marker");
        byte[] body = "{\"message\":\"ordinary-response-marker\"}".getBytes(StandardCharsets.UTF_8);

        try (CapturedLogs logs = new CapturedLogs()) {
            filter.doFilter(request, response, (incoming, outgoing) -> {
                assertThat(outgoing).isInstanceOf(ContentCachingResponseWrapper.class);
                outgoing.setContentType("application/json");
                outgoing.getOutputStream().write(body);
            });

            assertThat(response.getContentAsByteArray()).isEqualTo(body);
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("ordinary-query-marker"))
                    .anyMatch(message -> message.contains("ordinary-response-marker"));
        }
    }

    /** The public preview hook also suppresses platform payloads if another filter supplied a cached wrapper. */
    @Test
    void shouldOmitPlatformBodyFromExplicitResponsePreview() throws IOException {
        configurePlatformRequest("/api/v1/platform/overview", "");
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        byte[] body = "{\"details\":\"platform-preview-sentinel\"}".getBytes(StandardCharsets.UTF_8);
        wrapper.setContentType("application/json");
        wrapper.getOutputStream().write(body);

        try (CapturedLogs logs = new CapturedLogs()) {
            filter.logRequestEnd(request, wrapper, System.currentTimeMillis());

            assertThat(wrapper.getContentAsByteArray()).isEqualTo(body);
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("响应结果: <skipped>"));
            assertNoLogSentinels(logs.events(), "platform-preview-sentinel");
        }
    }

    /** Configures the URI and servlet path as the container does for platform requests. */
    private void configurePlatformRequest(String path, String contextPath) {
        request.setRequestURI(path);
        request.setContextPath(contextPath);
        request.setServletPath(path.substring(contextPath.length()));
        request.setMethod("PUT");
        request.setContentType("application/json");
    }

    /** Supplies canonical and normalized platform routes, including the production context path. */
    private static Stream<Arguments> platformRequests() {
        return Stream.of(
                Arguments.of("/api/v1/platform", ""),
                Arguments.of("/api/v1/platform/tenants", ""),
                Arguments.of("/record-platform/api/v1/platform/configuration", "/record-platform"),
                Arguments.of("/api/v1/platf%6frm;view=1/tenants", ""),
                Arguments.of("/record-platform/api/v1/platform;view=1/%2E/tenants", "/record-platform"));
    }

    /** Creates the checked exception kinds allowed by the servlet filter contract. */
    private static Stream<Exception> platformFailures() {
        return Stream.of(
                new ServletException("platform-exception-sentinel", new IllegalStateException("platform-cause-sentinel")),
                new IOException("platform-exception-sentinel", new IllegalStateException("platform-cause-sentinel")));
    }

    /** Checks both rendered messages and raw logging arguments so deferred formatting cannot hide a leak. */
    private void assertNoLogSentinels(List<ILoggingEvent> events, String... sentinels) {
        assertThat(events).isNotEmpty();
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage()).doesNotContain(sentinels);
            assertThat(event.getThrowableProxy()).isNull();
            if (event.getArgumentArray() != null) {
                for (Object argument : event.getArgumentArray()) {
                    assertThat(String.valueOf(argument)).doesNotContain(sentinels);
                }
            }
        }
    }

    /** Captures only filter events and restores the shared logger even if an assertion fails. */
    private static final class CapturedLogs implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
        private final Level previousLevel = logger.getLevel();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        /** Attaches a temporary INFO appender before exercising the real filter. */
        private CapturedLogs() {
            appender.start();
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
        }

        /** Returns the collected events without rendering their argument objects. */
        private List<ILoggingEvent> events() {
            return appender.list;
        }

        /** Detaches and stops the temporary appender and restores the logger's previous level. */
        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Nested
    @DisplayName("Filter Chain Continuation Tests")
    class FilterChainTests {

        @Test
        @DisplayName("Should continue filter chain for all requests")
        void shouldContinueFilterChain() throws ServletException, IOException {
            request.setServletPath("/api/v1/files");
            request.setMethod("GET");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
            assertThat(filterChain.getResponse()).isNotNull();
        }

        @Test
        @DisplayName("Should continue filter chain for skipped URLs")
        void shouldContinueFilterChainForSkippedUrls() throws ServletException, IOException {
            request.setServletPath("/favicon.ico");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }
    }
}
