package cn.flying.common.pool;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓冲区池管理器
 * 用于管理文件上传过程中使用的字节缓冲区，避免频繁创建和销毁缓冲区
 * 支持直接内存和堆内存两种缓冲区类型
 *
 * @author 王贝强
 * @date 2025-12-26
 */
@Slf4j
public class BufferPoolManager {

    /**
     * 默认缓冲区大小（5MB）
     */
    private static final int DEFAULT_BUFFER_SIZE = 5 * 1024 * 1024;

    /**
     * 最大池大小
     */
    private static final int MAX_POOL_SIZE = 50;

    /**
     * 最小空闲数量
     */
    private static final int MIN_IDLE = 5;
    /**
     * 单例实例
     */
    private static volatile BufferPoolManager instance;
    /**
     * 堆内存缓冲区池
     */
    private final GenericObjectPool<byte[]> heapBufferPool;
    /**
     * 直接内存缓冲区池（用于I/O操作）
     */
    private final GenericObjectPool<ByteBuffer> directBufferPool;
    /**
     * 缓冲区大小映射（支持不同大小的缓冲区）
     */
    private final ConcurrentHashMap<Integer, GenericObjectPool<byte[]>> sizedBufferPools = new ConcurrentHashMap<>();
    /**
     * 统计信息
     */
    private final AtomicLong totalBorrowed = new AtomicLong(0);
    private final AtomicLong totalReturned = new AtomicLong(0);
    private final AtomicLong totalCreated = new AtomicLong(0);

    /**
     * 私有构造函数
     */
    private BufferPoolManager() {
        // 初始化堆内存缓冲区池
        this.heapBufferPool = createHeapBufferPool(DEFAULT_BUFFER_SIZE);

        // 初始化直接内存缓冲区池
        this.directBufferPool = createDirectBufferPool(DEFAULT_BUFFER_SIZE);

        log.info("缓冲区池管理器初始化完成: 默认大小={}MB, 最大池大小={}",
                DEFAULT_BUFFER_SIZE / (1024 * 1024), MAX_POOL_SIZE);
    }

    /**
     * 创建堆内存缓冲区池
     */
    private GenericObjectPool<byte[]> createHeapBufferPool(int bufferSize) {
        GenericObjectPoolConfig<byte[]> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(MAX_POOL_SIZE);
        config.setMaxIdle(MAX_POOL_SIZE / 2);
        config.setMinIdle(MIN_IDLE);
        config.setTestOnBorrow(false);
        config.setTestOnReturn(false);
        config.setBlockWhenExhausted(true);
        config.setMaxWaitMillis(5000);

        return new GenericObjectPool<>(new BasePooledObjectFactory<byte[]>() {
            @Override
            public byte[] create() {
                totalCreated.incrementAndGet();
                log.debug("创建新的堆内存缓冲区: size={}", bufferSize);
                return new byte[bufferSize];
            }

            @Override
            public void passivateObject(PooledObject<byte[]> p) {
                // 归还时清空缓冲区（可选，视安全需求）
                // Arrays.fill(p.getObject(), (byte) 0);
            }

            @Override
            public PooledObject<byte[]> wrap(byte[] buffer) {
                return new DefaultPooledObject<>(buffer);
            }
        }, config);
    }

    /**
     * 创建直接内存缓冲区池
     */
    private GenericObjectPool<ByteBuffer> createDirectBufferPool(int bufferSize) {
        GenericObjectPoolConfig<ByteBuffer> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(MAX_POOL_SIZE / 2); // 直接内存更珍贵，池大小减半
        config.setMaxIdle(MAX_POOL_SIZE / 4);
        config.setMinIdle(MIN_IDLE / 2);
        config.setTestOnBorrow(false);
        config.setTestOnReturn(false);
        config.setBlockWhenExhausted(true);
        config.setMaxWaitMillis(5000);

        return new GenericObjectPool<>(new BasePooledObjectFactory<>() {
            @Override
            public ByteBuffer create() {
                totalCreated.incrementAndGet();
                log.debug("创建新的直接内存缓冲区: size={}", bufferSize);
                return ByteBuffer.allocateDirect(bufferSize);
            }

            @Override
            public void passivateObject(PooledObject<ByteBuffer> p) {
                // 归还时重置缓冲区位置
                p.getObject().clear();
            }

            @Override
            public PooledObject<ByteBuffer> wrap(ByteBuffer buffer) {
                return new DefaultPooledObject<>(buffer);
            }
        }, config);
    }

    /**
     * 获取单例实例
     */
    public static BufferPoolManager getInstance() {
        if (instance == null) {
            synchronized (BufferPoolManager.class) {
                if (instance == null) {
                    instance = new BufferPoolManager();
                }
            }
        }
        return instance;
    }

