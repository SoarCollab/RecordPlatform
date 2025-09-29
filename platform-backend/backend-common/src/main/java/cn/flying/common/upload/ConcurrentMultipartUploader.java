package cn.flying.common.upload;

import cn.flying.common.pool.BufferPoolManager;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.DistributedStorageService;
import lombok.Builder;
import lombok.Data;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 并发分块上传管理器
 * 支持文件的并发分块上传，提供进度监控、错误重试等功能
 *
 * @author 王贝强
 * @date 2025-12-26
 */
@Slf4j
public class ConcurrentMultipartUploader {

    /**
     * 默认并发数
     */
    private static final int DEFAULT_CONCURRENCY = 5;

    /**
     * 默认分块大小（5MB）
     */
    private static final int DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024;

    /**
     * 最大重试次数
     */
    private static final int MAX_RETRY_COUNT = 3;

    /**
     * 重试延迟基数（毫秒）
     */
    private static final long RETRY_DELAY_BASE = 1000;

    /**
     * 存储服务
     */
    private final DistributedStorageService storageService;

    /**
     * 上传线程池
     */
    private final ExecutorService uploadExecutor;

    /**
     * 并发控制信号量
     */
    private final Semaphore concurrencyLimiter;

    /**
     * 缓冲区池管理器
     */
    private final BufferPoolManager bufferPoolManager;

    /**
     * 上传配置
     */
    private final UploadConfig config;

    /**
     * 上传统计信息
     */
    private final UploadStatistics statistics = new UploadStatistics();

    /**
     * 进度回调
     * -- SETTER --
     *  设置进度回调

     */
    @Setter
    private Consumer<UploadProgress> progressCallback;

