-- V1.20.0: Add tenant runtime crypto policy and explicit historical provider/suite routing.

-- Historical envelope metadata is made explicit before nullable suite columns become mandatory.
UPDATE `file_key_envelope`
   SET `algorithm_suite` = 'RP-AES256-GCM-CHUNK-CHAIN-V1'
 WHERE `algorithm_suite` IS NULL OR TRIM(`algorithm_suite`) = '';

UPDATE `file_key_envelope`
   SET `signature_suite` = 'UNSIGNED-V1'
 WHERE `signature_suite` IS NULL OR TRIM(`signature_suite`) = '';

UPDATE `file_key_envelope`
   SET `kem_suite` = 'NONE-V1'
 WHERE `kem_suite` IS NULL OR TRIM(`kem_suite`) = '';

UPDATE `file_key_envelope`
   SET `proof_suite` = 'RP-MERKLE-SHA256-V1'
 WHERE `proof_suite` IS NULL OR TRIM(`proof_suite`) = '';

ALTER TABLE `file_key_envelope`
    MODIFY COLUMN `algorithm_suite` VARCHAR(96) NOT NULL COMMENT 'Persisted content encryption suite',
    MODIFY COLUMN `signature_suite` VARCHAR(96) NOT NULL COMMENT 'Persisted envelope signature suite',
    MODIFY COLUMN `kem_suite` VARCHAR(96) NOT NULL COMMENT 'Persisted key-establishment suite',
    MODIFY COLUMN `proof_suite` VARCHAR(96) NOT NULL COMMENT 'Persisted proof construction suite';

-- Existing signed-proof v2 rows were all produced by the local Ed25519 provider contract v1.
ALTER TABLE `proof_signing_key`
    ADD COLUMN `signing_provider` VARCHAR(64) NOT NULL DEFAULT 'local-ed25519'
        COMMENT 'Persisted signing provider ID' AFTER `key_version`,
    ADD COLUMN `signing_provider_contract` INT NOT NULL DEFAULT 1
        COMMENT 'Persisted signing provider contract' AFTER `signing_provider`,
    ADD COLUMN `signature_suite` VARCHAR(96) NOT NULL DEFAULT 'JWS-EDDSA-ED25519-V1'
        COMMENT 'Persisted signature suite' AFTER `signing_provider_contract`,
    ADD COLUMN `proof_suite` VARCHAR(96) NOT NULL DEFAULT 'RP-SIGNED-PROOF-ZIP-V2'
        COMMENT 'Persisted proof suite' AFTER `signature_suite`,
    ADD KEY `idx_proof_signing_provider_suite`
        (`signing_provider`, `signing_provider_contract`, `signature_suite`, `proof_suite`);

ALTER TABLE `proof_bundle_issuance`
    ADD COLUMN `signing_provider` VARCHAR(64) NOT NULL DEFAULT 'local-ed25519'
        COMMENT 'Persisted signing provider ID' AFTER `signature_jws`,
    ADD COLUMN `signing_provider_contract` INT NOT NULL DEFAULT 1
        COMMENT 'Persisted signing provider contract' AFTER `signing_provider`,
    ADD COLUMN `signature_suite` VARCHAR(96) NOT NULL DEFAULT 'JWS-EDDSA-ED25519-V1'
        COMMENT 'Persisted signature suite' AFTER `signing_provider_contract`,
    ADD COLUMN `proof_suite` VARCHAR(96) NOT NULL DEFAULT 'RP-SIGNED-PROOF-ZIP-V2'
        COMMENT 'Persisted proof suite' AFTER `signature_suite`,
    ADD KEY `idx_proof_bundle_provider_suite`
        (`tenant_id`, `signing_provider`, `signing_provider_contract`, `signature_suite`, `proof_suite`);

-- Remove compatibility defaults after deterministic backfill so all future writes must be explicit.
ALTER TABLE `proof_signing_key`
    MODIFY COLUMN `signing_provider` VARCHAR(64) NOT NULL COMMENT 'Persisted signing provider ID',
    MODIFY COLUMN `signing_provider_contract` INT NOT NULL COMMENT 'Persisted signing provider contract',
    MODIFY COLUMN `signature_suite` VARCHAR(96) NOT NULL COMMENT 'Persisted signature suite',
    MODIFY COLUMN `proof_suite` VARCHAR(96) NOT NULL COMMENT 'Persisted proof suite';

