package cn.flying.minio.config;

import lombok.Getter;
import lombok.Setter;

/**
 * 用于存储单个节点的关键指标 (使用系统级指标)
 */
@Getter
@Setter
public class NodeMetrics {
    // --- 保留/新增 的指标 ---
    private Double apiInflightRequests = 0.0;   // minio_s3_requests_inflight_total (累加)
    private Double apiWaitingRequests = 0.0;    // minio_s3_requests_waiting_total (累加)
    private Double diskUsagePercent = null;     // 计算得到的磁盘使用率

    // --- 用于计算 diskUsagePercent 的临时字段 ---
    private transient Double usableFreeBytes = null;
    private transient Double usableTotalBytes = null;

    // 重置需要累加/计算的指标（在每次 fetchAndParseMetrics 开始时调用）
    public void resetTransientMetrics() {
        this.apiInflightRequests = 0.0;
        this.apiWaitingRequests = 0.0;
        this.usableFreeBytes = null;
        this.usableTotalBytes = null;
        this.diskUsagePercent = null; // 清除上次计算的结果
    }

    // 添加 API 进行中请求 (累加)
    public void addApiInflightRequests(double value) {
        if (this.apiInflightRequests != null) {
            this.apiInflightRequests += value;
        }
    }

    // 添加 API 等待中请求 (累加)
    public void addApiWaitingRequests(double value) {
        if (this.apiWaitingRequests != null) {
            this.apiWaitingRequests += value;
        }
    }

    // 在所有指标解析完成后，计算磁盘使用率
    public void calculateDiskUsagePercent() {
        if (usableTotalBytes != null && usableFreeBytes != null && usableTotalBytes > 0) {
            // 计算已用百分比 = (1 - 剩余/总量) * 100
            this.diskUsagePercent = (1.0 - (this.usableFreeBytes / this.usableTotalBytes)) * 100.0;
            // 限制在 0-100 之间，以防万一
            this.diskUsagePercent = Math.max(0.0, Math.min(100.0, this.diskUsagePercent));
        } else {
            this.diskUsagePercent = null; // 如果信息不全，则无法计算
        }
    }
}
