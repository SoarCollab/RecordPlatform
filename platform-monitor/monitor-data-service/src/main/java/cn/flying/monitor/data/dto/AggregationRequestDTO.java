package cn.flying.monitor.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Aggregation request DTO for advanced analytics
 */
public class AggregationRequestDTO {
    
    @NotBlank(message = "Client ID is required")
    private String clientId;
    
    @NotNull(message = "Start time is required")
    private Instant startTime;
    
    @NotNull(message = "End time is required")
    private Instant endTime;
    
    @NotNull(message = "Metric names are required")
    private List<String> metricNames;
    
    @NotNull(message = "Aggregation functions are required")
    private List<String> aggregationFunctions; // avg, sum, min, max, count, percentile, stddev
    
    private String timeWindow = "1h"; // Default 1 hour window
    
    private List<String> groupBy;
    
    private List<Double> percentiles; // For percentile calculations (e.g., [50.0, 95.0, 99.0])
    
    private Boolean includeStatistics = true;
    
    private Boolean includeTrends = false;
    
    private Boolean includeAnomalies = false;
    
    @Min(value = 0, message = "Anomaly threshold must be positive")
    @Max(value = 10, message = "Anomaly threshold must not exceed 10")
    private Double anomalyThreshold = 2.0; // Standard deviations for anomaly detection
    
    private Integer maxResults = 1000; // Maximum number of results to return
    
    private String sortBy; // Field to sort by
    
    private String sortOrder = "DESC"; // ASC or DESC
    
    // Constructors
    public AggregationRequestDTO() {}
    
    public AggregationRequestDTO(String clientId, Instant startTime, Instant endTime, 
                                List<String> metricNames, List<String> aggregationFunctions) {
        this.clientId = clientId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.metricNames = metricNames;
        this.aggregationFunctions = aggregationFunctions;
    }
    
    // Getters and Setters
    public String getClientId() {
        return clientId;
    }
    
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
    
