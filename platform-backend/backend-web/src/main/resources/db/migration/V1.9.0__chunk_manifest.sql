-- V1.9.0: Add chunk manifest tables for resumable transfer, integrity, proof, migration, and repair.

CREATE TABLE IF NOT EXISTS `file_chunk_manifest` (
    `id`                   BIGINT       NOT NULL COMMENT 'Snowflake manifest ID',
    `tenant_id`            BIGINT       NOT NULL COMMENT 'Tenant ID',
    `file_id`              BIGINT       NOT NULL COMMENT 'File table ID',
    `file_version`         INT          DEFAULT NULL COMMENT 'File version when the manifest was written',
    `file_hash`            VARCHAR(255) NOT NULL COMMENT 'Original file hash',
    `schema_id`            VARCHAR(96)  NOT NULL COMMENT 'Manifest schema identifier',
    `manifest_hash`        VARCHAR(128) NOT NULL COMMENT 'Canonical manifest hash',
    `hash_algorithm`       VARCHAR(32)  NOT NULL COMMENT 'Manifest hash algorithm',
    `chunk_size`           BIGINT       NOT NULL COMMENT 'Nominal chunk size in bytes',
    `chunk_count`          INT          NOT NULL COMMENT 'Number of chunks',
    `total_size`           BIGINT       NOT NULL COMMENT 'Total stored chunk bytes',
    `merkle_root`          VARCHAR(128) DEFAULT NULL COMMENT 'Optional Merkle root over manifest chunks',
    `encryption_algorithm` VARCHAR(64)  DEFAULT NULL COMMENT 'Optional content encryption algorithm',
    `storage_backend`      VARCHAR(64)  DEFAULT NULL COMMENT 'Primary storage backend identifier',
    `manifest_json`        JSON         NOT NULL COMMENT 'Canonical manifest JSON payload without manifest_hash',
    `status`               VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'Manifest lifecycle status',
    `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`              TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_chunk_manifest_hash` (`tenant_id`, `file_id`, `manifest_hash`),
    KEY `idx_file_chunk_manifest_file` (`tenant_id`, `file_id`, `deleted`, `create_time`),
    KEY `idx_file_chunk_manifest_hash` (`tenant_id`, `manifest_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='File chunk manifest header table';

CREATE TABLE IF NOT EXISTS `file_chunk_manifest_item` (
    `id`                 BIGINT       NOT NULL COMMENT 'Snowflake manifest item ID',
    `tenant_id`          BIGINT       NOT NULL COMMENT 'Tenant ID',
    `manifest_id`        BIGINT       NOT NULL COMMENT 'Chunk manifest ID',
    `file_id`            BIGINT       NOT NULL COMMENT 'File table ID',
    `chunk_index`        INT          NOT NULL COMMENT 'Zero-based chunk index',
    `plain_hash`         VARCHAR(128) NOT NULL COMMENT 'Plain chunk hash',
    `cipher_hash`        VARCHAR(128) NOT NULL COMMENT 'Stored/encrypted chunk hash',
    `size`               BIGINT       NOT NULL COMMENT 'Stored chunk size in bytes',
    `storage_path`       VARCHAR(512) NOT NULL COMMENT 'Object storage path for this chunk',
    `storage_backend`    VARCHAR(64)  DEFAULT NULL COMMENT 'Storage backend identifier',
    `etag`               VARCHAR(255) DEFAULT NULL COMMENT 'Object storage ETag or equivalent checksum token',
    `checksum_algorithm` VARCHAR(32)  DEFAULT NULL COMMENT 'Chunk hash/checksum algorithm',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`            TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_chunk_manifest_item_index` (`tenant_id`, `manifest_id`, `chunk_index`),
    KEY `idx_file_chunk_manifest_item_file` (`tenant_id`, `file_id`, `chunk_index`),
    KEY `idx_file_chunk_manifest_item_cipher_hash` (`tenant_id`, `cipher_hash`),
    CONSTRAINT `fk_file_chunk_manifest_item_manifest`
        FOREIGN KEY (`manifest_id`) REFERENCES `file_chunk_manifest` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='File chunk manifest ordered chunk table';
