-- V1.11.0: Add idempotent, recoverable, and auditable Merkle batch chain submission state.

ALTER TABLE `attestation_batch`
    ADD COLUMN `idempotency_key` VARCHAR(64) DEFAULT NULL COMMENT 'Stable tenant-scoped issuance idempotency key' AFTER `batch_no`,
    ADD COLUMN `attempt_count` INT NOT NULL DEFAULT 0 COMMENT 'Number of submission claims' AFTER `chain_error`,
    ADD COLUMN `next_attempt_at` DATETIME DEFAULT NULL COMMENT 'Earliest next retry time' AFTER `attempt_count`,
    ADD COLUMN `claim_token` VARCHAR(36) DEFAULT NULL COMMENT 'Current worker claim token' AFTER `next_attempt_at`,
    ADD COLUMN `lease_expires_at` DATETIME DEFAULT NULL COMMENT 'Current worker lease expiration' AFTER `claim_token`,
    ADD COLUMN `confirmation_source` VARCHAR(32) DEFAULT NULL COMMENT 'How the chain record was confirmed' AFTER `lease_expires_at`,
    ADD COLUMN `state_version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Monotonic state transition version' AFTER `confirmation_source`;

UPDATE `attestation_batch`
SET `idempotency_key` = CONCAT('legacy:', `id`)
WHERE `idempotency_key` IS NULL;

ALTER TABLE `attestation_batch`
    MODIFY COLUMN `idempotency_key` VARCHAR(64) NOT NULL COMMENT 'Stable tenant-scoped issuance idempotency key',
    ADD UNIQUE KEY `uk_attestation_batch_idempotency` (`tenant_id`, `idempotency_key`),
    ADD KEY `idx_attestation_batch_submission` (`tenant_id`, `status`, `next_attempt_at`, `lease_expires_at`);

CREATE TABLE IF NOT EXISTS `attestation_batch_attempt` (
    `id`                  BIGINT        NOT NULL COMMENT 'Snowflake attempt ID',
    `tenant_id`           BIGINT        NOT NULL COMMENT 'Tenant ID',
    `batch_id`            BIGINT        NOT NULL COMMENT 'Attestation batch ID',
    `attempt_no`          INT           NOT NULL COMMENT 'Monotonic submission attempt number',
    `claim_token`         VARCHAR(36)   NOT NULL COMMENT 'Worker claim token',
    `status`              VARCHAR(32)   NOT NULL COMMENT 'Attempt result status',
    `confirmation_source` VARCHAR(32)   DEFAULT NULL COMMENT 'Chain confirmation source',
    `transaction_hash`    VARCHAR(255)  DEFAULT NULL COMMENT 'Observed chain transaction hash',
    `chain_root`          VARCHAR(128)  DEFAULT NULL COMMENT 'Observed chain Merkle root',
    `error_message`       VARCHAR(1024) DEFAULT NULL COMMENT 'Failure or reconciliation detail',
    `create_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Claim creation time',
    `update_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last result update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attestation_attempt_no` (`tenant_id`, `batch_id`, `attempt_no`),
    UNIQUE KEY `uk_attestation_attempt_claim` (`tenant_id`, `batch_id`, `claim_token`),
    KEY `idx_attestation_attempt_status` (`tenant_id`, `status`, `create_time`),
    CONSTRAINT `fk_attestation_attempt_batch`
        FOREIGN KEY (`batch_id`) REFERENCES `attestation_batch` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Merkle batch chain submission attempt audit';
