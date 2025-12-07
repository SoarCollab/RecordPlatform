package cn.flying.dao.entity;

/**
 * Saga 状态枚举。
 */
public enum FileSagaStatus {
    /**
     * 运行中
     */
    RUNNING,
    /**
     * 成功完成
     */
    SUCCEEDED,
    /**
     * 补偿中
     */
    COMPENSATING,
    /**
     * 补偿完成
     */
    COMPENSATED,
    /**
     * 待补偿重试（指数退避等待中）
     */
    PENDING_COMPENSATION,
    /**
     * 最终失败（超过最大重试次数）
     */
    FAILED
}
