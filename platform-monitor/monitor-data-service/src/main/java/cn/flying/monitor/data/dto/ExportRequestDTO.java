package cn.flying.monitor.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Export request DTO for advanced data export operations
 */
public class ExportRequestDTO {

    @NotBlank(message = "Export type is required")
    private String exportType; // metrics, report, dashboard, audit, etc.

    @NotBlank(message = "Format is required")
    private String format; // csv, json, excel, pdf, png

    private String clientId;

    private List<String> clientIds;

    @NotNull(message = "Start time is required")
    private Instant startTime;

    @NotNull(message = "End time is required")
    private Instant endTime;

    private List<String> metricNames;

    private Map<String, Object> filters;

    private String templateId;

    private Map<String, Object> templateParameters;

    private String title;

    private String description;

    private Boolean includeCharts = false;

    private Boolean includeStatistics = true;

    private Boolean includeTrends = false;

    private Boolean includeAnomalies = false;

    private Boolean includeMetadata = false;

    private String aggregationLevel; // raw, hourly, daily, weekly, monthly

    private List<String> groupBy;

    private String sortBy;

    private String sortOrder;

    private Integer limit;

    private String compressionType; // none, zip, gzip

    private Boolean asyncExport = false;

    private String notificationEmail;

    private Map<String, Object> customOptions;

    // Report-specific fields
    private String reportType; // summary, detailed, comparison, trend_analysis

    private List<String> sections; // overview, metrics, alerts, performance, etc.

    private String logoUrl;

    private Map<String, String> branding;

    // Dashboard export fields
    private String dashboardId;

    private List<String> widgetIds;

    private String resolution; // 1920x1080, 1366x768, etc.

    private Boolean includeInteractiveElements = false;

    // Constructors
    public ExportRequestDTO() {
    }

    public ExportRequestDTO(String exportType, String format, Instant startTime, Instant endTime) {
        this.exportType = exportType;
        this.format = format;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    // Getters and Setters
    public String getExportType() {
        return exportType;
    }

    public void setExportType(String exportType) {
        this.exportType = exportType;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public List<String> getClientIds() {
        return clientIds;
    }

    public void setClientIds(List<String> clientIds) {
        this.clientIds = clientIds;
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

    public Map<String, Object> getFilters() {
        return filters;
    }

    public void setFilters(Map<String, Object> filters) {
        this.filters = filters;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public Map<String, Object> getTemplateParameters() {
        return templateParameters;
    }

    public void setTemplateParameters(Map<String, Object> templateParameters) {
        this.templateParameters = templateParameters;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getIncludeCharts() {
        return includeCharts;
    }

    public void setIncludeCharts(Boolean includeCharts) {
        this.includeCharts = includeCharts;
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

    public Boolean getIncludeMetadata() {
        return includeMetadata;
    }

    public void setIncludeMetadata(Boolean includeMetadata) {
        this.includeMetadata = includeMetadata;
    }

    public String getAggregationLevel() {
        return aggregationLevel;
    }

    public void setAggregationLevel(String aggregationLevel) {
        this.aggregationLevel = aggregationLevel;
    }

    public List<String> getGroupBy() {
        return groupBy;
    }

    public void setGroupBy(List<String> groupBy) {
        this.groupBy = groupBy;
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

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public String getCompressionType() {
        return compressionType;
    }

    public void setCompressionType(String compressionType) {
        this.compressionType = compressionType;
    }

    public Boolean getAsyncExport() {
        return asyncExport;
    }

    public void setAsyncExport(Boolean asyncExport) {
        this.asyncExport = asyncExport;
    }

    public String getNotificationEmail() {
        return notificationEmail;
    }

    public void setNotificationEmail(String notificationEmail) {
        this.notificationEmail = notificationEmail;
    }

    public Map<String, Object> getCustomOptions() {
        return customOptions;
    }

    public void setCustomOptions(Map<String, Object> customOptions) {
        this.customOptions = customOptions;
    }

    public String getReportType() {
        return reportType;
    }

    public void setReportType(String reportType) {
        this.reportType = reportType;
    }

    public List<String> getSections() {
        return sections;
    }

    public void setSections(List<String> sections) {
        this.sections = sections;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public Map<String, String> getBranding() {
        return branding;
    }

    public void setBranding(Map<String, String> branding) {
        this.branding = branding;
    }

    public String getDashboardId() {
        return dashboardId;
    }

    public void setDashboardId(String dashboardId) {
        this.dashboardId = dashboardId;
    }

    public List<String> getWidgetIds() {
        return widgetIds;
    }

    public void setWidgetIds(List<String> widgetIds) {
        this.widgetIds = widgetIds;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public Boolean getIncludeInteractiveElements() {
        return includeInteractiveElements;
    }

    public void setIncludeInteractiveElements(Boolean includeInteractiveElements) {
        this.includeInteractiveElements = includeInteractiveElements;
    }

    // Validation methods
    public boolean isValidTimeRange() {
        return startTime != null && endTime != null && startTime.isBefore(endTime);
    }

    public boolean isValidFormat() {
        return format != null &&
                List.of("csv", "json", "excel", "pdf", "png", "html", "xml").contains(format.toLowerCase());
    }

    public boolean isValidExportType() {
        return exportType != null &&
                List.of("metrics", "report", "dashboard", "audit", "configuration", "benchmark")
                        .contains(exportType.toLowerCase());
    }

    public boolean hasFilters() {
        return filters != null && !filters.isEmpty();
    }

    public boolean hasTemplate() {
        return templateId != null && !templateId.isBlank();
    }

    public boolean isAsyncRequired() {
        return asyncExport != null && asyncExport;
    }

    public boolean hasCustomBranding() {
        return branding != null && !branding.isEmpty();
    }

    public boolean includesCharts() {
        return includeCharts != null && includeCharts;
    }

    public boolean requiresCompression() {
        return compressionType != null && !"none".equalsIgnoreCase(compressionType);
    }

    public boolean hasMultipleClients() {
        return clientIds != null && clientIds.size() > 1;
    }

    public boolean hasCustomOptions() {
        return customOptions != null && !customOptions.isEmpty();
    }

    public boolean isReportExport() {
        return "report".equalsIgnoreCase(exportType);
    }

    public boolean isDashboardExport() {
        return "dashboard".equalsIgnoreCase(exportType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ExportRequestDTO that = (ExportRequestDTO) o;
        return Objects.equals(exportType, that.exportType) &&
                Objects.equals(format, that.format) &&
                Objects.equals(startTime, that.startTime) &&
                Objects.equals(endTime, that.endTime) &&
                Objects.equals(clientId, that.clientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exportType, format, startTime, endTime, clientId);
    }

    @Override
    public String toString() {
        return "ExportRequestDTO{" +
                "exportType='" + exportType + '\'' +
                ", format='" + format + '\'' +
                ", clientId='" + clientId + '\'' +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                ", asyncExport=" + asyncExport +
                ", includeCharts=" + includeCharts +
                ", includeStatistics=" + includeStatistics +
                '}';
    }
}