package cn.flying.common.util;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnowflakeIdGenerator 测试类
 * 测试雪花算法ID生成器的核心功能
 */
class SnowflakeIdGeneratorTest {

    private SnowflakeIdGenerator generator;

    @BeforeEach
    void setUp() throws Exception {
        generator = new SnowflakeIdGenerator();
        // 设置测试用的数据中心ID和工作节点ID
        ReflectionTestUtils.setField(generator, "dataCenterId", 1);
        ReflectionTestUtils.setField(generator, "workerId", 1);
        // 调用初始化方法
        generator.afterPropertiesSet();
    }

    @Test
    @DisplayName("测试基本ID生成")
    void testNextId_generatesValidId() {
        // Given & When
        long id = generator.nextId();

        // Then
        assertTrue(id > 0, "生成的ID应该大于0");
    }

    @Test
    @DisplayName("测试生成的ID唯一性")
    void testNextId_generatesUniqueIds() {
        // Given
        int count = 10000;
        Set<Long> ids = new HashSet<>();

        // When
        for (int i = 0; i < count; i++) {
            long id = generator.nextId();
            ids.add(id);
        }

        // Then
        assertEquals(count, ids.size(), "生成的ID应该全部唯一");
    }

    @Test
    @DisplayName("测试ID递增性")
    void testNextId_generatesIncreasingIds() {
        // Given
        long previousId = 0;
        boolean isIncreasing = true;

        // When
        for (int i = 0; i < 1000; i++) {
            long currentId = generator.nextId();
            if (currentId <= previousId) {
                isIncreasing = false;
                break;
            }
            previousId = currentId;
        }

        // Then
        assertTrue(isIncreasing, "生成的ID应该递增");
    }

