package cn.flying.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.flying.common.annotation.OperationLog;
import cn.flying.config.RateLimitClientIpProperties;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.security.TrustedClientIpResolver;
import cn.flying.service.SysOperationLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OperationLogAspect Tests")
class OperationLogAspectTest {

    /**
     * 清理 servlet、安全与 MDC 上下文，避免测试线程复用造成状态泄漏。
     */
    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    /**
     * 验证操作日志切面会脱敏路径中的分享码和文件哈希。
     */
    @Test
    @DisplayName("Should mask sensitive identifiers in operation log path")
    void shouldMaskSensitiveIdentifiersInOperationLogPath() {
        OperationLogAspect aspect = newAspect("");

        String sanitized = ReflectionTestUtils.invokeMethod(
                aspect,
                "sanitizePathForLog",
                "/api/v1/public/shares/ABC123/files/hash-secret/chunks"
        );

        assertThat(sanitized).isEqualTo("/api/v1/public/shares/***/files/***/chunks");

        String fileHashRoute = ReflectionTestUtils.invokeMethod(
                aspect,
                "sanitizePathForLog",
                "/api/v1/files/hash/hash-secret/chunks"
        );
        assertThat(fileHashRoute).isEqualTo("/api/v1/files/hash/***/chunks");

        String saveRoute = ReflectionTestUtils.invokeMethod(
                aspect,
                "sanitizePathForLog",
                "/api/v1/shares/ABC123/files/save"
        );
        assertThat(saveRoute).isEqualTo("/api/v1/shares/***/files/save");

        String uploadSessionRoute = ReflectionTestUtils.invokeMethod(
                aspect,
                "sanitizePathForLog",
                "/api/v1/upload-sessions/client-secret/progress"
        );
        assertThat(uploadSessionRoute).isEqualTo("/api/v1/upload-sessions/***/progress");
    }

    /**
     * 验证文件接口会被标记为敏感操作，避免请求参数和响应体落库。
     */
    @Test
    @DisplayName("Should classify file operations as sensitive")
    void shouldClassifyFileOperationsAsSensitive() {
        OperationLogAspect aspect = newAspect("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions/0xtxhash");

        Boolean result = ReflectionTestUtils.invokeMethod(aspect, "isSensitiveFileOperation", request);

        assertThat(result).isTrue();
    }

    /**
     * 验证上传会话接口也会被标记为敏感操作，避免 clientId 作为方法参数落库。
     */
    @Test
    @DisplayName("Should classify upload session operations as sensitive")
    void shouldClassifyUploadSessionOperationsAsSensitive() {
        OperationLogAspect aspect = newAspect("");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/v1/upload-sessions/client-secret/complete"
        );

        Boolean result = ReflectionTestUtils.invokeMethod(aspect, "isSensitiveFileOperation", request);

