package cn.flying.monitor.data.validation;

import cn.flying.monitor.data.dto.MetricsDataDTO;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive metrics validation service
 * Implements data quality checks and error handling
 */
@Component
public class MetricsValidator {
    
    private static final double MAX_CPU_SPIKE = 20.0; // Max CPU change per minute
    private static final double MAX_MEMORY_SPIKE = 15.0; // Max memory change per minute
    private static final long MAX_TIMESTAMP_DRIFT = 300; // 5 minutes in seconds
    
    /**
     * Validates individual metrics data
     */
    public ValidationResult validateMetrics(MetricsDataDTO metrics) {
        ValidationResult result = new ValidationResult();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        // Timestamp validation
        validateTimestamp(metrics.getTimestamp(), errors, warnings);
        
        // Range validation (already handled by annotations, but add business logic)
        validateMetricRanges(metrics, errors, warnings);
        
        // Consistency validation
        validateMetricConsistency(metrics, errors, warnings);
        
        // Custom metrics validation
        validateCustomMetrics(metrics.getCustomMetrics(), errors, warnings);
        
        result.setValid(errors.isEmpty());
        result.setErrors(errors);
        result.setWarnings(warnings);
        result.setDataQualityScore(calculateDataQualityScore(errors, warnings));
        
        return result;
    }
    
    /**
     * Validates batch of metrics for consistency
     */
    public BatchValidationResult validateBatch(List<MetricsDataDTO> metricsList) {
        BatchValidationResult result = new BatchValidationResult();
        List<ValidationResult> individualResults = new ArrayList<>();
        List<String> batchErrors = new ArrayList<>();
        
        // Validate each metric individually
        for (int i = 0; i < metricsList.size(); i++) {
            ValidationResult individualResult = validateMetrics(metricsList.get(i));
            individualResults.add(individualResult);
            
            if (!individualResult.isValid()) {
                batchErrors.add("Metric at index " + i + " is invalid: " + 
                               String.join(", ", individualResult.getErrors()));
            }
        }
        
        // Validate batch consistency
        validateBatchConsistency(metricsList, batchErrors);
        
        result.setIndividualResults(individualResults);
        result.setBatchErrors(batchErrors);
        result.setValid(batchErrors.isEmpty());
        result.setTotalMetrics(metricsList.size());
        result.setValidMetrics((int) individualResults.stream().mapToLong(r -> r.isValid() ? 1 : 0).sum());
        
        return result;
    }
    
    private void validateTimestamp(Instant timestamp, List<String> errors, List<String> warnings) {
        if (timestamp == null) {
            errors.add("Timestamp cannot be null");
            return;
        }
        
        Instant now = Instant.now();
        long driftSeconds = Math.abs(ChronoUnit.SECONDS.between(timestamp, now));
        
        if (driftSeconds > MAX_TIMESTAMP_DRIFT) {
            if (timestamp.isAfter(now)) {
                errors.add("Timestamp is too far in the future: " + driftSeconds + " seconds");
            } else {
                warnings.add("Timestamp is significantly old: " + driftSeconds + " seconds");
            }
        }
    }
    
    private void validateMetricRanges(MetricsDataDTO metrics, List<String> errors, List<String> warnings) {
        // Additional business logic validation beyond annotations
        
        // Check for suspicious values
        if (metrics.getCpuUsage() != null && metrics.getCpuUsage() > 95.0) {
            warnings.add("CPU usage is critically high: " + metrics.getCpuUsage() + "%");
        }
        
        if (metrics.getMemoryUsage() != null && metrics.getMemoryUsage() > 90.0) {
            warnings.add("Memory usage is critically high: " + metrics.getMemoryUsage() + "%");
        }
        
        if (metrics.getDiskUsage() != null && metrics.getDiskUsage() > 95.0) {
            warnings.add("Disk usage is critically high: " + metrics.getDiskUsage() + "%");
        }
        
        // Validate network metrics for reasonable values
        if (metrics.getNetworkUpload() != null && metrics.getNetworkUpload() > 1_000_000_000) { // 1GB/s
            warnings.add("Network upload rate seems unusually high: " + metrics.getNetworkUpload() + " bytes/s");
        }
        
        if (metrics.getNetworkDownload() != null && metrics.getNetworkDownload() > 1_000_000_000) { // 1GB/s
            warnings.add("Network download rate seems unusually high: " + metrics.getNetworkDownload() + " bytes/s");
        }
    }
    
