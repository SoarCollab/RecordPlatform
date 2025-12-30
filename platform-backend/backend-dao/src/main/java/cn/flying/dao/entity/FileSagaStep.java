package cn.flying.dao.entity;

/**
 * Saga step enumeration for file upload flow.
 * Order matters - used for determining if a step has been reached.
 */
public enum FileSagaStep {
    PENDING,
    S3_UPLOADING,
    S3_UPLOADED,
    CHAIN_STORING,
    COMPLETED
}