    @Test
    @DisplayName("测试并发ID生成 - 多线程环境")
    void testNextId_concurrentGeneration() throws InterruptedException {
        // Given
        int threadCount = 10;
        int idsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Set<Long> allIds = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < idsPerThread; j++) {
                        allIds.add(generator.nextId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        assertEquals(threadCount * idsPerThread, allIds.size(),
                "并发生成的ID应该全部唯一");
    }

    @Test
    @DisplayName("测试提取时间戳")
    void testExtractTimestamp() {
        // Given
        long beforeTimestamp = System.currentTimeMillis();
        long id = generator.nextId();
        long afterTimestamp = System.currentTimeMillis();

        // When
        long extractedTimestamp = generator.extractTimestamp(id);

        // Then
        assertTrue(extractedTimestamp >= beforeTimestamp - 1000,
                "提取的时间戳应该在生成时间前后");
        assertTrue(extractedTimestamp <= afterTimestamp + 1000,
                "提取的时间戳应该在生成时间前后");
    }

    @Test
    @DisplayName("测试提取数据中心ID")
    void testExtractDataCenterId() {
        // Given
        long id = generator.nextId();

        // When
        int extractedDataCenterId = generator.extractDataCenterId(id);

        // Then
        assertEquals(1, extractedDataCenterId, "提取的数据中心ID应该与设置值一致");
    }

    @Test
    @DisplayName("测试提取工作节点ID")
    void testExtractWorkerId() {
        // Given
        long id = generator.nextId();

        // When
        int extractedWorkerId = generator.extractWorkerId(id);

        // Then
        assertEquals(1, extractedWorkerId, "提取的工作节点ID应该与设置值一致");
    }

    @Test
    @DisplayName("测试提取序列号")
    void testExtractSequence() {
        // Given
        long id = generator.nextId();

        // When
        int sequence = generator.extractSequence(id);

        // Then
        assertTrue(sequence >= 0, "序列号应该大于等于0");
        assertTrue(sequence < 4096, "序列号应该小于4096");
    }

    @Test
    @DisplayName("测试同一毫秒内生成多个ID")
    void testNextId_multipleIdsInSameMillisecond() {
        // Given
        Set<Long> ids = new HashSet<>();
        int count = 100;

        // When - 快速生成多个ID，可能在同一毫秒内
        for (int i = 0; i < count; i++) {
            ids.add(generator.nextId());
        }

        // Then
        assertEquals(count, ids.size(), "即使在同一毫秒内，ID也应该唯一");
    }

    @Test
    @DisplayName("测试ID的位结构")
    void testNextId_bitStructure() {
        // Given & When
        long id = generator.nextId();

        // Then - 验证ID的位结构
        // ID应该是正数（最高位为0）
        assertTrue(id > 0, "ID最高位应该为0（符号位）");

        // 提取各部分并验证
        long timestamp = generator.extractTimestamp(id);
        int dataCenterId = generator.extractDataCenterId(id);
        int workerId = generator.extractWorkerId(id);
        int sequence = generator.extractSequence(id);

        // 所有部分应该在有效范围内
        assertTrue(timestamp > 0, "时间戳应该大于0");
        assertTrue(dataCenterId >= 0 && dataCenterId < 32, "数据中心ID应该在[0,31]范围内");
        assertTrue(workerId >= 0 && workerId < 32, "工作节点ID应该在[0,31]范围内");
        assertTrue(sequence >= 0 && sequence < 4096, "序列号应该在[0,4095]范围内");
    }

    @Test
    @DisplayName("测试高并发下的性能")
    void testNextId_performance() throws InterruptedException {
        // Given
        int iterations = 100000;
        CountDownLatch latch = new CountDownLatch(1);

        // When
        long startTime = System.currentTimeMillis();
        Thread[] threads = IntStream.range(0, 10)
                .mapToObj(i -> new Thread(() -> {
                    try {
                        latch.await();
                        for (int j = 0; j < iterations / 10; j++) {
                            generator.nextId();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }))
                .toArray(Thread[]::new);

        for (Thread thread : threads) {
            thread.start();
        }

        latch.countDown();

        for (Thread thread : threads) {
            thread.join();
        }
        long duration = System.currentTimeMillis() - startTime;

        // Then
        double idsPerSecond = (double) iterations / duration * 1000;
        System.out.printf("生成 %d 个ID 耗时 %d ms, 速率: %.2f ids/s%n",
                iterations, duration, idsPerSecond);
        assertTrue(idsPerSecond > 10000, "ID生成速率应该大于10000/s");
    }

    @Test
    @DisplayName("测试序列号溢出场景")
    void testNextId_sequenceOverflow() {
        // Given - 快速生成大量ID，触发序列号溢出
        Set<Long> ids = new HashSet<>();
        int count = 5000; // 超过单个毫秒的最大序列号(4096)

        // When
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            ids.add(generator.nextId());
        }
        long duration = System.currentTimeMillis() - startTime;

        // Then
        assertEquals(count, ids.size(), "即使序列号溢出，ID也应该唯一");
        System.out.printf("生成 %d 个ID 耗时 %d ms%n", count, duration);
    }

    @Test
    @DisplayName("测试自动配置数据中心ID")
    void testAutoConfigureDataCenterId() throws Exception {
        // Given
        SnowflakeIdGenerator autoGenerator = new SnowflakeIdGenerator();
        ReflectionTestUtils.setField(autoGenerator, "dataCenterId", -1); // 无效值
        ReflectionTestUtils.setField(autoGenerator, "workerId", 1);

        // When
        autoGenerator.afterPropertiesSet();
        long id = autoGenerator.nextId();

        // Then
        int dataCenterId = autoGenerator.extractDataCenterId(id);
        assertTrue(dataCenterId >= 0 && dataCenterId < 32,
                "自动配置的数据中心ID应该在有效范围内");
    }

    @Test
    @DisplayName("测试自动配置工作节点ID")
    void testAutoConfigureWorkerId() throws Exception {
        // Given
        SnowflakeIdGenerator autoGenerator = new SnowflakeIdGenerator();
        ReflectionTestUtils.setField(autoGenerator, "dataCenterId", 1);
        ReflectionTestUtils.setField(autoGenerator, "workerId", -1); // 无效值

        // When
        autoGenerator.afterPropertiesSet();
        long id = autoGenerator.nextId();

        // Then
        int workerId = autoGenerator.extractWorkerId(id);
        assertTrue(workerId >= 0 && workerId < 32,
                "自动配置的工作节点ID应该在有效范围内");
    }

    @Test
    @DisplayName("测试ID格式一致性")
    void testNextId_formatConsistency() {
        // Given
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ids.add(generator.nextId());
        }

        // When & Then - 验证所有ID都有一致的格式
        for (long id : ids) {
            // ID长度应该在合理范围内（19位数字左右）
            String idStr = String.valueOf(id);
            assertTrue(idStr.length() >= 15 && idStr.length() <= 20,
                    "ID长度应该在合理范围内: " + idStr);

            // 验证可以正确提取各个部分
            assertDoesNotThrow(() -> {
                generator.extractTimestamp(id);
                generator.extractDataCenterId(id);
                generator.extractWorkerId(id);
                generator.extractSequence(id);
            }, "应该能够正确提取ID的各个部分");
        }
    }

    @Test
    @DisplayName("测试边界值 - 最大数据中心ID")
    void testWithMaxDataCenterId() throws Exception {
        // Given
        SnowflakeIdGenerator maxDcGenerator = new SnowflakeIdGenerator();
        ReflectionTestUtils.setField(maxDcGenerator, "dataCenterId", 31); // 最大值
        ReflectionTestUtils.setField(maxDcGenerator, "workerId", 1);

        // When
        maxDcGenerator.afterPropertiesSet();
        long id = maxDcGenerator.nextId();

        // Then
        assertEquals(31, maxDcGenerator.extractDataCenterId(id),
                "应该能够使用最大数据中心ID");
    }

    @Test
    @DisplayName("测试边界值 - 最大工作节点ID")
    void testWithMaxWorkerId() throws Exception {
        // Given
        SnowflakeIdGenerator maxWorkerGenerator = new SnowflakeIdGenerator();
        ReflectionTestUtils.setField(maxWorkerGenerator, "dataCenterId", 1);
        ReflectionTestUtils.setField(maxWorkerGenerator, "workerId", 31); // 最大值

        // When
        maxWorkerGenerator.afterPropertiesSet();
        long id = maxWorkerGenerator.nextId();

        // Then
        assertEquals(31, maxWorkerGenerator.extractWorkerId(id),
                "应该能够使用最大工作节点ID");
    }

    @Test
    @DisplayName("测试从环境变量读取配置")
    void testConfigFromEnvironment() throws Exception {
        // Given - 这个测试验证环境变量配置逻辑存在
        // 实际环境变量的设置需要在JVM启动时完成，这里只验证初始化不会出错
        SnowflakeIdGenerator envGenerator = new SnowflakeIdGenerator();
        ReflectionTestUtils.setField(envGenerator, "dataCenterId", -1);
        ReflectionTestUtils.setField(envGenerator, "workerId", -1);

        // When & Then - 应该能够自动配置并正常工作
        assertDoesNotThrow(() -> {
            envGenerator.afterPropertiesSet();
            long id = envGenerator.nextId();
            assertTrue(id > 0, "应该能够生成有效ID");
        });
    }
}
