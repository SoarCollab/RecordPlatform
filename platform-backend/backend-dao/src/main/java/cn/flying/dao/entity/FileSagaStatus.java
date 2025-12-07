package cn.flying.dao.entity;

/**
 * Saga status enumeration.
 */
public enum FileSagaStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    COMPENSATING,
    COMPENSATED
}
