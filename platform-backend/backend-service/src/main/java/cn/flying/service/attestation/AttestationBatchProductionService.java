package cn.flying.service.attestation;

/**
 * 管理租户级生产 Merkle batch 的 admission、flush、恢复和状态查询。
 */
public interface AttestationBatchProductionService {

    /**
     * 执行当前租户的一轮生产 batch 处理。
     */
    AttestationBatchProductionRunResult runTenant(Long tenantId, boolean force);

    /**
     * 查询当前租户的生产 batch 配置和 backlog。
     */
    AttestationBatchProductionStatus getStatus(Long tenantId);
}
