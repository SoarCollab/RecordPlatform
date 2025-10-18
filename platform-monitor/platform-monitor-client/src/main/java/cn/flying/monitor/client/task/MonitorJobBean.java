package cn.flying.monitor.client.task;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Resource;
import cn.flying.monitor.client.entity.RuntimeDetail;
import cn.flying.monitor.client.utils.MonitorUtils;
import cn.flying.monitor.client.utils.NetUtils;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced monitoring job with network optimization and batch processing
 */
@Slf4j
@Component
public class MonitorJobBean extends QuartzJobBean {
    
    @Resource
    private MonitorUtils monitor;
    
    @Resource
    private NetUtils net;

    @Value("${monitor.client.metrics.batch-size:10}")
    private int batchSize;

    @Value("${monitor.client.network.async-enabled:true}")
    private boolean asyncEnabled;

    private final List<RuntimeDetail> batchBuffer = new ArrayList<>();
    private volatile long lastBatchSent = System.currentTimeMillis();

    @Override
    protected void executeInternal(@Nonnull JobExecutionContext context) {
        try {
            long startTime = System.currentTimeMillis();
            
            // Collect runtime metrics
            RuntimeDetail runtimeDetail = monitor.monitorRuntimeDetail();
            if (runtimeDetail == null) {
                log.warn("Failed to collect runtime details, skipping this cycle");
                return;
            }

            // Apply metric filtering
            runtimeDetail = monitor.filterMetrics(runtimeDetail);
            if (runtimeDetail == null) {
                log.debug("All metrics filtered out, skipping this cycle");
                return;
            }

            // Add network efficiency metrics
            addNetworkEfficiencyMetrics(runtimeDetail);

            // Process based on batch configuration
            if (batchSize > 1) {
                processBatchMode(runtimeDetail);
            } else {
                processSingleMode(runtimeDetail);
            }

            long executionTime = System.currentTimeMillis() - startTime;
            log.debug("Monitor job execution completed in {}ms", executionTime);

        } catch (Exception e) {
            log.error("Error during monitor job execution", e);
        }
    }

    /**
     * Process metrics in batch mode for network efficiency
     */
    private void processBatchMode(RuntimeDetail runtimeDetail) {
        synchronized (batchBuffer) {
            batchBuffer.add(runtimeDetail);
            
            // Send batch if buffer is full or timeout reached
            boolean bufferFull = batchBuffer.size() >= batchSize;
            boolean timeoutReached = (System.currentTimeMillis() - lastBatchSent) > 30000; // 30 seconds
            
            if (bufferFull || timeoutReached) {
                sendBatch();
            }
        }
    }

    /**
     * Process metrics in single mode with async optimization
     */
    private void processSingleMode(RuntimeDetail runtimeDetail) {
        if (asyncEnabled) {
            // Send asynchronously to avoid blocking the job thread
            CompletableFuture.runAsync(() -> {
                try {
                    net.updateRuntimeDetails(runtimeDetail);
                } catch (Exception e) {
                    log.error("Async runtime details update failed", e);
                }
            }).orTimeout(10, TimeUnit.SECONDS)
              .exceptionally(throwable -> {
                  log.error("Async operation timed out or failed", throwable);
                  return null;
              });
        } else {
            net.updateRuntimeDetails(runtimeDetail);
        }
    }

    /**
     * Send accumulated batch to server
     */
    private void sendBatch() {
        if (batchBuffer.isEmpty()) {
            return;
        }

        RuntimeDetail[] batch = batchBuffer.toArray(new RuntimeDetail[0]);
        batchBuffer.clear();
        lastBatchSent = System.currentTimeMillis();

        if (asyncEnabled) {
            CompletableFuture.runAsync(() -> {
                try {
                    net.updateRuntimeDetailsBatch(batch);
                    log.debug("Batch of {} metrics sent successfully", batch.length);
                } catch (Exception e) {
                    log.error("Batch runtime details update failed", e);
                    // Re-add failed items to buffer for retry (with limit to prevent memory issues)
                    synchronized (batchBuffer) {
                        if (batchBuffer.size() < batchSize * 2) {
                            for (RuntimeDetail detail : batch) {
                                batchBuffer.add(detail);
                            }
                        }
                    }
                }
            }).orTimeout(30, TimeUnit.SECONDS)
              .exceptionally(throwable -> {
                  log.error("Batch operation timed out or failed", throwable);
                  return null;
              });
        } else {
            try {
                net.updateRuntimeDetailsBatch(batch);
                log.debug("Batch of {} metrics sent successfully", batch.length);
            } catch (Exception e) {
                log.error("Batch runtime details update failed", e);
            }
        }
    }

    /**
     * Add network efficiency metrics to runtime details
     */
    private void addNetworkEfficiencyMetrics(RuntimeDetail runtimeDetail) {
        try {
            // Add connection pool statistics
            String poolStats = net.getConnectionPoolStats();
            log.debug("Connection pool stats: {}", poolStats);
            
            // Add compression ratio if available (this would be tracked in NetUtils)
            // For now, we'll add a placeholder for future enhancement
            
        } catch (Exception e) {
            log.debug("Failed to add network efficiency metrics", e);
        }
    }

    /**
     * Force send any pending batch (called during shutdown)
     */
    public void flushPendingBatch() {
        synchronized (batchBuffer) {
            if (!batchBuffer.isEmpty()) {
                log.info("Flushing {} pending metrics before shutdown", batchBuffer.size());
                sendBatch();
            }
        }
    }

    /**
     * Get current batch buffer size for monitoring
     */
    public int getBatchBufferSize() {
        synchronized (batchBuffer) {
            return batchBuffer.size();
        }
    }
}
