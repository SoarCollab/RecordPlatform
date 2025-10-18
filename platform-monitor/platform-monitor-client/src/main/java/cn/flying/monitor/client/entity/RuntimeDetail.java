package cn.flying.monitor.client.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * Enhanced runtime detail entity with extended system metrics
 */
@Data
@Accessors(chain = true)
public class RuntimeDetail {
    // Basic metrics (existing)
    private long timestamp;
    private double cpuUsage;
    private double memoryUsage;
    private double diskUsage;
    private double networkUpload;
    private double networkDownload;
    private double diskRead;
    private double diskWrite;

    // Enhanced network metrics
    private Map<String, NetworkInterfaceMetrics> networkInterfaces;
    private double totalNetworkPacketsIn;
    private double totalNetworkPacketsOut;
    private double networkErrorRate;

    // Process metrics
    private List<ProcessMetrics> topProcesses;
    private int totalProcessCount;
    private int runningProcessCount;

    // JVM metrics (if running on JVM)
    private JvmMetrics jvmMetrics;

    // System load metrics
    private double loadAverage1min;
    private double loadAverage5min;
    private double loadAverage15min;

    // Memory breakdown
    private double memoryUsedGB;
    private double memoryAvailableGB;
    private double swapUsedGB;
    private double swapTotalGB;

    // Disk breakdown per mount point
    private Map<String, DiskMetrics> diskMountPoints;

    // Custom metrics (extensible)
    private Map<String, Object> customMetrics;

    @Data
    @Accessors(chain = true)
    public static class NetworkInterfaceMetrics {
        private String interfaceName;
        private double bytesReceived;
        private double bytesSent;
        private double packetsReceived;
        private double packetsSent;
        private double errorsReceived;
        private double errorsSent;
        private boolean isUp;
        private String ipAddress;
        // Rate metrics (bytes per second)
        private double bytesReceivedRate;
        private double bytesSentRate;
    }

    @Data
    @Accessors(chain = true)
    public static class ProcessMetrics {
        private int pid;
        private String name;
        private String command;
        private double cpuUsage;
        private double memoryUsage;
        private long memoryBytes;
        private String state;
        private long startTime;
    }

    @Data
    @Accessors(chain = true)
    public static class JvmMetrics {
        private double heapUsedMB;
        private double heapMaxMB;
        private double nonHeapUsedMB;
        private double nonHeapMaxMB;
        private int threadCount;
        private int daemonThreadCount;
        private long gcCollectionCount;
        private long gcCollectionTime;
        private double cpuUsage;
    }

    @Data
    @Accessors(chain = true)
    public static class DiskMetrics {
        private String mountPoint;
        private double totalGB;
        private double usedGB;
        private double availableGB;
        private double usagePercent;
        private double readBytesPerSec;
        private double writeBytesPerSec;
        private double readOpsPerSec;
        private double writeOpsPerSec;
    }
}


