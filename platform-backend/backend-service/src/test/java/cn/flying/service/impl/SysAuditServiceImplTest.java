package cn.flying.service.impl;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.audit.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysAuditServiceImplTest {

    @InjectMocks
    private SysAuditServiceImpl sysAuditService;

    @Mock
    private SysOperationLogMapper operationLogMapper;

    @Test
    void queryOperationLogs_returnsPageFromMapper() {
        AuditLogQueryVO queryVO = new AuditLogQueryVO();
        queryVO.setPageNum(2);
        queryVO.setPageSize(5);
        queryVO.setUserId("42");
        SysOperationLog log = new SysOperationLog();
        Page<SysOperationLog> mapperPage = new Page<>(2, 5);
        mapperPage.setRecords(List.of(log));

        when(operationLogMapper.selectPage(any(), any())).thenReturn(mapperPage);

        IPage<SysOperationLog> result = sysAuditService.queryOperationLogs(queryVO);

        assertThat(result.getRecords()).containsExactly(log);
        assertEquals(2, result.getCurrent());
        verify(operationLogMapper).selectPage(any(), any());
    }

    @Test
    void getSensitiveOperations_combinesRecordsAndTotal() {
        AuditLogQueryVO queryVO = new AuditLogQueryVO();
        queryVO.setPageNum(1);
        queryVO.setPageSize(10);
        SysOperationLog record = new SysOperationLog();

        when(operationLogMapper.selectSensitiveOperations(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(record));
        when(operationLogMapper.countSensitiveOperations(any(), any(), any(), any(), any(), any()))
                .thenReturn(20L);

        IPage<SysOperationLog> result = sysAuditService.getSensitiveOperations(queryVO);

        assertThat(result.getRecords()).containsExactly(record);
        assertEquals(20, result.getTotal());
        verify(operationLogMapper).selectSensitiveOperations(any(), any(), any(), any(), any(), any(), eq(10), eq(0));
        verify(operationLogMapper).countSensitiveOperations(any(), any(), any(), any(), any(), any());
    }

    @Test
    void getAuditOverview_populatesAllMetrics() {
        when(operationLogMapper.selectTotalOperations()).thenReturn(100L);
        when(operationLogMapper.selectOperationsBetween(any(), any())).thenReturn(12L);
        when(operationLogMapper.selectTotalErrorOperations()).thenReturn(8L);
        when(operationLogMapper.selectErrorOperationsBetween(any(), any())).thenReturn(2L);
        when(operationLogMapper.selectSensitiveOperationsCountBetween(any(), any())).thenReturn(3L);
        when(operationLogMapper.selectActiveUsersBetween(any(), any())).thenReturn(5L);
        when(operationLogMapper.selectHighFrequencyAlertCount()).thenReturn(1L);
        when(operationLogMapper.selectDailyStats(anyInt())).thenReturn(List.of(Map.of("date", "2024-01-01", "count", 10)));

        AuditConfigVO auditEnabled = new AuditConfigVO();
        auditEnabled.setConfigValue("false");
        AuditConfigVO retention = new AuditConfigVO();
        retention.setConfigValue("365");
        when(operationLogMapper.selectAuditConfigByKey("AUDIT_ENABLED")).thenReturn(auditEnabled);
        when(operationLogMapper.selectAuditConfigByKey("LOG_RETENTION_DAYS")).thenReturn(retention);

        Map<String, Object> overview = sysAuditService.getAuditOverview();

        assertEquals(100L, overview.get("totalOperations"));
        assertEquals(12L, overview.get("todayOperations"));
        assertEquals(8L, overview.get("totalErrorOperations"));
        assertEquals(2L, overview.get("todayErrorOperations"));
        assertEquals(3L, overview.get("todaySensitiveOperations"));
        assertEquals(5L, overview.get("todayActiveUsers"));
        assertEquals(1L, overview.get("highFrequencyAlerts"));
        assertEquals(List.of(Map.of("date", "2024-01-01", "count", 10)), overview.get("dailyStats"));
        assertEquals("false", overview.get("auditEnabled"));
        assertEquals("365", overview.get("logRetentionDays"));
    }

    @Test
    void getAuditOverview_handlesMapperException() {
        when(operationLogMapper.selectTotalOperations()).thenThrow(new RuntimeException("boom"));

        Map<String, Object> overview = sysAuditService.getAuditOverview();

        assertThat(overview.get("error").toString()).contains("boom");
    }

    @Test
    void checkAnomalies_returnsProcedureResult() {
        Map<String, Object> procedureResult = new HashMap<>();
        procedureResult.put("hasAnomalies", true);
        procedureResult.put("anomalyDetails", "details");
        when(operationLogMapper.checkAnomalies()).thenReturn(procedureResult);

        Map<String, Object> result = sysAuditService.checkAnomalies();

        assertTrue((Boolean) result.get("success"));
        assertThat(result.get("hasAnomalies")).isEqualTo(true);
        assertThat(result.get("anomalyDetails")).isEqualTo("details");
        assertThat(result).containsKey("checkTime");
    }

    @Test
    void checkAnomalies_handlesException() {
        when(operationLogMapper.checkAnomalies()).thenThrow(new RuntimeException("fail"));

        Map<String, Object> result = sysAuditService.checkAnomalies();

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("fail");
    }

    @Test
    void backupLogs_returnsSuccessMessage() {
        String message = sysAuditService.backupLogs(7, true);
        verify(operationLogMapper).backupLogs(7, true);
        assertThat(message).isEqualTo("成功备份7天前的日志并清理原表数据");
    }

    @Test
    void backupLogs_returnsFailureMessageOnException() {
        doThrow(new RuntimeException("oops")).when(operationLogMapper).backupLogs(30, false);

        String message = sysAuditService.backupLogs(30, false);

        assertThat(message).isEqualTo("备份失败：oops");
    }

    @Test
    void updateAuditConfig_reflectsUpdateResult() {
        when(operationLogMapper.updateAuditConfig(any(), any(), any())).thenReturn(1);
        assertTrue(sysAuditService.updateAuditConfig(new AuditConfigVO()));

        when(operationLogMapper.updateAuditConfig(any(), any(), any())).thenReturn(0);
        assertThat(sysAuditService.updateAuditConfig(new AuditConfigVO())).isFalse();
    }

    @Test
    void getLogDetail_delegatesToMapper() {
        SysOperationLog log = new SysOperationLog();
        when(operationLogMapper.selectById(1L)).thenReturn(log);

        assertThat(sysAuditService.getLogDetail(1L)).isSameAs(log);
    }

    @Test
    void exportOperationLogs_delegatesToMapper() {
        SysOperationLog log = new SysOperationLog();
        when(operationLogMapper.selectList(any())).thenReturn(List.of(log));

        assertThat(sysAuditService.exportOperationLogs(new AuditLogQueryVO())).containsExactly(log);
    }

    @Test
    void simpleDelegations_returnMapperData() {
        HighFrequencyOperationVO highFrequency = new HighFrequencyOperationVO();
        ErrorOperationStatsVO errorStats = new ErrorOperationStatsVO();
        UserTimeDistributionVO timeDistribution = new UserTimeDistributionVO();
        AuditConfigVO configVO = new AuditConfigVO();

        when(operationLogMapper.selectHighFrequencyOperations()).thenReturn(List.of(highFrequency));
        when(operationLogMapper.selectErrorOperationStats()).thenReturn(List.of(errorStats));
        when(operationLogMapper.selectUserTimeDistribution()).thenReturn(List.of(timeDistribution));
        when(operationLogMapper.selectAuditConfigs()).thenReturn(List.of(configVO));

        assertThat(sysAuditService.getHighFrequencyOperations()).containsExactly(highFrequency);
        assertThat(sysAuditService.getErrorOperationStats()).containsExactly(errorStats);
        assertThat(sysAuditService.getUserTimeDistribution()).containsExactly(timeDistribution);
        assertThat(sysAuditService.getAuditConfigs()).containsExactly(configVO);
    }
}
