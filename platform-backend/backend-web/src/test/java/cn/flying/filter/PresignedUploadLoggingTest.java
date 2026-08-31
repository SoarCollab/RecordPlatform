package cn.flying.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import cn.flying.aspect.OperationLogAspect;
import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.ErrorPayload;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import cn.flying.common.util.Const;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.filter.handler.GlobalExceptionHandler;
import cn.flying.security.TrustedClientIpResolver;
import cn.flying.service.SysOperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercise the real filter/aspect/handler logging boundaries with synthetic capability values. */
class PresignedUploadLoggingTest {
    private static final String SIGNATURE = "synthetic-signature-sentinel";
    private static final String CREDENTIAL = "synthetic-credential-sentinel";
    private static final String URL = "https://storage.example/bucket/object?X-Amz-Credential="
            + CREDENTIAL + "&X-Amz-Signature=" + SIGNATURE;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Map<Logger, Level> loggers = new LinkedHashMap<>();

    /** Capture message, argument and Throwable copies consumed by text and JSON appenders. */
    @BeforeEach
    void capture() {
        appender.start();
        for (Class<?> type : List.of(RequestLogFilter.class, OperationLogAspect.class, GlobalExceptionHandler.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            loggers.put(logger, logger.getLevel());
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
        }
    }

    /** Restore logger and request state without affecting other tests. */
    @AfterEach
    void cleanup() {
        loggers.forEach((logger, level) -> {
            logger.detachAppender(appender);
            logger.setLevel(level);
        });
        appender.stop();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    /** Direct-upload routes must not wrap/cache capabilities or alter the client bytes. */
    @Test
    void directResponseIsUncachedUnchangedAndUnlogged() throws Exception {
        byte[] body = ("{\"parts\":[{\"uploadUrl\":\"" + URL + "\"}],\"name\":\"ordinary\"}")
                .getBytes(StandardCharsets.UTF_8);
        for (String path : List.of("/api/v1/upload-sessions/direct",
                "/record-platform/api/v1/upload%2Dsessions;v=1/direct")) {
            MockHttpServletRequest request = request(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            new RequestLogFilter().doFilter(request, response, (req, res) -> {
                assertFalse(res instanceof ContentCachingResponseWrapper);
                res.setContentType("application/json");
                res.getOutputStream().write(body);
            });
            assertArrayEquals(body, response.getContentAsByteArray());
        }
        assertCleanLogs();
        assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("<skipped>")));
    }

    /** Unknown parameter names and ordinary cached endpoints still require value redaction. */
    @Test
    void parameterAndFallbackResponseCopiesAreRedacted() throws Exception {
        verifyParameterAndResponseCopies(false);
        verifyParameterAndResponseCopies(true);
        assertCleanLogs();
        assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("ordinary-user")));
        assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("visible-control")));
    }

    /** Exercise each authentication branch with capability names, encoded aliases and values. */
    private void verifyParameterAndResponseCopies(boolean authenticated) throws Exception {
        MockHttpServletRequest request = request("/api/v1/probe");
        if (authenticated) {
            User user = new User("ordinary-user", "unused-test-password", List.of());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, List.of()));
            request.setAttribute(Const.ATTR_USER_ID, 123L);
        }
        request.addParameter("destination", URL);
        request.addParameter(URL, "visible-control");
        request.addParameter("upload%255furl", SIGNATURE);
        request.addParameter("ordinary", "visible-control");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String body = "{\"message\":\"" + URL + "\",\"name\":\"visible-control\"}";
        new RequestLogFilter().doFilter(request, response, (req, res) -> {
            res.setContentType("application/json");
            res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        });
        assertEquals(body, response.getContentAsString());
    }

    /** Success audits sanitize ordinary-route copies and omit upload payloads without replacing results. */
    @Test
    void successfulAuditsKeepOriginalResultsAndSafeMetadata() throws Throwable {
        for (String path : List.of("/api/v1/upload-sessions/direct", "/api/v1/probe")) {
            MockHttpServletRequest request = request(path);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            SysOperationLogService service = mock(SysOperationLogService.class);
            TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
            when(resolver.resolve(request)).thenReturn("127.0.0.1");
            ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
            MethodSignature signature = mock(MethodSignature.class);
            when(point.getSignature()).thenReturn(signature);
            when(signature.getMethod()).thenReturn(Fixture.class.getDeclaredMethod("upload"));
            when(signature.getDeclaringTypeName()).thenReturn(Fixture.class.getName());
            when(signature.getName()).thenReturn("upload");
            Map<String, String> result = Map.of("uploadUrl", URL, "name", "visible-control");
            when(point.getArgs()).thenReturn(new Object[]{Map.of("destination", URL, "name", "visible-control")});
            when(point.proceed()).thenReturn(result);
            assertSame(result, new OperationLogAspect(service, resolver).doAround(point));
            ArgumentCaptor<SysOperationLog> audit = ArgumentCaptor.forClass(SysOperationLog.class);
            verify(service).saveOperationLog(audit.capture());
            SysOperationLog saved = audit.getValue();
            assertEquals(0, saved.getStatus());
            assertEquals("file", saved.getModule());
            assertEquals("upload", saved.getOperationType());
            assertNull(saved.getErrorMsg());
            for (String copy : List.of(saved.getRequestParam(), saved.getResponseResult())) {
                assertFalse(copy.contains(SIGNATURE));
                assertFalse(copy.contains(CREDENTIAL));
                assertTrue(copy.contains(path.contains("upload-sessions")
                        ? "<sensitive-file-operation>" : "visible-control"));
            }
            assertEquals(URL, result.get("uploadUrl"));
        }
        assertCleanLogs();
    }

    /** Failure messages must be sanitized in persisted audits and text without replacing the thrown error. */
    @Test
    void auditFailureKeepsBusinessExceptionButRedactsEveryLogCopy() throws Throwable {
        MockHttpServletRequest request = request("/api/v1/upload-sessions/direct");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SysOperationLogService service = mock(SysOperationLogService.class);
        TrustedClientIpResolver resolver = mock(TrustedClientIpResolver.class);
        when(resolver.resolve(request)).thenReturn("127.0.0.1");
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(point.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(Fixture.class.getDeclaredMethod("upload"));
        when(signature.getDeclaringTypeName()).thenReturn(Fixture.class.getName());
        when(signature.getName()).thenReturn("upload");
        GeneralException original = new GeneralException("upload failed: " + URL);
        when(point.proceed()).thenThrow(original);
        OperationLogAspect aspect = new OperationLogAspect(service, resolver);
        assertSame(original, assertThrows(GeneralException.class, () -> aspect.doAround(point)));
        ArgumentCaptor<SysOperationLog> audit = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(service).saveOperationLog(audit.capture());
        assertEquals(1, audit.getValue().getStatus());
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(audit.getValue()).contains(SIGNATURE));
        assertCleanLogs();
        assertTrue(original.getMessage().contains(SIGNATURE));
    }

    /** The same failure routed to exception advice must not leak through message/cause/suppressed copies. */
    @Test
    void exceptionAdviceRedactsMessagesAndThrowableGraphButKeepsDiagnostics() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        GeneralException original = new GeneralException("upload failed: " + URL);
        assertEquals(original.getMessage(), handler.handleBusinessException(original).getBody().getMessage());
        IllegalStateException failure = new IllegalStateException("upload failed: " + URL,
                new IllegalArgumentException("cause: " + URL));
        failure.addSuppressed(new IllegalStateException("suppressed: " + URL));
        handler.handleSystemException(failure);
        assertCleanLogs();
        ILoggingEvent error = appender.list.getLast();
        assertNotNull(error.getThrowableProxy());
        String trace = ThrowableProxyUtil.asString(error.getThrowableProxy());
        assertTrue(trace.contains("IllegalStateException"));
        assertTrue(trace.contains("IllegalArgumentException"));
        assertTrue(trace.contains("Suppressed"));
        assertTrue(trace.contains("PresignedUploadLoggingTest"));
        assertTrue(failure.getCause().getMessage().contains(SIGNATURE));
    }

    /** Truncation must not emit a credential prefix when the signature marker is beyond the preview boundary. */
    @Test
    void capabilityRedactionPrecedesPreviewTruncation() throws Exception {
        String longUrl = "https://storage.example/" + CREDENTIAL + "/" + "x".repeat(5000)
                + "?X-Amz-Signature=" + SIGNATURE;
        MockHttpServletRequest request = request("/api/v1/probe");
        request.addParameter("destination", longUrl);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String body = "{\"message\":\"" + longUrl + "\",\"name\":\"visible-control\"}";
        new RequestLogFilter().doFilter(request, response, (req, res) ->
                res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(body, response.getContentAsString());
        assertCleanLogs();
    }

    /** Typed IO and retryable variants of the same upload failure must use the same log-only policy. */
    @Test
    void typedUploadFailuresCannotReintroduceCapabilityMessages() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        handler.handleIOException(new java.io.IOException(URL), request("/api/v1/upload-sessions/direct"));
        handler.handleRetryableException(new RetryableException(URL));
        assertCleanLogs();
    }

    /** Numeric upload parameters may carry capabilities in conversion errors, but only log copies are masked. */
    @Test
    void argumentTypeMismatchKeepsClientDetailButRedactsLogs() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        for (String name : List.of("fileSize", "chunkSize", "totalChunks")) {
            MethodArgumentTypeMismatchException original = new MethodArgumentTypeMismatchException(
                    URL, Long.class, name, null, new NumberFormatException("synthetic conversion failure"));
            Result<?> result = handler.handleMethodArgumentTypeMismatchException(original);
            assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
            assertEquals("参数类型不匹配: " + name + "，期望类型=Long，实际值=" + URL,
                    assertInstanceOf(ErrorPayload.class, result.getData()).getDetail());
            assertEquals(URL, original.getValue());
        }
        assertEquals(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.class
                .getMethod("handleMethodArgumentTypeMismatchException", MethodArgumentTypeMismatchException.class)
                .getAnnotation(ResponseStatus.class).value());
        assertCleanLogs();
    }

    /** Unsupported converters preserve the original response while sanitizing all event representations. */
    @Test
    void unsupportedConversionKeepsClientDetailButRedactsLogs() throws Exception {
        MethodArgumentConversionNotSupportedException original = new MethodArgumentConversionNotSupportedException(
                URL, Long.class, "fileSize", null, new IllegalStateException("synthetic converter failure"));
        Result<?> result = new GlobalExceptionHandler().handleMethodArgumentConversionNotSupportedException(original);
        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
        assertEquals("参数类型不匹配: fileSize，期望类型=Long，实际值=" + URL,
                assertInstanceOf(ErrorPayload.class, result.getData()).getDetail());
        assertEquals(URL, original.getValue());
        assertEquals(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.class
                .getMethod("handleMethodArgumentConversionNotSupportedException", MethodArgumentConversionNotSupportedException.class)
                .getAnnotation(ResponseStatus.class).value());
        assertCleanLogs();
    }

    /** Both debug-only SSE branches must sanitize capabilities and retain ordinary IO diagnostics. */
    @Test
    void sseIOExceptionDebugCopiesAreSanitized() throws Exception {
        ((Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class)).setLevel(Level.DEBUG);
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        for (String path : List.of("/api/sse/connect", "/api/v1/upload-sessions/direct")) {
            MockHttpServletRequest request = request(path);
            if (!path.contains("/sse/")) {
                request.addHeader("Accept", "text/event-stream");
            }
            java.io.IOException original = new java.io.IOException(URL);
            assertEquals(200, handler.handleIOException(original, request).getStatusCode().value());
            assertEquals(URL, original.getMessage());
            handler.handleIOException(new java.io.IOException("ordinary IO diagnostic"), request);
        }
        assertCleanLogs();
        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.DEBUG
                && event.getFormattedMessage().contains("ordinary IO diagnostic")));
    }

    /** Over-budget responses must be omitted wholesale while every client byte remains available. */
    @Test
    void oversizedResponseCopiesAreOmittedWithoutChangingClientBytes() throws Exception {
        MockHttpServletRequest request = request("/api/v1/probe");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String body = "{\"message\":\"" + URL + "\",\"padding\":\"" + "x".repeat(70000) + "\"}";
        new RequestLogFilter().doFilter(request, response, (req, res) -> {
            res.setContentType("application/json");
            res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        });
        assertEquals(body, response.getContentAsString());
        assertCleanLogs();
        assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage()
                .contains("response exceeds log inspection limit")));
    }

    /** Use servlet state representative of the deployed context without host data. */
    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        return request;
    }

    /** Inspect every event representation rather than only its formatted message. */
    private void assertCleanLogs() throws Exception {
        assertFalse(appender.list.isEmpty());
        for (ILoggingEvent event : appender.list) {
            String throwable = event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy());
            String arguments = Arrays.toString(event.getArgumentArray());
            String json = new ObjectMapper().writeValueAsString(Map.of("message", event.getFormattedMessage(),
                    "template", event.getMessage(), "arguments", arguments, "throwable", throwable));
            for (String copy : List.of(event.getFormattedMessage(), arguments, throwable, json)) {
                assertFalse(copy.contains(SIGNATURE), copy);
                assertFalse(copy.contains(CREDENTIAL), copy);
            }
        }
    }

    private static final class Fixture {
        /** Supply only audit metadata; the mocked business invocation controls the result. */
        @OperationLog(module = "file", operationType = "upload", description = "synthetic upload", saveResponseData = true)
        private void upload() { }
    }
}
