package cn.flying.monitor.data.service;

import cn.flying.monitor.data.dto.QueryRequestDTO;
import cn.flying.monitor.data.dto.QueryResultDTO;
import cn.flying.monitor.data.dto.AggregationRequestDTO;
import cn.flying.monitor.data.dto.AggregationResultDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Advanced query service interface for monitoring data
 * Provides optimized data retrieval, aggregation, and analytics capabilities
 */
public interface QueryService {
    
    /**
     * Query historical metrics with time-range and pagination support
     */
    Page<QueryResultDTO> queryHistoricalMetrics(QueryRequestDTO request, Pageable pageable);
    
    /**
     * Query real-time metrics for a specific client
     */
    QueryResultDTO queryRealTimeMetrics(String clientId);
    
    /**
     * Query metrics with custom filtering and grouping
     */
    List<QueryResultDTO> queryMetricsWithFilters(QueryRequestDTO request);
    
    /**
     * Aggregate metrics data (averages, percentiles, trends)
     */
    AggregationResultDTO aggregateMetrics(AggregationRequestDTO request);
    
    /**
     * Calculate percentiles for specific metrics
     */
    Map<String, Double> calculatePercentiles(String clientId, String metricName, 
                                           Instant start, Instant end, 
                                           List<Double> percentiles);
    
    /**
     * Calculate trend analysis for metrics
     */
    Map<String, Object> calculateTrends(String clientId, List<String> metricNames, 
                                      Instant start, Instant end);
    
    /**
     * Query top N clients by specific metric
     */
    List<Map<String, Object>> queryTopClientsByMetric(String metricName, 
                                                     Instant start, Instant end, 
                                                     int limit);
    
    /**
     * Query metrics comparison between multiple clients
     */
    Map<String, List<QueryResultDTO>> compareClientsMetrics(List<String> clientIds, 
                                                           List<String> metricNames,
                                                           Instant start, Instant end);
    
    /**
     * Query metrics with custom time window aggregation
     */
    List<QueryResultDTO> queryMetricsWithTimeWindow(String clientId, 
                                                   List<String> metricNames,
                                                   Instant start, Instant end,
                                                   String timeWindow);
    
    /**
     * Query metrics statistics (min, max, avg, std dev)
     */
    Map<String, Map<String, Double>> queryMetricsStatistics(String clientId, 
                                                           List<String> metricNames,
                                                           Instant start, Instant end);
    
    /**
     * Query anomaly detection results
     */
    List<Map<String, Object>> queryAnomalies(String clientId, 
                                            List<String> metricNames,
                                            Instant start, Instant end,
                                            double threshold);
    
    /**
     * Query data availability and quality metrics
     */
    Map<String, Object> queryDataQuality(String clientId, Instant start, Instant end);
    
    /**
     * Optimize query performance by analyzing query patterns
     */
    void optimizeQueryPerformance();
    
    /**
     * Get query performance statistics
     */
    Map<String, Object> getQueryStatistics();
    
    /**
     * Query metrics with advanced time-range queries and enhanced pagination
     */
    Page<QueryResultDTO> queryHistoricalMetricsAdvanced(QueryRequestDTO request, Pageable pageable);
    
    /**
     * Query metrics with custom correlation analysis
     */
    Map<String, Double> queryMetricsCorrelation(String clientId, List<String> metricNames, 
                                               Instant start, Instant end);
    
    /**
     * Query metrics with forecasting capabilities
     */
    Map<String, Object> queryMetricsForecast(String clientId, String metricName, 
                                            Instant start, Instant end, int forecastPeriods);
    
    /**
     * Query metrics with advanced filtering and custom aggregation windows
     */
    List<QueryResultDTO> queryMetricsWithAdvancedFilters(QueryRequestDTO request);
    
    /**
     * Query metrics with multi-dimensional grouping and pivoting
     */
    Map<String, Map<String, Object>> queryMetricsWithPivot(QueryRequestDTO request);
    
    /**
     * Query metrics with performance optimization hints
     */
    List<QueryResultDTO> queryMetricsOptimized(QueryRequestDTO request, Map<String, Object> optimizationHints);
    
    /**
     * Export metrics data in various formats (CSV, JSON, Excel)
     */
    byte[] exportMetricsData(QueryRequestDTO request, String format);
    
    /**
     * Generate scheduled reports
     */
    String generateScheduledReport(String reportId, QueryRequestDTO request, String format);
    
    /**
     * Get export status and download link
     */
    Map<String, Object> getExportStatus(String exportId);
    
    /**
     * Query metrics with custom business logic aggregations
     */
    Map<String, Object> queryBusinessMetrics(String clientId, List<String> businessRules, 
                                           Instant start, Instant end);
    
    /**
     * Warm cache with frequently accessed data
     */
    void warmCache(List<String> clientIds, Instant start, Instant end);
    
    /**
     * Invalidate cache for specific client
     */
    void invalidateClientCache(String clientId);
    
    /**
     * Get cache statistics and performance metrics
     */
    Map<String, Object> getCacheStatistics();
}