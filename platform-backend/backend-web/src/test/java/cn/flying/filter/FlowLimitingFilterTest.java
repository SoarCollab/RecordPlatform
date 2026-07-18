package cn.flying.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.flying.common.util.Const;
import cn.flying.common.util.DistributedRateLimiter;
import cn.flying.common.util.DistributedRateLimiter.RateLimitResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FlowLimitingFilter Unit Tests")
class FlowLimitingFilterTest {

    @Mock
    private DistributedRateLimiter rateLimiter;

    @InjectMocks
    private FlowLimitingFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain filterChain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = new MockFilterChain();
        
        ReflectionTestUtils.setField(filter, "limit", 50);
        ReflectionTestUtils.setField(filter, "period", 10);
        ReflectionTestUtils.setField(filter, "blockTime", 300);
        ReflectionTestUtils.setField(filter, "excludePatterns", List.of());
    }

    @Nested
    @DisplayName("Rate Limiting Tests")
    class RateLimitingTests {

        @Test
        @DisplayName("Should allow request when within limit")
        void shouldAllowRequestWhenWithinLimit() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.setRemoteAddr("192.168.1.1");
            
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.ALLOWED);

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(rateLimiter).tryAcquireWithBlock(
                    eq(Const.FLOW_LIMIT_COUNTER + "192.168.1.1"),
                    eq(Const.FLOW_LIMIT_BLOCK + "192.168.1.1"),
                    eq(50), eq(10), eq(300)
            );
        }

        @Test
        @DisplayName("Should block request when limit exceeded")
        void shouldBlockRequestWhenLimitExceeded() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.setRemoteAddr("192.168.1.1");
            
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.RATE_LIMITED);

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getContentType()).contains("application/json");
        }

        @Test
        @DisplayName("Should block request when already blocked")
        void shouldBlockRequestWhenAlreadyBlocked() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.setRemoteAddr("192.168.1.1");
            
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.BLOCKED);

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
        }

        /**
         * 验证限流拒绝日志不会暴露可用于重放匿名分享请求的分享码和文件哈希。
         */
        @Test
        @DisplayName("Should mask public share credentials in rate limit logs")
        void shouldMaskPublicShareCredentialsInRateLimitLogs() throws ServletException, IOException {
            String shareCode = "share-secret-credential";
            String fileHash = "file-hash-secret";
            request.setMethod("GET");
            request.setRequestURI("/api/v1/public/sh%61res/" + shareCode
                    + ";probe=1/f%69les/" + fileHash + ";probe=2/chunks");
            request.setRemoteAddr("192.168.1.1");
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.RATE_LIMITED);

            Logger logger = (Logger) LoggerFactory.getLogger(FlowLimitingFilter.class);
            Level previousLevel = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.setLevel(Level.WARN);
            logger.addAppender(appender);
            try {
                filter.doFilter(request, response, filterChain);
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(previousLevel);
                appender.stop();
            }

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages).anyMatch(message ->
                    message.contains("/api/v1/public/sh%61res/***/f%69les/***/chunks"));
            assertThat(messages).noneMatch(message ->
                    message.contains(shareCode) || message.contains(fileHash));
        }

        /**
         * 验证静态段编码且矩阵参数非法时，限流拒绝日志仍不会暴露匿名分享凭据。
         */
        @Test
        @DisplayName("Should mask credentials when encoded routes contain invalid matrix parameters")
        void shouldMaskCredentialsWhenEncodedRoutesContainInvalidMatrixParameters()
                throws ServletException, IOException {
            String shareCode = "share-secret-credential";
            String fileHash = "file-hash-secret";
            request.setMethod("GET");
            request.setRequestURI("/api/v1/public/sh%61res;bad=%ZZ/" + shareCode
                    + "/f%69les;bad=%ZZ/" + fileHash + "/chunks");
            request.setRemoteAddr("192.168.1.1");
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.RATE_LIMITED);

            Logger logger = (Logger) LoggerFactory.getLogger(FlowLimitingFilter.class);
            Level previousLevel = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.setLevel(Level.WARN);
            logger.addAppender(appender);
            try {
                filter.doFilter(request, response, filterChain);
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(previousLevel);
                appender.stop();
            }

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages).anyMatch(message -> message.contains(
                    "/api/v1/public/sh%61res;bad=%ZZ/***/f%69les;bad=%ZZ/***/chunks"));
            assertThat(messages).noneMatch(message ->
                    message.contains(shareCode) || message.contains(fileHash));
        }

        /**
         * 验证容器规范化空段、点段和父段时，限流拒绝日志仍不会暴露实际分享凭据。
         */
        @Test
        @DisplayName("Should mask credentials in container-normalized public share routes")
        void shouldMaskCredentialsInContainerNormalizedPublicShareRoutes()
                throws ServletException, IOException {
            String shareCode = "share-secret-credential";
            String fileHash = "file-hash-secret";
            List<String> requestUris = List.of(
                    "/api/v1/public/shares/./" + shareCode + "/files/" + fileHash + "/chunks",
                    "/api/v1/public/shares//" + shareCode + "/files/" + fileHash + "/chunks",
                    "/api/v1/public/shares/%2E/" + shareCode + "/files/" + fileHash + "/chunks",
                    "/api/v1/public/shares/decoy-secret/../" + shareCode
                            + "/files/" + fileHash + "/chunks",
                    "/api/v1/public/shares/encoded-decoy/%2e%2E/" + shareCode
                            + "/files/" + fileHash + "/chunks"
            );
            request.setMethod("GET");
            request.setRemoteAddr("192.168.1.1");
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.RATE_LIMITED);

            Logger logger = (Logger) LoggerFactory.getLogger(FlowLimitingFilter.class);
            Level previousLevel = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.setLevel(Level.WARN);
            logger.addAppender(appender);
            try {
                for (String requestUri : requestUris) {
                    request.setRequestURI(requestUri);
                    filter.doFilter(request, new MockHttpServletResponse(), filterChain);
                }
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(previousLevel);
                appender.stop();
            }

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages).anyMatch(message ->
                    message.contains("/api/v1/public/shares/./***/files/***/chunks"));
            assertThat(messages).anyMatch(message ->
                    message.contains("/api/v1/public/shares//***/files/***/chunks"));
            assertThat(messages).anyMatch(message ->
                    message.contains("/api/v1/public/shares/%2E/***/files/***/chunks"));
            assertThat(messages).anyMatch(message ->
                    message.contains("/api/v1/public/shares/***/../***/files/***/chunks"));
            assertThat(messages).anyMatch(message ->
                    message.contains("/api/v1/public/shares/***/%2e%2E/***/files/***/chunks"));
            assertThat(messages).noneMatch(message ->
                    message.contains(shareCode)
                            || message.contains(fileHash)
                            || message.contains("decoy-secret")
                            || message.contains("encoded-decoy"));
        }
    }

    @Nested
    @DisplayName("Skip Conditions Tests")
    class SkipConditionsTests {

        @Test
        @DisplayName("Should skip OPTIONS requests")
        void shouldSkipOptionsRequests() throws ServletException, IOException {
            request.setMethod("OPTIONS");
            request.setRequestURI("/api/v1/files");

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(rateLimiter, never()).tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Should skip excluded paths")
        void shouldSkipExcludedPaths() throws ServletException, IOException {
            ReflectionTestUtils.setField(filter, "excludePatterns", List.of("/api/v1/health/**", "/actuator/**"));
            
            request.setMethod("GET");
            request.setServletPath("/api/v1/health/check");
            request.setRemoteAddr("192.168.1.1");

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(rateLimiter, never()).tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Should skip when limit is disabled (limit <= 0)")
        void shouldSkipWhenLimitDisabled() throws ServletException, IOException {
            ReflectionTestUtils.setField(filter, "limit", 0);
            
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(rateLimiter, never()).tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Should skip when period is invalid (period <= 0)")
        void shouldSkipWhenPeriodInvalid() throws ServletException, IOException {
            ReflectionTestUtils.setField(filter, "period", 0);
            
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(rateLimiter, never()).tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("Client IP Detection Tests")
    class ClientIpDetectionTests {

        @Test
        @DisplayName("Should ignore spoofed X-Forwarded-For header")
        void shouldIgnoreSpoofedXForwardedForHeader() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");
            request.setRemoteAddr("127.0.0.1");
            
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.ALLOWED);

            filter.doFilter(request, response, filterChain);

            verify(rateLimiter).tryAcquireWithBlock(
                    contains("127.0.0.1"),
                    contains("127.0.0.1"),
                    anyInt(), anyInt(), anyInt()
            );
        }

        @Test
        @DisplayName("Should ignore spoofed X-Real-IP header")
        void shouldIgnoreSpoofedXRealIpHeader() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.addHeader("X-Real-IP", "10.0.0.2");
            request.setRemoteAddr("127.0.0.1");
            
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.ALLOWED);

            filter.doFilter(request, response, filterChain);

            verify(rateLimiter).tryAcquireWithBlock(
                    contains("127.0.0.1"),
                    contains("127.0.0.1"),
                    anyInt(), anyInt(), anyInt()
            );
        }

        @Test
        @DisplayName("Should use remote address as fallback")
        void shouldUseRemoteAddressAsFallback() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.setRemoteAddr("192.168.1.100");
            
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.ALLOWED);

            filter.doFilter(request, response, filterChain);

            verify(rateLimiter).tryAcquireWithBlock(
                    contains("192.168.1.100"),
                    contains("192.168.1.100"),
                    anyInt(), anyInt(), anyInt()
            );
        }

        @Test
        @DisplayName("Should ignore 'unknown' X-Forwarded-For value")
        void shouldIgnoreUnknownXForwardedFor() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.addHeader("X-Forwarded-For", "unknown");
            request.setRemoteAddr("192.168.1.100");
            
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.ALLOWED);

            filter.doFilter(request, response, filterChain);

            verify(rateLimiter).tryAcquireWithBlock(
                    contains("192.168.1.100"),
                    contains("192.168.1.100"),
                    anyInt(), anyInt(), anyInt()
            );
        }
    }

    @Nested
    @DisplayName("Path Matching Tests")
    class PathMatchingTests {

        @Test
        @DisplayName("Should match wildcard patterns")
        void shouldMatchWildcardPatterns() throws ServletException, IOException {
            ReflectionTestUtils.setField(filter, "excludePatterns", List.of("/api/v1/public/**"));
            
            request.setMethod("GET");
            request.setServletPath("/api/v1/public/shares/share123/files/file123/chunks");
            request.setRemoteAddr("192.168.1.1");

            filter.doFilter(request, response, filterChain);

            verify(rateLimiter, never()).tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Should not match non-excluded paths")
        void shouldNotMatchNonExcludedPaths() throws ServletException, IOException {
            ReflectionTestUtils.setField(filter, "excludePatterns", List.of("/api/v1/health/**"));
            
            request.setMethod("GET");
            request.setServletPath("/api/v1/files");
            request.setRemoteAddr("192.168.1.1");
            
            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.ALLOWED);

            filter.doFilter(request, response, filterChain);

            verify(rateLimiter).tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("Response Format Tests")
    class ResponseFormatTests {

        @Test
        @DisplayName("Should return 429 status when rate limited")
        void shouldReturn429WhenRateLimited() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.setRemoteAddr("192.168.1.1");

            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.RATE_LIMITED);

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getContentType()).isEqualTo("application/json;charset=utf-8");
        }

        @Test
        @DisplayName("Should return 429 status when blocked")
        void shouldReturn429WhenBlocked() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.setRemoteAddr("192.168.1.1");

            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.BLOCKED);

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getContentType()).isEqualTo("application/json;charset=utf-8");
        }

        @Test
        @DisplayName("Should write non-empty response body when blocked")
        void shouldWriteResponseBodyWhenBlocked() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.setRemoteAddr("192.168.1.1");

            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.RATE_LIMITED);

            filter.doFilter(request, response, filterChain);

            String content = response.getContentAsString();
            assertThat(content).isNotEmpty();
        }

        @Test
        @DisplayName("Should use UTF-8 charset for error response")
        void shouldUseUtf8Charset() throws ServletException, IOException {
            request.setMethod("GET");
            request.setRequestURI("/api/v1/files");
            request.setRemoteAddr("192.168.1.1");

            when(rateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(RateLimitResult.RATE_LIMITED);

            filter.doFilter(request, response, filterChain);

            assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        }
    }
}
