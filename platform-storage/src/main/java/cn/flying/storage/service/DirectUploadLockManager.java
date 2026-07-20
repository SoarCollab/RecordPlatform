package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理直传分片 complete、abort 和生命周期清理之间的分布式互斥。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectUploadLockManager {

    private static final String LOCK_PREFIX = "storage:direct-upload:part-lock:";

    private final RedissonClient redissonClient;
    private final StorageProperties storageProperties;

    /**
     * 在配置的等待时间内获取分片锁，失败时关闭当前操作。
     *
     * @param descriptor staging 对象身份
     * @return 必须由当前线程关闭的锁句柄
     */
    public LockHandle acquire(DirectUploadStagingDescriptor descriptor) {
        int waitSeconds = storageProperties.getDirectUpload().getEffectiveLockWaitSeconds();
        return tryAcquire(descriptor, waitSeconds)
                .orElseThrow(() -> new IllegalStateException(
                        "direct-upload part is already being finalized"));
    }

    /**
     * 生命周期清理以非阻塞方式尝试获取分片锁，活跃分片会留到下一轮。
     *
     * @param descriptor staging 对象身份
     * @return 获取成功时返回锁句柄
     */
    public Optional<LockHandle> tryAcquireForCleanup(DirectUploadStagingDescriptor descriptor) {
        return tryAcquire(descriptor, 0);
    }

    /**
     * 使用 Redisson watchdog overload 获取锁，长时间流传输期间由 watchdog 自动续期。
     */
    private Optional<LockHandle> tryAcquire(DirectUploadStagingDescriptor descriptor, int waitSeconds) {
        String lockName = buildLockName(descriptor);
        RLock lock = redissonClient.getLock(lockName);
        try {
            if (!lock.tryLock(waitSeconds, TimeUnit.SECONDS)) {
                return Optional.empty();
            }
            return Optional.of(new RedissonLockHandle(lock, lockName));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while acquiring direct-upload part lock", e);
        }
    }

    /**
     * 构建 tenant/session/part 维度且不包含对象内容的低敏锁 key。
     */
    private String buildLockName(DirectUploadStagingDescriptor descriptor) {
        return LOCK_PREFIX
                + descriptor.tenantId()
                + ":"
                + descriptor.sessionId()
                + ":"
                + descriptor.partIndex();
    }

    /**
     * 只允许获取锁的线程释放一次，所有权已丢失时记录告警且不误解锁其他持有者。
     */
    public interface LockHandle extends AutoCloseable {

        /**
         * 释放当前操作持有的分片锁。
         */
        @Override
        void close();
    }

    /**
     * Redisson 锁句柄实现，保证同一线程最多释放一次。
     */
    private static final class RedissonLockHandle implements LockHandle {
        private final RLock lock;
        private final String lockName;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RedissonLockHandle(RLock lock, String lockName) {
            this.lock = lock;
            this.lockName = lockName;
        }

        /**
         * 直接进入 Redisson 的所有权校验与 renewal 取消路径；重复关闭是幂等操作。
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException e) {
                log.warn("直传分片锁在释放前已失去所有权: lock={}", lockName);
            } catch (RuntimeException e) {
                log.error("释放直传分片锁失败: lock={}", lockName, e);
            }
        }
    }
}
