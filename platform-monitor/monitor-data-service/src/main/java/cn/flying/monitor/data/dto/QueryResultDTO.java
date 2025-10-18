package cn.flying.monitor.data.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;

/**
 * Query result DTO for metrics data
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryResultDTO {
    
    private String clientId;
    
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant timestamp;
    
    private Map<String, Object> metrics;
    
    private Map<String, Object> metadata;
    
    private Map<String, Object> qualityMetrics;
    
    private Double dataQualityScore;
    
    private String aggregationWindow;
    
    private Long recordCount;
    
    private String status; // SUCCESS, PARTIAL, ERROR
    
    private List<String> warnings;
    
    private Map<String, Object> statistics; // Additional statistical data
    
    private Map<String, Object> trends; // Trend analysis data
    
    private List<Map<String, Object>> anomalies; // Detected anomalies
    
    // Constructors
    public QueryResultDTO() {
        this.status = "SUCCESS";
        this.warnings = new ArrayList<>();
    }
    
    public QueryResultDTO(String clientId, Instant timestamp) {
        this();
        this.clientId = clientId;
        this.timestamp = timestamp;
    }
    
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
    
    public Map<String, Object> getMetrics() {
        return metrics;
    }
    
    public void setMetrics(Map<String, Object> metrics) {
        this.metrics = metrics;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
    
    public Map<String, Object> getQualityMetrics() {
        return qualityMetrics;
    }
    
    public void setQualityMetrics(Map<String, Object> qualityMetrics) {
        this.qualityMetrics = qualityMetrics;
    }
    
    public Double getDataQualityScore() {
        return dataQualityScore;
    }
    
    public void setDataQualityScore(Double dataQualityScore) {
        this.dataQualityScore = dataQualityScore;
    }
    
    public String getAggregationWindow() {
        return aggregationWindow;
    }
    
    public void setAggregationWindow(String aggregationWindow) {
        this.aggregationWindow = aggregationWindow;
    }
    
    public Long getRecordCount() {
        return recordCount;
    }
    
    public void setRecordCount(Long recordCount) {
        this.recordCount = recordCount;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
    
    public Map<String, Object> getStatistics() {
        return statistics;
    }
    
    public void setStatistics(Map<String, Object> statistics) {
        this.statistics = statistics;
    }
    
    public Map<String, Object> getTrends() {
        return trends;
    }
    
    public void setTrends(Map<String, Object> trends) {
        this.trends = trends;
    }
    
    public List<Map<String, Object>> getAnomalies() {
        return anomalies;
    }
    
    public void setAnomalies(List<Map<String, Object>> anomalies) {
        this.anomalies = anomalies;
    }
    
    // Convenience methods for common metrics
    public Double getCpuUsage() {
        return getMetricValue("cpu_usage");
    }
    
    public Double getMemoryUsage() {
        return getMetricValue("memory_usage");
    }
    
    public Double getDiskUsage() {
        return getMetricValue("disk_usage");
    }
    
    public Double getNetworkUpload() {
        return getMetricValue("network_upload");
    }
    
    public Double getNetworkDownload() {
        return getMetricValue("network_download");
    }
    
    public Double getLoadAverage() {
        return getMetricValue("load_average");
    }
    
    public Double getSystemLoad() {
        return getMetricValue("system_load");
    }
    
    public Long getProcessCount() {
        Object value = getMetricValue("process_count");
        return value != null ? ((Number) value).longValue() : null;
    }
    
    public Double getTemperature() {
        return getMetricValue("temperature");
    }
    
    // Enhanced metric access methods
    private Double getMetricValue(String metricName) {
        if (metrics == null || metricName == null) {
            return null;
        }
        
        Object value = metrics.get(metricName);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        
        return null;
    }
    
    public Object getMetricValueRaw(String metricName) {
        if (metrics == null || metricName == null) {
            return null;
        }
        return metrics.get(metricName);
    }
    
    public String getMetricValueAsString(String metricName) {
        Object value = getMetricValueRaw(metricName);
        return value != null ? value.toString() : null;
    }
    
    public Boolean getMetricValueAsBoolean(String metricName) {
        Object value = getMetricValueRaw(metricName);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0;
        }
        return null;
    }
    
    // Data manipulation methods
    public void addMetric(String name, Object value) {
        if (metrics == null) {
            metrics = new java.util.HashMap<>();
        }
        metrics.put(name, value);
    }
    
    public void addMetadata(String key, Object value) {
        if (metadata == null) {
            metadata = new java.util.HashMap<>();
        }
        metadata.put(key, value);
    }
    
    public void addQualityMetric(String key, Object value) {
        if (qualityMetrics == null) {
            qualityMetrics = new java.util.HashMap<>();
        }
        qualityMetrics.put(key, value);
    }
    
    public void addStatistic(String key, Object value) {
        if (statistics == null) {
            statistics = new java.util.HashMap<>();
        }
        statistics.put(key, value);
    }
    
    public void addTrend(String key, Object value) {
        if (trends == null) {
            trends = new java.util.HashMap<>();
        }
        trends.put(key, value);
    }
    
    public void addAnomaly(Map<String, Object> anomaly) {
        if (anomalies == null) {
            anomalies = new ArrayList<>();
        }
        anomalies.add(anomaly);
    }
    
    public void addWarning(String warning) {
        if (warnings == null) {
            warnings = new ArrayList<>();
        }
        warnings.add(warning);
    }
    
    // Data conversion and formatting methods
    public String getFormattedTimestamp() {
        if (timestamp == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(timestamp);
    }
    
    public String getFormattedTimestamp(String pattern) {
        if (timestamp == null || pattern == null) {
            return null;
        }
        try {
            return DateTimeFormatter.ofPattern(pattern).format(timestamp.atZone(java.time.ZoneOffset.UTC));
        } catch (Exception e) {
            return getFormattedTimestamp();
        }
    }
    
    public Map<String, String> getFormattedMetrics() {
        if (metrics == null) {
            return null;
        }
        
        Map<String, String> formatted = new java.util.HashMap<>();
        metrics.forEach((key, value) -> {
            if (value != null) {
                if (value instanceof Double) {
                    formatted.put(key, String.format("%.2f", (Double) value));
                } else if (value instanceof Float) {
                    formatted.put(key, String.format("%.2f", (Float) value));
                } else {
                    formatted.put(key, value.toString());
                }
            }
        });
        return formatted;
    }
    
    public boolean hasMetrics() {
        return metrics != null && !metrics.isEmpty();
    }
    
    public boolean hasMetadata() {
        return metadata != null && !metadata.isEmpty();
    }
    
    public boolean hasQualityMetrics() {
        return qualityMetrics != null && !qualityMetrics.isEmpty();
    }
    
    public boolean hasStatistics() {
        return statistics != null && !statistics.isEmpty();
    }
    
    public boolean hasTrends() {
        return trends != null && !trends.isEmpty();
    }
    
    public boolean hasAnomalies() {
        return anomalies != null && !anomalies.isEmpty();
    }
    
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
    
    public boolean isSuccessful() {
        return "SUCCESS".equals(status);
    }
    
    public boolean isPartial() {
        return "PARTIAL".equals(status);
    }
    
    public boolean hasError() {
        return "ERROR".equals(status);
    }
    
    public int getMetricCount() {
        return metrics != null ? metrics.size() : 0;
    }
    
    public int getAnomalyCount() {
        return anomalies != null ? anomalies.size() : 0;
    }
    
    public int getWarningCount() {
        return warnings != null ? warnings.size() : 0;
    }
    
    // Data quality assessment
    public boolean isHighQuality() {
        return dataQualityScore != null && dataQualityScore >= 0.8;
    }
    
    public boolean isLowQuality() {
        return dataQualityScore != null && dataQualityScore < 0.5;
    }
    
    public String getQualityLevel() {
        if (dataQualityScore == null) {
            return "UNKNOWN";
        }
        if (dataQualityScore >= 0.9) {
            return "EXCELLENT";
        } else if (dataQualityScore >= 0.8) {
            return "GOOD";
        } else if (dataQualityScore >= 0.6) {
            return "FAIR";
        } else if (dataQualityScore >= 0.4) {
            return "POOR";
        } else {
            return "VERY_POOR";
        }
    }
    
    // Utility methods for data export
    public Map<String, Object> toFlatMap() {
        Map<String, Object> flatMap = new java.util.HashMap<>();
        
        flatMap.put("clientId", clientId);
        flatMap.put("timestamp", getFormattedTimestamp());
        flatMap.put("status", status);
        flatMap.put("recordCount", recordCount);
        flatMap.put("dataQualityScore", dataQualityScore);
        flatMap.put("aggregationWindow", aggregationWindow);
        
        if (hasMetrics()) {
            metrics.forEach((key, value) -> flatMap.put("metric_" + key, value));
        }
        
        if (hasMetadata()) {
            metadata.forEach((key, value) -> flatMap.put("metadata_" + key, value));
        }
        
        if (hasQualityMetrics()) {
            qualityMetrics.forEach((key, value) -> flatMap.put("quality_" + key, value));
        }
        
        return flatMap;
    }
    
    public String toCsvRow() {
        StringBuilder csv = new StringBuilder();
        Map<String, Object> flatMap = toFlatMap();
        
        boolean first = true;
        for (Object value : flatMap.values()) {
            if (!first) {
                csv.append(",");
            }
            if (value != null) {
                String strValue = value.toString();
                if (strValue.contains(",") || strValue.contains("\"") || strValue.contains("\n")) {
                    csv.append("\"").append(strValue.replace("\"", "\"\"")).append("\"");
                } else {
                    csv.append(strValue);
                }
            }
            first = false;
        }
        
        return csv.toString();
    }
    
    public static String getCsvHeader() {
        return "clientId,timestamp,status,recordCount,dataQualityScore,aggregationWindow";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryResultDTO that = (QueryResultDTO) o;
        return Objects.equals(clientId, that.clientId) &&
               Objects.equals(timestamp, that.timestamp) &&
               Objects.equals(metrics, that.metrics) &&
               Objects.equals(status, that.status);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(clientId, timestamp, metrics, status);
    }
    
    @Override
    public String toString() {
        return "QueryResultDTO{" +
               "clientId='" + clientId + '\'' +
               ", timestamp=" + timestamp +
               ", status='" + status + '\'' +
               ", recordCount=" + recordCount +
               ", dataQualityScore=" + dataQualityScore +
               ", metricCount=" + getMetricCount() +
               ", anomalyCount=" + getAnomalyCount() +
               ", warningCount=" + getWarningCount() +
               '}';
    }
}