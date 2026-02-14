package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.vo.file.BatchDownloadMetricsReportVO;
import cn.flying.service.DownloadBatchMetricsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 批量下载指标服务实现。
 * 负责校验前端上报的批次摘要并转换为 Micrometer 指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadBatchMetricsServiceImpl implements DownloadBatchMetricsService {

    private static final int MAX_BATCH_FILES = 100;
    private static final String BATCH_STATUS_SUCCESS = "success";
    private static final String BATCH_STATUS_PARTIAL = "partial";
    private static final String BATCH_STATUS_FAILED = "failed";

    private final MeterRegistry meterRegistry;

    /**
     * 处理批量下载指标上报并写入监控系统。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param report 指标上报内容
     */
    @Override
    public void reportBatchMetrics(Long tenantId, Long userId, BatchDownloadMetricsReportVO report) {
        validateReport(report);

        int total = report.total();
        int successCount = report.successCount();
        int failedCount = report.failedCount();
        int retryCount = report.retryCount();
        long durationMs = report.durationMs();
        String batchStatus = resolveBatchStatus(successCount, failedCount);

        Counter.builder("download.batch.total")
                .description("批量下载批次总数")
                .tag("status", batchStatus)
                .register(meterRegistry)
                .increment();

        Counter.builder("download.batch.file.total")
                .description("批量下载成功文件总数")
                .tag("result", "success")
                .register(meterRegistry)
                .increment(successCount);

        Counter.builder("download.batch.file.total")
                .description("批量下载失败文件总数")
                .tag("result", "failed")
                .register(meterRegistry)
                .increment(failedCount);

        if (retryCount > 0) {
            Counter.builder("download.batch.retry.total")
                    .description("批量下载累计重试次数")
                    .register(meterRegistry)
                    .increment(retryCount);
        }

        if (durationMs > 0) {
            Timer.builder("download.batch.duration")
                    .description("批量下载批次耗时")
                    .tag("status", batchStatus)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(durationMs));
        }

        recordFailureReasonMetrics(report.failureReasons());

        log.info("[download-batch-report] tenantId={}, userId={}, batchId={}, status={}, total={}, success={}, failed={}, retries={}, durationMs={}",
                tenantId,
                userId,
                report.batchId(),
                batchStatus,
                total,
                successCount,
                failedCount,
                retryCount,
                durationMs
        );
    }

    /**
     * 校验批次上报请求，保证计数与耗时为合法值。
     *
     * @param report 上报数据
     */
    private void validateReport(BatchDownloadMetricsReportVO report) {
        if (report == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "批次上报不能为空");
        }
        if (!StringUtils.hasText(report.batchId())) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "batchId 不能为空");
        }
        if (report.total() == null || report.total() <= 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "total 必须大于 0");
        }
        if (report.total() > MAX_BATCH_FILES) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "total 不能超过 " + MAX_BATCH_FILES);
        }
        if (report.successCount() == null || report.successCount() < 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "successCount 不能为负数");
        }
        if (report.failedCount() == null || report.failedCount() < 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "failedCount 不能为负数");
        }
        if (report.retryCount() == null || report.retryCount() < 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "retryCount 不能为负数");
        }
        if (report.durationMs() == null || report.durationMs() < 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "durationMs 不能为负数");
        }
        if (report.successCount() + report.failedCount() != report.total()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "successCount + failedCount 必须等于 total");
        }
        int failureReasonTotal = validateAndCountFailureReasons(report.failureReasons());
        if (failureReasonTotal != report.failedCount()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "failureReasons 汇总必须等于 failedCount");
        }
    }

    /**
     * 校验失败原因分布并返回失败总数。
     * 仅允许正整数计数，避免上报 0/负数导致统计口径失真。
     *
     * @param failureReasons 失败原因分布
     * @return 失败原因计数总和
     */
    private int validateAndCountFailureReasons(Map<String, Integer> failureReasons) {
        if (failureReasons == null || failureReasons.isEmpty()) {
            return 0;
        }

        long total = 0L;
        for (Map.Entry<String, Integer> entry : failureReasons.entrySet()) {
            Integer count = entry.getValue();
            if (count == null || count <= 0) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "failureReasons 计数必须为正整数");
            }
            total += count;
        }
        if (total > Integer.MAX_VALUE) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "failureReasons 计数总和超出范围");
        }
        return (int) total;
    }

    /**
     * 根据成功/失败数量推导批次状态。
     *
     * @param successCount 成功数
     * @param failedCount 失败数
     * @return 归一化批次状态
     */
    private String resolveBatchStatus(int successCount, int failedCount) {
        if (failedCount == 0) {
            return BATCH_STATUS_SUCCESS;
        }
        if (successCount == 0) {
            return BATCH_STATUS_FAILED;
        }
        return BATCH_STATUS_PARTIAL;
    }

    /**
     * 记录失败原因分布指标，原因标签按归一化类别上报，避免高基数问题。
     *
     * @param failureReasons 失败原因分布
     */
    private void recordFailureReasonMetrics(Map<String, Integer> failureReasons) {
        if (failureReasons == null || failureReasons.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Integer> entry : failureReasons.entrySet()) {
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (count <= 0) {
                continue;
            }
            String normalizedReason = normalizeFailureReason(entry.getKey());
            Counter.builder("download.batch.failure.reason.total")
                    .description("批量下载失败原因分布")
                    .tag("reason", normalizedReason)
                    .register(meterRegistry)
                    .increment(count);
        }
    }

    /**
     * 将原始错误文本归类为有限集合，避免将高基数字符串写入指标标签。
     *
     * @param reason 原始失败原因
     * @return 归一化失败原因
     */
    private String normalizeFailureReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "unknown";
        }
        String normalized = reason.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("quota") || normalized.contains("配额")) {
            return "quota";
        }
        if (normalized.contains("network") || normalized.contains("timeout") || normalized.contains("fetch") || normalized.contains("网络")) {
            return "network";
        }
        if (normalized.contains("decrypt") || normalized.contains("解密")) {
            return "decrypt";
        }
        if (normalized.contains("pause") || normalized.contains("cancel") || normalized.contains("暂停") || normalized.contains("取消")) {
            return "cancelled";
        }
        if (normalized.contains("auth") || normalized.contains("token") || normalized.contains("unauthorized") || normalized.contains("权限")) {
            return "auth";
        }
        return "other";
    }
}
