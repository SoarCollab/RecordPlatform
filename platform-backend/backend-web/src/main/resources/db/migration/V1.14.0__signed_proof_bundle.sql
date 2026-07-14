-- V1.14.0: Persist original content hashes and immutable signed-proof issuance snapshots.

ALTER TABLE `file`
    ADD COLUMN `content_hash` VARCHAR(71) DEFAULT NULL
        COMMENT 'Canonical sha256-prefixed digest of original file bytes' AFTER `file_hash`,
    ADD KEY `idx_file_content_hash` (`tenant_id`, `content_hash`),
    ADD UNIQUE KEY `uk_file_tenant_id_version` (`tenant_id`, `id`, `version`);

ALTER TABLE `attestation_leaf`
    ADD UNIQUE KEY `uk_attestation_leaf_binding`
        (`tenant_id`, `file_id`, `file_version`, `id`);

CREATE TABLE `proof_signing_key` (
    `id`                     BIGINT       NOT NULL COMMENT 'Snowflake key registry ID',
    `key_id`                 VARCHAR(64)  NOT NULL COMMENT 'Stable proof signing key ID',
    `key_version`            INT          NOT NULL COMMENT 'Monotonic key version',
    `signature_algorithm`    VARCHAR(32)  NOT NULL COMMENT 'JWS signature algorithm',
    `public_key_spki`        TEXT         NOT NULL COMMENT 'Base64 X.509 SPKI public key',
    `public_key_fingerprint` VARCHAR(71)  NOT NULL COMMENT 'sha256-prefixed SPKI fingerprint',
    `status`                 VARCHAR(32)  NOT NULL COMMENT 'ACTIVE, RETIRED, REVOKED, or INVALID',
    `first_seen_at`          DATETIME(3)  NOT NULL COMMENT 'First successful registration time',
    `create_time`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`                TINYINT      NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_proof_signing_key_identity` (`key_id`, `key_version`),
    KEY `idx_proof_signing_key_status` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Global signed-proof public key registry';

CREATE TABLE `proof_bundle_issuance` (
    `id`                     BIGINT        NOT NULL COMMENT 'Snowflake issuance ID',
    `tenant_id`              BIGINT        NOT NULL COMMENT 'Tenant ID',
    `proof_id`               VARCHAR(96)   NOT NULL COMMENT 'Opaque public proof identifier',
    `file_id`                BIGINT        NOT NULL COMMENT 'File table ID',
    `file_version`           INT           NOT NULL COMMENT 'Immutable file version',
    `leaf_id`                BIGINT        NOT NULL COMMENT 'Attestation leaf ID',
    `manifest_hash`          VARCHAR(71)   NOT NULL COMMENT 'Canonical top-level proof manifest SHA-256',
    `manifest_json`          MEDIUMTEXT    NOT NULL COMMENT 'Canonical top-level proof manifest JSON',
    `signature_jws`          MEDIUMTEXT    NOT NULL COMMENT 'Compact JWS over manifest_json',
    `signature_algorithm`    VARCHAR(32)   NOT NULL COMMENT 'JWS signing algorithm',
    `key_id`                 VARCHAR(64)   NOT NULL COMMENT 'Proof signing key ID',
    `key_version`            INT           NOT NULL COMMENT 'Proof signing key version',
    `public_key_spki`        TEXT          NOT NULL COMMENT 'Base64 X.509 SPKI public key',
    `public_key_fingerprint` VARCHAR(71)   NOT NULL COMMENT 'sha256-prefixed SPKI fingerprint',
    `issued_status`          VARCHAR(32)   NOT NULL COMMENT 'Status snapshot signed at issuance',
    `status`                 VARCHAR(32)   NOT NULL COMMENT 'ACTIVE, REVOKED, SUPERSEDED, or INVALID',
    `status_version`         BIGINT        NOT NULL DEFAULT 1 COMMENT 'Monotonic public status version',
    `status_reason`          VARCHAR(256)  DEFAULT NULL COMMENT 'Bounded lifecycle reason',
    `issued_at`              DATETIME(3)   NOT NULL COMMENT 'Deterministic issuance timestamp',
    `revoked_at`             DATETIME(3)   DEFAULT NULL COMMENT 'Revocation timestamp',
    `create_time`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    `deleted`                TINYINT       NOT NULL DEFAULT 0 COMMENT 'Soft delete flag',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_proof_bundle_proof_id` (`proof_id`),
    UNIQUE KEY `uk_proof_bundle_leaf` (`tenant_id`, `leaf_id`),
    KEY `idx_proof_bundle_file_version` (`tenant_id`, `file_id`, `file_version`),
    KEY `idx_proof_bundle_leaf_binding` (`tenant_id`, `file_id`, `file_version`, `leaf_id`),
    KEY `idx_proof_bundle_key` (`key_id`, `key_version`),
    KEY `idx_proof_bundle_status` (`tenant_id`, `status`, `update_time`),
    CONSTRAINT `fk_proof_bundle_file`
        FOREIGN KEY (`tenant_id`, `file_id`, `file_version`)
        REFERENCES `file` (`tenant_id`, `id`, `version`),
    CONSTRAINT `fk_proof_bundle_leaf`
        FOREIGN KEY (`tenant_id`, `file_id`, `file_version`, `leaf_id`)
        REFERENCES `attestation_leaf` (`tenant_id`, `file_id`, `file_version`, `id`),
    CONSTRAINT `fk_proof_bundle_signing_key`
        FOREIGN KEY (`key_id`, `key_version`)
        REFERENCES `proof_signing_key` (`key_id`, `key_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Signed proof bundle issuance and public lifecycle status';
