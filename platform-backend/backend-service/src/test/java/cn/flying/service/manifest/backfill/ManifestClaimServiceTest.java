package cn.flying.service.manifest.backfill;

import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.entity.ManifestReferenceSweepMark;
import cn.flying.dao.mapper.ManifestBackfillItemMapper;
import cn.flying.dao.mapper.ManifestReferenceSweepMarkMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证清单回填和引用清扫的短事务租约围栏。
 */
@ExtendWith(MockitoExtension.class)
class ManifestClaimServiceTest {

    private static final Long TENANT_ID = 11L;
    private static final Long RUN_ID = 20L;

    @Mock
    private ManifestBackfillItemMapper itemMapper;

    @Mock
    private ManifestReferenceSweepMarkMapper markMapper;

    private ManifestBackfillClaimService backfillClaimService;
    private ManifestReferenceSweepClaimService sweepClaimService;

    /**
     * 创建真实声明服务并建立租户上下文。
     */
    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        backfillClaimService = new ManifestBackfillClaimService(itemMapper);
        sweepClaimService = new ManifestReferenceSweepClaimService(markMapper);
    }

    /**
     * 清理线程租户，防止测试间串扰。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 无到期候选时不创建租约，也不执行批量更新。
     */
    @Test
    void shouldReturnNoBackfillClaimWhenNothingIsDue() {
        Instant now = Instant.parse("2026-07-26T10:00:00Z");
        when(itemMapper.selectClaimableForUpdate(
                RUN_ID, TENANT_ID, Date.from(now), 3, 20)).thenReturn(null);

        assertThat(backfillClaimService.claim(RUN_ID, TENANT_ID, 20, 3, 120L, now)).isNull();

        verify(itemMapper, never()).claimSelected(any(), any(), any(), anyString(), any(), any());
    }

    /**
     * 对锁定行写入同一随机令牌、到期时间和递增后的尝试次数。
     */
    @Test
    void shouldClaimEveryLockedBackfillCandidate() {
        Instant now = Instant.parse("2026-07-26T10:00:00Z");
        ManifestBackfillItem first = item(31L, null);
        ManifestBackfillItem second = item(32L, 2);
        when(itemMapper.selectClaimableForUpdate(
                RUN_ID, TENANT_ID, Date.from(now), 3, 20)).thenReturn(List.of(first, second));
        when(itemMapper.claimSelected(
                eq(RUN_ID), eq(TENANT_ID), eq(List.of(31L, 32L)), anyString(),
                eq(Date.from(now)), eq(Date.from(now.plusSeconds(120L))))).thenReturn(2);

        ManifestBackfillClaim claim = backfillClaimService.claim(
                RUN_ID, TENANT_ID, 20, 3, 120L, now);

        assertThat(claim).isNotNull();
        assertThat(claim.claimToken()).isNotBlank();
        assertThat(claim.items()).containsExactly(first, second);
        assertThat(claim.items()).extracting(ManifestBackfillItem::getStatus)
                .containsOnly(ManifestBackfillItemStatus.RUNNING.name());
        assertThat(claim.items()).extracting(ManifestBackfillItem::getAttemptCount)
                .containsExactly(1, 3);
        assertThat(claim.items()).extracting(ManifestBackfillItem::getClaimToken)
                .containsOnly(claim.claimToken());
        assertThat(first.getLeaseExpiresAt()).isEqualTo(Date.from(now.plusSeconds(120L)));
    }

    /**
     * 批量声明数量不完整时拒绝返回部分所有权。
     */
    @Test
    void shouldRejectPartialBackfillClaim() {
        Instant now = Instant.parse("2026-07-26T10:00:00Z");
        when(itemMapper.selectClaimableForUpdate(
                RUN_ID, TENANT_ID, Date.from(now), 3, 20)).thenReturn(List.of(item(31L, 0), item(32L, 0)));
        when(itemMapper.claimSelected(
                eq(RUN_ID), eq(TENANT_ID), eq(List.of(31L, 32L)), anyString(), any(), any()))
                .thenReturn(1);

        assertThatThrownBy(() -> backfillClaimService.claim(
                RUN_ID, TENANT_ID, 20, 3, 120L, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim lost");
    }

    /**
     * 可重试失败保留原原因、设置指数退避时间并截断异常类名。
     */
    @Test
    void shouldScheduleBoundedRetryForOwnedBackfillFailure() {
        ManifestBackfillItem item = item(31L, 2);
        String oversizedErrorClass = " X".repeat(100);
        ArgumentCaptor<Date> retryAt = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<String> errorClass = ArgumentCaptor.forClass(String.class);
        when(itemMapper.completeClaim(
                eq(TENANT_ID), eq(RUN_ID), eq(31L), eq("claim-a"),
                eq("FAILED"), eq("FAILED"), eq("DATABASE_TRANSIENT"), eq(null),
                eq(1), retryAt.capture(), errorClass.capture())).thenReturn(1);

        long before = System.currentTimeMillis();
        backfillClaimService.failClaim(item, "claim-a", ManifestBackfillReason.DATABASE_TRANSIENT,
                true, oversizedErrorClass);

        assertThat(retryAt.getValue().getTime()).isBetween(before + 9_000L, before + 11_000L);
        assertThat(errorClass.getValue()).hasSize(128).doesNotStartWith(" ");
    }

    /**
     * 最终失败清空重试计划，并在令牌围栏丢失时返回稳定原因。
     */
    @Test
    void shouldTerminateFailureAndExposeLostBackfillFence() {
        ManifestBackfillItem terminal = item(31L, null);
        when(itemMapper.completeClaim(
                TENANT_ID, RUN_ID, 31L, "claim-a", "FAILED", "FAILED", "MANUAL_REVIEW",
                null, 0, null, null)).thenReturn(1);
        backfillClaimService.failClaim(
                terminal, "claim-a", ManifestBackfillReason.MANUAL_REVIEW, false, "  ");

        ManifestBackfillItem lost = item(32L, 1);
        when(itemMapper.completeClaim(
                TENANT_ID, RUN_ID, 32L, "claim-b", "FAILED", "FAILED", "MANUAL_REVIEW",
                null, 0, null, "IllegalStateException")).thenReturn(0);

        assertThatThrownBy(() -> backfillClaimService.failClaim(
                lost, "claim-b", ManifestBackfillReason.MANUAL_REVIEW, false, "IllegalStateException"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ManifestBackfillReason.CLAIM_LOST.name());
    }

    /**
     * 引用清扫只返回成功写入租约围栏的标记，并更新本地声明状态。
     */
    @Test
    void shouldReturnOnlySuccessfullyClaimedSweepMarks() {
        ManifestReferenceSweepMark claimed = mark(41L, null);
        ManifestReferenceSweepMark raced = mark(42L, 2);
        when(markMapper.selectDueForUpdate(eq(TENANT_ID), any(Date.class), eq(20)))
                .thenReturn(List.of(claimed, raced));
        when(markMapper.claimMark(eq(41L), eq(TENANT_ID), anyString(), any(Date.class), any(Date.class)))
                .thenReturn(1);
        when(markMapper.claimMark(eq(42L), eq(TENANT_ID), anyString(), any(Date.class), any(Date.class)))
                .thenReturn(0);

        List<ManifestReferenceSweepMark> result = sweepClaimService.claimDue(20, 120L);

        assertThat(result).containsExactly(claimed);
        assertThat(claimed.getClaimToken()).isNotBlank();
        assertThat(claimed.getLeaseExpiresAt()).isNotNull();
        assertThat(claimed.getAttemptCount()).isEqualTo(1);
        assertThat(raced.getClaimToken()).isNull();
        assertThat(raced.getAttemptCount()).isEqualTo(2);
    }

    /**
     * 创建一个回填候选行。
     */
    private ManifestBackfillItem item(Long id, Integer attempts) {
        return new ManifestBackfillItem()
                .setId(id)
                .setTenantId(TENANT_ID)
                .setRunId(RUN_ID)
                .setAttemptCount(attempts);
    }

    /**
     * 创建一个引用清扫标记。
     */
    private ManifestReferenceSweepMark mark(Long id, Integer attempts) {
        return new ManifestReferenceSweepMark()
                .setId(id)
                .setTenantId(TENANT_ID)
                .setAttemptCount(attempts);
    }
}
