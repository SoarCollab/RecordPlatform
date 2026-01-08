package cn.flying.service.impl;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.audit.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SysAuditServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
class SysAuditServiceImplTest {

    @Mock
    private SysOperationLogMapper operationLogMapper;

    @InjectMocks
    private SysAuditServiceImpl sysAuditService;

    private AuditLogQueryVO defaultQuery;

    @BeforeEach
    void setUp() {
        defaultQuery = new AuditLogQueryVO();
        defaultQuery.setPageNum(1);
        defaultQuery.setPageSize(10);
    }

    @Nested
    @DisplayName("queryOperationLogs Tests")
    class QueryOperationLogsTests {

        @Test
        @DisplayName("should query with user ID filter")
        void shouldQueryWithUserIdFilter() {
            defaultQuery.setUserId("123");
            Page<SysOperationLog> mockPage = new Page<>();
            mockPage.setRecords(List.of(createOperationLog(1L)));
            when(operationLogMapper.selectPage(any(), any())).thenReturn(mockPage);

            IPage<SysOperationLog> result = sysAuditService.queryOperationLogs(defaultQuery);

            assertThat(result).isNotNull();
            assertThat(result.getRecords()).hasSize(1);
            verify(operationLogMapper).selectPage(any(), any());
        }

        @Test
        @DisplayName("should query with username like filter")
        void shouldQueryWithUsernameLikeFilter() {
            defaultQuery.setUsername("admin");
            Page<SysOperationLog> mockPage = new Page<>();
            when(operationLogMapper.selectPage(any(), any())).thenReturn(mockPage);

            IPage<SysOperationLog> result = sysAuditService.queryOperationLogs(defaultQuery);

            assertThat(result).isNotNull();
            verify(operationLogMapper).selectPage(any(), any());
        }

        @Test
        @DisplayName("should query with time range filter")
        void shouldQueryWithTimeRangeFilter() {
            defaultQuery.setStartTime("2024-01-01 00:00:00");
            defaultQuery.setEndTime("2024-01-31 23:59:59");
            Page<SysOperationLog> mockPage = new Page<>();
            when(operationLogMapper.selectPage(any(), any())).thenReturn(mockPage);

            IPage<SysOperationLog> result = sysAuditService.queryOperationLogs(defaultQuery);

            assertThat(result).isNotNull();
            verify(operationLogMapper).selectPage(any(), any());
        }

        @Test
        @DisplayName("should query with status filter")
        void shouldQueryWithStatusFilter() {
            defaultQuery.setStatus(1);
            Page<SysOperationLog> mockPage = new Page<>();
            when(operationLogMapper.selectPage(any(), any())).thenReturn(mockPage);

            IPage<SysOperationLog> result = sysAuditService.queryOperationLogs(defaultQuery);

            assertThat(result).isNotNull();
            verify(operationLogMapper).selectPage(any(), any());
        }
    }

    @Nested
    @DisplayName("getHighFrequencyOperations Tests")
    class GetHighFrequencyOperationsTests {

        @Test
        @DisplayName("should return high frequency operations list")
        void shouldReturnHighFrequencyOperations() {
            HighFrequencyOperationVO operation = new HighFrequencyOperationVO();
            operation.setUserId("user1");
            operation.setUsername("testuser");
            operation.setOperationCount(100);
            when(operationLogMapper.selectHighFrequencyOperations()).thenReturn(List.of(operation));

            List<HighFrequencyOperationVO> result = sysAuditService.getHighFrequencyOperations();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo("user1");
            assertThat(result.get(0).getOperationCount()).isEqualTo(100);
        }

