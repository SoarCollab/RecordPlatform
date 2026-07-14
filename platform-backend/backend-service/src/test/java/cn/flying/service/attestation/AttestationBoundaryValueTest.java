package cn.flying.service.attestation;

import cn.flying.dao.entity.AttestationBatchCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证批量存证结果值对象和链回执校验器的失败关闭边界。
 */
class AttestationBoundaryValueTest {

    /**
     * 验证空候选 claim 返回安全的空摘要，不产生空指针。
     */
    @Test
    void nullCandidateClaimShouldExposeEmptySummary() {
        AttestationCandidateClaim claim = new AttestationCandidateClaim(7L, "claim", null);

        assertThat(claim.size()).isZero();
        assertThat(claim.oldestEligibleAt()).isNull();
    }

    /**
     * 验证候选最早时间使用防御性副本并忽略空 admission 时间。
     */
    @Test
    void candidateClaimShouldReturnDefensiveOldestEligibleAt() {
        Date earliest = new Date(1_000L);
        AttestationCandidateClaim claim = new AttestationCandidateClaim(
                7L,
                "claim",
                List.of(
                        new AttestationBatchCandidate().setEligibleAt(new Date(2_000L)),
                        new AttestationBatchCandidate().setEligibleAt(earliest),
                        new AttestationBatchCandidate()));

        Date actual = claim.oldestEligibleAt();

        assertThat(actual).isEqualTo(earliest).isNotSameAs(earliest);
    }

    /**
     * 验证空或格式错误的链上 Merkle 根会被统一拒绝。
     */
    @Test
    void invalidChainRootShouldFailClosed() {
        assertThat(AttestationConfirmationReceiptValidator.isValid(
                AttestationConfirmationReceiptValidator.SOURCE_CHAIN_WRITE,
                "a".repeat(64),
                null)).isFalse();
        assertThat(AttestationConfirmationReceiptValidator.isValid(
                AttestationConfirmationReceiptValidator.SOURCE_CHAIN_WRITE,
                "a".repeat(64),
                "not-a-root")).isFalse();
        assertThatThrownBy(() -> AttestationConfirmationReceiptValidator.requireValid(
                AttestationConfirmationReceiptValidator.SOURCE_CHAIN_WRITE,
                "a".repeat(64),
                null)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证生产结果把 null 归一为空列表，并隔离调用方后续修改。
     */
    @Test
    void productionRunResultShouldNormalizeAndDefensivelyCopyBatchIds() {
        AttestationBatchProductionRunResult empty = result(null);
        ArrayList<Long> mutableIds = new ArrayList<>(List.of(11L));
        AttestationBatchProductionRunResult populated = result(mutableIds);

        mutableIds.add(12L);

        assertThat(empty.batchIds()).isEmpty();
        assertThat(populated.batchIds()).containsExactly(11L);
        assertThatThrownBy(() -> populated.batchIds().add(12L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 构造仅关注 batch ID 快照语义的生产结果。
     */
    private AttestationBatchProductionRunResult result(List<Long> batchIds) {
        return new AttestationBatchProductionRunResult(
                true, false, 0, 0, 0, 0, 0, 0, 0, 0, false, batchIds);
    }
}
