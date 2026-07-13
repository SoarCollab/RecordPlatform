-- Persist manifest-driven integrity severity/evidence and accelerate open-alert deduplication.
ALTER TABLE `integrity_alert`
    ADD COLUMN `severity` VARCHAR(16) NOT NULL DEFAULT 'ERROR'
        COMMENT 'WARNING, ERROR, CRITICAL' AFTER `alert_type`,
    ADD COLUMN `evidence` VARCHAR(1024) DEFAULT NULL
        COMMENT 'Bounded integrity observation evidence' AFTER `severity`,
    ADD INDEX `idx_integrity_alert_open_dedup`
        (`tenant_id`, `file_id`, `alert_type`, `status`, `deleted`);

-- Preserve the semantic severity of legacy rows created before the column existed.
UPDATE `integrity_alert`
SET `severity` = CASE
    WHEN `alert_type` = 'CHAIN_NOT_FOUND' THEN 'ERROR'
    ELSE 'CRITICAL'
END;