        @Test
        @DisplayName("should return empty list when no high frequency operations")
        void shouldReturnEmptyListWhenNoOperations() {
            when(operationLogMapper.selectHighFrequencyOperations()).thenReturn(List.of());

            List<HighFrequencyOperationVO> result = sysAuditService.getHighFrequencyOperations();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getSensitiveOperations Tests")
    class GetSensitiveOperationsTests {

        @Test
        @DisplayName("should return sensitive operations with pagination")
        void shouldReturnSensitiveOperationsWithPagination() {
            SysOperationLog log = createOperationLog(1L);
            when(operationLogMapper.selectSensitiveOperations(
                    any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of(log));
            when(operationLogMapper.countSensitiveOperations(
                    any(), any(), any(), any(), any(), any()))
                    .thenReturn(1L);

            IPage<SysOperationLog> result = sysAuditService.getSensitiveOperations(defaultQuery);

            assertThat(result).isNotNull();
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getTotal()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("getErrorOperationStats Tests")
    class GetErrorOperationStatsTests {

        @Test
        @DisplayName("should return error operation statistics")
        void shouldReturnErrorOperationStats() {
            ErrorOperationStatsVO stats = new ErrorOperationStatsVO();
            stats.setModule("file");
            stats.setErrorCount(10);
            when(operationLogMapper.selectErrorOperationStats()).thenReturn(List.of(stats));

            List<ErrorOperationStatsVO> result = sysAuditService.getErrorOperationStats();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getModule()).isEqualTo("file");
            assertThat(result.get(0).getErrorCount()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("getUserTimeDistribution Tests")
    class GetUserTimeDistributionTests {

        @Test
        @DisplayName("should return user time distribution data")
        void shouldReturnUserTimeDistribution() {
            UserTimeDistributionVO dist = new UserTimeDistributionVO();
            dist.setHourOfDay(9);
            dist.setOperationCount(50);
            when(operationLogMapper.selectUserTimeDistribution()).thenReturn(List.of(dist));

            List<UserTimeDistributionVO> result = sysAuditService.getUserTimeDistribution();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getHourOfDay()).isEqualTo(9);
            assertThat(result.get(0).getOperationCount()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("getAuditConfigs Tests")
    class GetAuditConfigsTests {

        @Test
        @DisplayName("should return audit configs list")
        void shouldReturnAuditConfigs() {
            AuditConfigVO config = new AuditConfigVO();
            config.setConfigKey("AUDIT_ENABLED");
            config.setConfigValue("true");
            when(operationLogMapper.selectAuditConfigs()).thenReturn(List.of(config));

            List<AuditConfigVO> result = sysAuditService.getAuditConfigs();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getConfigKey()).isEqualTo("AUDIT_ENABLED");
        }
    }

    @Nested
    @DisplayName("updateAuditConfig Tests")
    class UpdateAuditConfigTests {

        @Test
        @DisplayName("should return true when update succeeds")
        void shouldReturnTrueWhenUpdateSucceeds() {
            AuditConfigVO config = new AuditConfigVO();
            config.setConfigKey("AUDIT_ENABLED");
            config.setConfigValue("false");
            config.setDescription("Enable audit");
            when(operationLogMapper.updateAuditConfig(anyString(), anyString(), anyString())).thenReturn(1);

            boolean result = sysAuditService.updateAuditConfig(config);

            assertThat(result).isTrue();
            verify(operationLogMapper).updateAuditConfig("AUDIT_ENABLED", "false", "Enable audit");
        }

        @Test
        @DisplayName("should return false when update fails")
        void shouldReturnFalseWhenUpdateFails() {
            AuditConfigVO config = new AuditConfigVO();
            config.setConfigKey("NONEXISTENT");
            when(operationLogMapper.updateAuditConfig(anyString(), any(), any())).thenReturn(0);

            boolean result = sysAuditService.updateAuditConfig(config);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("getLogDetail Tests")
    class GetLogDetailTests {

        @Test
        @DisplayName("should return log detail by ID")
        void shouldReturnLogDetailById() {
            SysOperationLog log = createOperationLog(1L);
            when(operationLogMapper.selectById(1L)).thenReturn(log);

            SysOperationLog result = sysAuditService.getLogDetail(1L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should return null when log not found")
        void shouldReturnNullWhenNotFound() {
            when(operationLogMapper.selectById(999L)).thenReturn(null);

            SysOperationLog result = sysAuditService.getLogDetail(999L);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("exportOperationLogs Tests")
    class ExportOperationLogsTests {

        @Test
        @DisplayName("should export logs with limit")
        void shouldExportLogsWithLimit() {
            when(operationLogMapper.selectList(any())).thenReturn(List.of(createOperationLog(1L)));

            List<SysOperationLog> result = sysAuditService.exportOperationLogs(defaultQuery);

            assertThat(result).hasSize(1);
            verify(operationLogMapper).selectList(any());
        }
    }

    @Nested
    @DisplayName("getAuditOverview Tests")
    class GetAuditOverviewTests {

        @Test
        @DisplayName("should return audit overview with all metrics")
        void shouldReturnAuditOverviewWithAllMetrics() {
            when(operationLogMapper.selectTotalOperations()).thenReturn(1000L);
            when(operationLogMapper.selectOperationsBetween(any(), any())).thenReturn(50L);
            when(operationLogMapper.selectTotalErrorOperations()).thenReturn(10L);
            when(operationLogMapper.selectErrorOperationsBetween(any(), any())).thenReturn(2L);
            when(operationLogMapper.selectSensitiveOperationsCountBetween(any(), any())).thenReturn(5L);
            when(operationLogMapper.selectActiveUsersBetween(any(), any())).thenReturn(20L);
            when(operationLogMapper.selectHighFrequencyAlertCount()).thenReturn(3L);
            when(operationLogMapper.selectDailyStats(anyInt())).thenReturn(List.of());
            AuditConfigVO auditEnabledConfig = new AuditConfigVO();
            auditEnabledConfig.setConfigValue("true");
            when(operationLogMapper.selectAuditConfigByKey("AUDIT_ENABLED")).thenReturn(auditEnabledConfig);
            AuditConfigVO retentionConfig = new AuditConfigVO();
            retentionConfig.setConfigValue("180");
            when(operationLogMapper.selectAuditConfigByKey("LOG_RETENTION_DAYS")).thenReturn(retentionConfig);

            Map<String, Object> result = sysAuditService.getAuditOverview();

            assertThat(result).containsKey("totalOperations");
            assertThat(result.get("totalOperations")).isEqualTo(1000L);
            assertThat(result).containsKey("todayOperations");
            assertThat(result).containsKey("auditEnabled");
        }

        @Test
        @DisplayName("should handle overview errors gracefully")
        void shouldHandleOverviewErrorsGracefully() {
            when(operationLogMapper.selectTotalOperations()).thenThrow(new RuntimeException("DB error"));

            Map<String, Object> result = sysAuditService.getAuditOverview();

            assertThat(result).containsKey("error");
        }
    }

    @Nested
    @DisplayName("checkAnomalies Tests")
    class CheckAnomaliesTests {

        @Test
        @DisplayName("should return anomaly check results")
        void shouldReturnAnomalyCheckResults() {
            Map<String, Object> procedureResult = new HashMap<>();
            procedureResult.put("hasAnomalies", true);
            procedureResult.put("anomalyDetails", "High login failures detected");
            when(operationLogMapper.checkAnomalies()).thenReturn(procedureResult);

            Map<String, Object> result = sysAuditService.checkAnomalies();

            assertThat(result).containsKey("hasAnomalies");
            assertThat(result.get("hasAnomalies")).isEqualTo(true);
            assertThat(result).containsKey("success");
            assertThat(result.get("success")).isEqualTo(true);
        }

        @Test
        @DisplayName("should handle anomaly check failure")
        void shouldHandleAnomalyCheckFailure() {
            when(operationLogMapper.checkAnomalies()).thenThrow(new RuntimeException("Procedure error"));

            Map<String, Object> result = sysAuditService.checkAnomalies();

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result).containsKey("error");
        }
    }

    @Nested
    @DisplayName("backupLogs Tests")
    class BackupLogsTests {

        @Test
        @DisplayName("should return success message on backup")
        void shouldReturnSuccessMessageOnBackup() {
            doNothing().when(operationLogMapper).backupLogs(anyInt(), anyBoolean());

            String result = sysAuditService.backupLogs(30, false);

            assertThat(result).contains("成功备份30天前的日志");
            assertThat(result).doesNotContain("清理");
        }

        @Test
        @DisplayName("should return success message with cleanup")
        void shouldReturnSuccessMessageWithCleanup() {
            doNothing().when(operationLogMapper).backupLogs(anyInt(), anyBoolean());

            String result = sysAuditService.backupLogs(30, true);

            assertThat(result).contains("清理原表数据");
        }

        @Test
        @DisplayName("should return error message on backup failure")
        void shouldReturnErrorMessageOnFailure() {
            doThrow(new RuntimeException("Backup error")).when(operationLogMapper).backupLogs(anyInt(), anyBoolean());

            String result = sysAuditService.backupLogs(30, false);

            assertThat(result).contains("备份失败");
        }
    }

    private SysOperationLog createOperationLog(Long id) {
        SysOperationLog log = new SysOperationLog();
        log.setId(id);
        log.setUserId("1");
        log.setUsername("testuser");
        log.setModule("file");
        log.setOperationType("UPLOAD");
        log.setStatus(1);
        log.setOperationTime(LocalDateTime.now());
        return log;
    }
}
