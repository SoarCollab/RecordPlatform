package cn.flying.common.lock;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DistributedLockAspect Tests")
class DistributedLockAspectTest {

    static class Target {

        @DistributedLock(key = "#p0", waitTime = 0, leaseTime = 1, timeUnit = TimeUnit.SECONDS, throwOnFailure = true)
        public String byArg(String userId) {
            return "ok";
        }

        @DistributedLock(key = "literal:key", waitTime = 0, leaseTime = 1, timeUnit = TimeUnit.SECONDS, throwOnFailure = true)
        public String literal(String userId) {
            return "ok";
        }

        @DistributedLock(waitTime = 0, leaseTime = 1, timeUnit = TimeUnit.SECONDS, throwOnFailure = true)
        public String defaultKey(String userId) {
            return "ok";
        }
    }

    @Test
    @DisplayName("should evaluate SpEL key when key contains #")
    void shouldEvaluateSpelKeyWhenKeyContainsHash() throws Throwable {
        Target target = new Target();
        Method method = Target.class.getMethod("byArg", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"42"});
        when(joinPoint.proceed()).thenReturn("ok");

        DistributedLockAspect aspect = new DistributedLockAspect(redissonClient);
        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("ok");
        verify(redissonClient).getLock("distributed:lock:42");
        verify(lock).unlock();
    }

    @Test
    @DisplayName("should use literal key when key has no #")
    void shouldUseLiteralKeyWhenKeyHasNoHash() throws Throwable {
        Target target = new Target();
        Method method = Target.class.getMethod("literal", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.proceed()).thenReturn("ok");

        DistributedLockAspect aspect = new DistributedLockAspect(redissonClient);
        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("ok");
        verify(redissonClient).getLock("distributed:lock:literal:key");
        verify(lock).unlock();
    }

    @Test
    @DisplayName("should fallback to default key when key is empty")
    void shouldFallbackToDefaultKeyWhenKeyIsEmpty() throws Throwable {
        Target target = new Target();
        Method method = Target.class.getMethod("defaultKey", String.class);
        DistributedLock annotation = method.getAnnotation(DistributedLock.class);

        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.proceed()).thenReturn("ok");

        DistributedLockAspect aspect = new DistributedLockAspect(redissonClient);
        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("ok");
        verify(redissonClient).getLock("distributed:lock:Target:defaultKey");
        verify(lock).unlock();
    }
}
