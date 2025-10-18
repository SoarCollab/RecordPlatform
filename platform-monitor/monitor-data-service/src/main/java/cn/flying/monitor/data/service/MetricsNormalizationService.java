package cn.flying.monitor.data.service;

import cn.flying.monitor.data.dto.MetricsDataDTO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for normalizing and transforming metrics data
 * Handles data quality improvement and standardization
 */
@Service
public class MetricsNormalizationService {
    
    private static final double BYTES_TO_MB = 1024.0 * 1024.0;
    private static final double BYTES_TO_GB = 1024.0 * 1024.0 * 1024.0;
    
    /**
     * Normalizes metrics data for consistent processing
     */
    public MetricsDataDTO normalizeMetrics(MetricsDataDTO metrics) {
        MetricsDataDTO normalized = new MetricsDataDTO();
        
        // Copy basic fields
        normalized.setClientId(metrics.getClientId());
        normalized.setTimestamp(normalizeTimestamp(metrics.getTimestamp()));
        
        // Normalize percentage values
        normalized.setCpuUsage(normalizePercentage(metrics.getCpuUsage()));
        normalized.setMemoryUsage(normalizePercentage(metrics.getMemoryUsage()));
        normalized.setDiskUsage(normalizePercentage(metrics.getDiskUsage()));
        
        // Normalize network metrics (convert to bytes/second if needed)
        normalized.setNetworkUpload(normalizeNetworkMetric(metrics.getNetworkUpload()));
        normalized.setNetworkDownload(normalizeNetworkMetric(metrics.getNetworkDownload()));
        
        // Normalize disk I/O metrics
        normalized.setDiskRead(normalizeDiskMetric(metrics.getDiskRead()));
        normalized.setDiskWrite(normalizeDiskMetric(metrics.getDiskWrite()));
        
        // Normalize additional metrics
        normalized.setLoadAverage(normalizeLoadAverage(metrics.getLoadAverage()));
        normalized.setProcessCount(normalizeProcessCount(metrics.getProcessCount()));
        normalized.setNetworkConnections(normalizeNetworkConnections(metrics.getNetworkConnections()));
        
        // Normalize custom metrics
        normalized.setCustomMetrics(normalizeCustomMetrics(metrics.getCustomMetrics()));
        
        // Copy metadata
        normalized.setCompressed(metrics.getCompressed());
        normalized.setCompressionRatio(metrics.getCompressionRatio());
        
        return normalized;
    }
    
    /**
     * Applies data transformation rules for analytics
     */
    public MetricsDataDTO transformForAnalytics(MetricsDataDTO metrics) {
        MetricsDataDTO transformed = normalizeMetrics(metrics);
        
        // Add derived metrics
        Map<String, Object> derivedMetrics = new HashMap<>();
        
        // Calculate resource utilization index
        if (transformed.getCpuUsage() != null && transformed.getMemoryUsage() != null) {
            double resourceIndex = (transformed.getCpuUsage() + transformed.getMemoryUsage()) / 2.0;
            derivedMetrics.put("resource_utilization_index", resourceIndex);
        }
        
        // Calculate I/O intensity
        if (transformed.getDiskRead() != null && transformed.getDiskWrite() != null) {
            double ioIntensity = transformed.getDiskRead() + transformed.getDiskWrite();
            derivedMetrics.put("io_intensity", ioIntensity);
        }
        
        // Calculate network activity
        if (transformed.getNetworkUpload() != null && transformed.getNetworkDownload() != null) {
            double networkActivity = transformed.getNetworkUpload() + transformed.getNetworkDownload();
            derivedMetrics.put("network_activity", networkActivity);
        }
        
        // Add system health score
        derivedMetrics.put("system_health_score", calculateSystemHealthScore(transformed));
        
        // Merge with existing custom metrics
        Map<String, Object> allCustomMetrics = new HashMap<>();
        if (transformed.getCustomMetrics() != null) {
            allCustomMetrics.putAll(transformed.getCustomMetrics());
        }
        allCustomMetrics.putAll(derivedMetrics);
        transformed.setCustomMetrics(allCustomMetrics);
        
        return transformed;
    }
    
    private Instant normalizeTimestamp(Instant timestamp) {
        if (timestamp == null) {
            return Instant.now();
        }
        
        // Round to nearest second for consistency
        return Instant.ofEpochSecond(timestamp.getEpochSecond());
    }
    
