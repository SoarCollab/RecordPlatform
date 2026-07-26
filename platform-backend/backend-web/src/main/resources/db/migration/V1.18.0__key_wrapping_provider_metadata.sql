-- V1.18.0: Add versioned provider routing metadata for file key envelopes.

ALTER TABLE `file_key_envelope`
    ADD COLUMN `provider_contract_version` INT NOT NULL DEFAULT 1
        COMMENT 'Versioned key-wrapping provider contract' AFTER `kms_provider`,
    ADD COLUMN `provider_key_version` VARCHAR(128) DEFAULT NULL
        COMMENT 'Provider-native wrapping key material version' AFTER `kms_key_id`,
    ADD COLUMN `context_schema` VARCHAR(64) NOT NULL DEFAULT 'rp-file-envelope-aad-v1'
        COMMENT 'Canonical wrapping context schema' AFTER `provider_key_version`,
    MODIFY COLUMN `kms_key_id` VARCHAR(512) NOT NULL COMMENT 'Provider key reference',
    MODIFY COLUMN `wrapping_iv` VARCHAR(64) DEFAULT NULL COMMENT 'Optional Base64 wrapping IV/nonce';

UPDATE `file_key_envelope`
SET `kms_provider` = 'local',
    `provider_contract_version` = 1,
    `provider_key_version` = CAST(`key_version` AS CHAR),
    `context_schema` = 'rp-file-envelope-aad-v1';

ALTER TABLE `file_key_envelope`
    ADD KEY `idx_file_key_envelope_target` (
        `tenant_id`, `file_id`, `recipient_type`, `recipient_id`, `status`,
        `kms_provider`, `provider_contract_version`, `kms_key_id`(128),
        `provider_key_version`, `key_version`, `wrapping_algorithm`, `context_schema`, `deleted`
    );

ALTER TABLE `file_key_audit_log`
    ADD COLUMN `kms_provider` VARCHAR(64) DEFAULT NULL
        COMMENT 'Stable wrapping provider id' AFTER `key_version`,
    ADD COLUMN `provider_contract_version` INT DEFAULT NULL
        COMMENT 'Versioned provider contract' AFTER `kms_provider`,
    ADD COLUMN `provider_key_version` VARCHAR(128) DEFAULT NULL
        COMMENT 'Provider-native wrapping key version' AFTER `provider_contract_version`,
    ADD COLUMN `key_id_fingerprint` CHAR(64) DEFAULT NULL
        COMMENT 'SHA-256 fingerprint of provider key reference' AFTER `provider_key_version`,
    ADD COLUMN `wrapping_algorithm` VARCHAR(64) DEFAULT NULL
        COMMENT 'Provider wrapping algorithm' AFTER `key_id_fingerprint`,
    ADD COLUMN `algorithm_suite` VARCHAR(96) DEFAULT NULL
        COMMENT 'File content crypto suite' AFTER `wrapping_algorithm`,
    ADD COLUMN `failure_category` VARCHAR(64) DEFAULT NULL
        COMMENT 'Stable provider-neutral failure category' AFTER `algorithm_suite`,
    ADD KEY `idx_file_key_audit_provider` (
        `tenant_id`, `kms_provider`, `operation`, `failure_category`, `create_time`
    );
