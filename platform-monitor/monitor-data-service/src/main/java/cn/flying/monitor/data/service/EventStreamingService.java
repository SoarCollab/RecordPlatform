package cn.flying.monitor.data.service;

import cn.flying.monitor.data.dto.MetricsDataDTO;
import cn.flying.monitor.data.event.MetricsEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for real-time event streaming and processing
 * Handles WebSocket broadcasting and alert routing with <5 second latency
 */
@Service
public class EventStreamingService {
    private static final Logger log = LoggerFactory.getLogger(EventStreamingService.class);
    
    private final RabbitTemplate rabbitTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    
    // Performance tracking
    private final AtomicLong totalEventsPublished = new AtomicLong(0);
    private final AtomicLong totalAlertsGenerated = new AtomicLong(0);
    private final Map<String, Instant> lastEventTimestamps = new ConcurrentHashMap<>();
    
    // Exchange and routing key constants
    private static final String METRICS_EXCHANGE = "monitor.metrics.exchange";
    private static final String ALERTS_EXCHANGE = "monitor.alerts.exchange";
    private static final String WEBSOCKET_ROUTING_KEY = "websocket.broadcast";
    private static final String ALERT_ROUTING_KEY = "alert.process";
    
    // Redis keys for real-time data
    private static final String REAL_TIME_METRICS_KEY = "realtime:metrics:";
    private static final String ALERT_STATE_KEY = "alert:state:";
    