    private Double normalizePercentage(Double value) {
        if (value == null) {
            return null;
        }
        
        // Ensure percentage is within valid range
        return Math.max(0.0, Math.min(100.0, value));
    }
    
    private Double normalizeNetworkMetric(Double value) {
        if (value == null) {
            return null;
        }
        
        // Ensure non-negative and handle potential unit conversion
        double normalized = Math.max(0.0, value);
        
        // If value seems to be in KB or MB, convert to bytes
        if (normalized < 1000 && normalized > 0) {
            // Likely in MB/s, convert to bytes/s
            normalized *= BYTES_TO_MB;
        } else if (normalized < 10 && normalized > 0) {
            // Likely in GB/s, convert to bytes/s
            normalized *= BYTES_TO_GB;
        }
        
        return normalized;
    }
    
    private Double normalizeDiskMetric(Double value) {
        if (value == null) {
            return null;
        }
        
        // Similar to network metrics, ensure proper units
        return Math.max(0.0, value);
    }
    
    private Double normalizeLoadAverage(Double value) {
        if (value == null) {
            return null;
        }
        
        // Load average should be non-negative
        return Math.max(0.0, value);
    }
    
    private Integer normalizeProcessCount(Integer value) {
        if (value == null) {
            return null;
        }
        
        // Process count should be positive
        return Math.max(1, value);
    }
    
    private Integer normalizeNetworkConnections(Integer value) {
        if (value == null) {
            return null;
        }
        
        // Network connections should be non-negative
        return Math.max(0, value);
    }
    
    private Map<String, Object> normalizeCustomMetrics(Map<String, Object> customMetrics) {
        if (customMetrics == null || customMetrics.isEmpty()) {
            return customMetrics;
        }
        
        Map<String, Object> normalized = new HashMap<>();
        
        for (Map.Entry<String, Object> entry : customMetrics.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            // Normalize key (lowercase, replace spaces with underscores)
            String normalizedKey = key.toLowerCase().replaceAll("\\s+", "_");
            
            // Normalize value based on type
            Object normalizedValue = normalizeCustomMetricValue(value);
            
            normalized.put(normalizedKey, normalizedValue);
        }
        
        return normalized;
    }
    
    private Object normalizeCustomMetricValue(Object value) {
        if (value == null) {
            return null;
        }
        
        if (value instanceof Number) {
            Number numValue = (Number) value;
            // Ensure finite values
            if (value instanceof Double || value instanceof Float) {
                double doubleValue = numValue.doubleValue();
                if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                    return 0.0;
                }
                return doubleValue;
            }
            return numValue;
        }
        
        if (value instanceof String) {
            String strValue = (String) value;
            // Trim and limit length
            strValue = strValue.trim();
            if (strValue.length() > 255) {
                strValue = strValue.substring(0, 255);
            }
            return strValue;
        }
        
        return value;
    }
    
    private double calculateSystemHealthScore(MetricsDataDTO metrics) {
        double score = 100.0;
        
        // Deduct points based on resource usage
        if (metrics.getCpuUsage() != null) {
            if (metrics.getCpuUsage() > 90) score -= 30;
            else if (metrics.getCpuUsage() > 80) score -= 20;
            else if (metrics.getCpuUsage() > 70) score -= 10;
        }
        
        if (metrics.getMemoryUsage() != null) {
            if (metrics.getMemoryUsage() > 95) score -= 25;
            else if (metrics.getMemoryUsage() > 85) score -= 15;
            else if (metrics.getMemoryUsage() > 75) score -= 5;
        }
        
        if (metrics.getDiskUsage() != null) {
            if (metrics.getDiskUsage() > 95) score -= 20;
            else if (metrics.getDiskUsage() > 90) score -= 10;
            else if (metrics.getDiskUsage() > 85) score -= 5;
        }
        
        // Consider load average if available
        if (metrics.getLoadAverage() != null) {
            if (metrics.getLoadAverage() > 8.0) score -= 15;
            else if (metrics.getLoadAverage() > 4.0) score -= 10;
            else if (metrics.getLoadAverage() > 2.0) score -= 5;
        }
        
        return Math.max(0.0, score);
    }
}