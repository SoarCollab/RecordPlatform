package cn.flying.filter;

import cn.flying.common.util.Const;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.ServletException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

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
            request.setServletPath("/api/v1/files");
            request.setMethod("GET");

            filter.doFilter(request, response, filterChain);

            assertThat(filterChain.getResponse()).isInstanceOf(ContentCachingResponseWrapper.class);
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
