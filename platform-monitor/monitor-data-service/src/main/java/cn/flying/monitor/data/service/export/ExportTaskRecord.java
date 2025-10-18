package cn.flying.monitor.data.service.export;

import cn.flying.monitor.data.dto.ExportResultDTO;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * 内存中的导出任务记录，负责维护状态与导出内容
 */
public class ExportTaskRecord {

    private final ExportResultDTO result;
    private byte[] content;
    private final Instant createdAt;
    private Instant lastUpdated;

    public ExportTaskRecord(ExportResultDTO result) {
        this.result = result;
        this.createdAt = Instant.now();
        this.lastUpdated = this.createdAt;
    }

    public synchronized void markInProgress() {
        result.setStatus("IN_PROGRESS");
        result.setProgressPercentage(10.0);
        this.lastUpdated = Instant.now();
    }

    public synchronized void updateProgress(double progress) {
        result.setProgressPercentage(progress);
        this.lastUpdated = Instant.now();
    }

    public synchronized void complete(byte[] data) {
        this.content = data;
        result.setProgressPercentage(100.0);
        result.setStatus("COMPLETED");
        this.lastUpdated = Instant.now();
    }

    public synchronized void fail(String errorMessage) {
        result.setErrorMessage(errorMessage);
        result.setStatus("FAILED");
        this.lastUpdated = Instant.now();
        this.content = null;
    }

    public synchronized void cancel() {
        result.setStatus("CANCELLED");
        result.setProgressPercentage(0.0);
        this.lastUpdated = Instant.now();
        this.content = null;
    }

    public synchronized byte[] getContent() {
        return content == null ? null : Arrays.copyOf(content, content.length);
    }

    public ExportResultDTO getResult() {
        return result;
    }

    public boolean isExpired(Duration ttl) {
        return createdAt.plus(ttl).isBefore(Instant.now());
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }
}
