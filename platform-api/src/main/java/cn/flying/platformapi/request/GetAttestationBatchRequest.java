package cn.flying.platformapi.request;

import cn.flying.platformapi.response.ContractRegistryEntryResponse;

import java.io.Serial;
import java.io.Serializable;

/**
 * 查询链上 Merkle 批量存证记录的请求。
 *
 * @param tenantId 批次所属租户
 * @param batchId 批量存证业务 ID
 * @param contractRegistry 批次绑定的不可变合约注册表快照
 */
public record GetAttestationBatchRequest(
        Long tenantId,
        Long batchId,
        ContractRegistryEntryResponse contractRegistry
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
