package cn.flying.aspect;

import cn.flying.common.annotation.RateLimit;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.UserRole;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.config.RateLimitClientIpProperties;
import cn.flying.controller.PublicProofController;
import cn.flying.security.TrustedClientIpResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private MethodSignature methodSignature;

    private RateLimitAspect aspect;
    private Method limitedMethod;
    private RateLimit rateLimit;

    /**
     * 初始化限流切面和测试用注解方法。
     */
    @BeforeEach
    void setUp() throws NoSuchMethodException {
        RateLimitClientIpProperties properties = new RateLimitClientIpProperties();
        aspect = new RateLimitAspect(
                stringRedisTemplate,
                new TrustedClientIpResolver(properties, "none", "", ""));
        limitedMethod = LimitedController.class.getDeclaredMethod("limitedEndpoint");
        rateLimit = limitedMethod.getAnnotation(RateLimit.class);

        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_DEFAULT.getRole());
        TenantContext.setTenantId(1L);
    }

    /**
     * 清理测试写入的 MDC 和租户上下文。
     */
    @AfterEach
    void tearDown() {
        MDC.remove(Const.ATTR_USER_ROLE);
        MDC.remove(Const.ATTR_USER_ID);
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 验证 Redis Lua 返回 null 时执行受控限流拒绝，而不是抛出空指针异常。
     */
    @Test
    @DisplayName("should deny request when Redis script returns null")
    void shouldDenyWhenRedisScriptReturnsNull() throws Throwable {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(null);

        GeneralException ex = assertThrows(GeneralException.class, () -> aspect.around(joinPoint, rateLimit));

        assertSame(ResultEnum.PERMISSION_LIMIT, ex.getResultEnum());
        verify(joinPoint, never()).proceed();
    }

    /**
     * 验证 Redis Lua 返回 0 时保持原有限流拒绝行为。
     */
    @Test
    @DisplayName("should deny request when Redis script returns zero")
    void shouldDenyWhenRedisScriptReturnsZero() throws Throwable {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L);

        GeneralException ex = assertThrows(GeneralException.class, () -> aspect.around(joinPoint, rateLimit));

        assertSame(ResultEnum.PERMISSION_LIMIT, ex.getResultEnum());
        verify(joinPoint, never()).proceed();
    }

    /**
     * 验证 Redis Lua 返回 1 时合法请求继续执行。
     */
    @Test
    @DisplayName("should proceed when Redis script allows request")
    void shouldProceedWhenRedisScriptAllowsRequest() throws Throwable {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.around(joinPoint, rateLimit);

        assertEquals("ok", result);
        verify(joinPoint).proceed();
    }

    /**
     * 验证公共 proof 限流只使用直连 peer 的共享 v2 桶，不接受 JWT 租户或转发头分桶。
     */
    @Test
    @DisplayName("should use tenant-independent direct-peer bucket for public proof")
    @SuppressWarnings("unchecked")
    void shouldUseTenantIndependentDirectPeerBucketForPublicProof() throws Throwable {
        RateLimit publicRateLimit = PublicProofController.class
                .getMethod("getProofStatus", String.class)
                .getAnnotation(RateLimit.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.24");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader("X-Real-IP", "203.0.113.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        for (int index = 0; index < UserRole.values().length; index++) {
            UserRole role = UserRole.values()[index];
            MDC.put(Const.ATTR_USER_ROLE, role.getRole());
            TenantContext.setTenantId(42L + index);
            aspect.around(joinPoint, publicRateLimit);
        }

        RedisInvocations invocations = captureRedisInvocations(UserRole.values().length);
        assertEquals(
                List.of("rate:limit:public:proof-verification:v2:ip:198.51.100.24"),
                invocations.keys().stream().map(List::getFirst).distinct().toList());
        assertEquals(List.of("120"), invocations.limits().stream().distinct().toList());
        assertEquals(List.of("60"), invocations.periods().stream().distinct().toList());
        verify(joinPoint, times(UserRole.values().length)).proceed();
    }

    /**
     * 验证两个公共 proof 入口使用同一个精确 Redis key，不按方法拆分配额。
     */
    @Test
    @DisplayName("should share one bucket across both public proof endpoints")
    void shouldShareBucketAcrossBothPublicProofEndpoints() throws Throwable {
        RateLimit statusRateLimit = PublicProofController.class
                .getMethod("getProofStatus", String.class)
                .getAnnotation(RateLimit.class);
        RateLimit signingKeyRateLimit = PublicProofController.class
                .getMethod("getProofSigningKey", String.class, Integer.class)
                .getAnnotation(RateLimit.class);
        bindRequest("198.51.100.24", "203.0.113.1", "203.0.113.2");
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        aspect.around(joinPoint, statusRateLimit);
        aspect.around(joinPoint, signingKeyRateLimit);

        RedisInvocations invocations = captureRedisInvocations(2);
        assertEquals(
                List.of(
                        "rate:limit:public:proof-verification:v2:ip:198.51.100.24",
                        "rate:limit:public:proof-verification:v2:ip:198.51.100.24"),
                invocations.keys().stream().map(List::getFirst).toList());
    }

    /**
     * 验证不同 direct peer 使用不同公共桶，而调用者转发头不能改变身份。
     */
    @Test
    @DisplayName("should isolate public buckets by canonical direct peer")
    void shouldIsolatePublicBucketsByDirectPeer() throws Throwable {
        RateLimit publicRateLimit = PublicProofController.class
                .getMethod("getProofStatus", String.class)
                .getAnnotation(RateLimit.class);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        bindRequest("2001:0DB8:0:0:0:0:0:1", "203.0.113.7", null);
        aspect.around(joinPoint, publicRateLimit);
        bindRequest("2001:db8::2", "203.0.113.7", null);
        aspect.around(joinPoint, publicRateLimit);

        RedisInvocations invocations = captureRedisInvocations(2);
        assertEquals(
                List.of(
                        "rate:limit:public:proof-verification:v2:ip:2001:db8::1",
                        "rate:limit:public:proof-verification:v2:ip:2001:db8::2"),
                invocations.keys().stream().map(List::getFirst).toList());
    }

    /**
     * 验证 legacy USER/IP/API key、租户维度和类名方法名 fallback 逐字兼容。
     */
    @Test
    @DisplayName("should preserve exact legacy rate-limit keys")
    void shouldPreserveExactLegacyRateLimitKeys() throws Throwable {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        TenantContext.setTenantId(1L);
        aspect.around(joinPoint, annotation("limitedEndpoint"));

        TenantContext.setTenantId(42L);
        MDC.put(Const.ATTR_USER_ID, "123");
        aspect.around(joinPoint, annotation("userEndpoint"));
        MDC.remove(Const.ATTR_USER_ID);

        TenantContext.setTenantId(0L);
        bindRequest("198.51.100.24", "203.0.113.9, 10.0.0.2", "203.0.113.10");
        aspect.around(joinPoint, annotation("ipEndpoint"));

        Method fallbackMethod = LimitedController.class.getDeclaredMethod("fallbackEndpoint");
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getDeclaringType()).thenReturn(LimitedController.class);
        when(methodSignature.getMethod()).thenReturn(fallbackMethod);
        TenantContext.setTenantId(7L);
        aspect.around(joinPoint, fallbackMethod.getAnnotation(RateLimit.class));

        RedisInvocations invocations = captureRedisInvocations(4);
        assertEquals(
                List.of(
                        "rate:limit:test:t1:api",
                        "rate:limit:proof:archive:t42:u123",
                        "rate:limit:public:download:t0:ip203.0.113.9",
                        "rate:limit:LimitedController:fallbackEndpoint:t7:api"),
                invocations.keys().stream().map(List::getFirst).toList());
    }

    /**
     * 验证 legacy 未登录 USER 与 IP 请求的全部客户端地址回退路径逐字兼容。
     */
    @Test
    @DisplayName("should preserve exact legacy client-IP fallback keys")
    void shouldPreserveExactLegacyClientIpFallbackKeys() throws Throwable {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        TenantContext.setTenantId(11L);
        bindRequest("198.51.100.11", "203.0.113.11", null);
        aspect.around(joinPoint, annotation("userEndpoint"));

        TenantContext.setTenantId(12L);
        bindRequest("198.51.100.12", null, "203.0.113.12");
        aspect.around(joinPoint, annotation("ipEndpoint"));

        TenantContext.setTenantId(13L);
        bindRequest("198.51.100.13", null, null);
        aspect.around(joinPoint, annotation("ipEndpoint"));

        TenantContext.setTenantId(14L);
        RequestContextHolder.resetRequestAttributes();
        aspect.around(joinPoint, annotation("ipEndpoint"));

        RedisInvocations invocations = captureRedisInvocations(4);
        assertEquals(
                List.of(
                        "rate:limit:proof:archive:t11:ip203.0.113.11",
                        "rate:limit:public:download:t12:ip203.0.113.12",
                        "rate:limit:public:download:t13:ip198.51.100.13",
                        "rate:limit:public:download:t14:ipunknown"),
                invocations.keys().stream().map(List::getFirst).toList());
    }

    /**
     * 验证非公共注解继续使用原有角色阈值与默认五倍倍率。
     */
    @Test
    @DisplayName("should preserve legacy role-aware limits")
    void shouldPreserveLegacyRoleAwareLimits() throws Throwable {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);
        RateLimit explicitRoleLimits = annotation("roleEndpoint");

        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_DEFAULT.getRole());
        aspect.around(joinPoint, explicitRoleLimits);
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());
        aspect.around(joinPoint, explicitRoleLimits);
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_MONITOR.getRole());
        aspect.around(joinPoint, explicitRoleLimits);
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());
        aspect.around(joinPoint, annotation("limitedEndpoint"));

        RedisInvocations invocations = captureRedisInvocations(4);
        assertEquals(List.of("3", "7", "9", "10"), invocations.limits());
    }

    /**
     * 验证 Redis 依赖异常保持 fail closed，公共 controller 不会执行。
     */
    @Test
    @DisplayName("should fail closed when Redis execution throws")
    void shouldFailClosedWhenRedisExecutionThrows() throws Throwable {
        RateLimit publicRateLimit = PublicProofController.class
                .getMethod("getProofStatus", String.class)
                .getAnnotation(RateLimit.class);
        bindRequest("198.51.100.24", null, null);
        RuntimeException redisFailure = new RuntimeException("redis unavailable");
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenThrow(redisFailure);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> aspect.around(joinPoint, publicRateLimit));

        assertSame(redisFailure, thrown);
        verify(joinPoint, never()).proceed();
    }

    /**
     * 验证新注解字段默认保持 tenant scoped 与 legacy forwarding 行为。
     */
    @Test
    @DisplayName("should keep backward-compatible annotation defaults")
    void shouldKeepBackwardCompatibleAnnotationDefaults() {
        assertEquals(true, rateLimit.tenantScoped());
        assertSame(RateLimit.ClientIpMode.LEGACY_FORWARDED, rateLimit.clientIpMode());
    }

    /**
     * 验证半安全注解组合在访问 Redis 前拒绝，避免误配置形成可绕过桶。
     */
    @Test
    @DisplayName("should reject incomplete trusted-peer annotation mode")
    void shouldRejectIncompleteTrustedPeerAnnotationMode() throws Throwable {
        RateLimit invalid = annotation("invalidTrustedPeerEndpoint");

        assertThrows(IllegalStateException.class, () -> aspect.around(joinPoint, invalid));

        verify(stringRedisTemplate, never()).execute(
                any(DefaultRedisScript.class), anyList(), anyString(), anyString());
        verify(joinPoint, never()).proceed();
    }

    /**
     * 获取测试控制器指定方法的限流注解。
     */
    private RateLimit annotation(String methodName) throws NoSuchMethodException {
        return LimitedController.class.getDeclaredMethod(methodName).getAnnotation(RateLimit.class);
    }

    /**
     * 绑定具有 direct peer 和可选伪造转发头的当前请求。
     */
    private void bindRequest(String remoteAddr, String forwardedFor, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /**
     * 捕获指定次数的 Redis KEYS 与 ARGV 调用参数。
     */
    @SuppressWarnings("unchecked")
    private RedisInvocations captureRedisInvocations(int count) {
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> limits = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> periods = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate, times(count)).execute(
                any(DefaultRedisScript.class), keys.capture(), limits.capture(), periods.capture());
        return new RedisInvocations(keys.getAllValues(), limits.getAllValues(), periods.getAllValues());
    }

    private record RedisInvocations(
            List<List<String>> keys,
            List<String> limits,
            List<String> periods) {
    }

    private static class LimitedController {

        /**
         * 测试用 API 级限流方法。
         */
        @RateLimit(limit = 2, period = 10, type = RateLimit.LimitType.API, key = "test")
        void limitedEndpoint() {
        }

        /**
         * 测试 legacy USER key 与显式角色阈值。
         */
        @RateLimit(
                limit = 10,
                period = 60,
                adminLimit = 30,
                monitorLimit = 30,
                type = RateLimit.LimitType.USER,
                key = "proof:archive")
        void userEndpoint() {
        }

        /**
         * 测试 legacy IP 转发头优先级。
         */
        @RateLimit(limit = 30, period = 60, type = RateLimit.LimitType.IP, key = "public:download")
        void ipEndpoint() {
        }

        /**
         * 测试显式角色阈值。
         */
        @RateLimit(limit = 3, adminLimit = 7, monitorLimit = 9, type = RateLimit.LimitType.API, key = "roles")
        void roleEndpoint() {
        }

        /**
         * 测试空自定义 key 的类名和方法名 fallback。
         */
        @RateLimit(type = RateLimit.LimitType.API)
        void fallbackEndpoint() {
        }

        /**
         * 测试缺少 tenantScoped=false 的非法可信 peer 配置。
         */
        @RateLimit(type = RateLimit.LimitType.IP, clientIpMode = RateLimit.ClientIpMode.TRUSTED_PEER)
        void invalidTrustedPeerEndpoint() {
        }
    }
}