    /**
     * 借用指定大小的堆内存缓冲区
     *
     * @param size 缓冲区大小
     * @return 字节数组缓冲区
     */
    public byte[] borrowHeapBuffer(int size) {
        // 如果是默认大小，使用默认池
        if (size == DEFAULT_BUFFER_SIZE) {
            return borrowHeapBuffer();
        }

        // 获取或创建对应大小的缓冲区池
        GenericObjectPool<byte[]> pool = sizedBufferPools.computeIfAbsent(size,
                this::createHeapBufferPool);

        try {
            totalBorrowed.incrementAndGet();
            return pool.borrowObject();
        } catch (Exception e) {
            log.error("借用指定大小堆内存缓冲区失败: size={}", size, e);
            return new byte[size];
        }
    }

    /**
     * 借用堆内存缓冲区
     *
     * @return 字节数组缓冲区
     */
    public byte[] borrowHeapBuffer() {
        try {
            totalBorrowed.incrementAndGet();
            byte[] buffer = heapBufferPool.borrowObject();
            log.trace("借用堆内存缓冲区: size={}, 活跃={}, 空闲={}",
                    buffer.length, heapBufferPool.getNumActive(), heapBufferPool.getNumIdle());
            return buffer;
        } catch (Exception e) {
            log.error("借用堆内存缓冲区失败", e);
            // 降级：直接创建新缓冲区
            return new byte[DEFAULT_BUFFER_SIZE];
        }
    }

    /**
     * 借用直接内存缓冲区
     *
     * @return ByteBuffer缓冲区
     */
    public ByteBuffer borrowDirectBuffer() {
        try {
            totalBorrowed.incrementAndGet();
            ByteBuffer buffer = directBufferPool.borrowObject();
            buffer.clear(); // 确保缓冲区处于初始状态
            log.trace("借用直接内存缓冲区: capacity={}, 活跃={}, 空闲={}",
                    buffer.capacity(), directBufferPool.getNumActive(), directBufferPool.getNumIdle());
            return buffer;
        } catch (Exception e) {
            log.error("借用直接内存缓冲区失败", e);
            // 降级：创建堆内存ByteBuffer
            return ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
        }
    }

    /**
     * 归还堆内存缓冲区
     *
     * @param buffer 要归还的缓冲区
     */
    public void returnHeapBuffer(byte[] buffer) {
        if (buffer == null) {
            return;
        }

        try {
            totalReturned.incrementAndGet();

            // 根据大小选择对应的池
            if (buffer.length == DEFAULT_BUFFER_SIZE) {
                heapBufferPool.returnObject(buffer);
            } else {
                GenericObjectPool<byte[]> pool = sizedBufferPools.get(buffer.length);
                if (pool != null) {
                    pool.returnObject(buffer);
                }
                // 如果没有对应的池，则让GC回收
            }

            log.trace("归还堆内存缓冲区: size={}", buffer.length);
        } catch (Exception e) {
            log.error("归还堆内存缓冲区失败: size={}", buffer.length, e);
        }
    }

    /**
     * 归还直接内存缓冲区
     *
     * @param buffer 要归还的缓冲区
     */
    public void returnDirectBuffer(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }

        try {
            totalReturned.incrementAndGet();
            buffer.clear(); // 重置缓冲区
            directBufferPool.returnObject(buffer);
            log.trace("归还直接内存缓冲区: capacity={}", buffer.capacity());
        } catch (Exception e) {
            log.error("归还直接内存缓冲区失败", e);
        }
    }

    /**
     * 获取池状态信息
     */
    public PoolStatus getPoolStatus() {
        return new PoolStatus(
                heapBufferPool.getNumActive(),
                heapBufferPool.getNumIdle(),
                directBufferPool.getNumActive(),
                directBufferPool.getNumIdle(),
                totalBorrowed.get(),
                totalReturned.get(),
                totalCreated.get()
        );
    }

    /**
     * 清理池（在应用关闭时调用）
     */
    public void shutdown() {
        try {
            log.info("关闭缓冲区池管理器...");
            heapBufferPool.close();
            directBufferPool.close();
            sizedBufferPools.values().forEach(pool -> {
                try {
                    pool.close();
                } catch (Exception e) {
                    log.error("关闭缓冲区池失败", e);
                }
            });
            log.info("缓冲区池管理器已关闭");
        } catch (Exception e) {
            log.error("关闭缓冲区池管理器失败", e);
        }
    }

    /**
     * 池状态信息
     */
    public static class PoolStatus {
        public final int heapActive;
        public final int heapIdle;
        public final int directActive;
        public final int directIdle;
        public final long totalBorrowed;
        public final long totalReturned;
        public final long totalCreated;

        public PoolStatus(int heapActive, int heapIdle, int directActive, int directIdle,
                          long totalBorrowed, long totalReturned, long totalCreated) {
            this.heapActive = heapActive;
            this.heapIdle = heapIdle;
            this.directActive = directActive;
            this.directIdle = directIdle;
            this.totalBorrowed = totalBorrowed;
            this.totalReturned = totalReturned;
            this.totalCreated = totalCreated;
        }

        @Override
        public String toString() {
            return String.format(
                    "PoolStatus{heap[active=%d, idle=%d], direct[active=%d, idle=%d], " +
                            "total[borrowed=%d, returned=%d, created=%d]}",
                    heapActive, heapIdle, directActive, directIdle,
                    totalBorrowed, totalReturned, totalCreated
            );
        }
    }
}