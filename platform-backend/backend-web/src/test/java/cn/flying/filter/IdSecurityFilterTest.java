package cn.flying.filter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdSecurityFilter Unit Tests")
class IdSecurityFilterTest {

    @InjectMocks
    private IdSecurityFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain filterChain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = new MockFilterChain();
    }

    @Nested
    @DisplayName("ID Extraction Tests")
    class IdExtractionTests {

        @Test
        @DisplayName("Should extract resource ID from URL pattern /api/{resource}/{id}")
        void shouldExtractResourceIdFromUrl() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/files/12345");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isEqualTo(12345L);
            assertThat(request.getAttribute("resourceType")).isEqualTo("files");
        }

        @Test
        @DisplayName("Should extract ID from different resource types")
        void shouldExtractIdFromDifferentResourceTypes() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/users/67890");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isEqualTo(67890L);
            assertThat(request.getAttribute("resourceType")).isEqualTo("users");
        }

        @Test
        @DisplayName("Should handle large IDs")
        void shouldHandleLargeIds() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/tickets/9223372036854775807");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("Skip Conditions Tests")
    class SkipConditionsTests {

        @Test
        @DisplayName("Should skip non-GET requests")
        void shouldSkipNonGetRequests() throws ServletException, IOException {
            request.setMethod("POST");
            request.setRequestURI("/api/files/12345");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isNull();
            assertThat(request.getAttribute("resourceType")).isNull();
        }

        @Test
        @DisplayName("Should skip PUT requests")
        void shouldSkipPutRequests() throws ServletException, IOException {
            request.setMethod("PUT");
            request.setRequestURI("/api/files/12345");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isNull();
        }

        @Test
        @DisplayName("Should skip DELETE requests")
        void shouldSkipDeleteRequests() throws ServletException, IOException {
            request.setMethod("DELETE");
            request.setRequestURI("/api/files/12345");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isNull();
        }

        @Test
        @DisplayName("Should skip non-API paths")
        void shouldSkipNonApiPaths() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/static/files/12345");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isNull();
        }

        @Test
        @DisplayName("Should skip actuator endpoints")
        void shouldSkipActuatorEndpoints() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/actuator/health/12345");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isNull();
        }
    }

    @Nested
    @DisplayName("Invalid ID Handling Tests")
    class InvalidIdHandlingTests {

        @Test
        @DisplayName("Should handle invalid ID format gracefully")
        void shouldHandleInvalidIdFormatGracefully() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/files/invalid_id");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should extract numeric prefix from UUID-style IDs")
        void shouldExtractNumericPrefixFromUuidStyleIds() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/files/550e8400-e29b-41d4-a716-446655440000");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isEqualTo(550L);
        }
    }

    @Nested
    @DisplayName("URL Pattern Matching Tests")
    class UrlPatternMatchingTests {

        @Test
        @DisplayName("Should not match URLs without numeric ID")
        void shouldNotMatchUrlsWithoutNumericId() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/files/list");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isNull();
        }

        @Test
        @DisplayName("Should not match deeply nested paths")
        void shouldNotMatchDeeplyNestedPaths() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/users/profile/12345");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isNull();
        }

        @Test
        @DisplayName("Should match simple /api/resource/id pattern")
        void shouldMatchSimplePattern() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/tickets/999");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isEqualTo(999L);
            assertThat(request.getAttribute("resourceType")).isEqualTo("tickets");
        }
    }

    @Nested
    @DisplayName("Filter Chain Continuation Tests")
    class FilterChainContinuationTests {

        @Test
        @DisplayName("Should continue filter chain for valid requests")
        void shouldContinueFilterChainForValidRequests() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/files/12345");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should continue filter chain for skipped requests")
        void shouldContinueFilterChainForSkippedRequests() throws ServletException, IOException {
            request.setMethod("POST");
            request.setRequestURI("/api/files/12345");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Should continue filter chain for invalid ID format")
        void shouldContinueFilterChainForInvalidIdFormat() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/files/invalid");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(filterChain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle empty resource type")
        void shouldHandleEmptyResourceType() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api//12345");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isNull();
        }

        @Test
        @DisplayName("Should handle ID with leading zeros")
        void shouldHandleIdWithLeadingZeros() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/files/00123");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isEqualTo(123L);
        }

        @Test
        @DisplayName("Should handle zero ID")
        void shouldHandleZeroId() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/files/0");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(request.getAttribute("secureResourceId")).isEqualTo(0L);
        }
    }
}
