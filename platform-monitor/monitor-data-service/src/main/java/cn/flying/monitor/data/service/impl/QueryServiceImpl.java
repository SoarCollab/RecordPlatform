package cn.flying.monitor.data.service.impl;

import cn.flying.monitor.data.dto.*;
import cn.flying.monitor.data.service.QueryService;
import cn.flying.monitor.data.service.InfluxDBService;
import cn.flying.monitor.data.service.QueryCacheService;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Advanced query service implementation with optimization and caching
 */
@Service
public class QueryServiceImpl implements QueryService {
    private static final Logger log = LoggerFactory.getLogger(QueryServiceImpl.class);
    
    @Autowired
    private InfluxDBService influxDBService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private QueryCacheService queryCacheService;
    
    @Value("${spring.influx.url}")
    private String url;
    
    @Value("${spring.influx.token}")
    private String token;
    
    @Value("${spring.influx.bucket}")
    private String bucket;
    
    @Value("${spring.influx.organization}")
    private String organization;
    
    private InfluxDBClient client;
    
    // Performance tracking
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong totalQueryTime = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    
    // Query result cache
    private final Map<String, CachedQueryResult> queryCache = new ConcurrentHashMap<>();
    private final long CACHE_TTL_MS = 300_000; // 5 minutes
    
