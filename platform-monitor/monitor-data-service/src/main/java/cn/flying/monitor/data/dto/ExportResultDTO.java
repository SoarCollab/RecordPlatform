package cn.flying.monitor.data.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Export result DTO for data export operations
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExportResultDTO {
    
    private String exportId;
    
    private String exportType;
    
    private String format;
    
    private String status; // PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
    
    private String fileName;
    
    private String downloadUrl;
    
    private Long fileSizeBytes;
    
    private String mimeType;
    
    private Integer recordCount;
    
    private Double progressPercentage;
    
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant createdAt;
    
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant startedAt;
    
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant completedAt;
    
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant expiresAt;
    
    private Long processingTimeMs;
    
    private String errorMessage;
    
    private List<String> warnings;
    
    private Map<String, Object> metadata;
    
    private ExportRequestDTO originalRequest;
    
    private String compressionType;
    
    private String checksum;
    
    private Boolean isAsync;
    
    private String notificationEmail;
    
    // Constructors
    public ExportResultDTO() {
        this.createdAt = Instant.now();
        this.status = "PENDING";
        this.progressPercentage = 0.0;
    }
    
    public ExportResultDTO(String exportId, String exportType, String format) {
        this();
        this.exportId = exportId;
        this.exportType = exportType;
        this.format = format;
    }
    
    // Getters and Setters
    public String getExportId() {
        return exportId;
    }
    
    public void setExportId(String exportId) {
        this.exportId = exportId;
    }
    
    public String getExportType() {
        return exportType;
    }
    
    public void setExportType(String exportType) {
        this.exportType = exportType;
    }
    
    public String getFormat() {
        return format;
    }
    
    public void setFormat(String format) {
        this.format = format;
        this.mimeType = determineMimeType(format);
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
        if ("IN_PROGRESS".equals(status) && startedAt == null) {
            this.startedAt = Instant.now();
        } else if (("COMPLETED".equals(status) || "FAILED".equals(status)) && completedAt == null) {
            this.completedAt = Instant.now();
            if (startedAt != null) {
                this.processingTimeMs = java.time.Duration.between(startedAt, completedAt).toMillis();
            }
        }
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
    
    public String getDownloadUrl() {
        return downloadUrl;
    }
    
    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }
    
    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }
    
    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }
    
    public String getMimeType() {
        return mimeType;
    }
    
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
    
    public Integer getRecordCount() {
        return recordCount;
    }
    
    public void setRecordCount(Integer recordCount) {
        this.recordCount = recordCount;
    }
    
    public Double getProgressPercentage() {
        return progressPercentage;
    }
    
    public void setProgressPercentage(Double progressPercentage) {
        this.progressPercentage = progressPercentage;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getStartedAt() {
        return startedAt;
    }
    
    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }
    
    public Instant getCompletedAt() {
        return completedAt;
    }
    
    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
    
    public Instant getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
    
    public Long getProcessingTimeMs() {
        return processingTimeMs;
    }
    
    public void setProcessingTimeMs(Long processingTimeMs) {
        this.processingTimeMs = processingTimeMs;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
    
    public ExportRequestDTO getOriginalRequest() {
        return originalRequest;
    }
    
    public void setOriginalRequest(ExportRequestDTO originalRequest) {
        this.originalRequest = originalRequest;
    }
    
    public String getCompressionType() {
        return compressionType;
    }
    
    public void setCompressionType(String compressionType) {
        this.compressionType = compressionType;
    }
    
    public String getChecksum() {
        return checksum;
    }
    
    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }
    
    public Boolean getIsAsync() {
        return isAsync;
    }
    
    public void setIsAsync(Boolean isAsync) {
        this.isAsync = isAsync;
    }
    
    public String getNotificationEmail() {
        return notificationEmail;
    }
    
    public void setNotificationEmail(String notificationEmail) {
        this.notificationEmail = notificationEmail;
    }
    
    // Utility methods
    public void addWarning(String warning) {
        if (warnings == null) {
            warnings = new java.util.ArrayList<>();
        }
        warnings.add(warning);
    }
    
    public void addMetadata(String key, Object value) {
        if (metadata == null) {
            metadata = new java.util.HashMap<>();
        }
        metadata.put(key, value);
    }
    
    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }
    
    public boolean isFailed() {
        return "FAILED".equals(status);
    }
    
    public boolean isInProgress() {
        return "IN_PROGRESS".equals(status);
    }
    
    public boolean isPending() {
        return "PENDING".equals(status);
    }
    
    public boolean isCancelled() {
        return "CANCELLED".equals(status);
    }
    
    public boolean hasError() {
        return errorMessage != null && !errorMessage.trim().isEmpty();
    }
    
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
    
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
    
    public boolean isDownloadable() {
        return isCompleted() && downloadUrl != null && !isExpired();
    }
    
    public String getFormattedFileSize() {
        if (fileSizeBytes == null) {
            return null;
        }
        
        long bytes = fileSizeBytes;
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    public String getFormattedProcessingTime() {
        if (processingTimeMs == null) {
            return null;
        }
        
        long ms = processingTimeMs;
        if (ms < 1000) {
            return ms + " ms";
        } else if (ms < 60000) {
            return String.format("%.1f s", ms / 1000.0);
        } else {
            long minutes = ms / 60000;
            long seconds = (ms % 60000) / 1000;
            return String.format("%d:%02d", minutes, seconds);
        }
    }
    
    private String determineMimeType(String format) {
        if (format == null) {
            return null;
        }
        
        switch (format.toLowerCase()) {
            case "csv":
                return "text/csv";
            case "json":
                return "application/json";
            case "excel":
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pdf":
                return "application/pdf";
            case "png":
                return "image/png";
            case "html":
                return "text/html";
            case "xml":
                return "application/xml";
            default:
                return "application/octet-stream";
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExportResultDTO that = (ExportResultDTO) o;
        return Objects.equals(exportId, that.exportId) &&
               Objects.equals(exportType, that.exportType) &&
               Objects.equals(format, that.format) &&
               Objects.equals(status, that.status);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(exportId, exportType, format, status);
    }
    
    @Override
    public String toString() {
        return "ExportResultDTO{" +
               "exportId='" + exportId + '\'' +
               ", exportType='" + exportType + '\'' +
               ", format='" + format + '\'' +
               ", status='" + status + '\'' +
               ", fileName='" + fileName + '\'' +
               ", fileSizeBytes=" + fileSizeBytes +
               ", recordCount=" + recordCount +
               ", progressPercentage=" + progressPercentage +
               ", isAsync=" + isAsync +
               '}';
    }
}