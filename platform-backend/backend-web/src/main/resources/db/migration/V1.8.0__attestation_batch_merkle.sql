-- V1.8.0: Add Merkle batch attestation tables for exportable proof metadata.

CREATE TABLE IF NOT EXISTS `attestation_batch` (
    `id`                     BIGINT       NOT NULL COMMENT 'Snowflake batch ID',
    `tenant_id`              BIGINT       NOT NULL COMMENT 'Tenant ID',
    `batch_no`               VARCHAR(64)  NOT NULL COMMENT 'Human-readable batch number',
    `merkle_root`            VARCHAR(128) NOT NULL COMMENT 'Merkle root hash',
    `proof_algorithm`        VARCHAR(32)  NOT NULL COMMENT 'Proof algorithm identifier',
    `leaf_count`             INT          NOT NULL COMMENT 'Number of leaves in this batch',
    `status`                 VARCHAR(32)  NOT NULL COMMENT 'Batch status',
    `chain_transaction_hash` VARCHAR(255) DEFAULT NULL COMMENT 'Blockchain transaction hash for the batch root',
    `chain_file_hash`        VARCHAR(255) DEFAULT NULL COMMENT 'Blockchain adapter returned hash for the batch root record',
    `chain_error`            VARCHAR(512) DEFAULT NULL COMMENT 'Last chain write error if any',
    `create_time`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`                TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attestation_batch_no` (`tenant_id`, `batch_no`),
    KEY `idx_attestation_batch_root` (`tenant_id`, `merkle_root`),
    KEY `idx_attestation_batch_status` (`tenant_id`, `status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Merkle attestation batch table';

CREATE TABLE IF NOT EXISTS `attestation_leaf` (
    `id`              BIGINT       NOT NULL COMMENT 'Snowflake leaf ID',
    `tenant_id`       BIGINT       NOT NULL COMMENT 'Tenant ID',
    `batch_id`        BIGINT       NOT NULL COMMENT 'Attestation batch ID',
    `file_id`         BIGINT       NOT NULL COMMENT 'File table ID',
    `file_hash`       VARCHAR(255) NOT NULL COMMENT 'Original file hash',
    `leaf_hash`       VARCHAR(128) NOT NULL COMMENT 'Canonical leaf hash',
    `leaf_index`      INT          NOT NULL COMMENT 'Leaf index in canonical Merkle order',
    `proof_path_json` JSON         NOT NULL COMMENT 'Merkle proof path from leaf to root',
    `proof_algorithm` VARCHAR(32)  NOT NULL COMMENT 'Proof algorithm identifier',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attestation_leaf_file` (`tenant_id`, `batch_id`, `file_id`),
    UNIQUE KEY `uk_attestation_leaf_index` (`tenant_id`, `batch_id`, `leaf_index`),
    KEY `idx_attestation_leaf_file` (`tenant_id`, `file_id`),
    KEY `idx_attestation_leaf_hash` (`tenant_id`, `leaf_hash`),
    CONSTRAINT `fk_attestation_leaf_batch`
        FOREIGN KEY (`batch_id`) REFERENCES `attestation_batch` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Merkle attestation leaf table';
