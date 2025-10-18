package cn.flying.monitor.data.service;

import cn.flying.monitor.data.dto.ExportRequestDTO;
import cn.flying.monitor.data.dto.ExportResultDTO;
import cn.flying.monitor.data.dto.QueryResultDTO;
import cn.flying.monitor.data.service.export.CancelStatus;
import cn.flying.monitor.data.service.export.ExportDownload;
import cn.flying.monitor.data.service.export.ExportTaskManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DataExportService}
 */
@ExtendWith(MockitoExtension.class)
class DataExportServiceTest {

    @Mock
    private QueryService queryService;

    private ExportTaskManager taskManager;

    @InjectMocks
    private DataExportService dataExportService;

    @BeforeEach
    void setUp() {
        taskManager = new ExportTaskManager(24);
        dataExportService = new DataExportService(queryService, taskManager, new ObjectMapper());
    }

    @Test
    void exportDataSync_shouldPersistTaskAndReturnCompletedResult() {
        when(queryService.queryMetricsWithFilters(any())).thenReturn(buildResults());
        ExportRequestDTO request = buildRequest();

        ExportResultDTO result = dataExportService.exportDataSync(request);

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(1, result.getRecordCount());
        assertNotNull(result.getDownloadUrl());

        Optional<ExportDownload> download = dataExportService.loadExport(result.getExportId());
        assertTrue(download.isPresent());
        assertTrue(download.get().data().length > 0);
    }

    @Test
    void exportDataAsync_shouldCompleteInBackground() throws InterruptedException {
        when(queryService.queryMetricsWithFilters(any())).thenReturn(buildResults());
        ExportRequestDTO request = buildRequest();
        request.setAsyncExport(true);
        request.setNotificationEmail("ops@example.com");

        ExportResultDTO asyncResult = dataExportService.exportDataAsync(request);
        assertEquals("IN_PROGRESS", asyncResult.getStatus());

        ExportResultDTO completed = awaitStatus(asyncResult.getExportId(), 10);
        assertNotNull(completed);
        assertEquals("COMPLETED", completed.getStatus());
        Optional<ExportDownload> download = dataExportService.loadExport(asyncResult.getExportId());
        assertTrue(download.isPresent());
    }

    @Test
    void cancelExport_shouldReturnProperStatus() throws InterruptedException {
        doAnswer(invocation -> {
            Thread.sleep(200);
            return buildResults();
        }).when(queryService).queryMetricsWithFilters(any());

        ExportRequestDTO request = buildRequest();
        request.setAsyncExport(true);
        request.setNotificationEmail("ops@example.com");

        ExportResultDTO asyncResult = dataExportService.exportDataAsync(request);
        CancelStatus status = dataExportService.cancelExport(asyncResult.getExportId());
        assertEquals(CancelStatus.CANCELLED, status);

        ExportResultDTO finalStatus = awaitStatus(asyncResult.getExportId(), 10);
        assertNotNull(finalStatus);
        assertEquals("CANCELLED", finalStatus.getStatus());
    }

    private ExportRequestDTO buildRequest() {
        ExportRequestDTO request = new ExportRequestDTO();
        request.setExportType("metrics");
        request.setFormat("csv");
        request.setClientId("client-1");
        request.setStartTime(Instant.now().minusSeconds(3600));
        request.setEndTime(Instant.now());
        request.setMetricNames(List.of("cpu_usage"));
        return request;
    }

    private List<QueryResultDTO> buildResults() {
        QueryResultDTO dto = new QueryResultDTO();
        dto.setClientId("client-1");
        dto.setTimestamp(Instant.now());
        dto.setMetrics(java.util.Map.of("cpu_usage", 50.0));
        dto.setRecordCount(1L);
        return List.of(dto);
    }

    private ExportResultDTO awaitStatus(String exportId, int attempts) throws InterruptedException {
        for (int i = 0; i < attempts; i++) {
            Optional<ExportResultDTO> status = dataExportService.getExportStatus(exportId);
            if (status.isPresent() && !"IN_PROGRESS".equals(status.get().getStatus())) {
                return status.get();
            }
            Thread.sleep(100);
        }
        return null;
    }
}
