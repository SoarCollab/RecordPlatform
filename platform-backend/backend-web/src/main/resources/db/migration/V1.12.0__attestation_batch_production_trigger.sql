-- V1.12.0: Add durable production candidates and explicit Merkle leaf evidence semantics.

ALTER TABLE `attestation_leaf`
    ADD COLUMN `file_version` INT DEFAULT NULL COMMENT 'File version captured when the leaf was issued' AFTER `file_id`,
    ADD COLUMN `manifest_id` BIGINT DEFAULT NULL COMMENT 'Chunk manifest used as production evidence' AFTER `file_version`,
    ADD COLUMN `evidence_type` VARCHAR(32) DEFAULT NULL COMMENT 'LEGACY_CHAIN_RECORD_ID or MANIFEST_HASH' AFTER `file_hash`,
    ADD COLUMN `evidence_hash` VARCHAR(255) DEFAULT NULL COMMENT 'Exact value used to derive the Merkle leaf' AFTER `evidence_type`,
    ADD COLUMN `chain_record_id` VARCHAR(255) DEFAULT NULL COMMENT 'Single-file blockchain record identifier' AFTER `evidence_hash`;

UPDATE `attestation_leaf` leaf
LEFT JOIN `file` file_record
       ON file_record.id = leaf.file_id
      AND file_record.tenant_id = leaf.tenant_id
SET leaf.file_version = COALESCE(leaf.file_version, file_record.version, 1),
    leaf.evidence_type = COALESCE(leaf.evidence_type, 'LEGACY_CHAIN_RECORD_ID'),
    leaf.evidence_hash = COALESCE(leaf.evidence_hash, leaf.file_hash),
    leaf.chain_record_id = COALESCE(leaf.chain_record_id, file_record.file_hash, leaf.file_hash)
WHERE leaf.evidence_hash IS NULL
   OR leaf.evidence_type IS NULL
   OR leaf.file_version IS NULL
   OR leaf.chain_record_id IS NULL;

ALTER TABLE `attestation_leaf`
    MODIFY COLUMN `file_version` INT NOT NULL COMMENT 'File version captured when the leaf was issued',
    MODIFY COLUMN `evidence_type` VARCHAR(32) NOT NULL COMMENT 'LEGACY_CHAIN_RECORD_ID or MANIFEST_HASH',
    MODIFY COLUMN `evidence_hash` VARCHAR(255) NOT NULL COMMENT 'Exact value used to derive the Merkle leaf',
    ADD KEY `idx_attestation_leaf_file_version` (`tenant_id`, `file_id`, `file_version`),
    ADD KEY `idx_attestation_leaf_manifest` (`tenant_id`, `manifest_id`);

ALTER TABLE `file`
    ADD KEY `idx_file_attestation_candidate`
        (`tenant_id`, `status`, `deleted`, `create_time`, `id`);

ALTER TABLE `file_chunk_manifest`
    ADD KEY `idx_manifest_attestation_candidate`
        (`tenant_id`, `status`, `deleted`, `file_id`, `file_version`, `id`);

CREATE TABLE IF NOT EXISTS `attestation_batch_candidate` (
    `id`               BIGINT       NOT NULL COMMENT 'Snowflake candidate ID',
    `tenant_id`        BIGINT       NOT NULL COMMENT 'Tenant ID',
    `file_id`          BIGINT       NOT NULL COMMENT 'File table ID',
    `file_version`     INT          NOT NULL COMMENT 'File version admitted to production batching',
    `manifest_id`      BIGINT       NOT NULL COMMENT 'Manifest evidence snapshot ID',
    `evidence_type`    VARCHAR(32)  NOT NULL COMMENT 'Production evidence type',
    `evidence_hash`    VARCHAR(255) DEFAULT NULL COMMENT 'Exact Merkle leaf input evidence; null only for invalid dead-letter sources',
    `chain_record_id`  VARCHAR(255) DEFAULT NULL COMMENT 'Single-file blockchain record identifier',
    `status`           VARCHAR(32)  NOT NULL COMMENT 'READY, CLAIMED, BATCHED, or DEAD_LETTER',
    `batch_id`         BIGINT       DEFAULT NULL COMMENT 'Bound attestation batch ID',
    `claim_token`      VARCHAR(36)  DEFAULT NULL COMMENT 'Current worker claim token',
    `lease_expires_at` DATETIME     DEFAULT NULL COMMENT 'Current claim lease expiration',
    `attempt_count`    INT          NOT NULL DEFAULT 0 COMMENT 'Candidate processing claim count',
    `last_error`       VARCHAR(512) DEFAULT NULL COMMENT 'Last candidate processing error',
    `eligible_at`      DATETIME     NOT NULL COMMENT 'Backlog admission time used by the flush window',
    `batched_at`       DATETIME     DEFAULT NULL COMMENT 'Atomic batch binding time',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`          TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attestation_candidate_file_version` (`tenant_id`, `file_id`, `file_version`),
    KEY `idx_attestation_candidate_ready` (`tenant_id`, `deleted`, `status`, `eligible_at`, `id`),
    KEY `idx_attestation_candidate_claim` (`tenant_id`, `claim_token`, `status`, `deleted`),
    KEY `idx_attestation_candidate_lease` (`tenant_id`, `deleted`, `status`, `lease_expires_at`),
    KEY `idx_attestation_candidate_batch` (`tenant_id`, `deleted`, `batch_id`),
    CONSTRAINT `fk_attestation_candidate_manifest`
        FOREIGN KEY (`manifest_id`) REFERENCES `file_chunk_manifest` (`id`),
    CONSTRAINT `fk_attestation_candidate_batch`
        FOREIGN KEY (`batch_id`) REFERENCES `attestation_batch` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Durable production Merkle batch candidate ledger';