    public EventStreamingService(RabbitTemplate rabbitTemplate,
                                RedisTemplate<String, Object> redisTemplate,
                                ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Publishes metrics event for real-time streaming
     */
    public void publishMetricsEvent(MetricsDataDTO metrics) {
        try {
            MetricsEvent event = MetricsEvent.createMetricsUpdate(metrics.getClientId(), metrics);
            
            // Store in Redis for real-time access
            storeRealTimeMetrics(metrics);
            
            // Publish to RabbitMQ for WebSocket broadcasting
            publishToWebSocket(event);
            
            // Check for alert conditions
            checkAndPublishAlerts(metrics);
            
            // Update performance tracking
            totalEventsPublished.incrementAndGet();
            lastEventTimestamps.put(metrics.getClientId(), Instant.now());
            
            log.debug("Published metrics event for client: {}", metrics.getClientId());
            
        } catch (Exception e) {
            log.error("Failed to publish metrics event for client: {}", metrics.getClientId(), e);
        }
    }
    
    /**
     * Publishes health status event
     */
    public void publishHealthEvent(MetricsDataDTO metrics) {
        try {
            MetricsEvent event = MetricsEvent.createHealthEvent(metrics.getClientId(), metrics);
            
            // Publish to health monitoring queue
            rabbitTemplate.convertAndSend(METRICS_EXCHANGE, "health.update", event);
            
            log.debug("Published health event for client: {}", metrics.getClientId());
            
        } catch (Exception e) {
            log.error("Failed to publish health event for client: {}", metrics.getClientId(), e);
        }
    }
    
    /**
     * Gets real-time metrics for WebSocket clients
     */
    public Map<String, Object> getRealTimeMetrics(String clientId) {
        try {
            String key = REAL_TIME_METRICS_KEY + clientId;
            Object data = redisTemplate.opsForValue().get(key);
            
            if (data != null) {
                return objectMapper.convertValue(data, Map.class);
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("Failed to get real-time metrics for client: {}", clientId, e);
            return null;
        }
    }
    
    /**
     * Gets streaming statistics
     */
    public Map<String, Object> getStreamingStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("total_events_published", totalEventsPublished.get());
        stats.put("total_alerts_generated", totalAlertsGenerated.get());
        stats.put("active_clients", lastEventTimestamps.size());
        
        // Calculate average latency (simplified)
        long totalLatency = 0;
        int clientCount = 0;
        Instant now = Instant.now();
        
        for (Map.Entry<String, Instant> entry : lastEventTimestamps.entrySet()) {
            long latency = Duration.between(entry.getValue(), now).toMillis();
            if (latency < 60000) { // Only consider recent events (last minute)
                totalLatency += latency;
                clientCount++;
            }
        }
        
        if (clientCount > 0) {
            stats.put("average_latency_ms", totalLatency / clientCount);
        } else {
            stats.put("average_latency_ms", 0);
        }
        
        return stats;
    }
    
    /**
     * Filters events based on client subscriptions
     */
    public boolean shouldPublishToClient(String clientId, MetricsEvent event) {
        try {
            // Check client subscription preferences from Redis
            String subscriptionKey = "subscription:" + clientId;
            Object subscriptions = redisTemplate.opsForValue().get(subscriptionKey);
            
            if (subscriptions == null) {
                return true; // Default to all events if no preferences set
            }
            
            Map<String, Object> subscriptionMap = objectMapper.convertValue(subscriptions, Map.class);
            
            // Check event type filters
            if (subscriptionMap.containsKey("event_types")) {
                @SuppressWarnings("unchecked")
                java.util.List<String> allowedTypes = (java.util.List<String>) subscriptionMap.get("event_types");
                if (!allowedTypes.contains(event.getEventType())) {
                    return false;
                }
            }
            
            // Check client filters
            if (subscriptionMap.containsKey("client_filters")) {
                @SuppressWarnings("unchecked")
                java.util.List<String> allowedClients = (java.util.List<String>) subscriptionMap.get("client_filters");
                if (!allowedClients.contains(event.getClientId())) {
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            log.warn("Failed to check client subscription for: {}", clientId, e);
            return true; // Default to allowing events on error
        }
    }
    
    private void storeRealTimeMetrics(MetricsDataDTO metrics) {
        try {
            String key = REAL_TIME_METRICS_KEY + metrics.getClientId();
            
            // Create real-time data structure
            Map<String, Object> realTimeData = new HashMap<>();
            realTimeData.put("client_id", metrics.getClientId());
            realTimeData.put("timestamp", metrics.getTimestamp());
            realTimeData.put("cpu_usage", metrics.getCpuUsage());
            realTimeData.put("memory_usage", metrics.getMemoryUsage());
            realTimeData.put("disk_usage", metrics.getDiskUsage());
            realTimeData.put("network_upload", metrics.getNetworkUpload());
            realTimeData.put("network_download", metrics.getNetworkDownload());
            realTimeData.put("load_average", metrics.getLoadAverage());
            realTimeData.put("process_count", metrics.getProcessCount());
            realTimeData.put("data_quality_score", metrics.getDataQualityScore());
            
            // Store with 5-minute expiration to ensure fresh data
            redisTemplate.opsForValue().set(key, realTimeData, Duration.ofMinutes(5));
            
        } catch (Exception e) {
            log.error("Failed to store real-time metrics for client: {}", metrics.getClientId(), e);
        }
    }
    
    private void publishToWebSocket(MetricsEvent event) {
        try {
            // Publish to WebSocket broadcasting queue
            rabbitTemplate.convertAndSend(METRICS_EXCHANGE, WEBSOCKET_ROUTING_KEY, event);
            
        } catch (Exception e) {
            log.error("Failed to publish to WebSocket queue", e);
        }
    }
    
    private void checkAndPublishAlerts(MetricsDataDTO metrics) {
        try {
            // Check CPU alert
            if (metrics.getCpuUsage() != null && metrics.getCpuUsage() > 80.0) {
                publishAlert(metrics, "cpu", metrics.getCpuUsage());
            }
            
            // Check memory alert
            if (metrics.getMemoryUsage() != null && metrics.getMemoryUsage() > 85.0) {
                publishAlert(metrics, "memory", metrics.getMemoryUsage());
            }
            
            // Check disk alert
            if (metrics.getDiskUsage() != null && metrics.getDiskUsage() > 90.0) {
                publishAlert(metrics, "disk", metrics.getDiskUsage());
            }
            
            // Check system health
            if (isSystemUnhealthy(metrics)) {
                publishHealthAlert(metrics);
            }
            
        } catch (Exception e) {
            log.error("Failed to check alerts for client: {}", metrics.getClientId(), e);
        }
    }
    
    private void publishAlert(MetricsDataDTO metrics, String alertType, Double value) {
        try {
            String alertKey = ALERT_STATE_KEY + metrics.getClientId() + ":" + alertType;
            
            // Check if alert was already fired recently (debouncing)
            Object lastAlert = redisTemplate.opsForValue().get(alertKey);
            if (lastAlert != null) {
                return; // Alert already active
            }
            
            // Create and publish alert event
            MetricsEvent alertEvent = MetricsEvent.createAlertEvent(
                metrics.getClientId(), metrics, alertType, value);
            
            rabbitTemplate.convertAndSend(ALERTS_EXCHANGE, ALERT_ROUTING_KEY, alertEvent);
            
            // Store alert state with 5-minute expiration (debouncing period)
            redisTemplate.opsForValue().set(alertKey, Instant.now(), Duration.ofMinutes(5));
            
            totalAlertsGenerated.incrementAndGet();
            
            log.info("Published {} alert for client: {}, value: {}", 
                    alertType, metrics.getClientId(), value);
            
        } catch (Exception e) {
            log.error("Failed to publish alert for client: {}", metrics.getClientId(), e);
        }
    }
    
    private void publishHealthAlert(MetricsDataDTO metrics) {
        try {
            MetricsEvent healthEvent = MetricsEvent.createHealthEvent(metrics.getClientId(), metrics);
            healthEvent.setCritical(true);
            
            rabbitTemplate.convertAndSend(ALERTS_EXCHANGE, "health.critical", healthEvent);
            
            log.warn("Published critical health alert for client: {}", metrics.getClientId());
            
        } catch (Exception e) {
            log.error("Failed to publish health alert for client: {}", metrics.getClientId(), e);
        }
    }
    
    private boolean isSystemUnhealthy(MetricsDataDTO metrics) {
        int criticalCount = 0;
        
        if (metrics.getCpuUsage() != null && metrics.getCpuUsage() > 95.0) criticalCount++;
        if (metrics.getMemoryUsage() != null && metrics.getMemoryUsage() > 95.0) criticalCount++;
        if (metrics.getDiskUsage() != null && metrics.getDiskUsage() > 98.0) criticalCount++;
        
        return criticalCount >= 2;
    }
}