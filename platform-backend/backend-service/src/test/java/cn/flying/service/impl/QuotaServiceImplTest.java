package cn.flying.service.impl;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.QuotaPolicyMapper;
import cn.flying.dao.mapper.QuotaUsageSnapshotMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.dao.entity.QuotaUsageSnapshot;
import cn.flying.dao.vo.file.QuotaStatusVO;
import cn.flying.dao.vo.file.QuotaUserUsageVO;
import cn.flying.service.monitor.QuotaMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;

/**
 * QuotaServiceImpl 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotaServiceImplTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private QuotaPolicyMapper quotaPolicyMapper;

    @Mock
    private QuotaUsageSnapshotMapper quotaUsageSnapshotMapper;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private QuotaMetrics quotaMetrics;

    @InjectMocks
    private QuotaServiceImpl quotaService;

    /**
     * 初始化默认 mock 与配置字段。
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(quotaService, "defaultUserMaxStorageBytes", 1000L);
        ReflectionTestUtils.setField(quotaService, "defaultUserMaxFileCount", 10L);
        ReflectionTestUtils.setField(quotaService, "defaultTenantMaxStorageBytes", 5000L);
        ReflectionTestUtils.setField(quotaService, "defaultTenantMaxFileCount", 100L);
        ReflectionTestUtils.setField(quotaService, "rolloutStrategy", "TENANT_WHITELIST");
        ReflectionTestUtils.setField(quotaService, "enforceTenantWhitelist", "");
        ReflectionTestUtils.setField(quotaService, "forceShadow", false);
        ReflectionTestUtils.setField(quotaService, "alertEnabled", true);
        ReflectionTestUtils.setField(quotaService, "storageDriftRatioThreshold", 0.01D);
        ReflectionTestUtils.setField(quotaService, "fileCountDriftThreshold", 1L);

        when(quotaPolicyMapper.selectActivePolicy(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyLong()))
                .thenReturn(null);
        when(fileMapper.countQuotaByUserId(anyLong(), anyLong())).thenReturn(1L);
        when(fileMapper.countQuotaByTenantId(anyLong())).thenReturn(10L);
        when(fileMapper.sumQuotaStorageByUserId(anyLong(), anyLong())).thenReturn(200L);
        when(fileMapper.sumQuotaStorageByTenantId(anyLong())).thenReturn(1200L);
        when(quotaUsageSnapshotMapper.upsertSnapshot(anyLong(), anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(1);
    }

    /**
     * 验证 SHADOW 模式下超限请求不会抛异常。
     */
    @Test
    void shouldAllowUploadWhenOverQuotaInShadowMode() {
        ReflectionTestUtils.setField(quotaService, "enforcementMode", "SHADOW");
        when(fileMapper.sumQuotaStorageByUserId(2L, 1L)).thenReturn(950L);

        assertDoesNotThrow(() -> quotaService.checkUploadQuota(1L, 2L, 200L));
        verify(quotaMetrics).recordQuotaDecision("SHADOW", true);
    }

    /**
     * 验证 ENFORCE 模式下超限请求会抛出业务异常。
     */
    @Test
    void shouldRejectUploadWhenOverQuotaInEnforceMode() {
        ReflectionTestUtils.setField(quotaService, "enforcementMode", "ENFORCE");
        ReflectionTestUtils.setField(quotaService, "rolloutStrategy", "ALL");
        when(fileMapper.sumQuotaStorageByUserId(3L, 1L)).thenReturn(980L);

        GeneralException ex = assertThrows(GeneralException.class, () ->
                quotaService.checkUploadQuota(1L, 3L, 200L));

        assertEquals(50013, ex.getResultEnum().getCode());
        verify(quotaMetrics).recordQuotaDecision("ENFORCE", true);
    }

    /**
     * 验证租户在白名单内时 ENFORCE 生效。
     */
    @Test
    void shouldEnforceWhenTenantInWhitelist() {
        ReflectionTestUtils.setField(quotaService, "enforcementMode", "ENFORCE");
        ReflectionTestUtils.setField(quotaService, "rolloutStrategy", "TENANT_WHITELIST");
        ReflectionTestUtils.setField(quotaService, "enforceTenantWhitelist", "1,2");
        when(fileMapper.sumQuotaStorageByUserId(3L, 1L)).thenReturn(980L);

        GeneralException ex = assertThrows(GeneralException.class, () ->
                quotaService.checkUploadQuota(1L, 3L, 200L));

        assertEquals(50013, ex.getResultEnum().getCode());
        verify(quotaMetrics).recordQuotaDecision("ENFORCE", true);
    }

    /**
     * 验证租户不在白名单时强制回退 SHADOW。
     */
    @Test
    void shouldFallbackToShadowWhenTenantNotInWhitelist() {
        ReflectionTestUtils.setField(quotaService, "enforcementMode", "ENFORCE");
        ReflectionTestUtils.setField(quotaService, "rolloutStrategy", "TENANT_WHITELIST");
        ReflectionTestUtils.setField(quotaService, "enforceTenantWhitelist", "2,3");
        when(fileMapper.sumQuotaStorageByUserId(3L, 1L)).thenReturn(980L);

        assertDoesNotThrow(() -> quotaService.checkUploadQuota(1L, 3L, 200L));
        verify(quotaMetrics).recordQuotaDecision("SHADOW", true);
    }

    /**
     * 验证 force-shadow 可覆盖所有 ENFORCE 灰度配置。
     */
    @Test
    void shouldForceShadowWhenForceShadowEnabled() {
        ReflectionTestUtils.setField(quotaService, "enforcementMode", "ENFORCE");
        ReflectionTestUtils.setField(quotaService, "rolloutStrategy", "ALL");
        ReflectionTestUtils.setField(quotaService, "forceShadow", true);
        when(fileMapper.sumQuotaStorageByUserId(3L, 1L)).thenReturn(980L);

        assertDoesNotThrow(() -> quotaService.checkUploadQuota(1L, 3L, 200L));
        verify(quotaMetrics).recordQuotaDecision("SHADOW", true);
    }

    /**
     * 验证可返回当前配额状态明细。
     */
    @Test
    void shouldReturnCurrentQuotaStatus() {
        ReflectionTestUtils.setField(quotaService, "enforcementMode", "ENFORCE");
        ReflectionTestUtils.setField(quotaService, "rolloutStrategy", "TENANT_WHITELIST");
        ReflectionTestUtils.setField(quotaService, "enforceTenantWhitelist", "1");

        QuotaStatusVO status = quotaService.getCurrentQuotaStatus(1L, 2L);

        assertEquals(1L, status.tenantId());
        assertEquals(2L, status.userId());
        assertEquals("ENFORCE", status.enforcementMode());
        assertEquals(200L, status.userUsedStorageBytes());
    }

    /**
     * 验证对账任务会按租户和用户写入 RECON 快照。
     */
    @Test
    void shouldReconcileSnapshotsByTenant() {
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(1L));
        when(fileMapper.aggregateQuotaUserUsageByTenant(1L)).thenReturn(List.of(
                new QuotaUserUsageVO(2L, 300L, 2L),
                new QuotaUserUsageVO(3L, 500L, 3L)
        ));

        quotaService.reconcileUsageSnapshots();

        verify(quotaUsageSnapshotMapper).upsertSnapshot(1L, 0L, 1200L, 10L, "RECON");
        verify(quotaUsageSnapshotMapper).resetMissingUserSnapshots(eq(1L), eq(List.of(2L, 3L)), eq("RECON"));
        verify(quotaUsageSnapshotMapper).upsertSnapshot(eq(1L), eq(2L), eq(300L), eq(2L), eq("RECON"));
        verify(quotaUsageSnapshotMapper).upsertSnapshot(eq(1L), eq(3L), eq(500L), eq(3L), eq("RECON"));
    }

    /**
     * 验证对账偏差超阈值时会记录告警指标。
     */
    @Test
    void shouldRecordDriftAlertWhenThresholdExceeded() {
        QuotaUsageSnapshot tenantSnapshot = new QuotaUsageSnapshot();
        tenantSnapshot.setUsedStorageBytes(1000L);
        tenantSnapshot.setUsedFileCount(8L);
        when(quotaUsageSnapshotMapper.selectByScope(1L, 0L)).thenReturn(tenantSnapshot);

        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(1L));
        when(fileMapper.sumQuotaStorageByTenantId(1L)).thenReturn(1200L);
        when(fileMapper.countQuotaByTenantId(1L)).thenReturn(12L);
        when(fileMapper.aggregateQuotaUserUsageByTenant(1L)).thenReturn(List.of());

        quotaService.reconcileUsageSnapshots();

        verify(quotaMetrics).recordDriftAlert("TENANT", "storage_drift");
        verify(quotaMetrics).recordDriftAlert("TENANT", "file_count_drift");
    }

    /**
     * 验证告警关闭时不记录漂移告警指标。
     */
    @Test
    void shouldSkipDriftAlertWhenAlertDisabled() {
        QuotaUsageSnapshot tenantSnapshot = new QuotaUsageSnapshot();
        tenantSnapshot.setUsedStorageBytes(1000L);
        tenantSnapshot.setUsedFileCount(8L);
        when(quotaUsageSnapshotMapper.selectByScope(1L, 0L)).thenReturn(tenantSnapshot);

        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(1L));
        when(fileMapper.sumQuotaStorageByTenantId(1L)).thenReturn(1200L);
        when(fileMapper.countQuotaByTenantId(1L)).thenReturn(12L);
        when(fileMapper.aggregateQuotaUserUsageByTenant(1L)).thenReturn(List.of());
        ReflectionTestUtils.setField(quotaService, "alertEnabled", false);

        quotaService.reconcileUsageSnapshots();

        verify(quotaMetrics, never()).recordDriftAlert(anyString(), anyString());
    }

    /**
     * 验证租户无文件时会将该租户历史用户快照统一清零。
     */
    @Test
    void shouldResetAllUserSnapshotsWhenTenantHasNoFileUsage() {
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(1L));
        when(fileMapper.aggregateQuotaUserUsageByTenant(1L)).thenReturn(List.of());

        quotaService.reconcileUsageSnapshots();

        verify(quotaUsageSnapshotMapper).resetMissingUserSnapshots(eq(1L), eq(List.<Long>of()), eq("RECON"));
    }

    /**
     * 验证对账任务会通过 runWithoutIsolation 绕过租户行级拦截。
     */
    @Test
    void shouldRunReconcileInsideRunWithoutIsolation() {
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(1L));
        when(fileMapper.aggregateQuotaUserUsageByTenant(1L)).thenReturn(List.of());

        try (MockedStatic<TenantContext> tenantContextMock = mockStatic(TenantContext.class)) {
            tenantContextMock.when(() -> TenantContext.runWithoutIsolation(org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        Runnable action = invocation.getArgument(0);
                        action.run();
                        return null;
                    });
            tenantContextMock.when(() -> TenantContext.runWithoutIsolation(org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
                    .thenAnswer(invocation -> {
                        Supplier<?> action = invocation.getArgument(0);
                        return action.get();
                    });

            quotaService.reconcileUsageSnapshots();

            tenantContextMock.verify(() -> TenantContext.runWithoutIsolation(org.mockito.ArgumentMatchers.any(Runnable.class)));
        }
    }
}
