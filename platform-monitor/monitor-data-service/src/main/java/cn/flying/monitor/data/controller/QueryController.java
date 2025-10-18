package cn.flying.monitor.data.controller;

import cn.flying.monitor.data.dto.*;
import cn.flying.monitor.data.service.QueryService;
import cn.flying.monitor.common.entity.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Advanced query controller for monitoring data
 * Provides optimized data retrieval, aggregation, and analytics endpoints
 */
@RestController
@RequestMapping("/api/v2/query")
@Tag(name = "Query Service", description = "Advanced monitoring data query and analytics APIs")
@Slf4j
public class QueryController {
    
    @Autowired
    private QueryService queryService;
    
    @PostMapping("/historical")
    @Operation(summary = "Query historical metrics with pagination", 
               description = "Retrieve historical monitoring data with time-range filtering and pagination support")
    public ResponseEntity<Result<Page<QueryResultDTO>>> queryHistoricalMetrics(
            @Valid @RequestBody QueryRequestDTO request,
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "100") int size,
            @Parameter(description = "Sort field") @RequestParam(required = false) String sortBy,
            @Parameter(description = "Sort direction") @RequestParam(defaultValue = "desc") String sortDir) {
        
        try {
            // Validate request
            if (!request.isValidTimeRange()) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Invalid time range: start time must be before end time"));
            }
            
            // Create pageable
            Sort sort = Sort.unsorted();
            if (sortBy != null && !sortBy.isBlank()) {
                Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? 
                    Sort.Direction.DESC : Sort.Direction.ASC;
                sort = Sort.by(direction, sortBy);
            }
            
            Pageable pageable = PageRequest.of(page, size, sort);
            
            // Execute query
            Page<QueryResultDTO> results = queryService.queryHistoricalMetrics(request, pageable);
            
            log.info("Historical metrics query completed for client: {}, page: {}, size: {}, total: {}", 
                    request.getClientId(), page, size, results.getTotalElements());
            
            return ResponseEntity.ok(Result.success(results));
            
        } catch (Exception e) {
            log.error("Failed to query historical metrics", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query historical metrics: " + e.getMessage()));
        }
    }
    
    @GetMapping("/realtime/{clientId}")
    @Operation(summary = "Query real-time metrics", 
               description = "Retrieve the latest monitoring data for a specific client")
    public ResponseEntity<Result<QueryResultDTO>> queryRealTimeMetrics(
            @Parameter(description = "Client ID") @PathVariable String clientId) {
        
        try {
            QueryResultDTO result = queryService.queryRealTimeMetrics(clientId);
            
            if (result == null) {
                return ResponseEntity.ok(Result.success(null, "No recent data available"));
            }
            
            log.debug("Real-time metrics retrieved for client: {}", clientId);
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to query real-time metrics for client: {}", clientId, e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query real-time metrics: " + e.getMessage()));
        }
    }
    
    @PostMapping("/filtered")
    @Operation(summary = "Query metrics with custom filters", 
               description = "Retrieve monitoring data with custom filtering and grouping options")
    public ResponseEntity<Result<List<QueryResultDTO>>> queryMetricsWithFilters(
            @Valid @RequestBody QueryRequestDTO request) {
        
        try {
            // Validate request
            if (!request.isValidTimeRange()) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Invalid time range: start time must be before end time"));
            }
            
            List<QueryResultDTO> results = queryService.queryMetricsWithFilters(request);
            
            log.info("Filtered metrics query completed for client: {}, results: {}", 
                    request.getClientId(), results.size());
            
            return ResponseEntity.ok(Result.success(results));
            
        } catch (Exception e) {
            log.error("Failed to query metrics with filters", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query filtered metrics: " + e.getMessage()));
        }
    }
    
    @PostMapping("/aggregate")
    @Operation(summary = "Aggregate metrics data", 
               description = "Calculate aggregations like averages, percentiles, and trends for monitoring data")
    public ResponseEntity<Result<AggregationResultDTO>> aggregateMetrics(
            @Valid @RequestBody AggregationRequestDTO request) {
        
        try {
            // Validate request
            if (!request.isValidTimeRange()) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Invalid time range: start time must be before end time"));
            }
            
            if (!request.areValidAggregationFunctions()) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Invalid aggregation functions. Supported: avg, sum, min, max, count, percentile, stddev, median"));
            }
            
            AggregationResultDTO result = queryService.aggregateMetrics(request);
            
            log.info("Metrics aggregation completed for client: {}, functions: {}", 
                    request.getClientId(), request.getAggregationFunctions());
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to aggregate metrics", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to aggregate metrics: " + e.getMessage()));
        }
    }
    
    @GetMapping("/percentiles/{clientId}/{metricName}")
    @Operation(summary = "Calculate percentiles", 
               description = "Calculate percentile values for a specific metric")
    public ResponseEntity<Result<Map<String, Double>>> calculatePercentiles(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Metric name") @PathVariable String metricName,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @Parameter(description = "Percentiles to calculate") @RequestParam List<Double> percentiles) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            Map<String, Double> result = queryService.calculatePercentiles(clientId, metricName, start, end, percentiles);
            
            log.info("Percentiles calculated for client: {}, metric: {}, percentiles: {}", 
                    clientId, metricName, percentiles);
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to calculate percentiles", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to calculate percentiles: " + e.getMessage()));
        }
    }
    
    @GetMapping("/trends/{clientId}")
    @Operation(summary = "Calculate trend analysis", 
               description = "Analyze trends for specified metrics over time")
    public ResponseEntity<Result<Map<String, Object>>> calculateTrends(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Metric names") @RequestParam List<String> metricNames,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            Map<String, Object> result = queryService.calculateTrends(clientId, metricNames, start, end);
            
            log.info("Trends calculated for client: {}, metrics: {}", clientId, metricNames);
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to calculate trends", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to calculate trends: " + e.getMessage()));
        }
    }
    
    @GetMapping("/top-clients/{metricName}")
    @Operation(summary = "Query top clients by metric", 
               description = "Find top performing clients for a specific metric")
    public ResponseEntity<Result<List<Map<String, Object>>>> queryTopClientsByMetric(
            @Parameter(description = "Metric name") @PathVariable String metricName,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @Parameter(description = "Number of top clients") @RequestParam(defaultValue = "10") int limit) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            List<Map<String, Object>> result = queryService.queryTopClientsByMetric(metricName, start, end, limit);
            
            log.info("Top clients query completed for metric: {}, limit: {}, results: {}", 
                    metricName, limit, result.size());
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to query top clients", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query top clients: " + e.getMessage()));
        }
    }
    
    @PostMapping("/compare")
    @Operation(summary = "Compare metrics between clients", 
               description = "Compare monitoring data between multiple clients")
    public ResponseEntity<Result<Map<String, List<QueryResultDTO>>>> compareClientsMetrics(
            @Parameter(description = "Client IDs to compare") @RequestParam List<String> clientIds,
            @Parameter(description = "Metric names") @RequestParam List<String> metricNames,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            Map<String, List<QueryResultDTO>> result = queryService.compareClientsMetrics(clientIds, metricNames, start, end);
            
            log.info("Clients comparison completed for clients: {}, metrics: {}", clientIds, metricNames);
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to compare clients metrics", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to compare clients metrics: " + e.getMessage()));
        }
    }
    
    @GetMapping("/time-window/{clientId}")
    @Operation(summary = "Query metrics with time window aggregation", 
               description = "Retrieve metrics aggregated over specified time windows")
    public ResponseEntity<Result<List<QueryResultDTO>>> queryMetricsWithTimeWindow(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Metric names") @RequestParam List<String> metricNames,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @Parameter(description = "Time window (e.g., 1m, 5m, 1h)") @RequestParam String timeWindow) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            List<QueryResultDTO> result = queryService.queryMetricsWithTimeWindow(clientId, metricNames, start, end, timeWindow);
            
            log.info("Time window query completed for client: {}, window: {}, results: {}", 
                    clientId, timeWindow, result.size());
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to query metrics with time window", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query metrics with time window: " + e.getMessage()));
        }
    }
    
    @GetMapping("/statistics/{clientId}")
    @Operation(summary = "Query metrics statistics", 
               description = "Calculate statistical measures (min, max, avg, std dev) for metrics")
    public ResponseEntity<Result<Map<String, Map<String, Double>>>> queryMetricsStatistics(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Metric names") @RequestParam List<String> metricNames,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            Map<String, Map<String, Double>> result = queryService.queryMetricsStatistics(clientId, metricNames, start, end);
            
            log.info("Statistics calculated for client: {}, metrics: {}", clientId, metricNames);
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to query metrics statistics", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query metrics statistics: " + e.getMessage()));
        }
    }
    
    @GetMapping("/anomalies/{clientId}")
    @Operation(summary = "Query anomaly detection results", 
               description = "Detect anomalies in monitoring data using statistical analysis")
    public ResponseEntity<Result<List<Map<String, Object>>>> queryAnomalies(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Metric names") @RequestParam List<String> metricNames,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @Parameter(description = "Anomaly threshold (standard deviations)") @RequestParam(defaultValue = "2.0") double threshold) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            List<Map<String, Object>> result = queryService.queryAnomalies(clientId, metricNames, start, end, threshold);
            
            log.info("Anomalies detected for client: {}, metrics: {}, threshold: {}, anomalies: {}", 
                    clientId, metricNames, threshold, result.size());
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to query anomalies", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query anomalies: " + e.getMessage()));
        }
    }
    
    @GetMapping("/data-quality/{clientId}")
    @Operation(summary = "Query data quality metrics", 
               description = "Assess data availability and quality for monitoring data")
    public ResponseEntity<Result<Map<String, Object>>> queryDataQuality(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            Map<String, Object> result = queryService.queryDataQuality(clientId, start, end);
            
            log.info("Data quality assessed for client: {}", clientId);
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to query data quality", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query data quality: " + e.getMessage()));
        }
    }
    
    @PostMapping("/optimize")
    @Operation(summary = "Optimize query performance", 
               description = "Trigger query performance optimization")
    public ResponseEntity<Result<String>> optimizeQueryPerformance() {
        
        try {
            queryService.optimizeQueryPerformance();
            
            log.info("Query performance optimization triggered");
            
            return ResponseEntity.ok(Result.success("Query performance optimization completed"));
            
        } catch (Exception e) {
            log.error("Failed to optimize query performance", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to optimize query performance: " + e.getMessage()));
        }
    }
    
    @GetMapping("/statistics")
    @Operation(summary = "Get query performance statistics", 
               description = "Retrieve query service performance metrics and statistics")
    public ResponseEntity<Result<Map<String, Object>>> getQueryStatistics() {
        
        try {
            Map<String, Object> result = queryService.getQueryStatistics();
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to get query statistics", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to get query statistics: " + e.getMessage()));
        }
    }
    
    @PostMapping("/historical/advanced")
    @Operation(summary = "Query historical metrics with advanced capabilities", 
               description = "Enhanced historical data retrieval with optimization and advanced filtering")
    public ResponseEntity<Result<Page<QueryResultDTO>>> queryHistoricalMetricsAdvanced(
            @Valid @RequestBody QueryRequestDTO request,
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "100") int size,
            @Parameter(description = "Sort field") @RequestParam(required = false) String sortBy,
            @Parameter(description = "Sort direction") @RequestParam(defaultValue = "desc") String sortDir) {
        
        try {
            if (!request.isValidTimeRange()) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Invalid time range: start time must be before end time"));
            }
            
            Sort sort = Sort.unsorted();
            if (sortBy != null && !sortBy.isBlank()) {
                Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? 
                    Sort.Direction.DESC : Sort.Direction.ASC;
                sort = Sort.by(direction, sortBy);
            }
            
            Pageable pageable = PageRequest.of(page, size, sort);
            Page<QueryResultDTO> results = queryService.queryHistoricalMetricsAdvanced(request, pageable);
            
            log.info("Advanced historical metrics query completed for client: {}, page: {}, size: {}, total: {}", 
                    request.getClientId(), page, size, results.getTotalElements());
            
            return ResponseEntity.ok(Result.success(results));
            
        } catch (Exception e) {
            log.error("Failed to query advanced historical metrics", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query advanced historical metrics: " + e.getMessage()));
        }
    }
    
    @GetMapping("/correlation/{clientId}")
    @Operation(summary = "Calculate metrics correlation", 
               description = "Calculate correlation coefficients between different metrics")
    public ResponseEntity<Result<Map<String, Double>>> queryMetricsCorrelation(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Metric names") @RequestParam List<String> metricNames,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            Map<String, Double> result = queryService.queryMetricsCorrelation(clientId, metricNames, start, end);
            
            log.info("Metrics correlation calculated for client: {}, metrics: {}", clientId, metricNames);
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to calculate metrics correlation", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to calculate metrics correlation: " + e.getMessage()));
        }
    }
    
    @GetMapping("/forecast/{clientId}/{metricName}")
    @Operation(summary = "Generate metrics forecast", 
               description = "Generate predictive forecasts for specific metrics using statistical models")
    public ResponseEntity<Result<Map<String, Object>>> queryMetricsForecast(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Metric name") @PathVariable String metricName,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @Parameter(description = "Number of forecast periods") @RequestParam(defaultValue = "24") int forecastPeriods) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            Map<String, Object> result = queryService.queryMetricsForecast(clientId, metricName, start, end, forecastPeriods);
            
            log.info("Metrics forecast generated for client: {}, metric: {}, periods: {}", 
                    clientId, metricName, forecastPeriods);
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to generate metrics forecast", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to generate metrics forecast: " + e.getMessage()));
        }
    }
    
    @PostMapping("/advanced-filters")
    @Operation(summary = "Query metrics with advanced filters", 
               description = "Advanced filtering with mathematical operations and complex conditions")
    public ResponseEntity<Result<List<QueryResultDTO>>> queryMetricsWithAdvancedFilters(
            @Valid @RequestBody QueryRequestDTO request) {
        
        try {
            if (!request.isValidTimeRange()) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Invalid time range: start time must be before end time"));
            }
            
            List<QueryResultDTO> results = queryService.queryMetricsWithAdvancedFilters(request);
            
            log.info("Advanced filtered metrics query completed for client: {}, results: {}", 
                    request.getClientId(), results.size());
            
            return ResponseEntity.ok(Result.success(results));
            
        } catch (Exception e) {
            log.error("Failed to query metrics with advanced filters", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query metrics with advanced filters: " + e.getMessage()));
        }
    }
    
    @PostMapping("/pivot")
    @Operation(summary = "Query metrics with pivot operations", 
               description = "Multi-dimensional data analysis with pivot tables and grouping")
    public ResponseEntity<Result<Map<String, Map<String, Object>>>> queryMetricsWithPivot(
            @Valid @RequestBody QueryRequestDTO request) {
        
        try {
            if (!request.isValidTimeRange()) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Invalid time range: start time must be before end time"));
            }
            
            Map<String, Map<String, Object>> results = queryService.queryMetricsWithPivot(request);
            
            log.info("Pivot metrics query completed for client: {}, pivot groups: {}", 
                    request.getClientId(), results.size());
            
            return ResponseEntity.ok(Result.success(results));
            
        } catch (Exception e) {
            log.error("Failed to query metrics with pivot", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query metrics with pivot: " + e.getMessage()));
        }
    }
    
    @PostMapping("/export")
    @Operation(summary = "Export metrics data", 
               description = "Export monitoring data in various formats (CSV, JSON, Excel)")
    public ResponseEntity<byte[]> exportMetricsData(
            @Valid @RequestBody QueryRequestDTO request,
            @Parameter(description = "Export format (csv, json, excel)") @RequestParam String format) {
        
        try {
            if (!request.isValidTimeRange()) {
                return ResponseEntity.badRequest().build();
            }
            
            byte[] data = queryService.exportMetricsData(request, format);
            
            String filename = String.format("metrics_%s_%s.%s", 
                    request.getClientId(), 
                    System.currentTimeMillis(), 
                    format.toLowerCase());
            
            String contentType = switch (format.toLowerCase()) {
                case "csv" -> "text/csv";
                case "json" -> "application/json";
                case "excel" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                default -> "application/octet-stream";
            };
            
            log.info("Metrics data exported for client: {}, format: {}, size: {} bytes", 
                    request.getClientId(), format, data.length);
            
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + filename)
                .header("Content-Type", contentType)
                .body(data);
            
        } catch (Exception e) {
            log.error("Failed to export metrics data", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    @PostMapping("/reports/schedule")
    @Operation(summary = "Schedule report generation", 
               description = "Generate scheduled reports for monitoring data")
    public ResponseEntity<Result<Map<String, String>>> generateScheduledReport(
            @Parameter(description = "Report ID") @RequestParam String reportId,
            @Valid @RequestBody QueryRequestDTO request,
            @Parameter(description = "Report format") @RequestParam String format) {
        
        try {
            if (!request.isValidTimeRange()) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Invalid time range: start time must be before end time"));
            }
            
            String exportId = queryService.generateScheduledReport(reportId, request, format);
            
            Map<String, String> response = new HashMap<>();
            response.put("export_id", exportId);
            response.put("status", "processing");
            response.put("status_url", "/api/v2/query/reports/status/" + exportId);
            
            log.info("Scheduled report generation started: reportId={}, exportId={}", reportId, exportId);
            
            return ResponseEntity.ok(Result.success(response));
            
        } catch (Exception e) {
            log.error("Failed to generate scheduled report", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to generate scheduled report: " + e.getMessage()));
        }
    }
    
    @GetMapping("/reports/status/{exportId}")
    @Operation(summary = "Get export status", 
               description = "Check the status of a scheduled report export")
    public ResponseEntity<Result<Map<String, Object>>> getExportStatus(
            @Parameter(description = "Export ID") @PathVariable String exportId) {
        
        try {
            Map<String, Object> status = queryService.getExportStatus(exportId);
            
            return ResponseEntity.ok(Result.success(status));
            
        } catch (Exception e) {
            log.error("Failed to get export status", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to get export status: " + e.getMessage()));
        }
    }
    
    @GetMapping("/business-metrics/{clientId}")
    @Operation(summary = "Query business metrics", 
               description = "Calculate business-specific metrics and KPIs")
    public ResponseEntity<Result<Map<String, Object>>> queryBusinessMetrics(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @Parameter(description = "Business rules") @RequestParam List<String> businessRules,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            Map<String, Object> result = queryService.queryBusinessMetrics(clientId, businessRules, start, end);
            
            log.info("Business metrics calculated for client: {}, rules: {}", clientId, businessRules);
            
            return ResponseEntity.ok(Result.success(result));
            
        } catch (Exception e) {
            log.error("Failed to query business metrics", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to query business metrics: " + e.getMessage()));
        }
    }
    
    @PostMapping("/cache/warm")
    @Operation(summary = "Warm cache for clients", 
               description = "Preload cache with frequently accessed data for specified clients")
    public ResponseEntity<Result<String>> warmCache(
            @Parameter(description = "Client IDs") @RequestParam List<String> clientIds,
            @Parameter(description = "Start time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "End time") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        
        try {
            if (start.isAfter(end)) {
                return ResponseEntity.badRequest()
                    .body(Result.error("Start time must be before end time"));
            }
            
            queryService.warmCache(clientIds, start, end);
            
            log.info("Cache warming initiated for {} clients", clientIds.size());
            
            return ResponseEntity.ok(Result.success("Cache warming initiated successfully"));
            
        } catch (Exception e) {
            log.error("Failed to warm cache", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to warm cache: " + e.getMessage()));
        }
    }
    
    @DeleteMapping("/cache/invalidate/{clientId}")
    @Operation(summary = "Invalidate cache for client", 
               description = "Clear all cached data for a specific client")
    public ResponseEntity<Result<String>> invalidateClientCache(
            @Parameter(description = "Client ID") @PathVariable String clientId) {
        
        try {
            queryService.invalidateClientCache(clientId);
            
            log.info("Cache invalidated for client: {}", clientId);
            
            return ResponseEntity.ok(Result.success("Cache invalidated successfully"));
            
        } catch (Exception e) {
            log.error("Failed to invalidate cache for client: {}", clientId, e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to invalidate cache: " + e.getMessage()));
        }
    }
    
    @GetMapping("/cache/statistics")
    @Operation(summary = "Get cache statistics", 
               description = "Retrieve cache performance metrics and hit rates")
    public ResponseEntity<Result<Map<String, Object>>> getCacheStatistics() {
        
        try {
            Map<String, Object> statistics = queryService.getCacheStatistics();
            
            return ResponseEntity.ok(Result.success(statistics));
            
        } catch (Exception e) {
            log.error("Failed to get cache statistics", e);
            return ResponseEntity.internalServerError()
                .body(Result.error("Failed to get cache statistics: " + e.getMessage()));
        }
    }
}