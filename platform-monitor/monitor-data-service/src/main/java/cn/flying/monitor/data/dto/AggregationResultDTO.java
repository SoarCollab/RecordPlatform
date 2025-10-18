package cn.flying.monitor.data.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregation result DTO for analytics data
 */
@Data
public class AggregationResultDTO {
    
    private String clientId;
    
    private Instant startTime;
    
    private Instant endTime;
    
    private String timeWindow;
    
    private List<String> metricNames;
    
    private Map<String, Map<String, Double>> aggregations; // metric -> function -> value
    
    private Map<String, Double> percentiles; // percentile -> value
    
    private Map<String, Object> statistics; // min, max, avg, stddev, count
    
    private Map<String, Object> trends; // trend analysis results
    
    private List<Map<String, Object>> anomalies; // detected anomalies
    
    private Map<String, Object> metadata;
    
    private Long totalRecords;
    
    private Double dataQualityScore;
    
    private Instant calculatedAt;
    
    // Convenience methods for common aggregations
    public Double getAverageValue(String metricName) {
        return getAggregationValue(metricName, "avg");
    }
    
    public Double getMinValue(String metricName) {
        return getAggregationValue(metricName, "min");
    }
    
    public Double getMaxValue(String metricName) {
        return getAggregationValue(metricName, "max");
    }
    
    public Double getSumValue(String metricName) {
        return getAggregationValue(metricName, "sum");
    }
    
    public Double getStandardDeviation(String metricName) {
        return getAggregationValue(metricName, "stddev");
    }
    
    public Double getMedianValue(String metricName) {
        return getAggregationValue(metricName, "median");
    }
    
    private Double getAggregationValue(String metricName, String function) {
        if (aggregations == null) {
            return null;
        }
        
        Map<String, Double> metricAggregations = aggregations.get(metricName);
        if (metricAggregations == null) {
            return null;
        }
        
        return metricAggregations.get(function);
    }
    
    public void addAggregation(String metricName, String function, Double value) {
        if (aggregations == null) {
            aggregations = new java.util.HashMap<>();
        }
        
        aggregations.computeIfAbsent(metricName, k -> new java.util.HashMap<>())
                   .put(function, value);
    }
    
    public void addPercentile(Double percentile, Double value) {
        if (percentiles == null) {
            percentiles = new java.util.HashMap<>();
        }
        percentiles.put(String.valueOf(percentile), value);
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
            anomalies = new java.util.ArrayList<>();
        }
        anomalies.add(anomaly);
    }
    
    public void addMetadata(String key, Object value) {
        if (metadata == null) {
            metadata = new java.util.HashMap<>();
        }
        metadata.put(key, value);
    }
}