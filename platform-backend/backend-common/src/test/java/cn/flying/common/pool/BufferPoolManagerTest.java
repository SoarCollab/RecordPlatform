package cn.flying.common.pool;

import org.junit.jupiter.api.*;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BufferPoolManager 测试类
 * 测试缓冲池管理器的核心功能
 */
class BufferPoolManagerTest {

    private BufferPoolManager poolManager;

    @BeforeEach
    void setUp() {
        poolManager = BufferPoolManager.getInstance();
    }

    @Test
    @DisplayName("测试获取单例实例")
    void testGetInstance() {
        // When
        BufferPoolManager instance1 = BufferPoolManager.getInstance();
        BufferPoolManager instance2 = BufferPoolManager.getInstance();

        // Then
        assertNotNull(instance1, "实例不应为null");
        assertSame(instance1, instance2, "应该返回相同的单例实例");
    }

    @Test
    @DisplayName("测试借用堆内存缓冲区")
    void testBorrowHeapBuffer() {
        // When
        byte[] buffer = poolManager.borrowHeapBuffer();

        // Then
        assertNotNull(buffer, "缓冲区不应为null");
        assertEquals(5 * 1024 * 1024, buffer.length, "默认缓冲区大小应为5MB");
    }

    @Test
    @DisplayName("测试归还堆内存缓冲区")
    void testReturnHeapBuffer() {
        // Given
        byte[] buffer = poolManager.borrowHeapBuffer();

        // When & Then
        assertDoesNotThrow(() -> poolManager.returnHeapBuffer(buffer),
                "归还缓冲区不应抛出异常");
    }

    @Test
    @DisplayName("测试借用和归还循环")
    void testBorrowReturnCycle() {
        // Given
        int cycles = 10;
        
        // When & Then
        for (int i = 0; i < cycles; i++) {
            byte[] buffer = poolManager.borrowHeapBuffer();
            assertNotNull(buffer, "每次借用都应该成功");
            poolManager.returnHeapBuffer(buffer);
        }
    }

    @Test
    @DisplayName("测试借用指定大小的缓冲区")
    void testBorrowHeapBufferWithSize() {
        // Given
        int customSize = 1024 * 1024; // 1MB

        // When
        byte[] buffer = poolManager.borrowHeapBuffer(customSize);

        // Then
        assertNotNull(buffer, "缓冲区不应为null");
        assertEquals(customSize, buffer.length, "缓冲区大小应该符合指定大小");
    }

    @Test
    @DisplayName("测试归还null缓冲区")
    void testReturnNullBuffer() {
        // When & Then
        assertDoesNotThrow(() -> poolManager.returnHeapBuffer(null),
                "归还null缓冲区不应抛出异常");
    }

    @Test
    @DisplayName("测试借用直接内存缓冲区")
    void testBorrowDirectBuffer() {
        // When
        ByteBuffer buffer = poolManager.borrowDirectBuffer();

        // Then
        assertNotNull(buffer, "直接内存缓冲区不应为null");
        assertTrue(buffer.isDirect(), "应该是直接内存缓冲区");
        assertEquals(5 * 1024 * 1024, buffer.capacity(), "默认容量应为5MB");
        assertEquals(0, buffer.position(), "位置应该为0");
    }

    @Test
    @DisplayName("测试归还直接内存缓冲区")
    void testReturnDirectBuffer() {
        // Given
        ByteBuffer buffer = poolManager.borrowDirectBuffer();

        // When & Then
        assertDoesNotThrow(() -> poolManager.returnDirectBuffer(buffer),
                "归还直接内存缓冲区不应抛出异常");
    }

    @Test
    @DisplayName("测试归还null直接内存缓冲区")
    void testReturnNullDirectBuffer() {
        // When & Then
        assertDoesNotThrow(() -> poolManager.returnDirectBuffer(null),
                "归还null直接内存缓冲区不应抛出异常");
    }

    @Test
    @DisplayName("测试直接内存缓冲区状态重置")
    void testDirectBufferReset() {
        // Given
        ByteBuffer buffer = poolManager.borrowDirectBuffer();
        buffer.put(new byte[100]); // 修改缓冲区状态

        // When
        poolManager.returnDirectBuffer(buffer);
        ByteBuffer reusedBuffer = poolManager.borrowDirectBuffer();

        // Then
        assertEquals(0, reusedBuffer.position(), "重新借用的缓冲区位置应该重置为0");
    }

    @Test
    @DisplayName("测试获取池状态信息")
    void testGetPoolStatus() {
        // When
        BufferPoolManager.PoolStatus status = poolManager.getPoolStatus();

        // Then
        assertNotNull(status, "池状态不应为null");
        assertTrue(status.totalBorrowed >= 0, "总借用数应该非负");
        assertTrue(status.totalReturned >= 0, "总归还数应该非负");
        assertTrue(status.totalCreated >= 0, "总创建数应该非负");
    }

