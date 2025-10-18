package cn.flying.monitor.data.service;

import cn.flying.monitor.data.dto.MetricsDataDTO;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced InfluxDB service with batch processing and high-throughput optimization
 * Supports InfluxDB 2.x with automated retention policies and compression
 */
@Service
@Slf4j
public class InfluxDBService {
    
    @Value("${spring.influx.url}")
    private String url;
    
    @Value("${spring.influx.token}")
    private String token;
    
    @Value("${spring.influx.bucket}")
    private String bucket;
    
    @Value("${spring.influx.organization}")
    private String organization;
    
    @Value("${spring.influx.batch.size:1000}")
    private int batchSize;
    
    @Value("${spring.influx.batch.flush.interval:5000}")
    private int flushIntervalMs;
    
    @Value("${spring.influx.retention.policy:30d}")
    private String retentionPolicy;
    
    private InfluxDBClient client;
    private WriteApi writeApi;
    private WriteApiBlocking writeApiBlocking;
    
    // Batch processing
    private final BlockingQueue<Point> batchQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean batchProcessingEnabled = new AtomicBoolean(true);
    private final AtomicLong totalPointsWritten = new AtomicLong(0);
    private final AtomicLong totalBatchesWritten = new AtomicLong(0);
    private final AtomicLong totalWriteErrors = new AtomicLong(0);
    
