package cn.flying.aspect;

import cn.flying.common.annotation.RateLimit;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.UserRole;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ProceedingJoinPoint joinPoint;

    private RateLimitAspect aspect;
    private Method limitedMethod;
    private RateLimit rateLimit;

    /**
     * 初始化限流切面和测试用注解方法。
     */
    @BeforeEach
    void setUp() throws NoSuchMethodException {
        aspect = new RateLimitAspect();
        ReflectionTestUtils.setField(aspect, "stringRedisTemplate", stringRedisTemplate);
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
        TenantContext.clear();
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

    private static class LimitedController {

        /**
         * 测试用 API 级限流方法。
         */
        @RateLimit(limit = 2, period = 10, type = RateLimit.LimitType.API, key = "test")
        void limitedEndpoint() {
        }
    }
}