    @Test
    @DisplayName("测试池状态统计准确性")
    void testPoolStatusAccuracy() {
        // Given
        BufferPoolManager.PoolStatus beforeStatus = poolManager.getPoolStatus();
        long borrowedBefore = beforeStatus.totalBorrowed;
        
        // When
        byte[] buffer = poolManager.borrowHeapBuffer();
        poolManager.returnHeapBuffer(buffer);
        
        BufferPoolManager.PoolStatus afterStatus = poolManager.getPoolStatus();

        // Then
        assertEquals(borrowedBefore + 1, afterStatus.totalBorrowed,
                "借用计数应该增加1");
    }

    @Test
    @DisplayName("测试并发借用堆内存缓冲区")
    void testConcurrentBorrowHeapBuffer() throws InterruptedException {
        // Given
        int threadCount = 10;
        int borrowsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < borrowsPerThread; j++) {
                        byte[] buffer = poolManager.borrowHeapBuffer();
                        assertNotNull(buffer);
                        // 模拟使用缓冲区
                        Thread.sleep(1);
                        poolManager.returnHeapBuffer(buffer);
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        assertTrue(exceptions.isEmpty(), "并发操作不应该产生异常");
    }

    @Test
    @DisplayName("测试并发借用直接内存缓冲区")
    void testConcurrentBorrowDirectBuffer() throws InterruptedException {
        // Given
        int threadCount = 5;
        int borrowsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < borrowsPerThread; j++) {
                        ByteBuffer buffer = poolManager.borrowDirectBuffer();
                        assertNotNull(buffer);
                        assertTrue(buffer.isDirect());
                        Thread.sleep(1);
                        poolManager.returnDirectBuffer(buffer);
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        assertTrue(exceptions.isEmpty(), "并发操作不应该产生异常");
    }

    @Test
    @DisplayName("测试多种大小的缓冲区池")
    void testMultipleSizeBufferPools() {
        // Given
        int size1 = 1024 * 1024; // 1MB
        int size2 = 2 * 1024 * 1024; // 2MB
        int size3 = 10 * 1024 * 1024; // 10MB

        // When
        byte[] buffer1 = poolManager.borrowHeapBuffer(size1);
        byte[] buffer2 = poolManager.borrowHeapBuffer(size2);
        byte[] buffer3 = poolManager.borrowHeapBuffer(size3);

        // Then
        assertEquals(size1, buffer1.length, "第一个缓冲区大小应为1MB");
        assertEquals(size2, buffer2.length, "第二个缓冲区大小应为2MB");
        assertEquals(size3, buffer3.length, "第三个缓冲区大小应为10MB");

        // Cleanup
        poolManager.returnHeapBuffer(buffer1);
        poolManager.returnHeapBuffer(buffer2);
        poolManager.returnHeapBuffer(buffer3);
    }

    @Test
    @DisplayName("测试缓冲区重用")
    void testBufferReuse() {
        // Given
        byte[] buffer1 = poolManager.borrowHeapBuffer();
        byte[] marker = new byte[] {1, 2, 3, 4, 5};
        System.arraycopy(marker, 0, buffer1, 0, marker.length);
        
        // When
        poolManager.returnHeapBuffer(buffer1);
        byte[] buffer2 = poolManager.borrowHeapBuffer();

        // Then
        // 可能是同一个缓冲区（池重用），但这取决于池的实现
        assertNotNull(buffer2, "重新借用的缓冲区不应为null");
        assertEquals(5 * 1024 * 1024, buffer2.length, "缓冲区大小应该一致");
    }

    @Test
    @DisplayName("测试池状态toString方法")
    void testPoolStatusToString() {
        // When
        BufferPoolManager.PoolStatus status = poolManager.getPoolStatus();
        String statusStr = status.toString();

        // Then
        assertNotNull(statusStr, "状态字符串不应为null");
        assertTrue(statusStr.contains("heap"), "状态字符串应包含heap信息");
        assertTrue(statusStr.contains("direct"), "状态字符串应包含direct信息");
        assertTrue(statusStr.contains("borrowed"), "状态字符串应包含borrowed信息");
    }

    @Test
    @DisplayName("测试大量缓冲区借用")
    void testMassiveBorrow() {
        // Given
        int count = 40; // 减少到40以避免超过池大小限制（MAX_POOL_SIZE=50）
        List<byte[]> buffers = new ArrayList<>();

        // When
        for (int i = 0; i < count; i++) {
            buffers.add(poolManager.borrowHeapBuffer());
        }

        // Then
        assertEquals(count, buffers.size(), "应该成功借用指定数量的缓冲区");
        
        // Cleanup - 确保所有缓冲区都被归还
        for (byte[] buffer : buffers) {
            poolManager.returnHeapBuffer(buffer);
        }
    }

