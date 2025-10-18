package cn.flying.monitor.data.load;

import cn.flying.monitor.data.dto.BatchMetricsDTO;
import cn.flying.monitor.data.dto.MetricsDataDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load testing runner for data ingestion service
 * Can be run independently to test deployed service
 */
public class LoadTestRunner {
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    
    // Test configuration
    private final int targetRps;
    private final int testDurationSeconds;
    private final int maxResponseTimeMs;
    private final int threadPoolSize;
    
    // Metrics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicInteger slowRequests = new AtomicInteger(0);
    
    public LoadTestRunner(String baseUrl, int targetRps, int testDurationSeconds, int maxResponseTimeMs) {
        this.baseUrl = baseUrl;
        this.targetRps = targetRps;
        this.testDurationSeconds = testDurationSeconds;
        this.maxResponseTimeMs = maxResponseTimeMs;
        this.threadPoolSize = Math.min(targetRps / 10, 100); // Reasonable thread pool size
        
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }
    
    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8082";
        int targetRps = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int duration = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        int maxResponseTime = args.length > 3 ? Integer.parseInt(args[3]) : 100;
        
        LoadTestRunner runner = new LoadTestRunner(baseUrl, targetRps, duration, maxResponseTime);
        
        System.out.println("Starting load test with configuration:");
        System.out.println("Base URL: " + baseUrl);
        System.out.println("Target RPS: " + targetRps);
        System.out.println("Duration: " + duration + " seconds");
        System.out.println("Max Response Time: " + maxResponseTime + "ms");
        System.out.println("Thread Pool Size: " + runner.threadPoolSize);
        System.out.println();
        
        // Run different test scenarios
        runner.runSingleMetricsLoadTest();
        runner.runBatchMetricsLoadTest();
        runner.runMixedLoadTest();
        