    public Instant getStartTime() {
        return startTime;
    }
    
    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }
    
    public Instant getEndTime() {
        return endTime;
    }
    
    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }
    
    public List<String> getMetricNames() {
        return metricNames;
    }
    
    public void setMetricNames(List<String> metricNames) {
        this.metricNames = metricNames;
    }
    
    public List<String> getAggregationFunctions() {
        return aggregationFunctions;
    }
    
    public void setAggregationFunctions(List<String> aggregationFunctions) {
        this.aggregationFunctions = aggregationFunctions;
    }
    
    public String getTimeWindow() {
        return timeWindow;
    }
    
    public void setTimeWindow(String timeWindow) {
        this.timeWindow = timeWindow;
    }
    
    public List<String> getGroupBy() {
        return groupBy;
    }
    
    public void setGroupBy(List<String> groupBy) {
        this.groupBy = groupBy;
    }
    
    public List<Double> getPercentiles() {
        return percentiles;
    }
    
    public void setPercentiles(List<Double> percentiles) {
        this.percentiles = percentiles;
    }
    
    public Boolean getIncludeStatistics() {
        return includeStatistics;
    }
    
    public void setIncludeStatistics(Boolean includeStatistics) {
        this.includeStatistics = includeStatistics;
    }
    
    public Boolean getIncludeTrends() {
        return includeTrends;
    }
    
    public void setIncludeTrends(Boolean includeTrends) {
        this.includeTrends = includeTrends;
    }
    
    public Boolean getIncludeAnomalies() {
        return includeAnomalies;
    }
    
    public void setIncludeAnomalies(Boolean includeAnomalies) {
        this.includeAnomalies = includeAnomalies;
    }
    
    public Double getAnomalyThreshold() {
        return anomalyThreshold;
    }
    
    public void setAnomalyThreshold(Double anomalyThreshold) {
        this.anomalyThreshold = anomalyThreshold;
    }
    
    public Integer getMaxResults() {
        return maxResults;
    }
    
    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }
    
    public String getSortBy() {
        return sortBy;
    }
    
    public void setSortBy(String sortBy) {
        this.sortBy = sortBy;
    }
    
    public String getSortOrder() {
        return sortOrder;
    }
    
    public void setSortOrder(String sortOrder) {
        this.sortOrder = sortOrder;
    }
    
    // Enhanced Validation Methods
    public boolean isValidTimeRange() {
        if (startTime == null || endTime == null) {
            return false;
        }
        return startTime.isBefore(endTime);
    }
    
    public boolean isValidTimeWindow() {
        if (timeWindow == null || timeWindow.trim().isEmpty()) {
            return false;
        }
        // Validate time window format (e.g., "1h", "30m", "1d")
        return timeWindow.matches("^\\d+[smhd]$");
    }
    
    public Duration getTimeRangeDuration() {
        if (!isValidTimeRange()) {
            return Duration.ZERO;
        }
        return Duration.between(startTime, endTime);
    }
    
    public boolean isTimeRangeReasonable() {
        Duration duration = getTimeRangeDuration();
        // Check if time range is not too large (e.g., more than 1 year)
        return duration.toDays() <= 365;
    }
    
    public boolean hasPercentiles() {
        return percentiles != null && !percentiles.isEmpty();
    }
    
    public boolean areValidPercentiles() {
        if (!hasPercentiles()) {
            return true; // Optional field
        }
        return percentiles.stream().allMatch(p -> p >= 0.0 && p <= 100.0);
    }
    
    public boolean hasGroupBy() {
        return groupBy != null && !groupBy.isEmpty();
    }
    
    public boolean isValidAggregationFunction(String function) {
        if (function == null || function.trim().isEmpty()) {
            return false;
        }
        return List.of("avg", "sum", "min", "max", "count", "percentile", "stddev", "median", "first", "last")
                .contains(function.toLowerCase().trim());
    }
    
    public boolean areValidAggregationFunctions() {
        if (aggregationFunctions == null || aggregationFunctions.isEmpty()) {
            return false;
        }
        return aggregationFunctions.stream()
                .filter(Objects::nonNull)
                .allMatch(this::isValidAggregationFunction);
    }
    
    public boolean hasValidMetricNames() {
        if (metricNames == null || metricNames.isEmpty()) {
            return false;
        }
        return metricNames.stream()
                .filter(Objects::nonNull)
                .allMatch(name -> !name.trim().isEmpty());
    }
    
    public boolean isValidSortOrder() {
        if (sortOrder == null) {
            return true; // Optional field
        }
        return "ASC".equalsIgnoreCase(sortOrder) || "DESC".equalsIgnoreCase(sortOrder);
    }
    
    public boolean isValidMaxResults() {
        return maxResults != null && maxResults > 0 && maxResults <= 10000;
    }
    
    public boolean requiresPercentileFunction() {
        return hasPercentiles() && aggregationFunctions != null && 
               aggregationFunctions.stream().anyMatch(f -> "percentile".equalsIgnoreCase(f));
    }
    
    /**
     * Comprehensive validation of the entire request
     */
    public boolean isValid() {
        return isValidTimeRange() &&
               isTimeRangeReasonable() &&
               isValidTimeWindow() &&
               hasValidMetricNames() &&
               areValidAggregationFunctions() &&
               areValidPercentiles() &&
               isValidSortOrder() &&
               isValidMaxResults();
    }
    
    /**
     * Get validation error messages
     */
    public List<String> getValidationErrors() {
        List<String> errors = new java.util.ArrayList<>();
        
        if (!isValidTimeRange()) {
            errors.add("Invalid time range: start time must be before end time");
        }
        if (!isTimeRangeReasonable()) {
            errors.add("Time range is too large (maximum 1 year allowed)");
        }
        if (!isValidTimeWindow()) {
            errors.add("Invalid time window format (expected format: number + unit, e.g., '1h', '30m')");
        }
        if (!hasValidMetricNames()) {
            errors.add("Invalid metric names: must be non-empty list with valid names");
        }
        if (!areValidAggregationFunctions()) {
            errors.add("Invalid aggregation functions: must contain valid function names");
        }
        if (!areValidPercentiles()) {
            errors.add("Invalid percentiles: values must be between 0.0 and 100.0");
        }
        if (!isValidSortOrder()) {
            errors.add("Invalid sort order: must be 'ASC' or 'DESC'");
        }
        if (!isValidMaxResults()) {
            errors.add("Invalid max results: must be between 1 and 10000");
        }
        if (requiresPercentileFunction() && !hasPercentiles()) {
            errors.add("Percentile function requires percentile values to be specified");
        }
        
        return errors;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AggregationRequestDTO that = (AggregationRequestDTO) o;
        return Objects.equals(clientId, that.clientId) &&
               Objects.equals(startTime, that.startTime) &&
               Objects.equals(endTime, that.endTime) &&
               Objects.equals(metricNames, that.metricNames) &&
               Objects.equals(aggregationFunctions, that.aggregationFunctions) &&
               Objects.equals(timeWindow, that.timeWindow);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(clientId, startTime, endTime, metricNames, aggregationFunctions, timeWindow);
    }
    
    @Override
    public String toString() {
        return "AggregationRequestDTO{" +
               "clientId='" + clientId + '\'' +
               ", startTime=" + startTime +
               ", endTime=" + endTime +
               ", metricNames=" + metricNames +
               ", aggregationFunctions=" + aggregationFunctions +
               ", timeWindow='" + timeWindow + '\'' +
               ", groupBy=" + groupBy +
               ", percentiles=" + percentiles +
               ", includeStatistics=" + includeStatistics +
               ", includeTrends=" + includeTrends +
               ", includeAnomalies=" + includeAnomalies +
               ", anomalyThreshold=" + anomalyThreshold +
               ", maxResults=" + maxResults +
               ", sortBy='" + sortBy + '\'' +
               ", sortOrder='" + sortOrder + '\'' +
               '}';
    }
}