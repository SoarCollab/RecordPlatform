package cn.flying.monitor.data.service;

import cn.flying.monitor.data.dto.ExportRequestDTO;
import cn.flying.monitor.data.dto.ExportResultDTO;
import cn.flying.monitor.data.dto.QueryRequestDTO;
import cn.flying.monitor.data.dto.QueryResultDTO;
import cn.flying.monitor.data.service.export.ExportDownload;
import cn.flying.monitor.data.service.export.ExportTaskManager;
import cn.flying.monitor.data.service.export.ExportTaskRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Service for handling data export operations
 */
@Service
public class DataExportService {

    private static final Duration DEFAULT_EXPIRATION = Duration.ofHours(24);

    private final QueryService queryService;
    private final ExportTaskManager taskManager;
    private final ObjectMapper objectMapper;

    public DataExportService(QueryService queryService,
                             ExportTaskManager taskManager,
                             ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.taskManager = taskManager;
        this.objectMapper = objectMapper;
    }

    public List<String> validateExportRequest(ExportRequestDTO request) {
        List<String> errors = new java.util.ArrayList<>();

        if (request.getExportType() == null || request.getExportType().trim().isEmpty()) {
            errors.add("Export type is required");
        }

        if (request.getFormat() == null || request.getFormat().trim().isEmpty()) {
            errors.add("Export format is required");
        } else if (!request.isValidFormat()) {
            errors.add("Unsupported export format: " + request.getFormat());
        }

        if (!request.isValidTimeRange()) {
            errors.add("Invalid time range: start time must be before end time");
        }

        boolean hasClient = (request.getClientId() != null && !request.getClientId().isBlank())
                || (request.getClientIds() != null && !request.getClientIds().isEmpty());
        if (!hasClient) {
            errors.add("At least one clientId is required for export");
        }

        if (request.isAsyncRequired()
                && (request.getNotificationEmail() == null || request.getNotificationEmail().trim().isEmpty())) {
            errors.add("Notification email is required for async exports");
        }

        return errors;
    }

    public ExportResultDTO exportDataSync(ExportRequestDTO request) {
        ExportResultDTO result = createBaseResult(request, false);
        ExportTaskRecord record = taskManager.createTask(result);
        record.markInProgress();

        try {
            List<QueryResultDTO> data = fetchDataForExport(request);
            byte[] exportedData = generateExportData(request, data);

            populateResultMetrics(result, request, data, exportedData);
            record.complete(exportedData);
            result.setDownloadUrl(buildDownloadUrl(result.getExportId()));
            result.setExpiresAt(Instant.now().plus(DEFAULT_EXPIRATION));
            return result;
        } catch (Exception e) {
            record.fail(e.getMessage());
            throw new IllegalStateException("Export failed: " + e.getMessage(), e);
        }
    }

    public ExportResultDTO exportDataAsync(ExportRequestDTO request) {
        ExportResultDTO result = createBaseResult(request, true);
        ExportTaskRecord record = taskManager.createTask(result);
        record.markInProgress();
        result.setStatus("IN_PROGRESS");
        result.setProgressPercentage(5.0);
        result.setDownloadUrl(buildDownloadUrl(result.getExportId()));

        CompletableFuture.runAsync(() -> processAsyncExport(record, request));
        return result;
    }

    public Optional<ExportResultDTO> getExportStatus(String exportId) {
        return taskManager.findTask(exportId).map(ExportTaskRecord::getResult);
    }

