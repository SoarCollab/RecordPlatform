package cn.flying.fisco_bcos.adapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 不依赖具体链 SDK 的 Merkle 批量存证查询模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainAttestationBatch {

    private Boolean exists;

    private Long tenantId;

    private Long batchId;

    private String batchNo;

    private String proofAlgorithm;

    private String merkleRoot;

    private Integer leafCount;

    private Long recordedTime;

    /**
     * 创建链上不存在记录时的统一模型。
     */
    public static ChainAttestationBatch notFound(Long tenantId, Long batchId) {
        return ChainAttestationBatch.builder()
                .exists(false)
                .tenantId(tenantId)
                .batchId(batchId)
                .build();
    }
}