    @Test
    @DisplayName("测试缓冲区内容独立性")
    void testBufferIndependence() {
        // Given
        byte[] buffer1 = poolManager.borrowHeapBuffer();
        byte[] buffer2 = poolManager.borrowHeapBuffer();
        
        // When
        buffer1[0] = 1;
        buffer1[1] = 2;
        
        // Then
        // 如果是不同的缓冲区，buffer2不应受影响
        // 注意：如果池很小，可能借到同一个已归还的缓冲区
        assertNotSame(buffer1, buffer2, "应该是不同的缓冲区对象");
        
        // Cleanup
        poolManager.returnHeapBuffer(buffer1);
        poolManager.returnHeapBuffer(buffer2);
    }

    @Test
    @DisplayName("测试极小缓冲区")
    void testTinyBuffer() {
        // Given
        int tinySize = 1024; // 1KB

        // When
        byte[] buffer = poolManager.borrowHeapBuffer(tinySize);

        // Then
        assertNotNull(buffer, "应该能借用极小缓冲区");
        assertEquals(tinySize, buffer.length, "缓冲区大小应该符合要求");
        
        // Cleanup
        poolManager.returnHeapBuffer(buffer);
    }

    @Test
    @DisplayName("测试超大缓冲区")
    void testHugeBuffer() {
        // Given
        int hugeSize = 50 * 1024 * 1024; // 50MB

        // When
        byte[] buffer = poolManager.borrowHeapBuffer(hugeSize);

        // Then
        assertNotNull(buffer, "应该能借用超大缓冲区");
        assertEquals(hugeSize, buffer.length, "缓冲区大小应该符合要求");
        
        // Cleanup
        poolManager.returnHeapBuffer(buffer);
    }

    @Test
    @DisplayName("测试混合借用堆内存和直接内存缓冲区")
    void testMixedBorrow() {
        // When
        byte[] heapBuffer1 = poolManager.borrowHeapBuffer();
        ByteBuffer directBuffer1 = poolManager.borrowDirectBuffer();
        byte[] heapBuffer2 = poolManager.borrowHeapBuffer();
        ByteBuffer directBuffer2 = poolManager.borrowDirectBuffer();

        // Then
        assertNotNull(heapBuffer1, "堆缓冲区1不应为null");
        assertNotNull(directBuffer1, "直接缓冲区1不应为null");
        assertNotNull(heapBuffer2, "堆缓冲区2不应为null");
        assertNotNull(directBuffer2, "直接缓冲区2不应为null");
        
        assertTrue(directBuffer1.isDirect(), "应该是直接内存");
        assertTrue(directBuffer2.isDirect(), "应该是直接内存");

        // Cleanup
        poolManager.returnHeapBuffer(heapBuffer1);
        poolManager.returnDirectBuffer(directBuffer1);
        poolManager.returnHeapBuffer(heapBuffer2);
        poolManager.returnDirectBuffer(directBuffer2);
    }

    @Test
    @DisplayName("测试缓冲区归还顺序无关性")
    void testReturnOrderIndependence() {
        // Given
        byte[] buffer1 = poolManager.borrowHeapBuffer();
        byte[] buffer2 = poolManager.borrowHeapBuffer();
        byte[] buffer3 = poolManager.borrowHeapBuffer();

        // When & Then - 以不同顺序归还
        assertDoesNotThrow(() -> {
            poolManager.returnHeapBuffer(buffer2);
            poolManager.returnHeapBuffer(buffer1);
            poolManager.returnHeapBuffer(buffer3);
        }, "归还顺序应该不影响正确性");
    }

    @Test
    @DisplayName("测试性能 - 借用归还速度")
    void testPerformance() {
        // Given
        int iterations = 1000;
        long startTime = System.currentTimeMillis();

        // When
        for (int i = 0; i < iterations; i++) {
            byte[] buffer = poolManager.borrowHeapBuffer();
            poolManager.returnHeapBuffer(buffer);
        }
        long duration = System.currentTimeMillis() - startTime;

        // Then
        double opsPerSecond = (double) iterations / duration * 1000;
        System.out.printf("缓冲池操作速率: %.2f ops/s (耗时 %d ms)%n", opsPerSecond, duration);
        assertTrue(opsPerSecond > 1000, "操作速率应该大于1000 ops/s");
    }

    @Test
    @DisplayName("测试直接内存缓冲区可写入和读取")
    void testDirectBufferReadWrite() {
        // Given
        ByteBuffer buffer = poolManager.borrowDirectBuffer();
        byte[] testData = "Hello, World!".getBytes();

        // When
        buffer.put(testData);
        buffer.flip();
        byte[] readData = new byte[testData.length];
        buffer.get(readData);

        // Then
        assertArrayEquals(testData, readData, "读取的数据应该与写入的数据一致");

        // Cleanup
        poolManager.returnDirectBuffer(buffer);
    }
}
