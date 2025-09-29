package cn.flying.minio.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * NodeMetrics测试类
 * 覆盖所有公共方法
 */
class NodeMetricsTest {

    private NodeMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new NodeMetrics();
    }

    @Test
    void testInitialValues() {
        // 验证初始值
        assertEquals(0.0, metrics.getApiInflightRequests());
        assertEquals(0.0, metrics.getApiWaitingRequests());
        assertNull(metrics.getDiskUsagePercent());
        assertNull(metrics.getUsableFreeBytes());
        assertNull(metrics.getUsableTotalBytes());
    }

    @Test
    void testSettersAndGetters() {
        // 测试所有的setter和getter
        metrics.setApiInflightRequests(10.0);
        assertEquals(10.0, metrics.getApiInflightRequests());

        metrics.setApiWaitingRequests(5.0);
        assertEquals(5.0, metrics.getApiWaitingRequests());

        metrics.setDiskUsagePercent(75.0);
        assertEquals(75.0, metrics.getDiskUsagePercent());

        metrics.setUsableFreeBytes(1000000.0);
        assertEquals(1000000.0, metrics.getUsableFreeBytes());

        metrics.setUsableTotalBytes(2000000.0);
        assertEquals(2000000.0, metrics.getUsableTotalBytes());
    }

    @Test
    void testResetTransientMetrics() {
        // 设置一些值
        metrics.setApiInflightRequests(10.0);
        metrics.setApiWaitingRequests(5.0);
        metrics.setDiskUsagePercent(50.0);
        metrics.setUsableFreeBytes(1000.0);
        metrics.setUsableTotalBytes(2000.0);

        // 重置瞬时指标
        metrics.resetTransientMetrics();

        // 验证重置后的值
        assertEquals(0.0, metrics.getApiInflightRequests());
        assertEquals(0.0, metrics.getApiWaitingRequests());
        assertNull(metrics.getDiskUsagePercent());
        assertNull(metrics.getUsableFreeBytes());
        assertNull(metrics.getUsableTotalBytes());
    }

    @Test
    void testAddApiInflightRequests() {
        // 初始值应该是0
        assertEquals(0.0, metrics.getApiInflightRequests());

        // 添加第一个值
        metrics.addApiInflightRequests(10.0);
        assertEquals(10.0, metrics.getApiInflightRequests());

        // 累加第二个值
        metrics.addApiInflightRequests(5.0);
        assertEquals(15.0, metrics.getApiInflightRequests());

        // 累加负值也应该工作
        metrics.addApiInflightRequests(-3.0);
        assertEquals(12.0, metrics.getApiInflightRequests());
    }

    @Test
    void testAddApiInflightRequestsWithNull() {
        // 设置为null
        metrics.setApiInflightRequests(null);

        // 当值为null时添加不应该改变值
        metrics.addApiInflightRequests(10.0);
        assertNull(metrics.getApiInflightRequests());
    }

    @Test
    void testAddApiWaitingRequests() {
        // 初始值应该是0
        assertEquals(0.0, metrics.getApiWaitingRequests());

        // 添加第一个值
        metrics.addApiWaitingRequests(8.0);
        assertEquals(8.0, metrics.getApiWaitingRequests());

        // 累加第二个值
        metrics.addApiWaitingRequests(7.0);
        assertEquals(15.0, metrics.getApiWaitingRequests());

        // 累加负值
        metrics.addApiWaitingRequests(-5.0);
        assertEquals(10.0, metrics.getApiWaitingRequests());
    }

    @Test
    void testAddApiWaitingRequestsWithNull() {
        // 设置为null
        metrics.setApiWaitingRequests(null);

        // 当值为null时添加不应该改变值
        metrics.addApiWaitingRequests(10.0);
        assertNull(metrics.getApiWaitingRequests());
    }

    @Test
    void testCalculateDiskUsagePercent() {
        // 设置可用空间和总空间
        metrics.setUsableFreeBytes(250000.0);
        metrics.setUsableTotalBytes(1000000.0);

        // 计算磁盘使用率
        metrics.calculateDiskUsagePercent();

        // 验证计算结果: (1 - 250000/1000000) * 100 = 75%
        assertEquals(75.0, metrics.getDiskUsagePercent());
    }

    @Test
    void testCalculateDiskUsagePercentFullDisk() {
        // 设置磁盘已满
        metrics.setUsableFreeBytes(0.0);
        metrics.setUsableTotalBytes(1000000.0);

        // 计算磁盘使用率
        metrics.calculateDiskUsagePercent();

        // 应该是100%
        assertEquals(100.0, metrics.getDiskUsagePercent());
    }

    @Test
    void testCalculateDiskUsagePercentEmptyDisk() {
        // 设置磁盘为空
        metrics.setUsableFreeBytes(1000000.0);
        metrics.setUsableTotalBytes(1000000.0);

        // 计算磁盘使用率
        metrics.calculateDiskUsagePercent();

        // 应该是0%
        assertEquals(0.0, metrics.getDiskUsagePercent());
    }

    @Test
    void testCalculateDiskUsagePercentWithNullFreeBytes() {
        // 只设置总空间，不设置可用空间
        metrics.setUsableTotalBytes(1000000.0);

        // 计算磁盘使用率
        metrics.calculateDiskUsagePercent();

        // 由于信息不全，应该返回null
        assertNull(metrics.getDiskUsagePercent());
    }

    @Test
    void testCalculateDiskUsagePercentWithNullTotalBytes() {
        // 只设置可用空间，不设置总空间
        metrics.setUsableFreeBytes(250000.0);

        // 计算磁盘使用率
        metrics.calculateDiskUsagePercent();

        // 由于信息不全，应该返回null
        assertNull(metrics.getDiskUsagePercent());
    }

    @Test
    void testCalculateDiskUsagePercentWithZeroTotalBytes() {
        // 设置总空间为0（异常情况）
        metrics.setUsableFreeBytes(100.0);
        metrics.setUsableTotalBytes(0.0);

        // 计算磁盘使用率
        metrics.calculateDiskUsagePercent();

        // 应该返回null因为总空间为0
        assertNull(metrics.getDiskUsagePercent());
    }

    @Test
    void testCalculateDiskUsagePercentWithNegativeValues() {
        // 测试负值的处理（异常情况）
        metrics.setUsableFreeBytes(-100.0);
        metrics.setUsableTotalBytes(1000.0);

        // 计算磁盘使用率
        metrics.calculateDiskUsagePercent();

        // 应该被限制在100%
        assertEquals(100.0, metrics.getDiskUsagePercent());
    }

    @Test
    void testCalculateDiskUsagePercentWithFreeGreaterThanTotal() {
        // 可用空间大于总空间（异常情况）
        metrics.setUsableFreeBytes(2000.0);
        metrics.setUsableTotalBytes(1000.0);

        // 计算磁盘使用率
        metrics.calculateDiskUsagePercent();

        // 应该被限制在0%
        assertEquals(0.0, metrics.getDiskUsagePercent());
    }

    @Test
    void testCompleteWorkflow() {
        // 模拟一个完整的工作流

        // 1. 初始状态
        assertEquals(0.0, metrics.getApiInflightRequests());
        assertEquals(0.0, metrics.getApiWaitingRequests());

        // 2. 累加API请求
        metrics.addApiInflightRequests(5.0);
        metrics.addApiInflightRequests(3.0);
        metrics.addApiWaitingRequests(2.0);

        assertEquals(8.0, metrics.getApiInflightRequests());
        assertEquals(2.0, metrics.getApiWaitingRequests());

        // 3. 设置磁盘指标
        metrics.setUsableFreeBytes(400000.0);
        metrics.setUsableTotalBytes(1000000.0);

        // 4. 计算磁盘使用率
        metrics.calculateDiskUsagePercent();
        assertEquals(60.0, metrics.getDiskUsagePercent());

        // 5. 重置
        metrics.resetTransientMetrics();

        assertEquals(0.0, metrics.getApiInflightRequests());
        assertEquals(0.0, metrics.getApiWaitingRequests());
        assertNull(metrics.getDiskUsagePercent());
        assertNull(metrics.getUsableFreeBytes());
        assertNull(metrics.getUsableTotalBytes());
    }
}