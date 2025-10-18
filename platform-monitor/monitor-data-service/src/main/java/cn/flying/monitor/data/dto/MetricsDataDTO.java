package cn.flying.monitor.data.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.Map;

/**
 * Enhanced metrics data transfer object with comprehensive validation
 * Supports batch processing and network optimization
 */
public class MetricsDataDTO {
    
    @NotNull(message = "Client ID cannot be null")
    @Size(min = 1, max = 64, message = "Client ID must be between 1 and 64 characters")
    @JsonProperty("client_id")
    private String clientId;
    
    @NotNull(message = "Timestamp cannot be null")
    @PastOrPresent(message = "Timestamp cannot be in the future")
    private Instant timestamp;
    
    @NotNull(message = "CPU usage cannot be null")
    @DecimalMin(value = "0.0", message = "CPU usage must be non-negative")
    @DecimalMax(value = "100.0", message = "CPU usage cannot exceed 100%")
    @JsonProperty("cpu_usage")
    private Double cpuUsage;
    
    @NotNull(message = "Memory usage cannot be null")
    @DecimalMin(value = "0.0", message = "Memory usage must be non-negative")
    @DecimalMax(value = "100.0", message = "Memory usage cannot exceed 100%")
    @JsonProperty("memory_usage")
    private Double memoryUsage;
    
    @NotNull(message = "Disk usage cannot be null")
    @DecimalMin(value = "0.0", message = "Disk usage must be non-negative")
    @DecimalMax(value = "100.0", message = "Disk usage cannot exceed 100%")
    @JsonProperty("disk_usage")
    private Double diskUsage;
    
    @NotNull(message = "Network upload cannot be null")
    @PositiveOrZero(message = "Network upload must be non-negative")
    @JsonProperty("network_upload")
    private Double networkUpload;
    
    @NotNull(message = "Network download cannot be null")
    @PositiveOrZero(message = "Network download must be non-negative")
    @JsonProperty("network_download")
    private Double networkDownload;
    
    @NotNull(message = "Disk read cannot be null")
    @PositiveOrZero(message = "Disk read must be non-negative")
    @JsonProperty("disk_read")
    private Double diskRead;
    
    @NotNull(message = "Disk write cannot be null")
    @PositiveOrZero(message = "Disk write must be non-negative")
    @JsonProperty("disk_write")
    private Double diskWrite;
    
    // Additional system metrics for enhanced monitoring
    @PositiveOrZero(message = "Load average must be non-negative")
    @JsonProperty("load_average")
    private Double loadAverage;
    
    @Min(value = 0, message = "Process count must be non-negative")
    @JsonProperty("process_count")
    private Integer processCount;
    
    @PositiveOrZero(message = "Network connections must be non-negative")
    @JsonProperty("network_connections")
    private Integer networkConnections;
    
    // Custom metrics support
    @JsonProperty("custom_metrics")
    private Map<String, Object> customMetrics;
    
    // Compression metadata
    @JsonProperty("compressed")
    private Boolean compressed = false;
    
    @JsonProperty("compression_ratio")
    private Double compressionRatio;
    
    // Data quality indicators
    @JsonProperty("data_quality_score")
    private Double dataQualityScore;
    
    @JsonProperty("validation_errors")
    private Map<String, String> validationErrors;

    // Getters and Setters
    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Double getCpuUsage() {
        return cpuUsage;
    }

    public void setCpuUsage(Double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    public Double getMemoryUsage() {
        return memoryUsage;
    }

    public void setMemoryUsage(Double memoryUsage) {
        this.memoryUsage = memoryUsage;
    }

    public Double getDiskUsage() {
        return diskUsage;
    }

    public void setDiskUsage(Double diskUsage) {
        this.diskUsage = diskUsage;
    }

    public Double getNetworkUpload() {
        return networkUpload;
    }

    public void setNetworkUpload(Double networkUpload) {
        this.networkUpload = networkUpload;
    }

    public Double getNetworkDownload() {
        return networkDownload;
    }

    public void setNetworkDownload(Double networkDownload) {
        this.networkDownload = networkDownload;
    }

    public Double getDiskRead() {
        return diskRead;
    }

    public void setDiskRead(Double diskRead) {
        this.diskRead = diskRead;
    }

    public Double getDiskWrite() {
        return diskWrite;
    }

    public void setDiskWrite(Double diskWrite) {
        this.diskWrite = diskWrite;
    }

    public Double getLoadAverage() {
        return loadAverage;
    }

    public void setLoadAverage(Double loadAverage) {
        this.loadAverage = loadAverage;
    }

    public Integer getProcessCount() {
        return processCount;
    }

    public void setProcessCount(Integer processCount) {
        this.processCount = processCount;
    }

    public Integer getNetworkConnections() {
        return networkConnections;
    }

    public void setNetworkConnections(Integer networkConnections) {
        this.networkConnections = networkConnections;
    }

    public Map<String, Object> getCustomMetrics() {
        return customMetrics;
    }

    public void setCustomMetrics(Map<String, Object> customMetrics) {
        this.customMetrics = customMetrics;
    }

    public Boolean getCompressed() {
        return compressed;
    }

    public void setCompressed(Boolean compressed) {
        this.compressed = compressed;
    }

    public Double getCompressionRatio() {
        return compressionRatio;
    }

    public void setCompressionRatio(Double compressionRatio) {
        this.compressionRatio = compressionRatio;
    }

    public Double getDataQualityScore() {
        return dataQualityScore;
    }

    public void setDataQualityScore(Double dataQualityScore) {
        this.dataQualityScore = dataQualityScore;
    }

    public Map<String, String> getValidationErrors() {
        return validationErrors;
    }

    public void setValidationErrors(Map<String, String> validationErrors) {
        this.validationErrors = validationErrors;
    }
}