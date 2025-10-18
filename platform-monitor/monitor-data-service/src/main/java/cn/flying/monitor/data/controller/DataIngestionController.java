package cn.flying.monitor.data.controller;

import cn.flying.monitor.common.entity.Result;
import cn.flying.monitor.data.dto.BatchMetricsDTO;
import cn.flying.monitor.data.dto.MetricsDataDTO;
import cn.flying.monitor.data.service.DataIngestionService;
import cn.flying.monitor.data.validation.BatchValidationResult;
import cn.flying.monitor.data.validation.ValidationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Enhanced data ingestion controller for high-performance metrics processing
 */
@RestController
@RequestMapping("/api/v2/data")
@Tag(name = "Data Ingestion", description = "High-performance metrics data ingestion APIs")
@Slf4j
public class DataIngestionController {

    private final DataIngestionService dataIngestionService;

    public DataIngestionController(DataIngestionService dataIngestionService) {
        this.dataIngestionService = dataIngestionService;
    }

    @PostMapping("/metrics")
    @Operation(summary = "Ingest single metrics data",
            description = "Processes individual metrics data with validation and normalization")
    public ResponseEntity<Result<Map<String, Object>>> ingestMetrics(
            @Valid @RequestBody MetricsDataDTO metrics,
            @Parameter(description = "Enable data compression")
            @RequestParam(defaultValue = "false") boolean compress,
            @Parameter(description = "Skip validation for performance")
            @RequestParam(defaultValue = "false") boolean skipValidation) {

        try {
            Map<String, Object> result = dataIngestionService.processMetrics(metrics, compress, skipValidation);
            return ResponseEntity.ok(Result.success(result, "接收成功"));
        } catch (Exception e) {
            log.error("Failed to ingest metrics for client: {}", metrics.getClientId(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error("Metrics ingestion failed: " + e.getMessage()));
        }
    }

    @PostMapping("/metrics/batch")
    @Operation(summary = "Ingest batch metrics data",
            description = "Processes multiple metrics in a single request for high throughput")
    public ResponseEntity<Result<Map<String, Object>>> ingestBatchMetrics(
            @Valid @RequestBody BatchMetricsDTO batchMetrics,
            @Parameter(description = "Enable batch compression")
            @RequestParam(defaultValue = "true") boolean compress,
            @Parameter(description = "Continue processing on validation errors")
            @RequestParam(defaultValue = "true") boolean continueOnError) {

        try {
            Map<String, Object> result = dataIngestionService.processBatchMetrics(
                    batchMetrics, compress, continueOnError);
            return ResponseEntity.ok(Result.success(result, "批量接收成功"));
        } catch (Exception e) {
            log.error("Failed to ingest batch metrics, batch size: {}",
                    batchMetrics.getMetrics().size(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error("Batch metrics ingestion failed: " + e.getMessage()));
        }
    }

    @PostMapping("/metrics/validate")
    @Operation(summary = "Validate metrics data",
            description = "Validates metrics data without storing it")
    public ResponseEntity<Result<ValidationResult>> validateMetrics(
            @Valid @RequestBody MetricsDataDTO metrics) {

        try {
            ValidationResult result = dataIngestionService.validateMetrics(metrics);
            return ResponseEntity.ok(Result.success(result, "校验成功"));
        } catch (Exception e) {
            log.error("Failed to validate metrics for client: {}", metrics.getClientId(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error("Metrics validation failed: " + e.getMessage()));
        }
    }

    @PostMapping("/metrics/batch/validate")
    @Operation(summary = "Validate batch metrics data",
            description = "Validates batch metrics data without storing it")
    public ResponseEntity<Result<BatchValidationResult>> validateBatchMetrics(
            @Valid @RequestBody BatchMetricsDTO batchMetrics) {

        try {
            BatchValidationResult result = dataIngestionService.validateBatchMetrics(batchMetrics);
            return ResponseEntity.ok(Result.success(result, "校验成功"));
        } catch (Exception e) {
            log.error("Failed to validate batch metrics, batch size: {}",
                    batchMetrics.getMetrics().size(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error("Batch metrics validation failed: " + e.getMessage()));
        }
    }

    @GetMapping("/metrics/compression/test")
    @Operation(summary = "Test compression efficiency",
            description = "Tests compression efficiency for sample metrics data")
    public ResponseEntity<Result<Map<String, Object>>> testCompression(
            @Valid @RequestBody MetricsDataDTO metrics) {

        try {
            Map<String, Object> result = dataIngestionService.testCompression(metrics);
            return ResponseEntity.ok(Result.success(result, "获取成功"));
        } catch (Exception e) {
            log.error("Failed to test compression for client: {}", metrics.getClientId(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error("Compression test failed: " + e.getMessage()));
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Data service health check",
            description = "Returns health status of data ingestion service")
    public ResponseEntity<Result<Map<String, Object>>> healthCheck() {
        Map<String, Object> health = dataIngestionService.getHealthStatus();
        return ResponseEntity.ok(Result.success(health, "获取成功"));
    }
}
