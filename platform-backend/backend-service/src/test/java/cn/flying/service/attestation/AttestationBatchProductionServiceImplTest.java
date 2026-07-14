package cn.flying.service.attestation;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationBatchCandidate;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.vo.attestation.AttestationBatchCandidateStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttestationBatchProductionServiceImplTest {

    private static final Long TENANT_ID = 7L;
    private static final Long BATCH_ID = 900L;

    @Mock
    private AttestationBatchCandidatePersistenceService candidatePersistence;

    @Mock
    private AttestationBatchService batchService;

    @Mock
    private AttestationBatchMapper batchMapper;

    private AttestationBatchProductionProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private AttestationBatchProductionServiceImpl service;

    /**
     * 初始化启用且单轮最多一个 batch 的生产编排服务。
     */
    @BeforeEach
    void setUp() {
        properties = new AttestationBatchProductionProperties();
        properties.setEnabled(true);
        properties.setMinBatchSize(2);
        properties.setMaxBatchSize(10);
        properties.setMaxBatchesPerRun(1);
        meterRegistry = new SimpleMeterRegistry();
        service = new AttestationBatchProductionServiceImpl(
                properties,
                candidatePersistence,
                new AttestationBatchFlushPolicy(),
                batchService,
                batchMapper,
                new AttestationBatchProductionMetrics(meterRegistry));
        TenantContext.setTenantId(TENANT_ID);
    }

    /**
     * 清理租户上下文和内存指标注册表。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        meterRegistry.close();
    }

    /**
     * 验证 feature flag 关闭时不读取或写入任何生产状态。
     */
    @Test
    void runTenantShouldDoNothingWhenFeatureIsDisabled() {
        properties.setEnabled(false);

        AttestationBatchProductionRunResult result = service.runTenant(TENANT_ID, true);

        assertThat(result.enabled()).isFalse();
        verify(candidatePersistence, never()).recoverExpiredClaims(any(), anyInt(), any());
        verify(batchMapper, never()).selectDueBatchIds(any(), any(), anyInt(), anyInt());
        assertThat(meterRegistry.get("app.attestation.production.run")
                .tag("result", "disabled").counter().count()).isEqualTo(1.0);
    }

    /**
     * 验证运行结果冻结 batch ID 列表，不受构造方或读取方后续修改影响。
     */
    @Test
    void runResultShouldDefensivelyCopyBatchIds() {
        List<Long> source = new ArrayList<>(List.of(BATCH_ID));
        AttestationBatchProductionRunResult result = new AttestationBatchProductionRunResult(
                true, false, 1, 1, 0, 0, 1, 1, 0, 0, false, source);

        source.add(901L);

        assertThat(result.batchIds()).containsExactly(BATCH_ID);
        assertThatThrownBy(() -> result.batchIds().add(902L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 验证达到 size 阈值后先原子创建本地 batch，再单独进入链提交状态机。
     */
    @Test
    void runTenantShouldCreateAndSubmitBatchAtSizeThreshold() {
        stubRunPrelude();
        AttestationBatchCandidateStats stats = stats(2, Instant.now().minusSeconds(1));
        AttestationCandidateClaim claim = claim(2);
        AttestationBatch pending = batch("CHAIN_PENDING");
        AttestationBatch completed = batch("COMPLETED");
        when(candidatePersistence.stats(TENANT_ID)).thenReturn(stats);
        when(candidatePersistence.claimCandidates(eq(TENANT_ID), eq(2), eq(3), eq(120L), any()))
                .thenReturn(claim);
        when(batchService.createProductionBatch(claim)).thenReturn(pending);
        when(batchService.submitBatch(BATCH_ID)).thenReturn(completed);

        AttestationBatchProductionRunResult result = service.runTenant(TENANT_ID, false);

        assertThat(result.batchesCreated()).isEqualTo(1);
        assertThat(result.batchesCompleted()).isEqualTo(1);
        assertThat(result.candidatesClaimed()).isEqualTo(2);
        assertThat(result.batchIds()).containsExactly(BATCH_ID);
        verify(batchService).createProductionBatch(claim);
        verify(batchService).submitBatch(BATCH_ID);
        verify(candidatePersistence, never()).releaseClaim(any(), anyInt(), any());
    }

    /**
     * 验证低于数量阈值但超过等待窗口的小租户可以 flush。
     */
    @Test
    void runTenantShouldFlushByTimeWindow() {
        properties.setMaxWaitSeconds(60);
        stubRunPrelude();
        AttestationBatchCandidateStats stats = stats(1, Instant.now().minusSeconds(61));
        AttestationCandidateClaim claim = claim(1);
        when(candidatePersistence.stats(TENANT_ID)).thenReturn(stats);
        when(candidatePersistence.claimCandidates(eq(TENANT_ID), eq(1), eq(3), eq(120L), any()))
                .thenReturn(claim);
        when(batchService.createProductionBatch(claim)).thenReturn(batch("CHAIN_PENDING"));
        when(batchService.submitBatch(BATCH_ID)).thenReturn(batch("CHAIN_RETRY"));

        AttestationBatchProductionRunResult result = service.runTenant(TENANT_ID, false);

        assertThat(result.batchesCreated()).isEqualTo(1);
        assertThat(result.batchesRetrying()).isEqualTo(1);
    }

    /**
     * 验证未达到 size/time 阈值时保留 READY candidate，不产生空 batch。
     */
    @Test
    void runTenantShouldDeferBelowThreshold() {
        properties.setMaxWaitSeconds(600);
        stubRunPrelude();
        when(candidatePersistence.stats(TENANT_ID))
                .thenReturn(stats(1, Instant.now()));

        AttestationBatchProductionRunResult result = service.runTenant(TENANT_ID, false);

        assertThat(result.thresholdDeferred()).isTrue();
        assertThat(result.batchesCreated()).isZero();
        verify(candidatePersistence, never()).claimCandidates(any(), anyInt(), anyInt(), anyLong(), any());
    }

    /**
     * 验证本地 batch 事务失败会释放 claim，而不会调用链提交。
     */
    @Test
    void runTenantShouldReleaseClaimWhenLocalBatchCreationFails() {
        stubRunPrelude();
        AttestationCandidateClaim claim = claim(2);
        when(candidatePersistence.stats(TENANT_ID))
                .thenReturn(stats(2, Instant.now().minusSeconds(1)));
        when(candidatePersistence.claimCandidates(eq(TENANT_ID), eq(2), eq(3), eq(120L), any()))
                .thenReturn(claim);
        when(batchService.createProductionBatch(claim))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(candidatePersistence.releaseClaim(
                claim, 3, "Batch creation failed: IllegalStateException")).thenReturn(0);

        assertThatThrownBy(() -> service.runTenant(TENANT_ID, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        verify(candidatePersistence).releaseClaim(
                claim, 3, "Batch creation failed: IllegalStateException");
        verify(batchService, never()).submitBatch(any());
    }

    /**
     * 验证 due batch 优先恢复并消耗单轮预算，避免新 backlog 放大链压力。
     */
    @Test
    void runTenantShouldPrioritizeDueBatchRecovery() {
        when(candidatePersistence.recoverExpiredClaims(eq(TENANT_ID), eq(3), any())).thenReturn(0);
        when(batchMapper.selectDueBatchIds(eq(TENANT_ID), any(Date.class), eq(5), eq(1)))
                .thenReturn(List.of(BATCH_ID));
        when(batchService.submitBatch(BATCH_ID)).thenReturn(batch("COMPLETED"));
        when(candidatePersistence.seedEligibleCandidates(eq(TENANT_ID), eq(200), any(Date.class)))
                .thenReturn(new AttestationCandidateAdmissionResult(1, 0));

        AttestationBatchProductionRunResult result = service.runTenant(TENANT_ID, false);

        assertThat(result.batchesRecovered()).isEqualTo(1);
        assertThat(result.batchesCreated()).isZero();
        verify(candidatePersistence, never()).claimCandidates(any(), anyInt(), anyInt(), anyLong(), any());
    }

    /**
     * 验证方法参数租户不能与线程上下文租户不一致。
     */
    @Test
    void runTenantShouldRejectTenantContextMismatch() {
        assertThatThrownBy(() -> service.runTenant(99L, false))
                .isInstanceOf(GeneralException.class);
        verify(candidatePersistence, never()).recoverExpiredClaims(any(), anyInt(), any());
    }

    /**
     * 验证状态查询只读取当前租户，并返回有效配置、backlog 与可恢复批次数。
     */
    @Test
    void getStatusShouldReturnCurrentTenantConfigurationAndBacklog() {
        AttestationBatchCandidateStats stats = stats(4, Instant.parse("2026-07-14T00:00:00Z"));
        stats.setClaimedCount(1);
        stats.setBatchedCount(8);
        stats.setDeadLetterCount(2);
        when(candidatePersistence.stats(TENANT_ID)).thenReturn(stats);
        when(batchMapper.countDueBatches(eq(TENANT_ID), any(Date.class), eq(5))).thenReturn(3L);

        AttestationBatchProductionStatus status = service.getStatus(TENANT_ID);

        assertThat(status.enabled()).isTrue();
        assertThat(status.minBatchSize()).isEqualTo(2);
        assertThat(status.maxBatchSize()).isEqualTo(10);
        assertThat(status.readyCandidates()).isEqualTo(4);
        assertThat(status.claimedCandidates()).isEqualTo(1);
        assertThat(status.batchedCandidates()).isEqualTo(8);
        assertThat(status.deadLetterCandidates()).isEqualTo(2);
        assertThat(status.dueBatches()).isEqualTo(3);
        verify(candidatePersistence).stats(TENANT_ID);
        verify(batchMapper).countDueBatches(eq(TENANT_ID), any(Date.class), eq(5));
    }

    /**
     * 配置无 due batch、无新增 admission 的通用运行前置。
     */
    private void stubRunPrelude() {
        when(candidatePersistence.recoverExpiredClaims(eq(TENANT_ID), eq(3), any())).thenReturn(0);
        when(batchMapper.selectDueBatchIds(eq(TENANT_ID), any(Date.class), eq(5), eq(1)))
                .thenReturn(List.of());
        when(candidatePersistence.seedEligibleCandidates(eq(TENANT_ID), eq(200), any(Date.class)))
                .thenReturn(new AttestationCandidateAdmissionResult(0, 0));
    }

    /**
     * 构造 READY candidate 聚合状态。
     */
    private AttestationBatchCandidateStats stats(long readyCount, Instant oldest) {
        AttestationBatchCandidateStats stats = new AttestationBatchCandidateStats();
        stats.setReadyCount(readyCount);
        stats.setOldestReadyAt(Date.from(oldest));
        return stats;
    }

    /**
     * 构造一个或多个由同一 token 持有的 manifest candidate。
     */
    private AttestationCandidateClaim claim(int size) {
        List<AttestationBatchCandidate> candidates = java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> new AttestationBatchCandidate()
                        .setId(1_000L + index)
                        .setTenantId(TENANT_ID)
                        .setFileId(10L + index)
                        .setFileVersion(1)
                        .setManifestId(100L + index)
                        .setEvidenceType("MANIFEST_HASH")
                        .setEvidenceHash("sha256:" + String.valueOf(index).repeat(64))
                        .setChainRecordId("chain-" + index)
                        .setStatus("CLAIMED")
                        .setClaimToken("claim")
                        .setAttemptCount(1)
                        .setEligibleAt(Date.from(Instant.now().minusSeconds(5))))
                .toList();
        return new AttestationCandidateClaim(TENANT_ID, "claim", candidates);
    }

    /**
     * 构造指定链提交状态的 batch。
     */
    private AttestationBatch batch(String status) {
        return new AttestationBatch()
                .setId(BATCH_ID)
                .setTenantId(TENANT_ID)
                .setStatus(status);
    }
}
