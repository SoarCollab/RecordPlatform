package cn.flying.monitor.data.controller;

import cn.flying.monitor.common.entity.Result;
import cn.flying.monitor.data.dto.ExportRequestDTO;
import cn.flying.monitor.data.dto.ExportResultDTO;
import cn.flying.monitor.data.dto.ExportListResponse;
import cn.flying.monitor.data.dto.PaginationInfo;
import cn.flying.monitor.data.service.DataExportService;
import cn.flying.monitor.data.service.export.PagedExports;
import cn.flying.monitor.data.service.export.ExportDownload;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for data export operations with comprehensive API response handling
 */
@RestController
@RequestMapping("/api/v1/exports")
public class DataExportController {

    private final DataExportService dataExportService;

    public DataExportController(DataExportService dataExportService) {
        this.dataExportService = dataExportService;
    }

    @PostMapping("/sync")
    public ResponseEntity<Result<ExportResultDTO>> exportDataSync(
            @Valid @RequestBody ExportRequestDTO request) {

        List<String> validationErrors = dataExportService.validateExportRequest(request);
        if (!validationErrors.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Result.error(String.join("; ", validationErrors)));
        }

        try {
            ExportResultDTO result = dataExportService.exportDataSync(request);
            return ResponseEntity.ok(Result.success(result, "导出完成"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.error("Failed to export data: " + e.getMessage()));
        }
    }

    @PostMapping("/async")
    public ResponseEntity<Result<ExportResultDTO>> exportDataAsync(
            @Valid @RequestBody ExportRequestDTO request) {

        List<String> validationErrors = dataExportService.validateExportRequest(request);
        if (!validationErrors.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Result.error(String.join("; ", validationErrors)));
        }

        try {
            ExportResultDTO result = dataExportService.exportDataAsync(request);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Result.success(result, "导出任务已启动"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.error("Failed to start async export: " + e.getMessage()));
        }
    }

    @GetMapping("/{exportId}/status")
    public ResponseEntity<Result<ExportResultDTO>> getExportStatus(
            @PathVariable String exportId) {

        return dataExportService.getExportStatus(exportId)
                .map(result -> ResponseEntity.ok(Result.success(result, "获取成功")))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Result.error("Export with ID " + exportId + " not found")));
    }

    @DeleteMapping("/{exportId}")
    public ResponseEntity<Result<Void>> cancelExport(@PathVariable String exportId) {
        cn.flying.monitor.data.service.export.CancelStatus status = dataExportService.cancelExport(exportId);
        return switch (status) {
            case CANCELLED -> ResponseEntity.ok(Result.success(null, "导出任务已取消"));
            case NOT_ALLOWED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error("导出任务已完成或失败，无法取消"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error("导出任务不存在"));
        };
    }

    @GetMapping
    public ResponseEntity<Result<List<ExportResultDTO>>> listExports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String exportType) {

        if (page < 0) {
            return ResponseEntity.badRequest()
                    .body(Result.error("Page number must be non-negative"));
        }
        if (size <= 0 || size > 100) {
            return ResponseEntity.badRequest()
                    .body(Result.error("Page size must be between 1 and 100"));
        }

        PagedExports paged = dataExportService.listExports(page, size, status, exportType);
        PaginationInfo pagination = new PaginationInfo(page, size, paged.total());
        ExportListResponse responseBody = new ExportListResponse(paged.items(), pagination);
        return ResponseEntity.ok(Result.success(responseBody.items(), "获取成功"));
    }

    @GetMapping("/{exportId}/download")
    public ResponseEntity<ByteArrayResource> downloadExport(@PathVariable String exportId) {
        return dataExportService.loadExport(exportId)
                .map(download -> {
                    ByteArrayResource resource = new ByteArrayResource(download.data());
                    MediaType mediaType = download.mimeType() != null ? MediaType.parseMediaType(download.mimeType()) : MediaType.APPLICATION_OCTET_STREAM;
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + download.fileName())
                            .contentLength(download.data().length)
                            .contentType(mediaType)
                            .body(resource);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