    public cn.flying.monitor.data.service.export.CancelStatus cancelExport(String exportId) {
        Optional<ExportTaskRecord> recordOpt = taskManager.findTask(exportId);
        if (recordOpt.isEmpty()) {
            return cn.flying.monitor.data.service.export.CancelStatus.NOT_FOUND;
        }
        ExportTaskRecord record = recordOpt.get();
        synchronized (record) {
            String status = record.getResult().getStatus();
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return cn.flying.monitor.data.service.export.CancelStatus.NOT_ALLOWED;
            }
            record.cancel();
            return cn.flying.monitor.data.service.export.CancelStatus.CANCELLED;
        }
    }

    public cn.flying.monitor.data.service.export.PagedExports listExports(int page, int size, String statusFilter, String typeFilter) {
        List<ExportResultDTO> records = taskManager.listTasks().stream()
                .map(ExportTaskRecord::getResult)
                .filter(result -> statusFilter == null || statusFilter.equalsIgnoreCase(result.getStatus()))
                .filter(result -> typeFilter == null || typeFilter.equalsIgnoreCase(result.getExportType()))
                .toList();

        if (size <= 0) {
            size = 20;
        }
        if (page < 0) {
            page = 0;
        }
        int fromIndex = Math.min(page * size, records.size());
        int toIndex = Math.min(fromIndex + size, records.size());
        List<ExportResultDTO> pageContent = records.subList(fromIndex, toIndex);
        return new cn.flying.monitor.data.service.export.PagedExports(pageContent, records.size());
    }

    public Optional<ExportDownload> loadExport(String exportId) {
        return taskManager.findTask(exportId)
                .flatMap(record -> {
                    byte[] data = record.getContent();
                    if (data == null) {
                        return Optional.empty();
                    }
                    ExportResultDTO result = record.getResult();
                    return Optional.of(new ExportDownload(data, result.getFileName(), result.getMimeType()));
                });
    }

    private void processAsyncExport(ExportTaskRecord record, ExportRequestDTO request) {
        try {
            if (isCancelled(record)) {
                return;
            }
            record.updateProgress(20.0);
            if (isCancelled(record)) {
                return;
            }
            List<QueryResultDTO> data = fetchDataForExport(request);
            record.updateProgress(60.0);
            if (isCancelled(record)) {
                return;
            }
            byte[] exportedData = generateExportData(request, data);
            ExportResultDTO result = record.getResult();
            populateResultMetrics(result, request, data, exportedData);
            if (isCancelled(record)) {
                return;
            }
            record.complete(exportedData);
            result.setDownloadUrl(buildDownloadUrl(result.getExportId()));
            result.setExpiresAt(Instant.now().plus(DEFAULT_EXPIRATION));
        } catch (Exception e) {
            record.fail(e.getMessage());
        }
    }

    private ExportResultDTO createBaseResult(ExportRequestDTO request, boolean async) {
        ExportResultDTO result = new ExportResultDTO(UUID.randomUUID().toString(), request.getExportType(), request.getFormat());
        result.setOriginalRequest(request);
        result.setIsAsync(async);
        result.setCreatedAt(Instant.now());
        return result;
    }

    private void populateResultMetrics(ExportResultDTO result, ExportRequestDTO request,
                                       List<QueryResultDTO> data, byte[] exportedData) {
        result.setRecordCount(data.size());
        result.setFileSizeBytes((long) exportedData.length);
        result.setFileName(generateFileName(request));
        result.setProgressPercentage(100.0);
        result.setCompressionType(request.getCompressionType());
    }

    private List<QueryResultDTO> fetchDataForExport(ExportRequestDTO request) {
        List<QueryResultDTO> data = new ArrayList<>();

        List<String> targetClientIds = new ArrayList<>();
        if (request.getClientIds() != null && !request.getClientIds().isEmpty()) {
            targetClientIds.addAll(request.getClientIds());
        }
        if (request.getClientId() != null && !request.getClientId().isBlank()) {
            targetClientIds.add(request.getClientId());
        }
        if (targetClientIds.isEmpty()) {
            throw new IllegalArgumentException("ClientId is required");
        }

        List<String> uniqueClientIds = targetClientIds.stream().distinct().toList();

        if (uniqueClientIds.size() > 1 && request.getMetricNames() != null && !request.getMetricNames().isEmpty()) {
            Map<String, List<QueryResultDTO>> comparison = queryService.compareClientsMetrics(
                    uniqueClientIds,
                    request.getMetricNames(),
                    request.getStartTime(),
                    request.getEndTime());
            data.addAll(comparison.values().stream().flatMap(List::stream).toList());
        } else {
            for (String clientId : uniqueClientIds) {
                QueryRequestDTO query = buildQueryRequest(request, clientId);
                data.addAll(queryService.queryMetricsWithFilters(query));
            }
        }

        if (request.getLimit() != null && request.getLimit() > 0 && data.size() > request.getLimit()) {
            return data.subList(0, request.getLimit());
        }
        return data;
    }

    private QueryRequestDTO buildQueryRequest(ExportRequestDTO request, String clientId) {
        QueryRequestDTO query = new QueryRequestDTO();
        query.setClientId(clientId);
        query.setStartTime(request.getStartTime());
        query.setEndTime(request.getEndTime());
        query.setMetricNames(request.getMetricNames());
        query.setFilters(request.getFilters());
        query.setGroupBy(request.getGroupBy());
        query.setSortBy(request.getSortBy());
        query.setSortOrder(request.getSortOrder());
        query.setLimit(request.getLimit());
        query.setIncludeMetadata(request.getIncludeMetadata());
        query.setIncludeQualityMetrics(request.getIncludeStatistics());
        String timeWindow = null;
        if (request.getAggregationLevel() != null) {
            timeWindow = mapAggregationLevel(request.getAggregationLevel());
        }
        if (timeWindow != null) {
            query.setTimeWindow(timeWindow);
        }
        return query;
    }

    private String mapAggregationLevel(String aggregationLevel) {
        return switch (aggregationLevel.toLowerCase()) {
            case "raw" -> null;
            case "hourly" -> "1h";
            case "daily" -> "1d";
            case "weekly" -> "1w";
            case "monthly" -> "30d";
            default -> aggregationLevel;
        };
    }

    private boolean isCancelled(ExportTaskRecord record) {
        return "CANCELLED".equals(record.getResult().getStatus());
    }

    private byte[] generateExportData(ExportRequestDTO request, List<QueryResultDTO> data) throws IOException {
        String format = request.getFormat().toLowerCase();
        return switch (format) {
            case "csv" -> generateCsvData(request, data);
            case "json" -> generateJsonData(request, data);
            case "excel", "xlsx" -> generateExcelData(request, data);
            default -> throw new IllegalArgumentException("Unsupported export format: " + request.getFormat());
        };
    }

    private byte[] generateCsvData(ExportRequestDTO request, List<QueryResultDTO> data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            if (data.isEmpty()) {
                writer.println(QueryResultDTO.getCsvHeader());
            } else {
                QueryResultDTO firstRecord = data.get(0);
                Map<String, Object> flatMap = firstRecord.toFlatMap();
                writer.println(String.join(",", flatMap.keySet()));
                for (QueryResultDTO record : data) {
                    writer.println(record.toCsvRow());
                }
            }
        }
        return baos.toByteArray();
    }

    private byte[] generateJsonData(ExportRequestDTO request, List<QueryResultDTO> data) throws IOException {
        Map<String, Object> payload = Map.of(
                "exportInfo", Map.of(
                        "exportType", request.getExportType(),
                        "format", request.getFormat(),
                        "exportedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                        "recordCount", data.size()
                ),
                "data", data.stream().map(QueryResultDTO::toFlatMap).collect(Collectors.toList())
        );
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
    }

    private byte[] generateExcelData(ExportRequestDTO request, List<QueryResultDTO> data) throws IOException {
        return generateCsvData(request, data);
    }

    private String generateFileName(ExportRequestDTO request) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .format(Instant.now().atZone(java.time.ZoneOffset.UTC));

        String baseName = request.getExportType() + "_export_" + timestamp;
        String extension = getFileExtension(request.getFormat());

        return baseName + '.' + extension;
    }

    private String getFileExtension(String format) {
        return switch (format.toLowerCase()) {
            case "csv" -> "csv";
            case "json" -> "json";
            case "excel", "xlsx" -> "xlsx";
            case "pdf" -> "pdf";
            case "png" -> "png";
            case "html" -> "html";
            case "xml" -> "xml";
            default -> "dat";
        };
    }

    private String buildDownloadUrl(String exportId) {
        return "/api/v1/exports/" + exportId + "/download";
    }
}