        System.out.println("Load testing completed!");
    }
    
    public void runSingleMetricsLoadTest() {
        System.out.println("=== Single Metrics Load Test ===");
        
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        
        resetMetrics();
        
        // Schedule requests at target rate
        long intervalMs = 1000 / targetRps;
        ScheduledFuture<?> requestScheduler = scheduler.scheduleAtFixedRate(() -> {
            executor.submit(this::sendSingleMetricsRequest);
        }, 0, intervalMs, TimeUnit.MILLISECONDS);
        
        // Run for specified duration
        try {
            Thread.sleep(testDurationSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        requestScheduler.cancel(false);
        executor.shutdown();
        scheduler.shutdown();
        
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        printResults("Single Metrics");
    }
    
    public void runBatchMetricsLoadTest() {
        System.out.println("=== Batch Metrics Load Test ===");
        
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize / 2);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        
        resetMetrics();
        
        // Batch processing - fewer requests but larger payloads
        int batchSize = 50;
        int batchRps = targetRps / batchSize;
        long intervalMs = 1000 / Math.max(batchRps, 1);
        
        ScheduledFuture<?> requestScheduler = scheduler.scheduleAtFixedRate(() -> {
            executor.submit(() -> sendBatchMetricsRequest(batchSize));
        }, 0, intervalMs, TimeUnit.MILLISECONDS);
        
        // Run for specified duration
        try {
            Thread.sleep(testDurationSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        requestScheduler.cancel(false);
        executor.shutdown();
        scheduler.shutdown();
        
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        printResults("Batch Metrics");
    }
    
    public void runMixedLoadTest() {
        System.out.println("=== Mixed Load Test ===");
        
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        
        resetMetrics();
        
        // 70% single requests, 30% batch requests
        long singleIntervalMs = 1000 / (targetRps * 7 / 10);
        long batchIntervalMs = 1000 / (targetRps * 3 / 10 / 20); // 20 metrics per batch
        
        ScheduledFuture<?> singleScheduler = scheduler.scheduleAtFixedRate(() -> {
            executor.submit(this::sendSingleMetricsRequest);
        }, 0, singleIntervalMs, TimeUnit.MILLISECONDS);
        
        ScheduledFuture<?> batchScheduler = scheduler.scheduleAtFixedRate(() -> {
            executor.submit(() -> sendBatchMetricsRequest(20));
        }, 0, batchIntervalMs, TimeUnit.MILLISECONDS);
        
        // Run for specified duration
        try {
            Thread.sleep(testDurationSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        singleScheduler.cancel(false);
        batchScheduler.cancel(false);
        executor.shutdown();
        scheduler.shutdown();
        
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        printResults("Mixed Load");
    }
    
    private void sendSingleMetricsRequest() {
        long startTime = System.nanoTime();
        
        try {
            MetricsDataDTO metrics = createTestMetrics();
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<MetricsDataDTO> request = new HttpEntity<>(metrics, headers);
            
            ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl + "/api/v2/data/metrics", request, Map.class);
            
            long endTime = System.nanoTime();
            long responseTimeMs = (endTime - startTime) / 1_000_000;
            
            totalRequests.incrementAndGet();
            totalResponseTime.addAndGet(responseTimeMs);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                successfulRequests.incrementAndGet();
            } else {
                failedRequests.incrementAndGet();
            }
            
            if (responseTimeMs > maxResponseTimeMs) {
                slowRequests.incrementAndGet();
            }
            
        } catch (Exception e) {
            long endTime = System.nanoTime();
            long responseTimeMs = (endTime - startTime) / 1_000_000;
            
            totalRequests.incrementAndGet();
            totalResponseTime.addAndGet(responseTimeMs);
            failedRequests.incrementAndGet();
            
            System.err.println("Request failed: " + e.getMessage());
        }
    }
    
    private void sendBatchMetricsRequest(int batchSize) {
        long startTime = System.nanoTime();
        
        try {
            BatchMetricsDTO batch = createTestBatch(batchSize);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<BatchMetricsDTO> request = new HttpEntity<>(batch, headers);
            
            ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl + "/api/v2/data/metrics/batch", request, Map.class);
            
            long endTime = System.nanoTime();
            long responseTimeMs = (endTime - startTime) / 1_000_000;
            
            totalRequests.addAndGet(batchSize); // Count individual metrics
            totalResponseTime.addAndGet(responseTimeMs);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                successfulRequests.addAndGet(batchSize);
            } else {
                failedRequests.addAndGet(batchSize);
            }
            
            if (responseTimeMs > maxResponseTimeMs * 2) { // Allow more time for batches
                slowRequests.incrementAndGet();
            }
            
        } catch (Exception e) {
            long endTime = System.nanoTime();
            long responseTimeMs = (endTime - startTime) / 1_000_000;
            
            totalRequests.addAndGet(batchSize);
            totalResponseTime.addAndGet(responseTimeMs);
            failedRequests.addAndGet(batchSize);
            
            System.err.println("Batch request failed: " + e.getMessage());
        }
    }
    
    private void resetMetrics() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        totalResponseTime.set(0);
        slowRequests.set(0);
    }
    
    private void printResults(String testType) {
        long total = totalRequests.get();
        long successful = successfulRequests.get();
        long failed = failedRequests.get();
        long totalTime = totalResponseTime.get();
        int slow = slowRequests.get();
        
        double actualRps = (double) total / testDurationSeconds;
        double successRate = total > 0 ? (double) successful / total * 100.0 : 0.0;
        double averageResponseTime = total > 0 ? (double) totalTime / total : 0.0;
        double slowRequestRate = total > 0 ? (double) slow / total * 100.0 : 0.0;
        
        System.out.println(testType + " Load Test Results:");
        System.out.println("Total Requests: " + total);
        System.out.println("Successful Requests: " + successful);
        System.out.println("Failed Requests: " + failed);
        System.out.println("Actual RPS: " + String.format("%.2f", actualRps));
        System.out.println("Success Rate: " + String.format("%.2f", successRate) + "%");
        System.out.println("Average Response Time: " + String.format("%.2f", averageResponseTime) + "ms");
        System.out.println("Slow Requests (>" + maxResponseTimeMs + "ms): " + slow + " (" + String.format("%.2f", slowRequestRate) + "%)");
        
        // Performance assessment
        boolean rpsTarget = actualRps >= targetRps * 0.8;
        boolean responseTimeTarget = averageResponseTime <= maxResponseTimeMs;
        boolean successRateTarget = successRate >= 95.0;
        
        System.out.println("Performance Assessment:");
        System.out.println("RPS Target (80% of " + targetRps + "): " + (rpsTarget ? "PASS" : "FAIL"));
        System.out.println("Response Time Target (<=" + maxResponseTimeMs + "ms): " + (responseTimeTarget ? "PASS" : "FAIL"));
        System.out.println("Success Rate Target (>=95%): " + (successRateTarget ? "PASS" : "FAIL"));
        System.out.println("Overall: " + (rpsTarget && responseTimeTarget && successRateTarget ? "PASS" : "FAIL"));
        System.out.println();
    }
    
    private MetricsDataDTO createTestMetrics() {
        MetricsDataDTO metrics = new MetricsDataDTO();
        metrics.setClientId("load-test-client-" + Thread.currentThread().getId());
        metrics.setTimestamp(Instant.now());
        metrics.setCpuUsage(Math.random() * 100);
        metrics.setMemoryUsage(Math.random() * 100);
        metrics.setDiskUsage(Math.random() * 100);
        metrics.setNetworkUpload(Math.random() * 1000000);
        metrics.setNetworkDownload(Math.random() * 1000000);
        metrics.setDiskRead(Math.random() * 100000);
        metrics.setDiskWrite(Math.random() * 100000);
        metrics.setLoadAverage(Math.random() * 4);
        metrics.setProcessCount((int) (Math.random() * 500) + 50);
        metrics.setNetworkConnections((int) (Math.random() * 1000) + 10);
        return metrics;
    }
    
    private BatchMetricsDTO createTestBatch(int size) {
        BatchMetricsDTO batch = new BatchMetricsDTO();
        batch.setBatchId("load-test-batch-" + System.currentTimeMillis());
        batch.setBatchTimestamp(System.currentTimeMillis());
        batch.setCompressionEnabled(true);
        
        List<MetricsDataDTO> metricsList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            metricsList.add(createTestMetrics());
        }
        batch.setMetrics(metricsList);
        
        return batch;
    }
}