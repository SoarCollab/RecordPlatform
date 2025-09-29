package cn.flying.common.metrics;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件上传性能指标收集器
 * 使用Micrometer收集和监控文件上传相关的性能指标
 *
 * @author 王贝强
 * @date 2025-12-26
 */
@Slf4j
@Component
public class UploadMetricsCollector {

    private final MeterRegistry meterRegistry;

    // === 计数器指标 ===
    /**
     * 上传开始次数
     */
    private final Counter uploadStartCounter;

    /**
     * 上传成功次数
     */
    private final Counter uploadSuccessCounter;

    /**
     * 上传失败次数
     */
    private final Counter uploadFailureCounter;

    /**
     * 分块上传次数
     */
    private final Counter partUploadCounter;

    /**
     * 分块重试次数
     */
    private final Counter partRetryCounter;

    // === 计时器指标 ===
    /**
     * 上传持续时间
     */
    private final Timer uploadDurationTimer;

    /**
     * 分块上传时间
     */
    private final Timer partUploadTimer;

    // === 仪表指标 ===
    /**
     * 当前活跃上传数
     */
    private final AtomicLong activeUploads = new AtomicLong(0);

    /**
     * 当前使用的内存
     */
    private final AtomicLong memoryUsage = new AtomicLong(0);

    // === 分布统计 ===
    /**
     * 文件大小分布
     */
    private final DistributionSummary fileSizeDistribution;

    /**
     * 上传速度分布（MB/s）
     */
    private final DistributionSummary uploadSpeedDistribution;

    /**
     * 分块大小分布
     */
    private final DistributionSummary partSizeDistribution;

    // === 自定义统计 ===
    /**
     * 文件类型统计
     */
    private final ConcurrentHashMap<String, Counter> fileTypeCounters = new ConcurrentHashMap<>();

    /**
     * 用户上传统计
     */
    private final ConcurrentHashMap<String, Counter> userUploadCounters = new ConcurrentHashMap<>();

    /**
     * 构造函数
     */
    public UploadMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // 初始化计数器
        this.uploadStartCounter = Counter.builder("file.upload.started")
            .description("文件上传开始次数")
            .tag("service", "file-upload")
            .register(meterRegistry);

        this.uploadSuccessCounter = Counter.builder("file.upload.success")
            .description("文件上传成功次数")
            .tag("service", "file-upload")
            .register(meterRegistry);

        this.uploadFailureCounter = Counter.builder("file.upload.failure")
            .description("文件上传失败次数")
            .tag("service", "file-upload")
            .register(meterRegistry);

        this.partUploadCounter = Counter.builder("file.part.uploaded")
            .description("分块上传次数")
            .tag("service", "file-upload")
            .register(meterRegistry);

        this.partRetryCounter = Counter.builder("file.part.retry")
            .description("分块重试次数")
            .tag("service", "file-upload")
            .register(meterRegistry);

        // 初始化计时器
        this.uploadDurationTimer = Timer.builder("file.upload.duration")
            .description("文件上传持续时间")
            .tag("service", "file-upload")
            .publishPercentiles(0.5, 0.75, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(meterRegistry);

        this.partUploadTimer = Timer.builder("file.part.upload.duration")
            .description("分块上传时间")
            .tag("service", "file-upload")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);

        // 初始化仪表
        Gauge.builder("file.upload.active", activeUploads, AtomicLong::get)
            .description("当前活跃上传数")
            .tag("service", "file-upload")
            .register(meterRegistry);

        Gauge.builder("file.upload.memory.usage", memoryUsage, AtomicLong::get)
            .description("上传使用的内存（字节）")
            .tag("service", "file-upload")
            .baseUnit("bytes")
            .register(meterRegistry);

        // 初始化分布统计
        this.fileSizeDistribution = DistributionSummary.builder("file.size")
            .description("文件大小分布")
            .tag("service", "file-upload")
            .baseUnit("bytes")
            .publishPercentiles(0.5, 0.75, 0.95, 0.99)
            .register(meterRegistry);

