package cn.flying.service.job;

import cn.flying.dao.mapper.TenantMapper;
import cn.flying.service.SysAuditService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditTask 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AuditTaskTest {

    @Mock
    private SysAuditService sysAuditService;

    @Mock
    private SseEmitterManager sseEmitterManager;

    @Mock
    private TenantMapper tenantMapper;

    private AuditTask auditTask;

    /**
     * 初始化定时任务及配置字段。
     */
    @BeforeEach
    void setUp() {
        auditTask = new AuditTask();
        ReflectionTestUtils.setField(auditTask, "sysAuditService", sysAuditService);
        ReflectionTestUtils.setField(auditTask, "sseEmitterManager", sseEmitterManager);
        ReflectionTestUtils.setField(auditTask, "tenantMapper", tenantMapper);
        ReflectionTestUtils.setField(auditTask, "anomalyCheckEnabled", true);
        ReflectionTestUtils.setField(auditTask, "backupEnabled", true);
        ReflectionTestUtils.setField(auditTask, "logRetentionDays", 180);
        ReflectionTestUtils.setField(auditTask, "deleteAfterBackup", true);
    }

    /**
     * 清理租户上下文，避免测试间污染。
     */
    @AfterEach
    void tearDown() {
        cn.flying.common.tenant.TenantContext.clear();
    }

    /**
     * 验证异常告警只向触发异常的租户广播，不做全租户复用事件广播。
     */
    @Test
    void shouldBroadcastAnomalyOnlyInsideAffectedTenant() {
        Map<String, Object> anomaly = Map.of(
                "hasAnomalies", true,
                "anomalyDetails", "{\"tenantId\":1,\"highFrequencyUsers\":1,\"failedLoginUsers\":0,\"errorRatePercent\":0,"
                        + "\"thresholds\":{\"highFrequency\":100,\"failedLogin\":5,\"errorRate\":10}}"
        );
        Map<String, Object> normal = Map.of(
                "hasAnomalies", false,
                "anomalyDetails", "{\"tenantId\":2,\"highFrequencyUsers\":0,\"failedLoginUsers\":0,\"errorRatePercent\":0}"
        );
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(1L, 2L));
        when(sysAuditService.checkAnomalies()).thenReturn(anomaly, normal);

        auditTask.checkAnomaliesScheduled();

        verify(sseEmitterManager).broadcastToAdmins(eq(1L), any(SseEvent.class));
        verify(sseEmitterManager, never()).broadcastToAdmins(eq(2L), any(SseEvent.class));
    }

    /**
     * 验证备份任务按活跃租户逐个执行租户上下文内备份。
     */
    @Test
    void shouldBackupLogsForEachActiveTenant() {
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(1L, 2L));
        when(sysAuditService.backupLogs(180, true)).thenReturn("ok");

        auditTask.backupLogsScheduled();

        verify(sysAuditService, times(2)).backupLogs(180, true);
    }
}