    @PostConstruct
    public void init() {
        client = InfluxDBClientFactory.create(url, token.toCharArray(), organization, bucket);
        log.info("QueryService initialized successfully");
        
        // Start intelligent cache warming in background
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(30000); // Wait 30 seconds after startup
                performIntelligentCacheWarming();
                log.info("Initial cache warming completed");
            } catch (Exception e) {
                log.warn("Initial cache warming failed", e);
            }
        });
    }
    
    @PreDestroy
    public void cleanup() {
        if (client != null) {
            client.close();
        }
    }
    
    @Override
    public Page<QueryResultDTO> queryHistoricalMetrics(QueryRequestDTO request, Pageable pageable) {
        long startTime = System.currentTimeMillis();
        totalQueries.incrementAndGet();
        
        try {
            // Generate enhanced cache key
            String cacheKey = queryCacheService.generateCacheKey(request, pageable);
            
            // Check enhanced cache first
            Optional<Page<QueryResultDTO>> cachedResult = queryCacheService.getCachedHistoricalMetrics(cacheKey);
            if (cachedResult.isPresent()) {
                cacheHits.incrementAndGet();
                log.debug("Cache hit for historical metrics: {}", cacheKey);
                return cachedResult.get();
            }
            
            cacheMisses.incrementAndGet();
            
            // Build and execute query
            String query = buildHistoricalQuery(request, pageable);
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            List<QueryResultDTO> results = processQueryResults(tables, request);
            
            // Apply pagination to results
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), results.size());
            List<QueryResultDTO> pageContent = results.subList(start, end);
            Page<QueryResultDTO> pageResult = new PageImpl<>(pageContent, pageable, results.size());
            
            // Cache results with intelligent TTL
            long ttl = calculateIntelligentCacheTTL(request);
            queryCacheService.cacheHistoricalMetrics(cacheKey, pageResult, ttl);
            
            return pageResult;
            
        } catch (Exception e) {
            log.error("Failed to query historical metrics", e);
            throw new RuntimeException("Historical metrics query failed", e);
        } finally {
            totalQueryTime.addAndGet(System.currentTimeMillis() - startTime);
        }
    }
    
    @Override
    public QueryResultDTO queryRealTimeMetrics(String clientId) {
        long startTime = System.currentTimeMillis();
        totalQueries.incrementAndGet();
        
        try {
            // Check cache first for real-time metrics
            Optional<QueryResultDTO> cachedResult = queryCacheService.getCachedRealTimeMetrics(clientId);
            if (cachedResult.isPresent()) {
                cacheHits.incrementAndGet();
                log.debug("Cache hit for real-time metrics: {}", clientId);
                return cachedResult.get();
            }
            
            cacheMisses.incrementAndGet();
            
            String query = String.format("""
                from(bucket: "%s")
                |> range(start: -5m)
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["client_id"] == "%s")
                |> last()
                """, bucket, clientId);
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            List<QueryResultDTO> results = processQueryResults(tables, null);
            
            QueryResultDTO result = results.isEmpty() ? null : results.get(0);
            
            // Cache the result if not null
            if (result != null) {
                queryCacheService.cacheRealTimeMetrics(clientId, result);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("Failed to query real-time metrics for client: {}", clientId, e);
            throw new RuntimeException("Real-time metrics query failed", e);
        } finally {
            totalQueryTime.addAndGet(System.currentTimeMillis() - startTime);
        }
    }
    
    @Override
    public List<QueryResultDTO> queryMetricsWithFilters(QueryRequestDTO request) {
        long startTime = System.currentTimeMillis();
        totalQueries.incrementAndGet();
        
        try {
            String query = buildFilteredQuery(request);
            
            // Check cache
            String cacheKey = generateCacheKey("filtered", query);
            CachedQueryResult cached = queryCache.get(cacheKey);
            
            if (cached != null && !cached.isExpired()) {
                cacheHits.incrementAndGet();
                @SuppressWarnings("unchecked")
                List<QueryResultDTO> cachedData = (List<QueryResultDTO>) cached.getData();
                return cachedData;
            }
            
            cacheMisses.incrementAndGet();
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            List<QueryResultDTO> results = processQueryResults(tables, request);
            
            // Cache results
            queryCache.put(cacheKey, new CachedQueryResult(results, System.currentTimeMillis()));
            
            return results;
            
        } catch (Exception e) {
            log.error("Failed to query metrics with filters", e);
            throw new RuntimeException("Filtered metrics query failed", e);
        } finally {
            totalQueryTime.addAndGet(System.currentTimeMillis() - startTime);
        }
    }
    
    @Override
    public AggregationResultDTO aggregateMetrics(AggregationRequestDTO request) {
        long startTime = System.currentTimeMillis();
        totalQueries.incrementAndGet();
        
        try {
            // Generate cache key for aggregation
            String cacheKey = queryCacheService.generateAggregationCacheKey(
                request.getClientId(), 
                request.getMetricNames(), 
                request.getStartTime(), 
                request.getEndTime(), 
                String.join(",", request.getAggregationFunctions())
            );
            
            // Check cache first
            Optional<AggregationResultDTO> cachedResult = queryCacheService.getCachedAggregationResults(cacheKey);
            if (cachedResult.isPresent()) {
                cacheHits.incrementAndGet();
                log.debug("Cache hit for aggregation: {}", cacheKey);
                return cachedResult.get();
            }
            
            cacheMisses.incrementAndGet();
            
            AggregationResultDTO result = new AggregationResultDTO();
            result.setClientId(request.getClientId());
            result.setStartTime(request.getStartTime());
            result.setEndTime(request.getEndTime());
            result.setTimeWindow(request.getTimeWindow());
            result.setMetricNames(request.getMetricNames());
            result.setCalculatedAt(Instant.now());
            
            // Execute aggregation queries for each function
            for (String function : request.getAggregationFunctions()) {
                for (String metricName : request.getMetricNames()) {
                    String query = buildAggregationQuery(request, metricName, function);
                    
                    List<FluxTable> tables = client.getQueryApi().query(query, organization);
                    Double value = extractAggregationValue(tables);
                    
                    if (value != null) {
                        result.addAggregation(metricName, function, value);
                    }
                }
            }
            
            // Calculate percentiles if requested
            if (request.hasPercentiles()) {
                Map<String, Double> percentiles = calculatePercentiles(
                    request.getClientId(),
                    request.getMetricNames().get(0), // Use first metric for percentiles
                    request.getStartTime(),
                    request.getEndTime(),
                    request.getPercentiles()
                );
                
                for (Map.Entry<String, Double> entry : percentiles.entrySet()) {
                    result.addPercentile(Double.valueOf(entry.getKey()), entry.getValue());
                }
            }
            
            // Calculate statistics if requested
            if (request.getIncludeStatistics()) {
                Map<String, Map<String, Double>> statistics = queryMetricsStatistics(
                    request.getClientId(),
                    request.getMetricNames(),
                    request.getStartTime(),
                    request.getEndTime()
                );
                
                for (Map.Entry<String, Map<String, Double>> entry : statistics.entrySet()) {
                    for (Map.Entry<String, Double> stat : entry.getValue().entrySet()) {
                        result.addStatistic(entry.getKey() + "_" + stat.getKey(), stat.getValue());
                    }
                }
            }
            
            // Calculate trends if requested
            if (request.getIncludeTrends()) {
                Map<String, Object> trends = calculateTrends(
                    request.getClientId(),
                    request.getMetricNames(),
                    request.getStartTime(),
                    request.getEndTime()
                );
                
                for (Map.Entry<String, Object> entry : trends.entrySet()) {
                    result.addTrend(entry.getKey(), entry.getValue());
                }
            }
            
            // Detect anomalies if requested
            if (request.getIncludeAnomalies()) {
                List<Map<String, Object>> anomalies = queryAnomalies(
                    request.getClientId(),
                    request.getMetricNames(),
                    request.getStartTime(),
                    request.getEndTime(),
                    request.getAnomalyThreshold()
                );
                
                for (Map<String, Object> anomaly : anomalies) {
                    result.addAnomaly(anomaly);
                }
            }
            
            // Cache the aggregation result
            long ttl = calculateAggregationCacheTTL(request);
            queryCacheService.cacheAggregationResults(cacheKey, result, ttl);
            
            return result;
            
        } catch (Exception e) {
            log.error("Failed to aggregate metrics", e);
            throw new RuntimeException("Metrics aggregation failed", e);
        } finally {
            totalQueryTime.addAndGet(System.currentTimeMillis() - startTime);
        }
    }
    
    @Override
    public Map<String, Double> calculatePercentiles(String clientId, String metricName, 
                                                  Instant start, Instant end, 
                                                  List<Double> percentiles) {
        try {
            Map<String, Double> results = new HashMap<>();
            
            for (Double percentile : percentiles) {
                String query = String.format("""
                    from(bucket: "%s")
                    |> range(start: %s, stop: %s)
                    |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                    |> filter(fn: (r) => r["client_id"] == "%s")
                    |> filter(fn: (r) => r["_field"] == "%s")
                    |> quantile(q: %.2f)
                    """, bucket, start, end, clientId, metricName, percentile / 100.0);
                
                List<FluxTable> tables = client.getQueryApi().query(query, organization);
                Double value = extractAggregationValue(tables);
                
                if (value != null) {
                    results.put(String.valueOf(percentile), value);
                }
            }
            
            return results;
            
        } catch (Exception e) {
            log.error("Failed to calculate percentiles", e);
            throw new RuntimeException("Percentile calculation failed", e);
        }
    }
    
    @Override
    public Map<String, Object> calculateTrends(String clientId, List<String> metricNames, 
                                             Instant start, Instant end) {
        try {
            Map<String, Object> trends = new HashMap<>();
            
            for (String metricName : metricNames) {
                // Calculate linear regression for trend analysis
                String query = String.format("""
                    from(bucket: "%s")
                    |> range(start: %s, stop: %s)
                    |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                    |> filter(fn: (r) => r["client_id"] == "%s")
                    |> filter(fn: (r) => r["_field"] == "%s")
                    |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
                    |> derivative(unit: 1h, nonNegative: false)
                    |> mean()
                    """, bucket, start, end, clientId, metricName);
                
                List<FluxTable> tables = client.getQueryApi().query(query, organization);
                Double trendValue = extractAggregationValue(tables);
                
                if (trendValue != null) {
                    Map<String, Object> trendData = new HashMap<>();
                    trendData.put("slope", trendValue);
                    trendData.put("direction", trendValue > 0 ? "increasing" : 
                                             trendValue < 0 ? "decreasing" : "stable");
                    trendData.put("magnitude", Math.abs(trendValue));
                    
                    trends.put(metricName, trendData);
                }
            }
            
            return trends;
            
        } catch (Exception e) {
            log.error("Failed to calculate trends", e);
            throw new RuntimeException("Trend calculation failed", e);
        }
    }
    
    @Override
    public List<Map<String, Object>> queryTopClientsByMetric(String metricName, 
                                                           Instant start, Instant end, 
                                                           int limit) {
        try {
            String query = String.format("""
                from(bucket: "%s")
                |> range(start: %s, stop: %s)
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["_field"] == "%s")
                |> group(columns: ["client_id"])
                |> mean()
                |> group()
                |> sort(columns: ["_value"], desc: true)
                |> limit(n: %d)
                """, bucket, start, end, metricName, limit);
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            List<Map<String, Object>> results = new ArrayList<>();
            
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("client_id", record.getValueByKey("client_id"));
                    result.put("metric_name", metricName);
                    result.put("average_value", record.getValue());
                    result.put("start_time", start);
                    result.put("end_time", end);
                    results.add(result);
                }
            }
            
            return results;
            
        } catch (Exception e) {
            log.error("Failed to query top clients by metric", e);
            throw new RuntimeException("Top clients query failed", e);
        }
    }
    
    @Override
    public Map<String, List<QueryResultDTO>> compareClientsMetrics(List<String> clientIds, 
                                                                 List<String> metricNames,
                                                                 Instant start, Instant end) {
        try {
            Map<String, List<QueryResultDTO>> comparison = new HashMap<>();
            
            for (String clientId : clientIds) {
                QueryRequestDTO request = new QueryRequestDTO();
                request.setClientId(clientId);
                request.setStartTime(start);
                request.setEndTime(end);
                request.setMetricNames(metricNames);
                
                List<QueryResultDTO> results = queryMetricsWithFilters(request);
                comparison.put(clientId, results);
            }
            
            return comparison;
            
        } catch (Exception e) {
            log.error("Failed to compare clients metrics", e);
            throw new RuntimeException("Clients comparison failed", e);
        }
    }
    
    @Override
    public List<QueryResultDTO> queryMetricsWithTimeWindow(String clientId, 
                                                         List<String> metricNames,
                                                         Instant start, Instant end,
                                                         String timeWindow) {
        try {
            String query = String.format("""
                from(bucket: "%s")
                |> range(start: %s, stop: %s)
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["client_id"] == "%s")
                |> filter(fn: (r) => contains(value: r["_field"], set: %s))
                |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
                """, bucket, start, end, clientId, formatMetricNames(metricNames), timeWindow);
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            return processQueryResults(tables, null);
            
        } catch (Exception e) {
            log.error("Failed to query metrics with time window", e);
            throw new RuntimeException("Time window query failed", e);
        }
    }
    
    @Override
    public Map<String, Map<String, Double>> queryMetricsStatistics(String clientId, 
                                                                 List<String> metricNames,
                                                                 Instant start, Instant end) {
        try {
            Map<String, Map<String, Double>> statistics = new HashMap<>();
            
            for (String metricName : metricNames) {
                Map<String, Double> metricStats = new HashMap<>();
                
                // Calculate various statistics
                String[] functions = {"min", "max", "mean", "stddev", "count"};
                
                for (String function : functions) {
                    String query = String.format("""
                        from(bucket: "%s")
                        |> range(start: %s, stop: %s)
                        |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                        |> filter(fn: (r) => r["client_id"] == "%s")
                        |> filter(fn: (r) => r["_field"] == "%s")
                        |> %s()
                        """, bucket, start, end, clientId, metricName, function);
                    
                    List<FluxTable> tables = client.getQueryApi().query(query, organization);
                    Double value = extractAggregationValue(tables);
                    
                    if (value != null) {
                        metricStats.put(function, value);
                    }
                }
                
                statistics.put(metricName, metricStats);
            }
            
            return statistics;
            
        } catch (Exception e) {
            log.error("Failed to query metrics statistics", e);
            throw new RuntimeException("Statistics query failed", e);
        }
    }
    
    @Override
    public List<Map<String, Object>> queryAnomalies(String clientId, 
                                                   List<String> metricNames,
                                                   Instant start, Instant end,
                                                   double threshold) {
        try {
            List<Map<String, Object>> anomalies = new ArrayList<>();
            
            for (String metricName : metricNames) {
                // Calculate mean and standard deviation
                Map<String, Map<String, Double>> stats = queryMetricsStatistics(
                    clientId, List.of(metricName), start, end);
                
                Map<String, Double> metricStats = stats.get(metricName);
                if (metricStats == null) continue;
                
                Double mean = metricStats.get("mean");
                Double stddev = metricStats.get("stddev");
                
                if (mean == null || stddev == null) continue;
                
                // Find values outside threshold * standard deviations
                String query = String.format("""
                    from(bucket: "%s")
                    |> range(start: %s, stop: %s)
                    |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                    |> filter(fn: (r) => r["client_id"] == "%s")
                    |> filter(fn: (r) => r["_field"] == "%s")
                    |> map(fn: (r) => ({
                        r with
                        _value: r._value,
                        z_score: (r._value - %.2f) / %.2f,
                        is_anomaly: math.abs((r._value - %.2f) / %.2f) > %.2f
                    }))
                    |> filter(fn: (r) => r.is_anomaly == true)
                    """, bucket, start, end, clientId, metricName, 
                    mean, stddev, mean, stddev, threshold);
                
                List<FluxTable> tables = client.getQueryApi().query(query, organization);
                
                for (FluxTable table : tables) {
                    for (FluxRecord record : table.getRecords()) {
                        Map<String, Object> anomaly = new HashMap<>();
                        anomaly.put("client_id", clientId);
                        anomaly.put("metric_name", metricName);
                        anomaly.put("timestamp", record.getTime());
                        anomaly.put("value", record.getValue());
                        anomaly.put("z_score", record.getValueByKey("z_score"));
                        anomaly.put("threshold", threshold);
                        anomaly.put("mean", mean);
                        anomaly.put("stddev", stddev);
                        
                        anomalies.add(anomaly);
                    }
                }
            }
            
            return anomalies;
            
        } catch (Exception e) {
            log.error("Failed to query anomalies", e);
            throw new RuntimeException("Anomaly detection failed", e);
        }
    }
    
    @Override
    public Map<String, Object> queryDataQuality(String clientId, Instant start, Instant end) {
        try {
            Map<String, Object> quality = new HashMap<>();
            
            // Calculate data completeness
            String completenessQuery = String.format("""
                from(bucket: "%s")
                |> range(start: %s, stop: %s)
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["client_id"] == "%s")
                |> group(columns: ["_field"])
                |> count()
                """, bucket, start, end, clientId);
            
            List<FluxTable> tables = client.getQueryApi().query(completenessQuery, organization);
            Map<String, Long> fieldCounts = new HashMap<>();
            
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    String field = record.getField();
                    Long count = ((Number) record.getValue()).longValue();
                    fieldCounts.put(field, count);
                }
            }
            
            // Calculate expected data points (assuming 1-minute intervals)
            long expectedPoints = ChronoUnit.MINUTES.between(start, end);
            
            // Calculate completeness percentage
            double totalCompleteness = 0.0;
            for (Long count : fieldCounts.values()) {
                totalCompleteness += (double) count / expectedPoints;
            }
            
            double averageCompleteness = fieldCounts.isEmpty() ? 0.0 : 
                                       totalCompleteness / fieldCounts.size();
            
            quality.put("completeness_percentage", averageCompleteness * 100);
            quality.put("expected_points", expectedPoints);
            quality.put("field_counts", fieldCounts);
            quality.put("total_fields", fieldCounts.size());
            quality.put("start_time", start);
            quality.put("end_time", end);
            
            // Calculate overall quality score (0-100)
            double qualityScore = Math.min(100.0, averageCompleteness * 100);
            quality.put("quality_score", qualityScore);
            
            return quality;
            
        } catch (Exception e) {
            log.error("Failed to query data quality", e);
            throw new RuntimeException("Data quality query failed", e);
        }
    }
    
    @Override
    public void optimizeQueryPerformance() {
        try {
            // Clear expired cache entries
            long currentTime = System.currentTimeMillis();
            queryCache.entrySet().removeIf(entry -> 
                currentTime - entry.getValue().getTimestamp() > CACHE_TTL_MS);
            
            // Perform intelligent cache warming for frequently accessed data
            performIntelligentCacheWarming();
            
            // Clean up cache service
            queryCacheService.cleanExpiredEntries();
            
            log.info("Query performance optimization completed. Cache size: {}", queryCache.size());
            
        } catch (Exception e) {
            log.error("Failed to optimize query performance", e);
        }
    }
    
    @Override
    public Map<String, Object> getQueryStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("total_queries", totalQueries.get());
        stats.put("total_query_time_ms", totalQueryTime.get());
        stats.put("average_query_time_ms", 
                 totalQueries.get() > 0 ? totalQueryTime.get() / totalQueries.get() : 0);
        stats.put("cache_hits", cacheHits.get());
        stats.put("cache_misses", cacheMisses.get());
        stats.put("cache_hit_rate", 
                 (cacheHits.get() + cacheMisses.get()) > 0 ? 
                 (double) cacheHits.get() / (cacheHits.get() + cacheMisses.get()) : 0.0);
        stats.put("cache_size", queryCache.size());
        
        return stats;
    }
    
    // Helper methods
    
    private String buildHistoricalQuery(QueryRequestDTO request, Pageable pageable) {
        StringBuilder query = new StringBuilder();
        query.append(String.format("from(bucket: \"%s\")", bucket));
        query.append(String.format("|> range(start: %s, stop: %s)", 
                                 request.getStartTime(), request.getEndTime()));
        query.append("|> filter(fn: (r) => r[\"_measurement\"] == \"system_metrics\")");
        query.append(String.format("|> filter(fn: (r) => r[\"client_id\"] == \"%s\")", 
                                 request.getClientId()));
        
        if (request.getMetricNames() != null && !request.getMetricNames().isEmpty()) {
            query.append(String.format("|> filter(fn: (r) => contains(value: r[\"_field\"], set: %s))", 
                                     formatMetricNames(request.getMetricNames())));
        }
        
        if (request.hasTimeWindow()) {
            query.append(String.format("|> aggregateWindow(every: %s, fn: mean, createEmpty: false)", 
                                     request.getTimeWindow()));
        }
        
        if (request.getSortBy() != null) {
            boolean desc = "desc".equalsIgnoreCase(request.getSortOrder());
            query.append(String.format("|> sort(columns: [\"%s\"], desc: %s)", 
                                     request.getSortBy(), desc));
        }
        
        // Apply optimization hints
        String baseQuery = query.toString();
        String optimizedQuery = optimizeQueryForDataCharacteristics(baseQuery, request);
        return addQueryOptimizationHints(optimizedQuery, request);
    }
    
    private String buildFilteredQuery(QueryRequestDTO request) {
        StringBuilder query = new StringBuilder();
        query.append(String.format("from(bucket: \"%s\")", bucket));
        query.append(String.format("|> range(start: %s, stop: %s)", 
                                 request.getStartTime(), request.getEndTime()));
        query.append("|> filter(fn: (r) => r[\"_measurement\"] == \"system_metrics\")");
        query.append(String.format("|> filter(fn: (r) => r[\"client_id\"] == \"%s\")", 
                                 request.getClientId()));
        
        if (request.getMetricNames() != null && !request.getMetricNames().isEmpty()) {
            query.append(String.format("|> filter(fn: (r) => contains(value: r[\"_field\"], set: %s))", 
                                     formatMetricNames(request.getMetricNames())));
        }
        
        // Add custom filters
        if (request.hasFilters()) {
            for (Map.Entry<String, Object> filter : request.getFilters().entrySet()) {
                String key = filter.getKey();
                Object value = filter.getValue();
                
                if (value instanceof Number) {
                    query.append(String.format("|> filter(fn: (r) => r[\"%s\"] == %s)", key, value));
                } else if (value instanceof String) {
                    query.append(String.format("|> filter(fn: (r) => r[\"%s\"] == \"%s\")", key, value));
                }
            }
        }
        
        if (request.hasTimeWindow()) {
            query.append(String.format("|> aggregateWindow(every: %s, fn: mean, createEmpty: false)", 
                                     request.getTimeWindow()));
        }
        
        if (request.hasGroupBy()) {
            query.append(String.format("|> group(columns: %s)", 
                                     formatStringList(request.getGroupBy())));
        }
        
        if (request.getLimit() != null && request.getLimit() > 0) {
            query.append(String.format("|> limit(n: %d)", request.getLimit()));
        }
        
        return query.toString();
    }
    
    private String buildAggregationQuery(AggregationRequestDTO request, String metricName, String function) {
        StringBuilder query = new StringBuilder();
        query.append(String.format("from(bucket: \"%s\")", bucket));
        query.append(String.format("|> range(start: %s, stop: %s)", 
                                 request.getStartTime(), request.getEndTime()));
        query.append("|> filter(fn: (r) => r[\"_measurement\"] == \"system_metrics\")");
        query.append(String.format("|> filter(fn: (r) => r[\"client_id\"] == \"%s\")", 
                                 request.getClientId()));
        query.append(String.format("|> filter(fn: (r) => r[\"_field\"] == \"%s\")", metricName));
        
        if (request.hasGroupBy()) {
            query.append(String.format("|> group(columns: %s)", 
                                     formatStringList(request.getGroupBy())));
        }
        
        // Map aggregation functions to Flux functions
        String fluxFunction;
        switch (function.toLowerCase()) {
            case "avg":
            case "mean":
                fluxFunction = "mean";
                break;
            case "sum":
                fluxFunction = "sum";
                break;
            case "min":
                fluxFunction = "min";
                break;
            case "max":
                fluxFunction = "max";
                break;
            case "count":
                fluxFunction = "count";
                break;
            case "stddev":
                fluxFunction = "stddev";
                break;
            case "median":
                fluxFunction = "median";
                break;
            default:
                fluxFunction = "mean";
                break;
        }
        
        query.append(String.format("|> %s()", fluxFunction));
        
        return query.toString();
    }
    
    private List<QueryResultDTO> processQueryResults(List<FluxTable> tables, QueryRequestDTO request) {
        Map<Instant, QueryResultDTO> resultMap = new HashMap<>();
        
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Instant timestamp = record.getTime();
                String clientId = (String) record.getValueByKey("client_id");
                String field = record.getField();
                Object value = record.getValue();
                
                QueryResultDTO result = resultMap.computeIfAbsent(timestamp, t -> {
                    QueryResultDTO dto = new QueryResultDTO();
                    dto.setTimestamp(t);
                    dto.setClientId(clientId);
                    return dto;
                });
                
                result.addMetric(field, value);
                
                // Add metadata if requested
                if (request != null && request.getIncludeMetadata()) {
                    result.addMetadata("measurement", record.getMeasurement());
                    result.addMetadata("table", table.getColumns().size());
                }
            }
        }
        
        return new ArrayList<>(resultMap.values());
    }
    
    private Double extractAggregationValue(List<FluxTable> tables) {
        if (tables.isEmpty()) {
            return null;
        }
        
        FluxTable table = tables.get(0);
        if (table.getRecords().isEmpty()) {
            return null;
        }
        
        FluxRecord record = table.getRecords().get(0);
        Object value = record.getValue();
        
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        
        return null;
    }
    
    private String formatMetricNames(List<String> metricNames) {
        return "[" + metricNames.stream()
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(", ")) + "]";
    }
    
    private String formatStringList(List<String> strings) {
        return "[" + strings.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(", ")) + "]";
    }
    
    private String generateCacheKey(String type, String query) {
        return type + ":" + Integer.toHexString(query.hashCode());
    }
    
    @Override
    public Page<QueryResultDTO> queryHistoricalMetricsAdvanced(QueryRequestDTO request, Pageable pageable) {
        long startTime = System.currentTimeMillis();
        totalQueries.incrementAndGet();
        
        try {
            // Enhanced query with advanced time-range optimization
            String query = buildAdvancedHistoricalQuery(request, pageable);
            
            // Check cache with enhanced key generation
            String cacheKey = generateAdvancedCacheKey("historical_advanced", query, pageable);
            CachedQueryResult cached = queryCache.get(cacheKey);
            
            if (cached != null && !cached.isExpired()) {
                cacheHits.incrementAndGet();
                @SuppressWarnings("unchecked")
                List<QueryResultDTO> results = (List<QueryResultDTO>) cached.getData();
                return new PageImpl<>(results, pageable, results.size());
            }
            
            cacheMisses.incrementAndGet();
            
            // Execute optimized query with performance hints
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            List<QueryResultDTO> results = processAdvancedQueryResults(tables, request);
            
            // Enhanced caching with TTL based on data freshness
            long ttl = calculateOptimalCacheTTL(request);
            queryCache.put(cacheKey, new CachedQueryResult(results, System.currentTimeMillis(), ttl));
            
            // Apply enhanced pagination with performance optimization
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), results.size());
            List<QueryResultDTO> pageContent = results.subList(start, end);
            
            return new PageImpl<>(pageContent, pageable, results.size());
            
        } catch (Exception e) {
            log.error("Failed to query advanced historical metrics", e);
            throw new RuntimeException("Advanced historical metrics query failed", e);
        } finally {
            totalQueryTime.addAndGet(System.currentTimeMillis() - startTime);
        }
    }
    
    @Override
    public Map<String, Double> queryMetricsCorrelation(String clientId, List<String> metricNames, 
                                                      Instant start, Instant end) {
        try {
            Map<String, Double> correlations = new HashMap<>();
            
            // Calculate correlation between each pair of metrics
            for (int i = 0; i < metricNames.size(); i++) {
                for (int j = i + 1; j < metricNames.size(); j++) {
                    String metric1 = metricNames.get(i);
                    String metric2 = metricNames.get(j);
                    
                    String query = String.format("""
                        import "contrib/anaisdg/statsmodels"
                        
                        data1 = from(bucket: "%s")
                        |> range(start: %s, stop: %s)
                        |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                        |> filter(fn: (r) => r["client_id"] == "%s")
                        |> filter(fn: (r) => r["_field"] == "%s")
                        |> keep(columns: ["_time", "_value"])
                        
                        data2 = from(bucket: "%s")
                        |> range(start: %s, stop: %s)
                        |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                        |> filter(fn: (r) => r["client_id"] == "%s")
                        |> filter(fn: (r) => r["_field"] == "%s")
                        |> keep(columns: ["_time", "_value"])
                        
                        join(tables: {data1: data1, data2: data2}, on: ["_time"])
                        |> map(fn: (r) => ({
                            _time: r._time,
                            x: r._value_data1,
                            y: r._value_data2
                        }))
                        |> statsmodels.linearRegression(x: "x", y: "y")
                        """, bucket, start, end, clientId, metric1,
                        bucket, start, end, clientId, metric2);
                    
                    List<FluxTable> tables = client.getQueryApi().query(query, organization);
                    Double correlation = extractCorrelationValue(tables);
                    
                    if (correlation != null) {
                        correlations.put(metric1 + "_" + metric2, correlation);
                    }
                }
            }
            
            return correlations;
            
        } catch (Exception e) {
            log.error("Failed to calculate metrics correlation", e);
            throw new RuntimeException("Metrics correlation calculation failed", e);
        }
    }
    
    @Override
    public Map<String, Object> queryMetricsForecast(String clientId, String metricName, 
                                                   Instant start, Instant end, int forecastPeriods) {
        try {
            // Use Holt-Winters exponential smoothing for forecasting
            String query = String.format("""
                import "contrib/anaisdg/statsmodels"
                
                from(bucket: "%s")
                |> range(start: %s, stop: %s)
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["client_id"] == "%s")
                |> filter(fn: (r) => r["_field"] == "%s")
                |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
                |> statsmodels.linearRegression(x: "_time", y: "_value")
                |> map(fn: (r) => ({
                    _time: r._time,
                    actual: r._value,
                    predicted: r.y_hat,
                    residual: r._value - r.y_hat,
                    trend: r.slope
                }))
                """, bucket, start, end, clientId, metricName);
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            
            Map<String, Object> forecast = new HashMap<>();
            List<Map<String, Object>> predictions = new ArrayList<>();
            
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Map<String, Object> prediction = new HashMap<>();
                    prediction.put("timestamp", record.getTime());
                    prediction.put("actual", record.getValueByKey("actual"));
                    prediction.put("predicted", record.getValueByKey("predicted"));
                    prediction.put("residual", record.getValueByKey("residual"));
                    prediction.put("trend", record.getValueByKey("trend"));
                    predictions.add(prediction);
                }
            }
            
            forecast.put("predictions", predictions);
            forecast.put("forecast_periods", forecastPeriods);
            forecast.put("metric_name", metricName);
            forecast.put("client_id", clientId);
            forecast.put("model_type", "linear_regression");
            
            return forecast;
            
        } catch (Exception e) {
            log.error("Failed to generate metrics forecast", e);
            throw new RuntimeException("Metrics forecasting failed", e);
        }
    }
    
    @Override
    public List<QueryResultDTO> queryMetricsWithAdvancedFilters(QueryRequestDTO request) {
        long startTime = System.currentTimeMillis();
        totalQueries.incrementAndGet();
        
        try {
            String query = buildAdvancedFilteredQuery(request);
            
            // Check cache with advanced key
            String cacheKey = generateAdvancedCacheKey("advanced_filtered", query, null);
            CachedQueryResult cached = queryCache.get(cacheKey);
            
            if (cached != null && !cached.isExpired()) {
                cacheHits.incrementAndGet();
                @SuppressWarnings("unchecked")
                List<QueryResultDTO> cachedData = (List<QueryResultDTO>) cached.getData();
                return cachedData;
            }
            
            cacheMisses.incrementAndGet();
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            List<QueryResultDTO> results = processAdvancedQueryResults(tables, request);
            
            // Cache with dynamic TTL
            long ttl = calculateOptimalCacheTTL(request);
            queryCache.put(cacheKey, new CachedQueryResult(results, System.currentTimeMillis(), ttl));
            
            return results;
            
        } catch (Exception e) {
            log.error("Failed to query metrics with advanced filters", e);
            throw new RuntimeException("Advanced filtered metrics query failed", e);
        } finally {
            totalQueryTime.addAndGet(System.currentTimeMillis() - startTime);
        }
    }
    
    @Override
    public Map<String, Map<String, Object>> queryMetricsWithPivot(QueryRequestDTO request) {
        try {
            String query = buildPivotQuery(request);
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            Map<String, Map<String, Object>> pivotResults = new HashMap<>();
            
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    String pivotKey = (String) record.getValueByKey("pivot_key");
                    String metricName = record.getField();
                    Object value = record.getValue();
                    
                    pivotResults.computeIfAbsent(pivotKey, k -> new HashMap<>())
                              .put(metricName, value);
                }
            }
            
            return pivotResults;
            
        } catch (Exception e) {
            log.error("Failed to query metrics with pivot", e);
            throw new RuntimeException("Pivot metrics query failed", e);
        }
    }
    
    @Override
    public List<QueryResultDTO> queryMetricsOptimized(QueryRequestDTO request, Map<String, Object> optimizationHints) {
        try {
            String query = buildOptimizedQuery(request, optimizationHints);
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            return processAdvancedQueryResults(tables, request);
            
        } catch (Exception e) {
            log.error("Failed to query optimized metrics", e);
            throw new RuntimeException("Optimized metrics query failed", e);
        }
    }
    
    @Override
    public byte[] exportMetricsData(QueryRequestDTO request, String format) {
        try {
            List<QueryResultDTO> data = queryMetricsWithFilters(request);
            
            return switch (format.toLowerCase()) {
                case "csv" -> exportToCsv(data);
                case "json" -> exportToJson(data);
                case "excel" -> exportToExcel(data);
                default -> throw new IllegalArgumentException("Unsupported export format: " + format);
            };
            
        } catch (Exception e) {
            log.error("Failed to export metrics data", e);
            throw new RuntimeException("Metrics data export failed", e);
        }
    }
    
    @Override
    public String generateScheduledReport(String reportId, QueryRequestDTO request, String format) {
        try {
            // Generate unique export ID
            String exportId = "export_" + System.currentTimeMillis() + "_" + reportId;
            
            // Store export request for async processing
            Map<String, Object> exportRequest = new HashMap<>();
            exportRequest.put("request", request);
            exportRequest.put("format", format);
            exportRequest.put("status", "processing");
            exportRequest.put("created_at", Instant.now());
            
            // In a real implementation, this would be stored in Redis or database
            // For now, we'll simulate async processing
            
            return exportId;
            
        } catch (Exception e) {
            log.error("Failed to generate scheduled report", e);
            throw new RuntimeException("Scheduled report generation failed", e);
        }
    }
    
    @Override
    public Map<String, Object> getExportStatus(String exportId) {
        Map<String, Object> status = new HashMap<>();
        status.put("export_id", exportId);
        status.put("status", "completed"); // Simulated status
        status.put("download_url", "/api/v2/query/download/" + exportId);
        status.put("file_size", 1024 * 1024); // Simulated file size
        status.put("expires_at", Instant.now().plus(24, ChronoUnit.HOURS));
        
        return status;
    }
    
    @Override
    public Map<String, Object> queryBusinessMetrics(String clientId, List<String> businessRules, 
                                                   Instant start, Instant end) {
        try {
            Map<String, Object> businessMetrics = new HashMap<>();
            
            for (String rule : businessRules) {
                Object result = evaluateBusinessRule(clientId, rule, start, end);
                businessMetrics.put(rule, result);
            }
            
            return businessMetrics;
            
        } catch (Exception e) {
            log.error("Failed to query business metrics", e);
            throw new RuntimeException("Business metrics query failed", e);
        }
    }
    
    @Override
    public void warmCache(List<String> clientIds, Instant start, Instant end) {
        queryCacheService.warmCache(clientIds, start, end);
    }
    
    @Override
    public void invalidateClientCache(String clientId) {
        queryCacheService.invalidateClientCache(clientId);
    }
    
    @Override
    public Map<String, Object> getCacheStatistics() {
        Map<String, Object> queryStats = getQueryStatistics();
        Map<String, Object> cacheStats = queryCacheService.getCacheStatistics();
        
        // Combine both statistics
        Map<String, Object> combinedStats = new HashMap<>();
        combinedStats.put("query_performance", queryStats);
        combinedStats.put("cache_performance", cacheStats);
        
        return combinedStats;
    }
    
    // Enhanced helper methods for intelligent caching
    
    private long calculateIntelligentCacheTTL(QueryRequestDTO request) {
        long baseTtl = 300; // 5 minutes base
        
        // Adjust TTL based on time range
        long timeRangeHours = ChronoUnit.HOURS.between(request.getStartTime(), request.getEndTime());
        if (timeRangeHours > 24) {
            baseTtl *= 3; // Longer TTL for historical data
        } else if (timeRangeHours < 1) {
            baseTtl /= 2; // Shorter TTL for recent data
        }
        
        // Adjust based on aggregation
        if (request.hasTimeWindow()) {
            baseTtl *= 2; // Aggregated data can be cached longer
        }
        
        // Adjust based on filters complexity
        if (request.hasFilters()) {
            baseTtl = (long) (baseTtl * 1.5); // Complex queries can be cached longer
        }
        
        // Adjust based on data freshness
        long dataAge = ChronoUnit.HOURS.between(request.getEndTime(), Instant.now());
        if (dataAge > 168) { // Data older than 1 week
            baseTtl *= 5; // Much longer TTL for old data
        } else if (dataAge > 24) { // Data older than 1 day
            baseTtl *= 2; // Longer TTL for day-old data
        }
        
        return baseTtl;
    }
    
    /**
     * Enhanced cache warming strategy for frequently accessed data
     */
    private void performIntelligentCacheWarming() {
        try {
            // Warm cache for common time ranges
            Instant now = Instant.now();
            List<String> commonTimeWindows = List.of("1h", "6h", "24h", "7d");
            List<String> commonMetrics = List.of("cpu_usage", "memory_usage", "disk_usage", "network_upload", "network_download");
            
            // Get list of active clients (this would come from a client registry in real implementation)
            List<String> activeClients = getActiveClientIds();
            
            for (String clientId : activeClients.subList(0, Math.min(10, activeClients.size()))) { // Limit to top 10 clients
                for (String timeWindow : commonTimeWindows) {
                    Instant start = now.minus(parseTimeWindow(timeWindow), ChronoUnit.SECONDS);
                    
                    QueryRequestDTO warmupRequest = new QueryRequestDTO();
                    warmupRequest.setClientId(clientId);
                    warmupRequest.setStartTime(start);
                    warmupRequest.setEndTime(now);
                    warmupRequest.setMetricNames(commonMetrics);
                    warmupRequest.setTimeWindow("5m"); // 5-minute aggregation
                    
                    String cacheKey = queryCacheService.generateCacheKey(warmupRequest, null);
                    
                    // Only warm if not already cached
                    if (!queryCacheService.isCached("historical:" + cacheKey)) {
                        log.debug("Warming cache for client: {}, timeWindow: {}", clientId, timeWindow);
                        // This will trigger caching in the background
                        CompletableFuture.runAsync(() -> {
                            try {
                                queryHistoricalMetrics(warmupRequest, org.springframework.data.domain.PageRequest.of(0, 100));
                            } catch (Exception e) {
                                log.warn("Cache warming failed for client: {}", clientId, e);
                            }
                        });
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Intelligent cache warming failed", e);
        }
    }
    
    /**
     * Parse time window string to seconds
     */
    private long parseTimeWindow(String timeWindow) {
        if (timeWindow == null || timeWindow.isEmpty()) {
            return 3600; // Default 1 hour
        }
        
        String unit = timeWindow.substring(timeWindow.length() - 1).toLowerCase();
        String value = timeWindow.substring(0, timeWindow.length() - 1);
        
        try {
            long numericValue = Long.parseLong(value);
            switch (unit) {
                case "s":
                    return numericValue;
                case "m":
                    return numericValue * 60;
                case "h":
                    return numericValue * 3600;
                case "d":
                    return numericValue * 86400;
                case "w":
                    return numericValue * 604800;
                default:
                    return 3600; // Default 1 hour
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid time window format: {}", timeWindow);
            return 3600; // Default 1 hour
        }
    }
    
    /**
     * Get list of active client IDs (mock implementation)
     */
    private List<String> getActiveClientIds() {
        // In a real implementation, this would query the client registry
        // For now, return a mock list
        return List.of("client-001", "client-002", "client-003", "client-004", "client-005");
    }
    
    /**
     * Add InfluxDB query optimization hints
     */
    private String addQueryOptimizationHints(String query, QueryRequestDTO request) {
        StringBuilder optimizedQuery = new StringBuilder(query);
        
        // Add sampling for large time ranges
        long timeRangeHours = ChronoUnit.HOURS.between(request.getStartTime(), request.getEndTime());
        if (timeRangeHours > 168) { // More than 1 week
            optimizedQuery.append("|> sample(n: 10000)"); // Sample to reduce data volume
        } else if (timeRangeHours > 24) { // More than 1 day
            optimizedQuery.append("|> sample(n: 50000)"); // Moderate sampling
        }
        
        // Add limit for safety
        if (request.getLimit() == null || request.getLimit() > 100000) {
            optimizedQuery.append("|> limit(n: 100000)"); // Safety limit
        }
        
        // Add yield for better performance
        optimizedQuery.append("|> yield(name: \"result\")");
        
        return optimizedQuery.toString();
    }
    
    /**
     * Optimize query based on data characteristics
     */
    private String optimizeQueryForDataCharacteristics(String baseQuery, QueryRequestDTO request) {
        StringBuilder optimizedQuery = new StringBuilder(baseQuery);
        
        // Add pushdown predicates for better performance
        if (request.hasFilters()) {
            // Move filters closer to the source for better pushdown
            log.debug("Applying filter pushdown optimization for query");
        }
        
        // Add appropriate aggregation windows based on time range
        long timeRangeHours = ChronoUnit.HOURS.between(request.getStartTime(), request.getEndTime());
        if (timeRangeHours > 720 && !request.hasTimeWindow()) { // More than 30 days
            optimizedQuery.append("|> aggregateWindow(every: 1h, fn: mean, createEmpty: false)");
        } else if (timeRangeHours > 168 && !request.hasTimeWindow()) { // More than 1 week
            optimizedQuery.append("|> aggregateWindow(every: 15m, fn: mean, createEmpty: false)");
        }
        
        return optimizedQuery.toString();
    }
    
    private long calculateAggregationCacheTTL(AggregationRequestDTO request) {
        long baseTtl = 900; // 15 minutes base for aggregations
        
        // Longer TTL for complex aggregations
        if (request.getAggregationFunctions().size() > 3) {
            baseTtl *= 2;
        }
        
        // Longer TTL for historical data
        long timeRangeHours = ChronoUnit.HOURS.between(request.getStartTime(), request.getEndTime());
        if (timeRangeHours > 24) {
            baseTtl *= 3;
        }
        
        return baseTtl;
    }
    
    private String buildAdvancedHistoricalQuery(QueryRequestDTO request, Pageable pageable) {
        StringBuilder query = new StringBuilder();
        query.append(String.format("from(bucket: \"%s\")", bucket));
        query.append(String.format("|> range(start: %s, stop: %s)", 
                                 request.getStartTime(), request.getEndTime()));
        query.append("|> filter(fn: (r) => r[\"_measurement\"] == \"system_metrics\")");
        query.append(String.format("|> filter(fn: (r) => r[\"client_id\"] == \"%s\")", 
                                 request.getClientId()));
        
        if (request.getMetricNames() != null && !request.getMetricNames().isEmpty()) {
            query.append(String.format("|> filter(fn: (r) => contains(value: r[\"_field\"], set: %s))", 
                                     formatMetricNames(request.getMetricNames())));
        }
        
        // Add advanced time window aggregation with interpolation
        if (request.hasTimeWindow()) {
            query.append(String.format("|> aggregateWindow(every: %s, fn: mean, createEmpty: true)", 
                                     request.getTimeWindow()));
            query.append("|> fill(usePrevious: true)"); // Fill missing values
        }
        
        // Add performance optimization hints
        query.append("|> yield(name: \"optimized_result\")");
        
        return query.toString();
    }
    
    private String buildAdvancedFilteredQuery(QueryRequestDTO request) {
        StringBuilder query = new StringBuilder();
        query.append(String.format("from(bucket: \"%s\")", bucket));
        query.append(String.format("|> range(start: %s, stop: %s)", 
                                 request.getStartTime(), request.getEndTime()));
        query.append("|> filter(fn: (r) => r[\"_measurement\"] == \"system_metrics\")");
        query.append(String.format("|> filter(fn: (r) => r[\"client_id\"] == \"%s\")", 
                                 request.getClientId()));
        
        if (request.getMetricNames() != null && !request.getMetricNames().isEmpty()) {
            query.append(String.format("|> filter(fn: (r) => contains(value: r[\"_field\"], set: %s))", 
                                     formatMetricNames(request.getMetricNames())));
        }
        
        // Add advanced custom filters with mathematical operations
        if (request.hasFilters()) {
            for (Map.Entry<String, Object> filter : request.getFilters().entrySet()) {
                String key = filter.getKey();
                Object value = filter.getValue();
                
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> complexFilter = (Map<String, Object>) value;
                    
                    if (complexFilter.containsKey("operator")) {
                        String operator = (String) complexFilter.get("operator");
                        Object filterValue = complexFilter.get("value");
                        
                        query.append(String.format("|> filter(fn: (r) => r[\"%s\"] %s %s)", 
                                                 key, operator, filterValue));
                    }
                } else if (value instanceof Number) {
                    query.append(String.format("|> filter(fn: (r) => r[\"%s\"] == %s)", key, value));
                } else {
                    query.append(String.format("|> filter(fn: (r) => r[\"%s\"] == \"%s\")", key, value));
                }
            }
        }
        
        // Add advanced aggregation with statistical functions
        if (request.hasTimeWindow()) {
            query.append(String.format("|> aggregateWindow(every: %s, fn: mean, createEmpty: false)", 
                                     request.getTimeWindow()));
        }
        
        // Add advanced grouping with pivot operations
        if (request.hasGroupBy()) {
            query.append(String.format("|> group(columns: %s)", 
                                     formatStringList(request.getGroupBy())));
        }
        
        return query.toString();
    }
    
    private String buildPivotQuery(QueryRequestDTO request) {
        StringBuilder query = new StringBuilder();
        query.append(String.format("from(bucket: \"%s\")", bucket));
        query.append(String.format("|> range(start: %s, stop: %s)", 
                                 request.getStartTime(), request.getEndTime()));
        query.append("|> filter(fn: (r) => r[\"_measurement\"] == \"system_metrics\")");
        query.append(String.format("|> filter(fn: (r) => r[\"client_id\"] == \"%s\")", 
                                 request.getClientId()));
        
        if (request.hasGroupBy()) {
            String pivotColumn = request.getGroupBy().get(0); // Use first group by as pivot
            query.append(String.format("|> pivot(rowKey: [\"_time\"], columnKey: [\"%s\"], valueColumn: \"_value\")", 
                                     pivotColumn));
        }
        
        return query.toString();
    }
    
    private String buildOptimizedQuery(QueryRequestDTO request, Map<String, Object> optimizationHints) {
        StringBuilder query = new StringBuilder();
        
        // Apply optimization hints
        if (optimizationHints.containsKey("use_sampling") && (Boolean) optimizationHints.get("use_sampling")) {
            query.append(String.format("from(bucket: \"%s\")", bucket));
            query.append(String.format("|> range(start: %s, stop: %s)", 
                                     request.getStartTime(), request.getEndTime()));
            query.append("|> filter(fn: (r) => r[\"_measurement\"] == \"system_metrics\")");
            query.append(String.format("|> filter(fn: (r) => r[\"client_id\"] == \"%s\")", 
                                     request.getClientId()));
            query.append("|> sample(n: 1000)"); // Sample for performance
        } else {
            query.append(buildFilteredQuery(request));
        }
        
        return query.toString();
    }
    
    private List<QueryResultDTO> processAdvancedQueryResults(List<FluxTable> tables, QueryRequestDTO request) {
        Map<Instant, QueryResultDTO> resultMap = new HashMap<>();
        
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Instant timestamp = record.getTime();
                String clientId = (String) record.getValueByKey("client_id");
                String field = record.getField();
                Object value = record.getValue();
                
                QueryResultDTO result = resultMap.computeIfAbsent(timestamp, t -> {
                    QueryResultDTO dto = new QueryResultDTO();
                    dto.setTimestamp(t);
                    dto.setClientId(clientId);
                    return dto;
                });
                
                result.addMetric(field, value);
                
                // Add enhanced metadata
                if (request != null && request.getIncludeMetadata()) {
                    result.addMetadata("measurement", record.getMeasurement());
                    result.addMetadata("table_columns", table.getColumns().size());
                    result.addMetadata("query_time", System.currentTimeMillis());
                }
                
                // Add quality metrics if requested
                if (request != null && request.getIncludeQualityMetrics()) {
                    result.addQualityMetric("data_freshness", calculateDataFreshness(timestamp));
                    result.addQualityMetric("completeness", calculateCompleteness(record));
                }
            }
        }
        
        return new ArrayList<>(resultMap.values());
    }
    
    private String generateAdvancedCacheKey(String type, String query, Pageable pageable) {
        StringBuilder keyBuilder = new StringBuilder(type);
        keyBuilder.append(":").append(Integer.toHexString(query.hashCode()));
        
        if (pageable != null) {
            keyBuilder.append(":page:").append(pageable.getPageNumber());
            keyBuilder.append(":size:").append(pageable.getPageSize());
            keyBuilder.append(":sort:").append(pageable.getSort().toString().hashCode());
        }
        
        return keyBuilder.toString();
    }
    
    private long calculateOptimalCacheTTL(QueryRequestDTO request) {
        // Calculate TTL based on data freshness and query complexity
        long baseTime = 300_000; // 5 minutes base
        
        if (request.hasTimeWindow()) {
            // Longer TTL for aggregated data
            return baseTime * 2;
        }
        
        if (request.hasFilters()) {
            // Shorter TTL for filtered data
            return baseTime / 2;
        }
        
        return baseTime;
    }
    
    private Double extractCorrelationValue(List<FluxTable> tables) {
        if (tables.isEmpty()) return null;
        
        FluxTable table = tables.get(0);
        if (table.getRecords().isEmpty()) return null;
        
        FluxRecord record = table.getRecords().get(0);
        Object correlation = record.getValueByKey("r");
        
        if (correlation instanceof Number) {
            return ((Number) correlation).doubleValue();
        }
        
        return null;
    }
    
    private byte[] exportToCsv(List<QueryResultDTO> data) {
        StringBuilder csv = new StringBuilder();
        
        // Header
        csv.append("timestamp,client_id");
        if (!data.isEmpty() && data.get(0).getMetrics() != null) {
            for (String metricName : data.get(0).getMetrics().keySet()) {
                csv.append(",").append(metricName);
            }
        }
        csv.append("\n");
        
        // Data rows
        for (QueryResultDTO result : data) {
            csv.append(result.getTimestamp()).append(",").append(result.getClientId());
            if (result.getMetrics() != null) {
                for (Object value : result.getMetrics().values()) {
                    csv.append(",").append(value != null ? value.toString() : "");
                }
            }
            csv.append("\n");
        }
        
        return csv.toString().getBytes();
    }
    
    private byte[] exportToJson(List<QueryResultDTO> data) {
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (Exception e) {
            throw new RuntimeException("JSON export failed", e);
        }
    }
    
    private byte[] exportToExcel(List<QueryResultDTO> data) {
        // Simplified Excel export - in real implementation, use Apache POI
        return exportToCsv(data); // Fallback to CSV for now
    }
    
    private Object evaluateBusinessRule(String clientId, String rule, Instant start, Instant end) {
        // Simplified business rule evaluation
        return switch (rule) {
            case "availability_sla" -> calculateAvailabilitySLA(clientId, start, end);
            case "performance_score" -> calculatePerformanceScore(clientId, start, end);
            case "resource_efficiency" -> calculateResourceEfficiency(clientId, start, end);
            default -> "Unknown rule: " + rule;
        };
    }
    
    private Double calculateAvailabilitySLA(String clientId, Instant start, Instant end) {
        // Calculate uptime percentage
        try {
            String query = String.format("""
                from(bucket: "%s")
                |> range(start: %s, stop: %s)
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["client_id"] == "%s")
                |> filter(fn: (r) => r["_field"] == "cpu_usage")
                |> map(fn: (r) => ({
                    _time: r._time,
                    _value: if r._value > 0.0 then 1.0 else 0.0
                }))
                |> mean()
                """, bucket, start, end, clientId);
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            Double uptime = extractAggregationValue(tables);
            
            return uptime != null ? uptime * 100.0 : 0.0; // Convert to percentage
            
        } catch (Exception e) {
            log.error("Failed to calculate availability SLA", e);
            return 0.0;
        }
    }
    
    private Double calculatePerformanceScore(String clientId, Instant start, Instant end) {
        // Calculate composite performance score
        try {
            Map<String, Map<String, Double>> stats = queryMetricsStatistics(
                clientId, List.of("cpu_usage", "memory_usage", "disk_usage"), start, end);
            
            double cpuScore = 100.0 - (stats.getOrDefault("cpu_usage", Map.of("mean", 0.0)).getOrDefault("mean", 0.0));
            double memoryScore = 100.0 - (stats.getOrDefault("memory_usage", Map.of("mean", 0.0)).getOrDefault("mean", 0.0));
            double diskScore = 100.0 - (stats.getOrDefault("disk_usage", Map.of("mean", 0.0)).getOrDefault("mean", 0.0));
            
            return (cpuScore + memoryScore + diskScore) / 3.0;
            
        } catch (Exception e) {
            log.error("Failed to calculate performance score", e);
            return 0.0;
        }
    }
    
    private Double calculateResourceEfficiency(String clientId, Instant start, Instant end) {
        // Calculate resource utilization efficiency
        try {
            Map<String, Map<String, Double>> stats = queryMetricsStatistics(
                clientId, List.of("cpu_usage", "memory_usage"), start, end);
            
            double cpuEfficiency = stats.getOrDefault("cpu_usage", Map.of("mean", 0.0)).getOrDefault("mean", 0.0);
            double memoryEfficiency = stats.getOrDefault("memory_usage", Map.of("mean", 0.0)).getOrDefault("mean", 0.0);
            
            // Efficiency is optimal around 70-80% utilization
            double optimalCpu = Math.abs(75.0 - cpuEfficiency);
            double optimalMemory = Math.abs(75.0 - memoryEfficiency);
            
            return 100.0 - ((optimalCpu + optimalMemory) / 2.0);
            
        } catch (Exception e) {
            log.error("Failed to calculate resource efficiency", e);
            return 0.0;
        }
    }
    
    private double calculateDataFreshness(Instant timestamp) {
        long ageMinutes = ChronoUnit.MINUTES.between(timestamp, Instant.now());
        return Math.max(0.0, 100.0 - (ageMinutes / 60.0)); // Freshness decreases over time
    }
    
    private double calculateCompleteness(FluxRecord record) {
        // Calculate data completeness based on non-null fields
        int totalFields = 10; // Expected number of fields
        int nonNullFields = 0;
        
        if (record.getValue() != null) nonNullFields++;
        if (record.getTime() != null) nonNullFields++;
        if (record.getField() != null) nonNullFields++;
        if (record.getMeasurement() != null) nonNullFields++;
        
        return (double) nonNullFields / totalFields * 100.0;
    }
    
    /**
     * Cached query result wrapper with TTL support
     */
    public static class CachedQueryResult {
        private final Object data;
        private final long timestamp;
        private final long ttl;
        
        public CachedQueryResult(Object data, long timestamp) {
            this(data, timestamp, 300_000); // Default 5 minutes TTL
        }
        
        public CachedQueryResult(Object data, long timestamp, long ttl) {
            this.data = data;
            this.timestamp = timestamp;
            this.ttl = ttl;
        }
        
        public Object getData() {
            return data;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
        
        public long getTtl() {
            return ttl;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > ttl;
        }
    }
}