    /**
     * 构造函数
     */
    public ConcurrentMultipartUploader(DistributedStorageService storageService, UploadConfig config) {
        this.storageService = storageService;
        this.config = config != null ? config : UploadConfig.builder().build();
        this.bufferPoolManager = BufferPoolManager.getInstance();

        // 初始化线程池
        int concurrency = this.config.getConcurrency() > 0 ? this.config.getConcurrency() : DEFAULT_CONCURRENCY;
        this.uploadExecutor = new ThreadPoolExecutor(
                concurrency,
                concurrency * 2,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);

                    @Override
                    public Thread newThread(@NotNull Runnable r) {
                        Thread t = new Thread(r, "MultipartUploader-" + counter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 初始化并发控制
        this.concurrencyLimiter = new Semaphore(concurrency);

        log.info("并发分块上传管理器初始化: concurrency={}, chunkSize={}MB",
                concurrency, this.config.getChunkSize() / (1024 * 1024));
    }

    /**
     * 上传文件（并发分块）
     *
     * @param file     文件
     * @param fileName 文件名
     * @param fileHash 文件哈希
     * @return 上传结果
     */
    public UploadResult uploadFile(File file, String fileName, String fileHash) {
        long startTime = System.currentTimeMillis();
        statistics.reset();

        try {
            log.info("开始并发上传文件: fileName={}, size={}MB, hash={}",
                    fileName, file.length() / (1024 * 1024), fileHash);

            // 初始化分块上传
            Result<String> initResult = storageService.initMultipartUpload(
                    fileName,
                    fileHash,
                    file.length(),
                    null
            );

            if (!initResult.isSuccess()) {
                log.error("初始化分块上传失败: {}", initResult.getMessage());
                return UploadResult.failure("初始化失败: " + initResult.getMessage());
            }

            String uploadId = initResult.getData();

            // 计算分块信息
            int chunkSize = config.getChunkSize() > 0 ? config.getChunkSize() : DEFAULT_CHUNK_SIZE;
            long fileSize = file.length();
            int totalParts = (int) Math.ceil((double) fileSize / chunkSize);

            log.info("文件分块信息: totalParts={}, chunkSize={}MB",
                    totalParts, chunkSize / (1024 * 1024));

            // 并发上传所有分块
            List<CompletableFuture<PartUploadResult>> futures = new ArrayList<>();

            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                for (int partNumber = 1; partNumber <= totalParts; partNumber++) {
                    long offset = (long) (partNumber - 1) * chunkSize;
                    int currentPartSize = (int) Math.min(chunkSize, fileSize - offset);

                    // 创建异步上传任务
                    CompletableFuture<PartUploadResult> future = uploadPartAsync(
                            raf,
                            uploadId,
                            partNumber,
                            offset,
                            currentPartSize,
                            fileHash
                    );

                    futures.add(future);
                }

                // 等待所有分块上传完成
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0])
                );

                // 设置总超时时间
                long timeout = calculateTimeout(fileSize);
                allFutures.get(timeout, TimeUnit.MILLISECONDS);

                // 收集所有分块的ETag
                List<String> partETags = new ArrayList<>();
                for (CompletableFuture<PartUploadResult> future : futures) {
                    PartUploadResult result = future.get();
                    if (result.isSuccess()) {
                        partETags.add(result.getEtag());
                    } else {
                        log.error("分块上传失败: partNumber={}, error={}",
                                result.getPartNumber(), result.getErrorMessage());
                        // 取消其他正在进行的上传
                        cancelRemainingUploads(futures);
                        storageService.abortMultipartUpload(uploadId);
                        return UploadResult.failure("分块" + result.getPartNumber() + "上传失败");
                    }
                }

                // 完成分块上传
                Result<String> completeResult = storageService.completeMultipartUpload(uploadId, partETags);
                if (!completeResult.isSuccess()) {
                    log.error("完成分块上传失败: {}", completeResult.getMessage());
                    return UploadResult.failure("完成上传失败: " + completeResult.getMessage());
                }

                long duration = System.currentTimeMillis() - startTime;
                double throughput = (fileSize / 1024.0 / 1024.0) / (duration / 1000.0);

                log.info("文件上传成功: fileName={}, duration={}ms, throughput={}MB/s, path={}",
                        fileName, duration, throughput, completeResult.getData());

                return UploadResult.success(
                        completeResult.getData(),
                        duration,
                        throughput,
                        statistics.getUploadedBytes()
                );

            } catch (TimeoutException e) {
                log.error("上传超时: fileName={}", fileName);
                storageService.abortMultipartUpload(uploadId);
                return UploadResult.failure("上传超时");
            } catch (Exception e) {
                log.error("上传异常: fileName={}", fileName, e);
                storageService.abortMultipartUpload(uploadId);
                return UploadResult.failure("上传异常: " + e.getMessage());
            }

        } catch (Exception e) {
            log.error("上传文件失败: fileName={}", fileName, e);
            return UploadResult.failure("上传失败: " + e.getMessage());
        }
    }

    /**
     * 异步上传单个分块（支持重试）
     */
    private CompletableFuture<PartUploadResult> uploadPartAsync(
            RandomAccessFile raf,
            String uploadId,
            int partNumber,
            long offset,
            int size,
            String fileHash) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 获取并发许可
                concurrencyLimiter.acquire();

                try {
                    // 借用缓冲区
                    byte[] buffer = bufferPoolManager.borrowHeapBuffer(size);

                    try {
                        // 读取分块数据
                        synchronized (raf) {
                            raf.seek(offset);
                            raf.readFully(buffer, 0, size);
                        }

                        // 上传分块（带重试）
                        return uploadPartWithRetry(uploadId, partNumber, buffer, size, fileHash);

                    } finally {
                        // 归还缓冲区
                        bufferPoolManager.returnHeapBuffer(buffer);
                    }

                } finally {
                    // 释放并发许可
                    concurrencyLimiter.release();
                }

            } catch (Exception e) {
                log.error("异步上传分块失败: partNumber={}", partNumber, e);
                return PartUploadResult.failure(partNumber, e.getMessage());
            }
        }, uploadExecutor);
    }

    /**
     * 计算上传超时时间
     */
    private long calculateTimeout(long fileSize) {
        // 基础超时时间（30秒）+ 每MB额外1秒
        long baseTimeout = 30000;
        long sizeTimeout = (fileSize / (1024 * 1024)) * 1000;
        return Math.min(baseTimeout + sizeTimeout, 600000); // 最大10分钟
    }

    /**
     * 取消剩余的上传任务
     */
    private void cancelRemainingUploads(List<CompletableFuture<PartUploadResult>> futures) {
        for (CompletableFuture<PartUploadResult> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    /**
     * 上传分块（带重试机制）
     */
    private PartUploadResult uploadPartWithRetry(
            String uploadId,
            int partNumber,
            byte[] data,
            int size,
            String partHash) {

        int retryCount = 0;
        long delay = RETRY_DELAY_BASE;

        while (retryCount <= MAX_RETRY_COUNT) {
            try {
                long startTime = System.currentTimeMillis();

                // 调用存储服务上传分块
                byte[] partData = size < data.length ? Arrays.copyOf(data, size) : data;
                Result<String> result = storageService.uploadPart(uploadId, partNumber, partData, partHash);

                if (result.isSuccess()) {
                    long duration = System.currentTimeMillis() - startTime;

                    // 更新统计信息
                    statistics.incrementUploadedBytes(size);
                    statistics.incrementUploadedParts();

                    // 触发进度回调
                    if (progressCallback != null) {
                        UploadProgress progress = new UploadProgress(
                                uploadId,
                                partNumber,
                                statistics.getUploadedParts(),
                                statistics.getUploadedBytes(),
                                duration
                        );
                        progressCallback.accept(progress);
                    }

                    log.debug("分块上传成功: partNumber={}, size={}KB, duration={}ms, etag={}",
                            partNumber, size / 1024, duration, result.getData());

                    return PartUploadResult.success(partNumber, result.getData(), duration);
                }

                log.warn("分块上传失败: partNumber={}, attempt={}, error={}",
                        partNumber, retryCount + 1, result.getMessage());

                retryCount++;

            } catch (Exception e) {
                log.error("分块上传异常: partNumber={}, attempt={}",
                        partNumber, retryCount + 1, e);
                retryCount++;
            }

            // 如果还有重试机会，等待后重试
            if (retryCount <= MAX_RETRY_COUNT) {
                try {
                    log.info("等待{}ms后重试分块{}", delay, partNumber);
                    Thread.sleep(delay);
                    delay *= 2; // 指数退避
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return PartUploadResult.failure(partNumber, "重试" + MAX_RETRY_COUNT + "次后仍然失败");
    }

    /**
     * 关闭上传器
     */
    public void shutdown() {
        uploadExecutor.shutdown();
        try {
            if (!uploadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                uploadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            uploadExecutor.shutdownNow();
        }
    }

    /**
     * 上传配置
     */
    @Data
    @Builder
    public static class UploadConfig {
        @Builder.Default
        private int concurrency = DEFAULT_CONCURRENCY;

        @Builder.Default
        private int chunkSize = DEFAULT_CHUNK_SIZE;

        @Builder.Default
        private boolean useDirectBuffer = false;

        @Builder.Default
        private boolean enableRetry = true;
    }

    /**
     * 上传进度
     */
    @Data
    public static class UploadProgress {
        private final String uploadId;
        private final int currentPart;
        private final int totalParts;
        private final long uploadedBytes;
        private final long partDuration;
        private final double percentage;

        public UploadProgress(String uploadId, int currentPart, int totalParts,
                              long uploadedBytes, long partDuration) {
            this.uploadId = uploadId;
            this.currentPart = currentPart;
            this.totalParts = totalParts;
            this.uploadedBytes = uploadedBytes;
            this.partDuration = partDuration;
            this.percentage = (currentPart * 100.0) / totalParts;
        }
    }

    /**
     * 分块上传结果
     */
    @Data
    private static class PartUploadResult {
        private final int partNumber;
        private final boolean success;
        private final String etag;
        private final String errorMessage;
        private final long duration;

        private PartUploadResult(int partNumber, boolean success, String etag,
                                 String errorMessage, long duration) {
            this.partNumber = partNumber;
            this.success = success;
            this.etag = etag;
            this.errorMessage = errorMessage;
            this.duration = duration;
        }

        public static PartUploadResult success(int partNumber, String etag, long duration) {
            return new PartUploadResult(partNumber, true, etag, null, duration);
        }

        public static PartUploadResult failure(int partNumber, String errorMessage) {
            return new PartUploadResult(partNumber, false, null, errorMessage, 0);
        }
    }

    /**
     * 上传结果
     */
    @Data
    public static class UploadResult {
        private final boolean success;
        private final String path;
        private final String errorMessage;
        private final long duration;
        private final double throughput;
        private final long uploadedBytes;

        private UploadResult(boolean success, String path, String errorMessage,
                             long duration, double throughput, long uploadedBytes) {
            this.success = success;
            this.path = path;
            this.errorMessage = errorMessage;
            this.duration = duration;
            this.throughput = throughput;
            this.uploadedBytes = uploadedBytes;
        }

        public static UploadResult success(String path, long duration, double throughput, long uploadedBytes) {
            return new UploadResult(true, path, null, duration, throughput, uploadedBytes);
        }

        public static UploadResult failure(String errorMessage) {
            return new UploadResult(false, null, errorMessage, 0, 0, 0);
        }
    }

    /**
     * 上传统计信息
     */
    private static class UploadStatistics {
        private final AtomicLong uploadedBytes = new AtomicLong(0);
        private final AtomicInteger uploadedParts = new AtomicInteger(0);

        public void reset() {
            uploadedBytes.set(0);
            uploadedParts.set(0);
        }

        public void incrementUploadedBytes(long bytes) {
            uploadedBytes.addAndGet(bytes);
        }

        public void incrementUploadedParts() {
            uploadedParts.incrementAndGet();
        }

        public long getUploadedBytes() {
            return uploadedBytes.get();
        }

        public int getUploadedParts() {
            return uploadedParts.get();
        }
    }
}