        assertThat(result).isTrue();
    }

    /**
     * 验证生产 context-path 下的文件接口仍会被标记为敏感操作。
     */
    @Test
    @DisplayName("Should classify sensitive operations behind context path")
    void shouldClassifySensitiveOperationsBehindContextPath() {
        OperationLogAspect aspect = newAspect("");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/record-platform/api/v1/public/shares/ABC123/files/hash-secret/chunks"
        );
        request.setContextPath("/record-platform");

        Boolean result = ReflectionTestUtils.invokeMethod(aspect, "isSensitiveFileOperation", request);

        assertThat(result).isTrue();
    }

    /**
     * 验证系统审计接口本身也会进入操作日志，避免敏感管理动作缺失审计记录。
     */
    @Test
    @DisplayName("Should not ignore system audit API paths")
    void shouldNotIgnoreSystemAuditApiPaths() {
        OperationLogAspect aspect = newAspect("");

        Boolean result = ReflectionTestUtils.invokeMethod(
                aspect,
                "isIgnoreUrl",
                "/api/v1/system/audit/logs/export"
        );

        assertThat(result).isFalse();
    }

    /**
     * 验证非业务文档路由仍然会被操作日志切面忽略。
     */
    @Test
    @DisplayName("Should still ignore API documentation paths")
    void shouldStillIgnoreApiDocumentationPaths() {
        OperationLogAspect aspect = newAspect("");

        Boolean result = ReflectionTestUtils.invokeMethod(
                aspect,
                "isIgnoreUrl",
                "/swagger-ui/index.html"
        );

        assertThat(result).isTrue();
    }

    /**
     * 验证不可信直接对端不能用任何旧代理头污染文本日志或数据库审计 IP。
     */
    @Test
    @DisplayName("Should use the same untrusted peer IP in text and persisted audit logs")
    void shouldUseSameUntrustedPeerIpInTextAndPersistedAuditLogs() throws Throwable {
        SysOperationLogService operationLogService = mock(SysOperationLogService.class);
        OperationLogAspect aspect = new OperationLogAspect(operationLogService, newResolver("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/v1/public/proofs/rp-test/status"
        );
        request.setServletPath("/api/v1/public/proofs/rp-test/status");
        request.setRemoteAddr("198.51.100.24");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Real-IP", "203.0.113.8");
        request.addHeader("Proxy-Client-IP", "203.0.113.9");
        request.addHeader("WL-Proxy-Client-IP", "203.0.113.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ProceedingJoinPoint joinPoint = auditedJoinPoint();
        Logger aspectLogger = (Logger) LoggerFactory.getLogger(OperationLogAspect.class);
        Level previousLevel = aspectLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        aspectLogger.setLevel(Level.INFO);
        aspectLogger.addAppender(appender);
        try {
            assertThat(aspect.doAround(joinPoint)).isEqualTo("ok");
        } finally {
            aspectLogger.detachAppender(appender);
            aspectLogger.setLevel(previousLevel);
            appender.stop();
        }

        var captor = org.mockito.ArgumentCaptor.forClass(SysOperationLog.class);
        verify(operationLogService).saveOperationLog(captor.capture());
        assertThat(captor.getValue().getRequestIp()).isEqualTo("198.51.100.24");

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(message -> message.contains("IP: 198.51.100.24"));
        assertThat(messages).noneMatch(message -> message.contains("203.0.113."));
    }

    /**
     * 验证可信代理携带合法多跳 XFF 时审计切面使用 resolver 选择的规范地址。
     */
    @Test
    @DisplayName("Should resolve a valid multi-hop chain from a trusted peer")
    void shouldResolveValidMultiHopChainFromTrustedPeer() {
        OperationLogAspect aspect = newAspect("10.0.0.0/8");
        MockHttpServletRequest request = requestFrom("10.0.0.20");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.1.0.8, 10.2.0.9");

        String clientIp = ReflectionTestUtils.invokeMethod(aspect, "getClientIp", request);

        assertThat(clientIp).isEqualTo("203.0.113.7");
    }

    /**
     * 验证重复、超长、超多跳、非数字和旧代理头均安全回退到可信直接对端。
     */
    @Test
    @DisplayName("Should fall back to the peer for malicious forwarding headers")
    void shouldFallbackToPeerForMaliciousForwardingHeaders() {
        OperationLogAspect aspect = newAspect("10.0.0.0/8");
        List<MockHttpServletRequest> requests = new ArrayList<>();

        MockHttpServletRequest duplicate = requestFrom("10.0.0.20");
        duplicate.addHeader("X-Forwarded-For", "203.0.113.1");
        duplicate.addHeader("X-Forwarded-For", "203.0.113.2");
        requests.add(duplicate);

        MockHttpServletRequest tooLong = requestFrom("10.0.0.20");
        tooLong.addHeader("X-Forwarded-For", "1".repeat(1025));
        requests.add(tooLong);

        MockHttpServletRequest tooMany = requestFrom("10.0.0.20");
        tooMany.addHeader("X-Forwarded-For", IntStream.rangeClosed(1, 17)
                .mapToObj(index -> "10.0.0." + index)
                .collect(java.util.stream.Collectors.joining(", ")));
        requests.add(tooMany);

        MockHttpServletRequest hostname = requestFrom("10.0.0.20");
        hostname.addHeader("X-Forwarded-For", "attacker.example");
        requests.add(hostname);

        MockHttpServletRequest legacyHeaders = requestFrom("10.0.0.20");
        legacyHeaders.addHeader("Proxy-Client-IP", "203.0.113.3");
        legacyHeaders.addHeader("WL-Proxy-Client-IP", "203.0.113.4");
        requests.add(legacyHeaders);

        for (MockHttpServletRequest request : requests) {
            String clientIp = ReflectionTestUtils.invokeMethod(aspect, "getClientIp", request);
            assertThat(clientIp).isEqualTo("10.0.0.20");
        }
    }

    /**
     * 验证 saveRequestData=false 同时约束开始日志和落库参数，并把 HTTP 401 记为失败。
     */
    @Test
    @DisplayName("Should omit sensitive request data and classify HTTP errors")
    void shouldOmitSensitiveRequestDataAndClassifyHttpErrors() throws Throwable {
        String secretToken = "sse-secret-token-that-must-never-be-logged";
        SysOperationLogService operationLogService = mock(SysOperationLogService.class);
        OperationLogAspect aspect = new OperationLogAspect(operationLogService, newResolver(""));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sse/connect");
        request.setServletPath("/api/v1/sse/connect");
        request.setRemoteAddr("198.51.100.31");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ProceedingJoinPoint joinPoint = sensitiveAuditedJoinPoint(
                secretToken,
                ResponseEntity.status(401).build());
        Logger aspectLogger = (Logger) LoggerFactory.getLogger(OperationLogAspect.class);
        Level previousLevel = aspectLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        aspectLogger.setLevel(Level.INFO);
        aspectLogger.addAppender(appender);
        try {
            assertThat(aspect.doAround(joinPoint)).isInstanceOf(ResponseEntity.class);
        } finally {
            aspectLogger.detachAppender(appender);
            aspectLogger.setLevel(previousLevel);
            appender.stop();
        }

        var captor = org.mockito.ArgumentCaptor.forClass(SysOperationLog.class);
        verify(operationLogService).saveOperationLog(captor.capture());
        assertThat(captor.getValue().getRequestParam()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getErrorMsg()).isEqualTo("HTTP 401");

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(message -> message.contains("参数: <omitted>"));
        assertThat(messages).noneMatch(message -> message.contains(secretToken));
    }

    /**
     * 验证无 HTTP 请求上下文时仍执行目标方法且不会尝试持久化操作日志。
     */
    @Test
    @DisplayName("Should preserve non-HTTP invocation behavior")
    void shouldPreserveNonHttpInvocationBehavior() throws Throwable {
        SysOperationLogService operationLogService = mock(SysOperationLogService.class);
        OperationLogAspect aspect = new OperationLogAspect(operationLogService, newResolver(""));
        ProceedingJoinPoint joinPoint = auditedJoinPoint();

        assertThat(aspect.doAround(joinPoint)).isEqualTo("ok");

        verify(operationLogService, never()).saveOperationLog(any());
    }

    /**
     * 构造带指定可信代理网段的操作日志切面。
     */
    private OperationLogAspect newAspect(String trustedProxies) {
        return new OperationLogAspect(mock(SysOperationLogService.class), newResolver(trustedProxies));
    }

    /**
     * 构造启用严格容器头策略的真实可信客户端 IP 解析器。
     */
    private TrustedClientIpResolver newResolver(String trustedProxies) {
        RateLimitClientIpProperties properties = new RateLimitClientIpProperties();
        properties.setTrustedProxies(trustedProxies);
        return new TrustedClientIpResolver(properties, "none", "", "");
    }

    /**
     * 构造指定直接对端的 Mock servlet 请求。
     */
    private MockHttpServletRequest requestFrom(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        return request;
    }

    /**
     * 构造可被真实操作日志切面读取注解和签名的测试连接点。
     */
    private ProceedingJoinPoint auditedJoinPoint() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = AuditedFixture.class.getDeclaredMethod("execute");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn("ok");
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringTypeName()).thenReturn(AuditedFixture.class.getName());
        when(signature.getName()).thenReturn(method.getName());
        return joinPoint;
    }

    /**
     * 构造携带敏感参数并返回指定 HTTP 响应的测试连接点。
     */
    private ProceedingJoinPoint sensitiveAuditedJoinPoint(
            String token,
            ResponseEntity<Void> response) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = AuditedFixture.class.getDeclaredMethod("executeSensitive", String.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{token});
        when(joinPoint.proceed()).thenReturn(response);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringTypeName()).thenReturn(AuditedFixture.class.getName());
        when(signature.getName()).thenReturn(method.getName());
        return joinPoint;
    }

    private static final class AuditedFixture {

        /**
         * 提供携带操作日志注解的测试目标方法。
         */
        @OperationLog(module = "test", operationType = "query", description = "test audit")
        private String execute() {
            return "ok";
        }

        /**
         * 提供禁止保存敏感请求参数的测试目标方法。
         */
        @OperationLog(
                module = "sse",
                operationType = "connect",
                description = "sensitive audit",
                saveRequestData = false)
        private ResponseEntity<Void> executeSensitive(String token) {
            return ResponseEntity.ok().build();
        }
    }
}
