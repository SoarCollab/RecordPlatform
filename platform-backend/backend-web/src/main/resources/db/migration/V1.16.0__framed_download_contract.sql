-- V1.16.0: Add nullable framed-encryption metadata for bounded authenticated downloads.

ALTER TABLE `file_chunk_manifest`
    ADD COLUMN `encryption_metadata` JSON DEFAULT NULL
        COMMENT 'Versioned framed-encryption descriptor; null for historical manifests'
        AFTER `storage_backend`;

ALTER TABLE `file_chunk_manifest_item`
    ADD COLUMN `plain_size` BIGINT DEFAULT NULL
        COMMENT 'Plaintext bytes represented by this stored object'
        AFTER `size`,
    ADD COLUMN `frame_count` INT DEFAULT NULL
        COMMENT 'Authenticated frame count for framed encryption v2'
        AFTER `plain_size`;