    private void validateMetricConsistency(MetricsDataDTO metrics, List<String> errors, List<String> warnings) {
        // Check for logical inconsistencies
        
        // If load average is provided, validate against CPU usage
        if (metrics.getLoadAverage() != null && metrics.getCpuUsage() != null) {
            if (metrics.getLoadAverage() > 4.0 && metrics.getCpuUsage() < 50.0) {
                warnings.add("High load average (" + metrics.getLoadAverage() + 
                           ") with low CPU usage (" + metrics.getCpuUsage() + "%) - possible I/O bottleneck");
            }
        }
        
        // Validate process count reasonableness
        if (metrics.getProcessCount() != null) {
            if (metrics.getProcessCount() > 10000) {
                warnings.add("Process count seems unusually high: " + metrics.getProcessCount());
            } else if (metrics.getProcessCount() < 10) {
                warnings.add("Process count seems unusually low: " + metrics.getProcessCount());
            }
        }
    }
    
    private void validateCustomMetrics(Map<String, Object> customMetrics, List<String> errors, List<String> warnings) {
        if (customMetrics == null || customMetrics.isEmpty()) {
            return;
        }
        
        // Validate custom metrics structure and values
        for (Map.Entry<String, Object> entry : customMetrics.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (key == null || key.trim().isEmpty()) {
                errors.add("Custom metric key cannot be null or empty");
                continue;
            }
            
            if (key.length() > 100) {
                errors.add("Custom metric key too long: " + key);
                continue;
            }
            
            if (value == null) {
                warnings.add("Custom metric '" + key + "' has null value");
            }
        }
        
        if (customMetrics.size() > 50) {
            warnings.add("Large number of custom metrics: " + customMetrics.size());
        }
    }
    
    private void validateBatchConsistency(List<MetricsDataDTO> metricsList, List<String> batchErrors) {
        if (metricsList.size() < 2) {
            return; // No consistency check needed for single metric
        }
        
        // Check for duplicate timestamps from same client
        Map<String, Map<Instant, Integer>> clientTimestamps = new HashMap<>();
        
        for (int i = 0; i < metricsList.size(); i++) {
            MetricsDataDTO metrics = metricsList.get(i);
            String clientId = metrics.getClientId();
            Instant timestamp = metrics.getTimestamp();
            
            clientTimestamps.computeIfAbsent(clientId, k -> new HashMap<>())
                           .merge(timestamp, 1, Integer::sum);
        }
        
        // Report duplicates
        clientTimestamps.forEach((clientId, timestamps) -> {
            timestamps.forEach((timestamp, count) -> {
                if (count > 1) {
                    batchErrors.add("Duplicate timestamp " + timestamp + 
                                  " for client " + clientId + " (count: " + count + ")");
                }
            });
        });
        
        // Check for chronological order within each client
        Map<String, Instant> lastTimestamps = new HashMap<>();
        for (MetricsDataDTO metrics : metricsList) {
            String clientId = metrics.getClientId();
            Instant timestamp = metrics.getTimestamp();
            
            Instant lastTimestamp = lastTimestamps.get(clientId);
            if (lastTimestamp != null && timestamp.isBefore(lastTimestamp)) {
                batchErrors.add("Metrics for client " + clientId + 
                              " are not in chronological order");
            }
            lastTimestamps.put(clientId, timestamp);
        }
    }
    
    private double calculateDataQualityScore(List<String> errors, List<String> warnings) {
        double score = 100.0;
        
        // Deduct points for errors and warnings
        score -= errors.size() * 20.0; // 20 points per error
        score -= warnings.size() * 5.0; // 5 points per warning
        
        return Math.max(0.0, score);
    }
}