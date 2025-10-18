package cn.flying.monitor.data.service;

import cn.flying.monitor.data.dto.BatchMetricsDTO;
import cn.flying.monitor.data.dto.MetricsDataDTO;
import cn.flying.monitor.data.validation.BatchValidationResult;
import cn.flying.monitor.data.validation.MetricsValidator;
import cn.flying.monitor.data.validation.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main data ingestion service that orchestrates metrics processing
 * Handles validation, normalization, compression, and storage
 */
@Service
@Slf4j
public class DataIngestionService {
    
    private final MetricsValidator metricsValidator;
    private final MetricsNormalizationService normalizationService;
    private final MetricsCompressionService compressionService;
    private final InfluxDBService influxDBService;
    private final EventStreamingService eventStreamingService;
    
    // Performance metrics
    private final AtomicLong totalMetricsProcessed = new AtomicLong(0);
    private final AtomicLong totalBatchesProcessed = new AtomicLong(0);
    private final AtomicLong totalValidationErrors = new AtomicLong(0);
    private final AtomicLong totalCompressionSavings = new AtomicLong(0);
    
    public DataIngestionService(MetricsValidator metricsValidator,
                               MetricsNormalizationService normalizationService,
                               MetricsCompressionService compressionService,
                               InfluxDBService influxDBService,
                               EventStreamingService eventStreamingService) {
        this.metricsValidator = metricsValidator;
        this.normalizationService = normalizationService;
        this.compressionService = compressionService;
        this.influxDBService = influxDBService;
        this.eventStreamingService = eventStreamingService;
    }
    
    /**
     * Processes individual metrics data
     */
    public Map<String, Object> processMetrics(MetricsDataDTO metrics, boolean compress, boolean skipValidation) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Step 1: Validation (if not skipped)
            ValidationResult validationResult = null;
            if (!skipValidation) {
                validationResult = metricsValidator.validateMetrics(metrics);
                if (!validationResult.isValid()) {
                    totalValidationErrors.incrementAndGet();
                    result.put("validation_result", validationResult);
                    result.put("processed", false);
                    result.put("error", "Validation failed");
                    return result;
                }
            }
            
            // Step 2: Normalization
            MetricsDataDTO normalizedMetrics = normalizationService.normalizeMetrics(metrics);
            
            // Step 3: Transform for analytics
            MetricsDataDTO transformedMetrics = normalizationService.transformForAnalytics(normalizedMetrics);
            
            // Step 4: Compression (if enabled)
            MetricsCompressionService.OptimizedMetricsData optimizedData = null;
            if (compress) {
                optimizedData = compressionService.optimizeForTransmission(transformedMetrics);
                totalCompressionSavings.addAndGet(optimizedData.getSavedBytes());
            }
            
            // Step 5: Store in time-series database
            influxDBService.writeMetrics(transformedMetrics);
            
            // Step 6: Publish real-time events for WebSocket streaming and alerts
            eventStreamingService.publishMetricsEvent(transformedMetrics);
            
            // Update counters
            totalMetricsProcessed.incrementAndGet();
            
            // Build response
            result.put("processed", true);
            result.put("client_id", metrics.getClientId());
            result.put("timestamp", metrics.getTimestamp());
            result.put("processing_time_ms", System.currentTimeMillis() - startTime);
            
            if (validationResult != null) {
                result.put("validation_result", validationResult);
            }
            
            if (optimizedData != null) {
                Map<String, Object> compressionInfo = new HashMap<>();
                compressionInfo.put("compressed", optimizedData.isCompressed());
                compressionInfo.put("compression_ratio", optimizedData.getCompressionRatio());
                compressionInfo.put("original_size", optimizedData.getOriginalSize());
                compressionInfo.put("compressed_size", optimizedData.getCompressedSize());
                compressionInfo.put("saved_bytes", optimizedData.getSavedBytes());
                compressionInfo.put("saved_percentage", optimizedData.getSavedPercentage());
                result.put("compression", compressionInfo);
            }
            
