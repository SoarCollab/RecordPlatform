package cn.flying.platformapi.response;

import java.io.Serial;
import java.io.Serializable;

/**
 * 链上 Merkle 批量存证记录查询结果。
 *
 * @param exists 链上是否存在该业务批次
 * @param tenantId 批次所属租户
 * @param batchId 批量存证业务 ID
 * @param batchNo 批次编号
 * @param proofAlgorithm Merkle 证明算法
 * @param merkleRoot Merkle 根哈希
 * @param leafCount 批次叶子数量
 * @param recordedTime 链上记录时间戳（毫秒）
 */
public record GetAttestationBatchResponse(
        Boolean exists,
        Long tenantId,
        Long batchId,
        String batchNo,
        String proofAlgorithm,
        String merkleRoot,
        Integer leafCount,
        Long recordedTime
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 创建链上不存在记录时的稳定返回值。
     */
    public static GetAttestationBatchResponse notFound(Long tenantId, Long batchId) {
        return new GetAttestationBatchResponse(false, tenantId, batchId, null, null, null, null, null);
    }
}