ALTER TABLE `proof_bundle_issuance`
    MODIFY COLUMN `signing_provider` VARCHAR(64) NOT NULL COMMENT 'Persisted signing provider ID',
    MODIFY COLUMN `signing_provider_contract` INT NOT NULL COMMENT 'Persisted signing provider contract',
    MODIFY COLUMN `signature_suite` VARCHAR(96) NOT NULL COMMENT 'Persisted signature suite',
    MODIFY COLUMN `proof_suite` VARCHAR(96) NOT NULL COMMENT 'Persisted proof suite';

CREATE TABLE IF NOT EXISTS `tenant_crypto_policy` (
    `id`                              BIGINT       NOT NULL COMMENT 'Snowflake policy ID',
    `tenant_id`                       BIGINT       NOT NULL COMMENT 'Tenant boundary',
    `content_encryption_suite`        VARCHAR(96)  NOT NULL COMMENT 'New envelope content suite',
    `envelope_signature_suite`        VARCHAR(96)  NOT NULL COMMENT 'New envelope signature suite',
    `kem_suite`                       VARCHAR(96)  NOT NULL COMMENT 'New envelope KEM suite',
    `proof_suite`                     VARCHAR(96)  NOT NULL COMMENT 'New envelope proof suite',
    `wrapping_provider`               VARCHAR(64)  NOT NULL COMMENT 'New envelope wrapping provider',
    `wrapping_provider_contract`      INT          NOT NULL COMMENT 'Wrapping provider contract',
    `signed_proof_signature_suite`    VARCHAR(96)  NOT NULL COMMENT 'New signed-proof signature suite',
    `signed_proof_suite`              VARCHAR(96)  NOT NULL COMMENT 'New signed-proof format suite',
    `signing_provider`                VARCHAR(64)  NOT NULL COMMENT 'New signed-proof provider',
    `signing_provider_contract`       INT          NOT NULL COMMENT 'Signing provider contract',
    `policy_version`                  BIGINT       NOT NULL DEFAULT 1 COMMENT 'Optimistic policy version',
    `created_by`                      BIGINT       NOT NULL COMMENT 'Creating administrator',
    `updated_by`                      BIGINT       NOT NULL COMMENT 'Last updating administrator',
    `create_time`                     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`                     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`                         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_crypto_policy` (`tenant_id`, `deleted`),
    UNIQUE KEY `uk_tenant_crypto_policy_identity` (`tenant_id`, `id`),
    KEY `idx_tenant_crypto_policy_version` (`tenant_id`, `policy_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Tenant runtime cryptographic suite and provider policy';

CREATE TABLE IF NOT EXISTS `tenant_crypto_policy_audit` (
    `id`                       BIGINT      NOT NULL COMMENT 'Snowflake audit ID',
    `tenant_id`                BIGINT      NOT NULL COMMENT 'Tenant boundary',
    `policy_id`                BIGINT      DEFAULT NULL COMMENT 'Policy ID',
    `policy_version`           BIGINT      DEFAULT NULL COMMENT 'Policy version after action',
    `actor_id`                 BIGINT      NOT NULL COMMENT 'Administrator ID',
    `action`                   VARCHAR(32) NOT NULL COMMENT 'CREATE or UPDATE',
    `outcome`                  VARCHAR(16) NOT NULL COMMENT 'SUCCESS or FAILURE',
    `old_policy_fingerprint`   CHAR(64)    DEFAULT NULL COMMENT 'SHA-256 of prior non-secret policy',
    `new_policy_fingerprint`   CHAR(64)    DEFAULT NULL COMMENT 'SHA-256 of resulting non-secret policy',
    `failure_reason`           VARCHAR(64) DEFAULT NULL COMMENT 'Stable failure category',
    `create_time`              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted`                  TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_crypto_policy_audit`
        (`tenant_id`, `policy_id`, `create_time`, `id`),
    CONSTRAINT `fk_tenant_crypto_policy_audit_policy`
        FOREIGN KEY (`tenant_id`, `policy_id`)
        REFERENCES `tenant_crypto_policy` (`tenant_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Sanitized tenant crypto policy audit evidence';
