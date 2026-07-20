package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证直传分片 watchdog 锁的 key、等待、中断和所有权释放语义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectUploadLockManager Unit Tests")
class DirectUploadLockManagerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private DirectUploadLockManager lockManager;
    private DirectUploadStagingDescriptor descriptor;
    private StorageProperties properties;

    /**
     * 使用真实配置默认值创建锁管理器和规范分片身份。
     */
    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        lockManager = new DirectUploadLockManager(redissonClient, properties);
        descriptor = new DirectUploadStagingDescriptor(
                7L,
                "session-1",
                3,
                "node-a",
                "tenant/7/staging/direct-upload/session-1/part-3"
        );
        when(redissonClient.getLock("storage:direct-upload:part-lock:7:session-1:3"))
                .thenReturn(lock);
    }

    /**
     * 清理可能由中断测试设置的线程中断标志。
     */
    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("acquire should use watchdog overload and release only once")
    void shouldAcquireAndReleaseOnlyOnce() throws Exception {
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);

        DirectUploadLockManager.LockHandle handle = lockManager.acquire(descriptor);
        handle.close();
        handle.close();

        verify(lock).tryLock(5, TimeUnit.SECONDS);
        verify(lock).unlock();
        verify(lock, never()).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("cleanup should be nonblocking and preserve a busy part")
    void shouldNotBlockCleanupWhenPartIsBusy() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        Optional<DirectUploadLockManager.LockHandle> result =
                lockManager.tryAcquireForCleanup(descriptor);

        assertThat(result).isEmpty();
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("normal acquire should fail closed when lock is unavailable")
    void shouldFailClosedWhenLockIsUnavailable() throws Exception {
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> lockManager.acquire(descriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already being finalized");
    }

    @Test
    @DisplayName("interruption should be preserved and wrapped")
    void shouldPreserveInterruptStatus() throws Exception {
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

        assertThatThrownBy(() -> lockManager.acquire(descriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interrupted");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    @DisplayName("close should enter Redisson unlock path and tolerate lost ownership")
    void shouldTolerateLostOwnershipFromUnlock() throws Exception {
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        doThrow(new IllegalMonitorStateException("ownership lost")).when(lock).unlock();

        lockManager.acquire(descriptor).close();

        verify(lock).unlock();
        verify(lock, never()).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("transient Redis failure should still pass through Redisson unlock cancellation path once")
    void shouldEnterUnlockCancellationPathWhenRedisFailsTransiently() throws Exception {
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        doThrow(new RedisConnectionException("Redis temporarily unavailable")).when(lock).unlock();

        DirectUploadLockManager.LockHandle handle = lockManager.acquire(descriptor);
        handle.close();
        handle.close();

        verify(lock).unlock();
        verify(lock, never()).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("reentrant handles should release every acquired hold count")
    void shouldReleaseEveryReentrantHoldCount() throws Exception {
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true, true);

        DirectUploadLockManager.LockHandle outer = lockManager.acquire(descriptor);
        DirectUploadLockManager.LockHandle inner = lockManager.acquire(descriptor);
        inner.close();
        outer.close();

        verify(lock, times(2)).tryLock(5, TimeUnit.SECONDS);
        verify(lock, times(2)).unlock();
        verify(lock, never()).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("transfer timeout should not disable Redisson watchdog renewal")
    void shouldAlwaysUseWatchdogRegardlessOfTransferTimeout() throws Exception {
        properties.getDirectUpload().setTransferTimeoutSeconds(Integer.MAX_VALUE);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);

        lockManager.acquire(descriptor).close();

        verify(lock).tryLock(5, TimeUnit.SECONDS);
        verify(lock).unlock();
    }
}
