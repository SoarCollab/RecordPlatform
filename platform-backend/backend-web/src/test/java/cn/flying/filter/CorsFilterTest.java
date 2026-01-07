package cn.flying.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CorsFilter Unit Tests
 *
 * Tests for CORS filter including:
 * - Exact origin matching
 * - Subdomain wildcard matching (*.example.com)
 * - Production environment wildcard blocking
 * - Development environment wildcard allowing
 * - CORS header setting
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorsFilter Tests")
class CorsFilterTest {

    private CorsFilter filter;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new CorsFilter();
        // Set default configuration
        ReflectionTestUtils.setField(filter, "credentials", true);
        ReflectionTestUtils.setField(filter, "methods", "GET,POST,PUT,DELETE,OPTIONS");
    }

    private void initFilterWithOrigins(String origins, String profile) {
        ReflectionTestUtils.setField(filter, "origin", origins);
        ReflectionTestUtils.setField(filter, "activeProfile", profile);
        filter.init();
    }

    @Nested
    @DisplayName("Exact Origin Matching Tests")
    class ExactOriginMatchingTests {

        @Test
        @DisplayName("should allow exact origin match")
        void shouldAllowExactOriginMatch() throws ServletException, IOException {
            initFilterWithOrigins("https://example.com,https://app.example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://example.com");
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should reject non-matching origin")
        void shouldRejectNonMatchingOrigin() throws ServletException, IOException {
            initFilterWithOrigins("https://example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://malicious.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should handle request without Origin header")
        void shouldHandleRequestWithoutOriginHeader() throws ServletException, IOException {
            initFilterWithOrigins("https://example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            // No Origin header
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should allow multiple configured origins")
        void shouldAllowMultipleConfiguredOrigins() throws ServletException, IOException {
            initFilterWithOrigins("https://example.com,https://app.example.com,https://admin.example.com", "local");

            // Test first origin
            MockHttpServletRequest request1 = new MockHttpServletRequest("GET", "/api/v1/files");
            request1.addHeader("Origin", "https://example.com");
            MockHttpServletResponse response1 = new MockHttpServletResponse();
            filter.doFilter(request1, response1, filterChain);
            assertThat(response1.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://example.com");

            // Test second origin
            MockHttpServletRequest request2 = new MockHttpServletRequest("GET", "/api/v1/files");
            request2.addHeader("Origin", "https://app.example.com");
            MockHttpServletResponse response2 = new MockHttpServletResponse();
            filter.doFilter(request2, response2, filterChain);
            assertThat(response2.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");

            // Test third origin
            MockHttpServletRequest request3 = new MockHttpServletRequest("GET", "/api/v1/files");
            request3.addHeader("Origin", "https://admin.example.com");
            MockHttpServletResponse response3 = new MockHttpServletResponse();
            filter.doFilter(request3, response3, filterChain);
            assertThat(response3.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://admin.example.com");
        }
    }

    @Nested
    @DisplayName("Subdomain Wildcard Matching Tests")
    class SubdomainWildcardMatchingTests {

        @Test
        @DisplayName("should allow subdomain with wildcard pattern")
        void shouldAllowSubdomainWithWildcardPattern() throws ServletException, IOException {
            initFilterWithOrigins("*.example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://app.example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");
        }

        @Test
        @DisplayName("should allow deep subdomain with wildcard pattern")
        void shouldAllowDeepSubdomainWithWildcardPattern() throws ServletException, IOException {
            initFilterWithOrigins("*.example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://deep.nested.example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://deep.nested.example.com");
        }

        @Test
        @DisplayName("should reject suffix-matching domain that is not subdomain")
        void shouldRejectSuffixMatchingDomainThatIsNotSubdomain() throws ServletException, IOException {
            initFilterWithOrigins("*.example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            // "notexample.com" ends with "example.com" but is not a subdomain
            request.addHeader("Origin", "https://notexample.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }

        @Test
        @DisplayName("should reject malicious subdomain attack")
        void shouldRejectMaliciousSubdomainAttack() throws ServletException, IOException {
            initFilterWithOrigins("*.example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            // Trying to bypass with evilexample.com
            request.addHeader("Origin", "https://evilexample.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }
    }

    @Nested
    @DisplayName("Production Environment Wildcard Blocking Tests")
    class ProductionWildcardBlockingTests {

        @Test
        @DisplayName("should block wildcard in production environment")
        void shouldBlockWildcardInProductionEnvironment() throws ServletException, IOException {
            initFilterWithOrigins("*", "prod");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://any-origin.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            // Should not set CORS header because wildcard is blocked in production
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should block wildcard in production profile variant")
        void shouldBlockWildcardInProductionProfileVariant() throws ServletException, IOException {
            initFilterWithOrigins("*", "production");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://any-origin.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }

        @Test
        @DisplayName("should remove wildcard from allowed origins in production")
        void shouldRemoveWildcardFromAllowedOriginsInProduction() {
            initFilterWithOrigins("*,https://example.com", "prod");

            Set<String> allowedOrigins = (Set<String>) ReflectionTestUtils.getField(filter, "allowedOrigins");
            assertThat(allowedOrigins).doesNotContain("*");
            assertThat(allowedOrigins).contains("https://example.com");
        }
    }

    @Nested
    @DisplayName("Development Environment Wildcard Tests")
    class DevelopmentWildcardTests {

        @Test
        @DisplayName("should allow wildcard in local environment")
        void shouldAllowWildcardInLocalEnvironment() throws ServletException, IOException {
            initFilterWithOrigins("*", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://any-origin.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://any-origin.com");
        }

        @Test
        @DisplayName("should allow wildcard in dev environment")
        void shouldAllowWildcardInDevEnvironment() throws ServletException, IOException {
            initFilterWithOrigins("*", "dev");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://localhost:3000");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://localhost:3000");
        }

        @Test
        @DisplayName("should allow wildcard in test environment")
        void shouldAllowWildcardInTestEnvironment() throws ServletException, IOException {
            initFilterWithOrigins("*", "test");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "http://test-server.internal");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://test-server.internal");
        }
    }

    @Nested
    @DisplayName("CORS Headers Tests")
    class CorsHeadersTests {

        @Test
        @DisplayName("should set all CORS headers when origin is allowed")
        void shouldSetAllCorsHeadersWhenOriginIsAllowed() throws ServletException, IOException {
            initFilterWithOrigins("https://example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://example.com");
            assertThat(response.getHeader("Access-Control-Allow-Methods")).isEqualTo("GET,POST,PUT,DELETE,OPTIONS");
            assertThat(response.getHeader("Access-Control-Allow-Headers")).isEqualTo("Authorization, Content-Type");
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        }

        @Test
        @DisplayName("should not set credentials header when credentials is false")
        void shouldNotSetCredentialsHeaderWhenCredentialsIsFalse() throws ServletException, IOException {
            ReflectionTestUtils.setField(filter, "credentials", false);
            initFilterWithOrigins("https://example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://example.com");
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
        }

        @Test
        @DisplayName("should not set any CORS headers when origin is not allowed")
        void shouldNotSetAnyCorsHeadersWhenOriginIsNotAllowed() throws ServletException, IOException {
            initFilterWithOrigins("https://example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://malicious.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
            assertThat(response.getHeader("Access-Control-Allow-Methods")).isNull();
            assertThat(response.getHeader("Access-Control-Allow-Headers")).isNull();
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle empty origin configuration")
        void shouldHandleEmptyOriginConfiguration() throws ServletException, IOException {
            initFilterWithOrigins("", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should handle origins with extra whitespace")
        void shouldHandleOriginsWithExtraWhitespace() throws ServletException, IOException {
            initFilterWithOrigins("  https://example.com  ,  https://app.example.com  ", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://example.com");
        }

        @Test
        @DisplayName("should handle malformed Origin header")
        void shouldHandleMalformedOriginHeader() throws ServletException, IOException {
            initFilterWithOrigins("*.example.com", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "not-a-valid-url");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should handle mixed case in profile names")
        void shouldHandleMixedCaseInProfileNames() throws ServletException, IOException {
            initFilterWithOrigins("*", "PROD");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://any-origin.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            // Should block wildcard even with uppercase PROD
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }

        @Test
        @DisplayName("should handle multiple profiles including production")
        void shouldHandleMultipleProfilesIncludingProduction() throws ServletException, IOException {
            initFilterWithOrigins("*", "common,prod");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://any-origin.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            // Should block wildcard when any profile is production
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }

        @Test
        @DisplayName("should handle origin with port number")
        void shouldHandleOriginWithPortNumber() throws ServletException, IOException {
            initFilterWithOrigins("https://localhost:3000,https://localhost:8080", "local");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
            request.addHeader("Origin", "https://localhost:3000");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://localhost:3000");
        }
    }
}
