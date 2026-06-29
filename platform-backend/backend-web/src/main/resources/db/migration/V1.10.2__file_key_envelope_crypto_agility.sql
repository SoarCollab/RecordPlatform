-- V1.10.2: Add crypto-agility suite metadata to file key envelopes.

ALTER TABLE `file_key_envelope`
    ADD COLUMN `signature_suite` VARCHAR(96) DEFAULT NULL COMMENT 'Proof or issuer signature suite identifier' AFTER `algorithm_suite`,
    ADD COLUMN `kem_suite`       VARCHAR(96) DEFAULT NULL COMMENT 'Recipient key-establishment suite identifier' AFTER `signature_suite`,
    ADD COLUMN `proof_suite`     VARCHAR(96) DEFAULT NULL COMMENT 'Proof construction suite identifier' AFTER `kem_suite`,
    ADD COLUMN `deprecated_after` DATETIME DEFAULT NULL COMMENT 'Suite deprecation cutoff for this envelope metadata' AFTER `status`;
