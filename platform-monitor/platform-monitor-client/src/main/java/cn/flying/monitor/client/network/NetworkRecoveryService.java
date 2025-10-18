package cn.flying.monitor.client.network;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Network failure recovery service with exponential backoff and connection resilience
 */
@Slf4j
@Service
public class NetworkRecoveryService {

    @Value("${monitor.client.network.max-retry-attempts:5}")
    private int maxRetryAttempts;

    @Value("${monitor.client.network.initial-retry-delay:1000}")
    private long initialRetryDelay;

    @Value("${monitor.client.network.max-retry-delay:30000}")
    private long maxRetryDelay;

    @Value("${monitor.client.network.retry-multiplier:2.0}")
    private double retryMultiplier;

    private final AtomicInteger totalRetries = new AtomicInteger(0);
    private final AtomicInteger successfulOperations = new AtomicInteger(0);
    private final AtomicInteger failedOperations = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    private RetryTemplate retryTemplate;

    /**
     * Initialize retry template with exponential backoff
     */
    public void initialize() {
        retryTemplate = RetryTemplate.builder()
                .maxAttempts(maxRetryAttempts)
                .exponentialBackoff(initialRetryDelay, retryMultiplier, maxRetryDelay)
                .retryOn(ConnectException.class)
                .retryOn(SocketTimeoutException.class)
                .retryOn(java.net.UnknownHostException.class)
                .retryOn(java.io.IOException.class)
                .withListener(new NetworkRetryListener())
                .build();

        log.info("Network recovery service initialized with max attempts: {}, initial delay: {}ms, max delay: {}ms",
                maxRetryAttempts, initialRetryDelay, maxRetryDelay);
    }

    /**
     * Execute operation with retry and exponential backoff
     */
    public <T> T executeWithRetry(Supplier<T> operation, String operationName) {
        if (retryTemplate == null) {
            initialize();
        }

        try {
            T result = retryTemplate.execute(context -> {
                log.debug("Executing {} (attempt {}/{})", operationName, 
                         context.getRetryCount() + 1, maxRetryAttempts);
                return operation.get();
            });

            successfulOperations.incrementAndGet();
            return result;

        } catch (Exception e) {
            failedOperations.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());
            log.error("Operation {} failed after {} attempts", operationName, maxRetryAttempts, e);
            throw new NetworkOperationException("Operation failed after retries: " + operationName, e);
        }
    }

    /**
     * Execute operation with retry (void return)
     */
    public void executeWithRetry(Runnable operation, String operationName) {
        executeWithRetry(() -> {
            operation.run();
            return null;
        }, operationName);
    }

    /**
     * Check if network is currently experiencing issues
     */
    public boolean isNetworkHealthy() {
        long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
        double failureRate = calculateFailureRate();
        
        // Consider network unhealthy if failure rate > 50% or recent failure within 30 seconds
        return failureRate < 0.5 && timeSinceLastFailure > 30000;
    }

    /**
     * Calculate current failure rate
     */
    public double calculateFailureRate() {
        int total = successfulOperations.get() + failedOperations.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) failedOperations.get() / total;
    }

    /**
     * Get network statistics
     */
    public NetworkStats getNetworkStats() {
        return NetworkStats.builder()
                .totalRetries(totalRetries.get())
                .successfulOperations(successfulOperations.get())
                .failedOperations(failedOperations.get())
                .failureRate(calculateFailureRate())
                .lastFailureTime(lastFailureTime.get())
                .isHealthy(isNetworkHealthy())
                .build();
    }

    /**
     * Reset statistics (useful for monitoring)
     */
    public void resetStats() {
        totalRetries.set(0);
        successfulOperations.set(0);
        failedOperations.set(0);
        lastFailureTime.set(0);
        log.info("Network recovery statistics reset");
    }

    /**
     * Retry listener for logging and statistics
     */
    private class NetworkRetryListener implements RetryListener {
        
        @Override
        public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            totalRetries.incrementAndGet();
            
            int attempt = context.getRetryCount() + 1;
            long delay = calculateDelay(attempt);
            
            log.warn("Network operation failed (attempt {}/{}), retrying in {}ms. Error: {}", 
                    attempt, maxRetryAttempts, delay, throwable.getMessage());
            
            if (attempt == maxRetryAttempts) {
                log.error("Network operation failed permanently after {} attempts", maxRetryAttempts);
            }
        }

        @Override
        public <T, E extends Throwable> void onSuccess(RetryContext context, RetryCallback<T, E> callback, T result) {
            if (context.getRetryCount() > 0) {
                log.info("Network operation succeeded after {} retries", context.getRetryCount());
            }
        }
    }

    /**
     * Calculate delay for given attempt number
     */
    private long calculateDelay(int attempt) {
        long delay = (long) (initialRetryDelay * Math.pow(retryMultiplier, attempt - 1));
        return Math.min(delay, maxRetryDelay);
    }

    /**
     * Network statistics data class
     */
    public static class NetworkStats {
        private final int totalRetries;
        private final int successfulOperations;
        private final int failedOperations;
        private final double failureRate;
        private final long lastFailureTime;
        private final boolean isHealthy;

        private NetworkStats(Builder builder) {
            this.totalRetries = builder.totalRetries;
            this.successfulOperations = builder.successfulOperations;
            this.failedOperations = builder.failedOperations;
            this.failureRate = builder.failureRate;
            this.lastFailureTime = builder.lastFailureTime;
            this.isHealthy = builder.isHealthy;
        }

        public static Builder builder() {
            return new Builder();
        }

        @Override
        public String toString() {
            String lastFailureStr = lastFailureTime > 0 ? 
                LocalDateTime.ofEpochSecond(lastFailureTime / 1000, 0, java.time.ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "Never";
                    
            return String.format("NetworkStats{retries=%d, success=%d, failed=%d, failureRate=%.2f%%, lastFailure=%s, healthy=%s}",
                    totalRetries, successfulOperations, failedOperations, failureRate * 100, lastFailureStr, isHealthy);
        }

        // Getters
        public int getTotalRetries() { return totalRetries; }
        public int getSuccessfulOperations() { return successfulOperations; }
        public int getFailedOperations() { return failedOperations; }
        public double getFailureRate() { return failureRate; }
        public long getLastFailureTime() { return lastFailureTime; }
        public boolean isHealthy() { return isHealthy; }

        public static class Builder {
            private int totalRetries;
            private int successfulOperations;
            private int failedOperations;
            private double failureRate;
            private long lastFailureTime;
            private boolean isHealthy;

            public Builder totalRetries(int totalRetries) {
                this.totalRetries = totalRetries;
                return this;
            }

            public Builder successfulOperations(int successfulOperations) {
                this.successfulOperations = successfulOperations;
                return this;
            }

            public Builder failedOperations(int failedOperations) {
                this.failedOperations = failedOperations;
                return this;
            }

            public Builder failureRate(double failureRate) {
                this.failureRate = failureRate;
                return this;
            }

            public Builder lastFailureTime(long lastFailureTime) {
                this.lastFailureTime = lastFailureTime;
                return this;
            }

            public Builder isHealthy(boolean isHealthy) {
                this.isHealthy = isHealthy;
                return this;
            }

            public NetworkStats build() {
                return new NetworkStats(this);
            }
        }
    }

    /**
     * Custom exception for network operations
     */
    public static class NetworkOperationException extends RuntimeException {
        public NetworkOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}