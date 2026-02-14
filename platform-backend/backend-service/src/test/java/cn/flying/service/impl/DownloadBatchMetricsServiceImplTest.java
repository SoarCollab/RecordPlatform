package cn.flying.service.impl;

import cn.flying.common.exception.GeneralException;
import cn.flying.dao.vo.file.BatchDownloadMetricsReportVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DownloadBatchMetricsServiceImpl 单元测试。
 */
class DownloadBatchMetricsServiceImplTest {

    private SimpleMeterRegistry meterRegistry;
    private DownloadBatchMetricsServiceImpl service;

    /**
     * 初始化测试依赖。
     */
    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new DownloadBatchMetricsServiceImpl(meterRegistry);
    }

    /**
     * 验证批次指标会按约定写入 Micrometer。
     */
    @Test
    void shouldRecordBatchMetrics() {
        BatchDownloadMetricsReportVO report = new BatchDownloadMetricsReportVO(
                "batch-1",
                10,
                7,
                3,
                4,
                2000L,
                Map.of("network timeout", 2, "配额超限", 1)
        );

        service.reportBatchMetrics(1L, 2L, report);

        assertEquals(1D, meterRegistry.get("download.batch.total").tag("status", "partial").counter().count());
        assertEquals(7D, meterRegistry.get("download.batch.file.total").tag("result", "success").counter().count());
        assertEquals(3D, meterRegistry.get("download.batch.file.total").tag("result", "failed").counter().count());
        assertEquals(4D, meterRegistry.get("download.batch.retry.total").counter().count());
        assertEquals(1D, meterRegistry.get("download.batch.failure.reason.total").tag("reason", "quota").counter().count());
        assertEquals(2D, meterRegistry.get("download.batch.failure.reason.total").tag("reason", "network").counter().count());
        assertEquals(1L, meterRegistry.get("download.batch.duration").tag("status", "partial").timer().count());
    }

    /**
     * 验证成功批次不会记录失败原因指标。
     */
    @Test
    void shouldSkipFailureReasonMetricWhenNoFailures() {
        BatchDownloadMetricsReportVO report = new BatchDownloadMetricsReportVO(
                "batch-2",
                2,
                2,
                0,
                0,
                800L,
                Map.of()
        );

        service.reportBatchMetrics(1L, 2L, report);

        assertEquals(1D, meterRegistry.get("download.batch.total").tag("status", "success").counter().count());
        assertThrows(Exception.class, () -> meterRegistry.get("download.batch.failure.reason.total").counter());
    }

    /**
     * 验证非法计数会被拒绝。
     */
    @Test
    void shouldRejectInvalidReport() {
        BatchDownloadMetricsReportVO invalid = new BatchDownloadMetricsReportVO(
                "batch-3",
                5,
                3,
                1,
                0,
                100L,
                null
        );

        assertThrows(GeneralException.class, () -> service.reportBatchMetrics(1L, 2L, invalid));
    }

    /**
     * 验证 total 超过批次上限时会被拒绝。
     */
    @Test
    void shouldRejectReportWhenTotalExceedsLimit() {
        BatchDownloadMetricsReportVO invalid = new BatchDownloadMetricsReportVO(
                "batch-4",
                101,
                100,
                1,
                0,
                300L,
                Map.of("network timeout", 1)
        );

        assertThrows(GeneralException.class, () -> service.reportBatchMetrics(1L, 2L, invalid));
    }

    /**
     * 验证失败原因分布与失败总数不一致时会被拒绝。
     */
    @Test
    void shouldRejectReportWhenFailureReasonsMismatchFailedCount() {
        BatchDownloadMetricsReportVO invalid = new BatchDownloadMetricsReportVO(
                "batch-5",
                3,
                1,
                2,
                1,
                1000L,
                Map.of("network timeout", 1)
        );

        assertThrows(GeneralException.class, () -> service.reportBatchMetrics(1L, 2L, invalid));
    }
}
