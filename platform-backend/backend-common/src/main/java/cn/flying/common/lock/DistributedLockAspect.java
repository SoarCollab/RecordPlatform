package cn.flying.common.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 分布式锁切面。
 * 基于 Redisson 实现，拦截 @DistributedLock 注解的方法。
 */
@Slf4j
@Aspect
@Order(1)
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final RedissonClient redissonClient;

    private static final String LOCK_KEY_PREFIX = "distributed:lock:";

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String lockKey = buildLockKey(joinPoint, distributedLock);
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired = false;
        try {
            acquired = lock.tryLock(
                    distributedLock.waitTime(),
                    distributedLock.leaseTime(),
                    distributedLock.timeUnit()
            );

            if (!acquired) {
                log.debug("获取分布式锁失败: key={}", lockKey);
                if (distributedLock.throwOnFailure()) {
                    throw new DistributedLockException("获取分布式锁失败: " + lockKey);
                }
                return null;
            }

            log.debug("获取分布式锁成功: key={}", lockKey);
            return joinPoint.proceed();

        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                    log.debug("释放分布式锁: key={}", lockKey);
                } catch (IllegalMonitorStateException e) {
                    log.warn("释放分布式锁异常（可能已过期）: key={}", lockKey);
                }
            }
        }
    }

    private String buildLockKey(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) {
        String key = distributedLock.key();
        if (key == null || key.isEmpty()) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            key = method.getDeclaringClass().getSimpleName() + ":" + method.getName();
        }
        return LOCK_KEY_PREFIX + key;
    }
}
