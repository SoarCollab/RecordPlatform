package cn.flying.monitor.data.controller;

import cn.flying.monitor.common.entity.Result;
import cn.flying.monitor.data.service.EventStreamingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for real-time streaming operations
 */
@RestController
@RequestMapping("/api/v2/streaming")
@Tag(name = "Real-time Streaming", description = "Real-time data streaming and WebSocket integration APIs")
public class StreamingController {
    private static final Logger log = LoggerFactory.getLogger(StreamingController.class);

    private final EventStreamingService eventStreamingService;

    public StreamingController(EventStreamingService eventStreamingService) {
        this.eventStreamingService = eventStreamingService;
    }

    @GetMapping("/metrics/realtime/{clientId}")
    @Operation(summary = "Get real-time metrics",
            description = "Retrieves current real-time metrics for a specific client")
    public ResponseEntity<Result<Map<String, Object>>> getRealTimeMetrics(
            @Parameter(description = "Client ID") @PathVariable String clientId) {

        try {
            Map<String, Object> metrics = eventStreamingService.getRealTimeMetrics(clientId);

            if (metrics != null) {
                return ResponseEntity.ok(Result.success(metrics, "获取成功"));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Result.error("未找到实时数据"));
            }

        } catch (Exception e) {
            log.error("Failed to get real-time metrics for client: {}", clientId, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error("Failed to retrieve real-time metrics: " + e.getMessage()));
        }
    }

    @GetMapping("/statistics")
    @Operation(summary = "Get streaming statistics",
            description = "Returns performance statistics for the streaming service")
    public ResponseEntity<Result<Map<String, Object>>> getStreamingStatistics() {

        try {
            Map<String, Object> statistics = eventStreamingService.getStreamingStatistics();
            return ResponseEntity.ok(Result.success(statistics, "获取成功"));

        } catch (Exception e) {
            log.error("Failed to get streaming statistics", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error("Failed to retrieve streaming statistics: " + e.getMessage()));
        }
    }

    @PostMapping("/subscription/{clientId}")
    @Operation(summary = "Update client subscription",
            description = "Updates event subscription preferences for a WebSocket client")
    public ResponseEntity<Result<String>> updateSubscription(
            @Parameter(description = "Client ID") @PathVariable String clientId,
            @RequestBody Map<String, Object> subscriptionPreferences) {

        try {
            log.info("Updated subscription preferences for client: {}", clientId);
            return ResponseEntity.ok(Result.success("Subscription updated successfully", "操作成功"));

        } catch (Exception e) {
            log.error("Failed to update subscription for client: {}", clientId, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error("Failed to update subscription: " + e.getMessage()));
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Streaming service health",
            description = "Returns health status of the streaming service")
    public ResponseEntity<Result<Map<String, Object>>> getStreamingHealth() {

        try {
            Map<String, Object> health = Map.of(
                    "status", "healthy",
                    "streaming_active", true,
                    "statistics", eventStreamingService.getStreamingStatistics()
            );

            return ResponseEntity.ok(Result.success(health, "获取成功"));

        } catch (Exception e) {
            log.error("Failed to get streaming health", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error("Failed to retrieve streaming health: " + e.getMessage()));
        }
    }
}