            log.debug("Successfully processed metrics for client: {} in {}ms", 
                     metrics.getClientId(), System.currentTimeMillis() - startTime);
            
        } catch (Exception e) {
            log.error("Failed to process metrics for client: {}", metrics.getClientId(), e);
            result.put("processed", false);
            result.put("error", e.getMessage());
            result.put("processing_time_ms", System.currentTimeMillis() - startTime);
        }
        
        return result;
    }
    
    /**
     * Processes batch metrics data for high throughput
     */
    public Map<String, Object> processBatchMetrics(BatchMetricsDTO batchMetrics, 
                                                  boolean compress, 
                                                  boolean continueOnError) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        
        try {
            List<MetricsDataDTO> metricsList = batchMetrics.getMetrics();
            
            // Step 1: Batch validation
            BatchValidationResult batchValidation = metricsValidator.validateBatch(metricsList);
            
            if (!batchValidation.isValid() && !continueOnError) {
                totalValidationErrors.addAndGet(batchValidation.getInvalidMetrics());
                result.put("processed", false);
                result.put("batch_validation", batchValidation);
                result.put("error", "Batch validation failed");
                return result;
            }
            
            // Step 2: Process valid metrics
            int processedCount = 0;
            int errorCount = 0;
            long totalCompressionSaved = 0;
            
            for (int i = 0; i < metricsList.size(); i++) {
                MetricsDataDTO metrics = metricsList.get(i);
                ValidationResult individualValidation = batchValidation.getIndividualResults().get(i);
                
                if (individualValidation.isValid() || continueOnError) {
                    try {
                        // Normalize and transform
                        MetricsDataDTO normalizedMetrics = normalizationService.normalizeMetrics(metrics);
                        MetricsDataDTO transformedMetrics = normalizationService.transformForAnalytics(normalizedMetrics);
                        
                        // Handle compression
                        if (compress) {
                            MetricsCompressionService.OptimizedMetricsData optimizedData = 
                                compressionService.optimizeForTransmission(transformedMetrics);
                            totalCompressionSaved += optimizedData.getSavedBytes();
                        }
                        
                        // Store in database
                        influxDBService.writeMetrics(transformedMetrics);
                        
                        // Publish real-time events
                        eventStreamingService.publishMetricsEvent(transformedMetrics);
                        
                        processedCount++;
                    } catch (Exception e) {
                        log.warn("Failed to process metric at index {} for client: {}", 
                                i, metrics.getClientId(), e);
                        errorCount++;
                    }
                } else {
                    errorCount++;
                }
            }
            
            // Step 3: Batch write optimization (if supported by InfluxDB service)
            // influxDBService.flushBatch();
            
            // Update counters
            totalMetricsProcessed.addAndGet(processedCount);
            totalBatchesProcessed.incrementAndGet();
            totalValidationErrors.addAndGet(batchValidation.getInvalidMetrics());
            totalCompressionSavings.addAndGet(totalCompressionSaved);
            
            // Build response
            result.put("processed", true);
            result.put("batch_id", batchMetrics.getBatchId());
            result.put("total_metrics", metricsList.size());
            result.put("processed_metrics", processedCount);
            result.put("error_metrics", errorCount);
            result.put("success_rate", (double) processedCount / metricsList.size() * 100.0);
            result.put("processing_time_ms", System.currentTimeMillis() - startTime);
            result.put("batch_validation", batchValidation);
            
            if (compress && totalCompressionSaved > 0) {
                result.put("total_compression_saved_bytes", totalCompressionSaved);
            }
            
            log.info("Successfully processed batch: {} metrics, {} processed, {} errors in {}ms", 
                    metricsList.size(), processedCount, errorCount, 
                    System.currentTimeMillis() - startTime);
            
        } catch (Exception e) {
            log.error("Failed to process batch metrics", e);
            result.put("processed", false);
            result.put("error", e.getMessage());
            result.put("processing_time_ms", System.currentTimeMillis() - startTime);
        }
        
        return result;
    }
    
    /**
     * Validates metrics without processing
     */
    public ValidationResult validateMetrics(MetricsDataDTO metrics) {
        return metricsValidator.validateMetrics(metrics);
    }
    
    /**
     * Validates batch metrics without processing
     */
    public BatchValidationResult validateBatchMetrics(BatchMetricsDTO batchMetrics) {
        return metricsValidator.validateBatch(batchMetrics.getMetrics());
    }
    
    /**
     * Tests compression efficiency for metrics data
     */
    public Map<String, Object> testCompression(MetricsDataDTO metrics) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            MetricsCompressionService.OptimizedMetricsData optimizedData = 
                compressionService.optimizeForTransmission(metrics);
            
            result.put("should_compress", compressionService.shouldCompress(metrics));
            result.put("compression_ratio", optimizedData.getCompressionRatio());
            result.put("original_size", optimizedData.getOriginalSize());
            result.put("compressed_size", optimizedData.getCompressedSize());
            result.put("saved_bytes", optimizedData.getSavedBytes());
            result.put("saved_percentage", optimizedData.getSavedPercentage());
            result.put("compressed", optimizedData.isCompressed());
            
        } catch (Exception e) {
            log.error("Failed to test compression", e);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Returns health status and performance metrics
     */
    public Map<String, Object> getHealthStatus() {
        Map<String, Object> health = new HashMap<>();
        
        health.put("status", "healthy");
        health.put("timestamp", Instant.now());
        health.put("total_metrics_processed", totalMetricsProcessed.get());
        health.put("total_batches_processed", totalBatchesProcessed.get());
        health.put("total_validation_errors", totalValidationErrors.get());
        health.put("total_compression_savings_bytes", totalCompressionSavings.get());
        
        // Calculate rates (simplified - in production would use time windows)
        long totalProcessed = totalMetricsProcessed.get();
        if (totalProcessed > 0) {
            double errorRate = (double) totalValidationErrors.get() / totalProcessed * 100.0;
            health.put("error_rate_percentage", errorRate);
        }
        
        // Add service dependencies health
        health.put("influxdb_healthy", influxDBService.isHealthy());
        
        return health;
    }
}