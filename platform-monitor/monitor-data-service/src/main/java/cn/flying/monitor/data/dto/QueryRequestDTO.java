package cn.flying.monitor.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Query request DTO for advanced metrics querying
 */
@Data
public class QueryRequestDTO {
    
    @NotBlank(message = "Client ID is required")
    private String clientId;
    
    @NotNull(message = "Start time is required")
    private Instant startTime;
    
    @NotNull(message = "End time is required")
    private Instant endTime;
    
    private List<String> metricNames;
    
    private Map<String, Object> filters;
    
    private List<String> groupBy;
    
    private String aggregationFunction; // avg, sum, min, max, count
    
    private String timeWindow; // 1m, 5m, 1h, 1d
    
    private String sortBy;
    
    private String sortOrder; // asc, desc
    
    private Integer limit;
    
    private Integer offset;
    
    private Boolean includeMetadata = false;
    
    private Boolean includeQualityMetrics = false;
    
    // Validation methods
    public boolean isValidTimeRange() {
        return startTime != null && endTime != null && startTime.isBefore(endTime);
    }
    
    public boolean hasFilters() {
        return filters != null && !filters.isEmpty();
    }
    
    public boolean hasGroupBy() {
        return groupBy != null && !groupBy.isEmpty();
    }
    
    public boolean hasAggregation() {
        return aggregationFunction != null && !aggregationFunction.isBlank();
    }
    
    public boolean hasTimeWindow() {
        return timeWindow != null && !timeWindow.isBlank();
    }
    
    public boolean hasPagination() {
        return limit != null && limit > 0;
    }
}