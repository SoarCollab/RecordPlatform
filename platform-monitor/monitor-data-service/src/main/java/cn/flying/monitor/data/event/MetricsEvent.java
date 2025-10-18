package cn.flying.monitor.data.event;

import cn.flying.monitor.data.dto.MetricsDataDTO;

import java.time.Instant;

/**
 * Event for real-time metrics data streaming
 * Used for WebSocket broadcasting and alert processing
 */
public class MetricsEvent {
    
    private String eventId;
    private String eventType;
    private String clientId;
    private MetricsDataDTO metrics;
    private Instant eventTimestamp;
    private String source;
    private Double alertThreshold;
    private String alertType;
    private boolean critical;
    
    public MetricsEvent() {
        this.eventTimestamp = Instant.now();
        this.eventId = generateEventId();
    }
    
    public MetricsEvent(String eventType, String clientId, MetricsDataDTO metrics) {
        this();
        this.eventType = eventType;
        this.clientId = clientId;
        this.metrics = metrics;
        this.source = "data-ingestion-service";
    }
    
    public static MetricsEvent createMetricsUpdate(String clientId, MetricsDataDTO metrics) {
        return new MetricsEvent("METRICS_UPDATE", clientId, metrics);
    }
    
    public static MetricsEvent createAlertEvent(String clientId, MetricsDataDTO metrics, 
                                               String alertType, Double threshold) {
        MetricsEvent event = new MetricsEvent("ALERT", clientId, metrics);
        event.setAlertType(alertType);
        event.setAlertThreshold(threshold);
        event.setCritical(isThresholdCritical(alertType, threshold, metrics));
        return event;
    }
    
    public static MetricsEvent createHealthEvent(String clientId, MetricsDataDTO metrics) {
        MetricsEvent event = new MetricsEvent("HEALTH_UPDATE", clientId, metrics);
        event.setCritical(isSystemUnhealthy(metrics));
        return event;
    }
    
    private String generateEventId() {
        return "evt_" + System.currentTimeMillis() + "_" + 
               Integer.toHexString(this.hashCode());
    }
    
    private static boolean isThresholdCritical(String alertType, Double threshold, MetricsDataDTO metrics) {
        switch (alertType.toLowerCase()) {
            case "cpu":
                return metrics.getCpuUsage() != null && metrics.getCpuUsage() > 90.0;
            case "memory":
                return metrics.getMemoryUsage() != null && metrics.getMemoryUsage() > 95.0;
            case "disk":
                return metrics.getDiskUsage() != null && metrics.getDiskUsage() > 95.0;
            default:
                return false;
        }
    }
    
    private static boolean isSystemUnhealthy(MetricsDataDTO metrics) {
        // System is considered unhealthy if multiple critical thresholds are exceeded
        int criticalCount = 0;
        
        if (metrics.getCpuUsage() != null && metrics.getCpuUsage() > 95.0) criticalCount++;
        if (metrics.getMemoryUsage() != null && metrics.getMemoryUsage() > 95.0) criticalCount++;
        if (metrics.getDiskUsage() != null && metrics.getDiskUsage() > 98.0) criticalCount++;
        
        return criticalCount >= 2;
    }

    // Getters and Setters
    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public MetricsDataDTO getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsDataDTO metrics) {
        this.metrics = metrics;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(Instant eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Double getAlertThreshold() {
        return alertThreshold;
    }

    public void setAlertThreshold(Double alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    public String getAlertType() {
        return alertType;
    }

    public void setAlertType(String alertType) {
        this.alertType = alertType;
    }

    public boolean isCritical() {
        return critical;
    }

    public void setCritical(boolean critical) {
        this.critical = critical;
    }
}