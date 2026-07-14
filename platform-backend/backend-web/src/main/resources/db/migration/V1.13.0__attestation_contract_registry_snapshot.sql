-- V1.13.0: Bind each new Merkle attestation batch to an immutable verified contract registry snapshot.

ALTER TABLE `attestation_batch`
    ADD COLUMN `contract_registry_fingerprint` VARCHAR(71) DEFAULT NULL COMMENT 'Immutable registry entry SHA-256 fingerprint' AFTER `confirmation_source`,
    ADD COLUMN `contract_registry_json` JSON DEFAULT NULL COMMENT 'Complete verified contract registry snapshot' AFTER `contract_registry_fingerprint`,
    ADD COLUMN `chain_type` VARCHAR(32) DEFAULT NULL COMMENT 'Verified blockchain adapter type' AFTER `contract_registry_json`,
    ADD COLUMN `chain_id` VARCHAR(128) DEFAULT NULL COMMENT 'Verified node chain ID' AFTER `chain_type`,
    ADD COLUMN `chain_group_id` VARCHAR(128) DEFAULT NULL COMMENT 'Verified FISCO group ID' AFTER `chain_id`,
    ADD COLUMN `contract_name` VARCHAR(64) DEFAULT NULL COMMENT 'Verified contract name' AFTER `chain_group_id`,
    ADD COLUMN `contract_version` VARCHAR(64) DEFAULT NULL COMMENT 'Verified contract semantic version' AFTER `contract_name`,
    ADD COLUMN `contract_address` VARCHAR(128) DEFAULT NULL COMMENT 'Verified active contract address' AFTER `contract_version`,
    ADD COLUMN `contract_abi_sha256` VARCHAR(71) DEFAULT NULL COMMENT 'Canonical ABI SHA-256 fingerprint' AFTER `contract_address`,
    ADD COLUMN `contract_artifact_bytecode_sha256` VARCHAR(71) DEFAULT NULL COMMENT 'Creation bytecode SHA-256 fingerprint' AFTER `contract_abi_sha256`,
    ADD COLUMN `contract_code_sha256` VARCHAR(71) DEFAULT NULL COMMENT 'On-chain runtime code SHA-256 fingerprint' AFTER `contract_artifact_bytecode_sha256`,
    ADD COLUMN `contract_status` VARCHAR(32) DEFAULT NULL COMMENT 'Registry lifecycle status at issuance' AFTER `contract_code_sha256`,
    ADD KEY `idx_attestation_batch_registry`
        (`tenant_id`, `contract_registry_fingerprint`, `deleted`),
    ADD KEY `idx_attestation_batch_contract`
        (`tenant_id`, `contract_address`, `deleted`);

-- Existing rows intentionally remain NULL: their historical address/ABI cannot be reconstructed safely.
