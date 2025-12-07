package cn.flying.dao.entity;

/**
 * Saga step enumeration for file upload flow.
 * Order matters - used for determining if a step has been reached.
 */
public enum FileSagaStep {
    PENDING,
    MINIO_UPLOADING,
    MINIO_UPLOADED,
    CHAIN_STORING,
    COMPLETED
}