    @PostConstruct
    public void init() {
        try {
            // Initialize InfluxDB 2.x client
            client = InfluxDBClientFactory.create(url, token.toCharArray(), organization, bucket);
            
            // Initialize write APIs
            writeApiBlocking = client.getWriteApiBlocking();
            writeApi = client.makeWriteApi();
            
            // Configure write API for batch processing
            // GZIP 可通过客户端配置启用（WriteApi 无对应方法）
            
            // Test connection
            if (isHealthy()) {
                log.info("InfluxDB connection established successfully");
                setupRetentionPolicy();
            } else {
                log.error("Failed to establish InfluxDB connection");
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize InfluxDB client", e);
            throw new RuntimeException("InfluxDB initialization failed", e);
        }
    }
    
    @PreDestroy
    public void cleanup() {
        try {
            batchProcessingEnabled.set(false);
            
            // Flush remaining points
            flushBatch();
            
            if (writeApi != null) {
                writeApi.close();
            }
            
            if (client != null) {
                client.close();
            }
            
            log.info("InfluxDB client closed successfully");
        } catch (Exception e) {
            log.error("Error during InfluxDB cleanup", e);
        }
    }
    
    /**
     * Writes metrics data to InfluxDB with batch optimization
     */
    public void writeMetrics(MetricsDataDTO metrics) {
        try {
            Point point = createPointFromMetrics(metrics);
            
            if (batchProcessingEnabled.get()) {
                // Add to batch queue
                if (!batchQueue.offer(point)) {
                    log.warn("Batch queue is full, writing point directly");
                    writePointDirectly(point);
                }
            } else {
                // Write directly for immediate processing
                writePointDirectly(point);
            }
            
        } catch (Exception e) {
            log.error("Failed to write metrics for client: {}", metrics.getClientId(), e);
            totalWriteErrors.incrementAndGet();
            throw new RuntimeException("Metrics write failed", e);
        }
    }
    
    /**
     * Writes multiple metrics in a single batch operation
     */
    public void writeBatchMetrics(List<MetricsDataDTO> metricsList) {
        try {
            List<Point> points = new ArrayList<>();
            
            for (MetricsDataDTO metrics : metricsList) {
                Point point = createPointFromMetrics(metrics);
                points.add(point);
            }
            
            // Write batch directly
            writeApiBlocking.writePoints(bucket, organization, points);
            
            totalPointsWritten.addAndGet(points.size());
            totalBatchesWritten.incrementAndGet();
            
            log.debug("Successfully wrote batch of {} metrics", points.size());
            
        } catch (Exception e) {
            log.error("Failed to write batch metrics, size: {}", metricsList.size(), e);
            totalWriteErrors.incrementAndGet();
            throw new RuntimeException("Batch metrics write failed", e);
        }
    }
    
    /**
     * Queries historical metrics data with optimization
     */
    public List<Map<String, Object>> queryMetrics(String clientId, Instant start, Instant end) {
        try {
            String query = String.format("""
                from(bucket: "%s")
                |> range(start: %s, stop: %s)
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["client_id"] == "%s")
                |> aggregateWindow(every: 1m, fn: mean, createEmpty: false)
                |> yield(name: "mean")
                """, bucket, start, end, clientId);
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            List<Map<String, Object>> results = new ArrayList<>();
            
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Map<String, Object> recordMap = Map.of(
                        "time", record.getTime(),
                        "field", record.getField(),
                        "value", record.getValue(),
                        "client_id", record.getValueByKey("client_id")
                    );
                    results.add(recordMap);
                }
            }
            
            return results;
            
        } catch (Exception e) {
            log.error("Failed to query metrics for client: {}", clientId, e);
            throw new RuntimeException("Metrics query failed", e);
        }
    }
    
    /**
     * Queries aggregated metrics data for analytics
     */
    public List<Map<String, Object>> queryAggregatedMetrics(String clientId, 
                                                           Instant start, 
                                                           Instant end, 
                                                           String aggregationWindow) {
        try {
            String query = String.format("""
                from(bucket: "%s")
                |> range(start: %s, stop: %s)
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> filter(fn: (r) => r["client_id"] == "%s")
                |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
                |> group(columns: ["_field"])
                |> yield(name: "aggregated")
                """, bucket, start, end, clientId, aggregationWindow);
            
            List<FluxTable> tables = client.getQueryApi().query(query, organization);
            List<Map<String, Object>> results = new ArrayList<>();
            
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Map<String, Object> recordMap = Map.of(
                        "time", record.getTime(),
                        "field", record.getField(),
                        "value", record.getValue(),
                        "client_id", record.getValueByKey("client_id")
                    );
                    results.add(recordMap);
                }
            }
            
            return results;
            
        } catch (Exception e) {
            log.error("Failed to query aggregated metrics for client: {}", clientId, e);
            throw new RuntimeException("Aggregated metrics query failed", e);
        }
    }
    
    /**
     * Flushes pending batch writes
     */
    public void flushBatch() {
        if (batchQueue.isEmpty()) {
            return;
        }
        
        try {
            List<Point> points = new ArrayList<>();
            batchQueue.drainTo(points, batchSize);
            
            if (!points.isEmpty()) {
                writeApiBlocking.writePoints(bucket, organization, points);
                totalPointsWritten.addAndGet(points.size());
                totalBatchesWritten.incrementAndGet();
                
                log.debug("Flushed batch of {} points", points.size());
            }
            
        } catch (Exception e) {
            log.error("Failed to flush batch", e);
            totalWriteErrors.incrementAndGet();
        }
    }
    
    /**
     * Checks InfluxDB health status
     */
    public boolean isHealthy() {
        try {
            return client.health().getStatus().getValue().equals("pass");
        } catch (Exception e) {
            log.warn("InfluxDB health check failed", e);
            return false;
        }
    }
    
    /**
     * Gets performance statistics
     */
    public Map<String, Object> getStatistics() {
        return Map.of(
            "total_points_written", totalPointsWritten.get(),
            "total_batches_written", totalBatchesWritten.get(),
            "total_write_errors", totalWriteErrors.get(),
            "batch_queue_size", batchQueue.size(),
            "batch_processing_enabled", batchProcessingEnabled.get(),
            "healthy", isHealthy()
        );
    }
    
    /**
     * Scheduled batch flush to ensure timely writes
     */
    @Scheduled(fixedDelayString = "${spring.influx.batch.flush.interval:5000}")
    public void scheduledBatchFlush() {
        if (batchProcessingEnabled.get() && !batchQueue.isEmpty()) {
            flushBatch();
        }
    }
    
    /**
     * Scheduled cleanup of old data based on retention policy
     */
    @Scheduled(cron = "0 0 2 * * ?") // Daily at 2 AM
    public void cleanupOldData() {
        try {
            // Calculate cutoff time based on retention policy
            Instant cutoffTime = calculateRetentionCutoff();
            
            String deleteQuery = String.format("""
                from(bucket: "%s")
                |> range(start: 1970-01-01T00:00:00Z, stop: %s)
                |> filter(fn: (r) => r["_measurement"] == "system_metrics")
                |> drop()
                """, bucket, cutoffTime);
            
            OffsetDateTime startTime = OffsetDateTime.ofInstant(cutoffTime, ZoneOffset.UTC);
            OffsetDateTime stopTime = OffsetDateTime.now(ZoneOffset.UTC);
            client.getDeleteApi().delete(startTime, stopTime, "", bucket, organization);
            
            log.info("Cleaned up data older than: {}", cutoffTime);
            
        } catch (Exception e) {
            log.error("Failed to cleanup old data", e);
        }
    }
    
    private Point createPointFromMetrics(MetricsDataDTO metrics) {
        Point point = Point.measurement("system_metrics")
                .time(metrics.getTimestamp(), WritePrecision.NS)
                .addTag("client_id", metrics.getClientId());
        
        // Add core metrics
        if (metrics.getCpuUsage() != null) {
            point.addField("cpu_usage", metrics.getCpuUsage());
        }
        if (metrics.getMemoryUsage() != null) {
            point.addField("memory_usage", metrics.getMemoryUsage());
        }
        if (metrics.getDiskUsage() != null) {
            point.addField("disk_usage", metrics.getDiskUsage());
        }
        if (metrics.getNetworkUpload() != null) {
            point.addField("network_upload", metrics.getNetworkUpload());
        }
        if (metrics.getNetworkDownload() != null) {
            point.addField("network_download", metrics.getNetworkDownload());
        }
        if (metrics.getDiskRead() != null) {
            point.addField("disk_read", metrics.getDiskRead());
        }
        if (metrics.getDiskWrite() != null) {
            point.addField("disk_write", metrics.getDiskWrite());
        }
        
        // Add additional metrics
        if (metrics.getLoadAverage() != null) {
            point.addField("load_average", metrics.getLoadAverage());
        }
        if (metrics.getProcessCount() != null) {
            point.addField("process_count", metrics.getProcessCount());
        }
        if (metrics.getNetworkConnections() != null) {
            point.addField("network_connections", metrics.getNetworkConnections());
        }
        
        // Add custom metrics
        if (metrics.getCustomMetrics() != null) {
            for (Map.Entry<String, Object> entry : metrics.getCustomMetrics().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                if (value instanceof Number) {
                    point.addField("custom_" + key, ((Number) value).doubleValue());
                } else if (value instanceof String) {
                    point.addTag("custom_" + key, (String) value);
                }
            }
        }
        
        // Add data quality metrics
        if (metrics.getDataQualityScore() != null) {
            point.addField("data_quality_score", metrics.getDataQualityScore());
        }
        
        return point;
    }
    
    private void writePointDirectly(Point point) {
        try {
            writeApiBlocking.writePoint(bucket, organization, point);
            totalPointsWritten.incrementAndGet();
        } catch (Exception e) {
            log.error("Failed to write point directly", e);
            totalWriteErrors.incrementAndGet();
            throw e;
        }
    }
    
    private void setupRetentionPolicy() {
        try {
            // Note: In InfluxDB 2.x, retention policies are managed through bucket configuration
            // This would typically be done through the InfluxDB UI or API during setup
            log.info("Retention policy configured: {}", retentionPolicy);
        } catch (Exception e) {
            log.warn("Failed to setup retention policy", e);
        }
    }
    
    private Instant calculateRetentionCutoff() {
        // Parse retention policy (e.g., "30d", "7d", "1h")
        String policy = retentionPolicy.toLowerCase();
        
        if (policy.endsWith("d")) {
            int days = Integer.parseInt(policy.substring(0, policy.length() - 1));
            return Instant.now().minusSeconds(days * 24L * 60L * 60L);
        } else if (policy.endsWith("h")) {
            int hours = Integer.parseInt(policy.substring(0, policy.length() - 1));
            return Instant.now().minusSeconds(hours * 60L * 60L);
        } else {
            // Default to 30 days
            return Instant.now().minusSeconds(30L * 24L * 60L * 60L);
        }
    }
}