        this.uploadSpeedDistribution = DistributionSummary.builder("file.upload.speed")
            .description("上传速度分布")
            .tag("service", "file-upload")
            .baseUnit("MB/s")
            .publishPercentiles(0.5, 0.75, 0.95, 0.99)
            .register(meterRegistry);

        this.partSizeDistribution = DistributionSummary.builder("file.part.size")
            .description("分块大小分布")
            .tag("service", "file-upload")
            .baseUnit("bytes")
            .publishPercentiles(0.5, 0.95)
            .register(meterRegistry);

        log.info("文件上传性能指标收集器初始化完成");
    }

    // === 上传生命周期指标记录 ===

    /**
     * 记录上传开始
     *
     * @param fileName 文件名
     * @param fileSize 文件大小（字节）
     * @param userId   用户ID
     */
    public void recordUploadStart(String fileName, long fileSize, String userId) {
        uploadStartCounter.increment();
        activeUploads.incrementAndGet();
        fileSizeDistribution.record(fileSize);

        // 记录文件类型
        String fileType = extractFileType(fileName);
        fileTypeCounters.computeIfAbsent(fileType,
            k -> Counter.builder("file.upload.by.type")
                .description("按文件类型统计上传次数")
                .tag("type", k)
                .register(meterRegistry)
        ).increment();

        // 记录用户上传
        if (userId != null) {
            userUploadCounters.computeIfAbsent(userId,
                k -> Counter.builder("file.upload.by.user")
                    .description("按用户统计上传次数")
                    .tag("user", k)
                    .register(meterRegistry)
            ).increment();
        }

        log.debug("记录上传开始: fileName={}, fileSize={}MB, userId={}",
            fileName, fileSize / (1024 * 1024), userId);
    }

    /**
     * 记录上传成功
     *
     * @param fileName     文件名
     * @param duration     持续时间（毫秒）
     * @param uploadedBytes 上传字节数
     */
    public void recordUploadSuccess(String fileName, long duration, long uploadedBytes) {
        uploadSuccessCounter.increment();
        activeUploads.decrementAndGet();
        uploadDurationTimer.record(duration, TimeUnit.MILLISECONDS);

        // 计算上传速度（MB/s）
        if (duration > 0) {
            double speedMBps = (uploadedBytes / 1024.0 / 1024.0) / (duration / 1000.0);
            uploadSpeedDistribution.record(speedMBps);
            log.info("上传成功: fileName={}, duration={}ms, speed={}MB/s",
                fileName, duration, speedMBps);
        }
    }

    /**
     * 记录上传失败
     *
     * @param fileName 文件名
     * @param reason   失败原因
     */
    public void recordUploadFailure(String fileName, String reason) {
        uploadFailureCounter.increment();
        activeUploads.decrementAndGet();

        log.warn("上传失败: fileName={}, reason={}", fileName, reason);

        // 可以根据失败原因进一步分类
        Counter.builder("file.upload.failure.by.reason")
            .description("按原因统计上传失败次数")
            .tag("reason", categorizeFailureReason(reason))
            .register(meterRegistry)
            .increment();
    }

    // === 分块上传指标记录 ===

    /**
     * 记录分块上传
     *
     * @param uploadId   上传ID
     * @param partNumber 分块编号
     * @param partSize   分块大小（字节）
     * @param duration   持续时间（毫秒）
     * @param success    是否成功
     */
    public void recordPartUpload(String uploadId, int partNumber, long partSize,
                                 long duration, boolean success) {
        if (success) {
            partUploadCounter.increment();
            partUploadTimer.record(duration, TimeUnit.MILLISECONDS);
            partSizeDistribution.record(partSize);

            log.trace("分块上传成功: uploadId={}, partNumber={}, size={}KB, duration={}ms",
                uploadId, partNumber, partSize / 1024, duration);
        } else {
            log.debug("分块上传失败: uploadId={}, partNumber={}", uploadId, partNumber);
        }
    }

    /**
     * 记录分块重试
     *
     * @param uploadId   上传ID
     * @param partNumber 分块编号
     * @param attempt    重试次数
     */
    public void recordPartRetry(String uploadId, int partNumber, int attempt) {
        partRetryCounter.increment();
        log.debug("分块重试: uploadId={}, partNumber={}, attempt={}", uploadId, partNumber, attempt);
    }

    // === 内存指标记录 ===

    /**
     * 更新内存使用量
     *
     * @param bytes 内存使用量（字节）
     */
    public void updateMemoryUsage(long bytes) {
        memoryUsage.set(bytes);
    }

    /**
     * 增加内存使用量
     *
     * @param bytes 增加的字节数
     */
    public void incrementMemoryUsage(long bytes) {
        memoryUsage.addAndGet(bytes);
    }

    /**
     * 减少内存使用量
     *
     * @param bytes 减少的字节数
     */
    public void decrementMemoryUsage(long bytes) {
        memoryUsage.addAndGet(-bytes);
    }

    // === 自定义指标查询 ===

    /**
     * 获取当前活跃上传数
     */
    public long getActiveUploads() {
        return activeUploads.get();
    }

    /**
     * 获取上传成功率
     */
    public double getSuccessRate() {
        double success = uploadSuccessCounter.count();
        double total = uploadStartCounter.count();
        return total > 0 ? (success / total) * 100 : 0;
    }

    /**
     * 获取平均上传速度（MB/s）
     */
    public double getAverageUploadSpeed() {
        return uploadSpeedDistribution.mean();
    }

    /**
     * 获取性能摘要
     */
    public PerformanceSummary getPerformanceSummary() {
        return new PerformanceSummary(
            uploadStartCounter.count(),
            uploadSuccessCounter.count(),
            uploadFailureCounter.count(),
            getSuccessRate(),
            getAverageUploadSpeed(),
            activeUploads.get(),
            memoryUsage.get()
        );
    }

    // === 辅助方法 ===

    /**
     * 提取文件类型
     */
    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        return extension.length() <= 10 ? extension : "other";
    }

    /**
     * 分类失败原因
     */
    private String categorizeFailureReason(String reason) {
        if (reason == null) {
            return "unknown";
        }
        String lowerReason = reason.toLowerCase();
        if (lowerReason.contains("timeout")) {
            return "timeout";
        } else if (lowerReason.contains("network")) {
            return "network";
        } else if (lowerReason.contains("size")) {
            return "file_size";
        } else if (lowerReason.contains("permission")) {
            return "permission";
        } else {
            return "other";
        }
    }

    /**
     * 性能摘要
     */
    public static class PerformanceSummary {
        public final double totalUploads;
        public final double successfulUploads;
        public final double failedUploads;
        public final double successRate;
        public final double averageSpeed;
        public final long activeUploads;
        public final long memoryUsage;

        public PerformanceSummary(double totalUploads, double successfulUploads,
                                 double failedUploads, double successRate,
                                 double averageSpeed, long activeUploads, long memoryUsage) {
            this.totalUploads = totalUploads;
            this.successfulUploads = successfulUploads;
            this.failedUploads = failedUploads;
            this.successRate = successRate;
            this.averageSpeed = averageSpeed;
            this.activeUploads = activeUploads;
            this.memoryUsage = memoryUsage;
        }

        @Override
        public String toString() {
            return String.format(
                "PerformanceSummary{total=%.0f, success=%.0f, failed=%.0f, " +
                "successRate=%.2f%%, avgSpeed=%.2fMB/s, active=%d, memory=%dMB}",
                totalUploads, successfulUploads, failedUploads,
                successRate, averageSpeed, activeUploads, memoryUsage / (1024 * 1024)
            );
        }
    }
}