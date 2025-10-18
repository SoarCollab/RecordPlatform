package cn.flying.monitor.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration for InfluxDB 2.x integration
 * Supports batch processing and performance optimization
 */
@Configuration
@EnableScheduling
@ConfigurationProperties(prefix = "spring.influx")
public class InfluxDBConfig {
    
    private String url;
    private String token;
    private String bucket;
    private String organization;
    private String retentionPolicy = "30d";
    
    private Batch batch = new Batch();
    private Performance performance = new Performance();
    
    // Getters and setters
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    
    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }
    
    public String getRetentionPolicy() { return retentionPolicy; }
    public void setRetentionPolicy(String retentionPolicy) { this.retentionPolicy = retentionPolicy; }
    
    public Batch getBatch() { return batch; }
    public void setBatch(Batch batch) { this.batch = batch; }
    
    public Performance getPerformance() { return performance; }
    public void setPerformance(Performance performance) { this.performance = performance; }
    
    public static class Batch {
        private int size = 1000;
        private int flushInterval = 5000; // milliseconds
        private boolean enabled = true;
        
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        
        public int getFlushInterval() { return flushInterval; }
        public void setFlushInterval(int flushInterval) { this.flushInterval = flushInterval; }
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
    
    public static class Performance {
        private boolean compressionEnabled = true;
        private int connectionPoolSize = 10;
        private int queryTimeout = 30000; // milliseconds
        private int writeTimeout = 10000; // milliseconds
        
        public boolean isCompressionEnabled() { return compressionEnabled; }
        public void setCompressionEnabled(boolean compressionEnabled) { this.compressionEnabled = compressionEnabled; }
        
        public int getConnectionPoolSize() { return connectionPoolSize; }
        public void setConnectionPoolSize(int connectionPoolSize) { this.connectionPoolSize = connectionPoolSize; }
        
        public int getQueryTimeout() { return queryTimeout; }
        public void setQueryTimeout(int queryTimeout) { this.queryTimeout = queryTimeout; }
        
        public int getWriteTimeout() { return writeTimeout; }
        public void setWriteTimeout(int writeTimeout) { this.writeTimeout = writeTimeout; }
    }
}