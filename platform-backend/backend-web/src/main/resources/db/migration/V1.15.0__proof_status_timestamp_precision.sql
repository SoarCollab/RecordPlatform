-- V1.15.0: Preserve millisecond ordering between signed-proof issuance and public status updates.

ALTER TABLE `proof_bundle_issuance`
    MODIFY COLUMN `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Create time',
    MODIFY COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Update time';

UPDATE `proof_bundle_issuance`
SET `create_time` = CASE
        WHEN `create_time` < `issued_at` THEN `issued_at`
        ELSE `create_time`
    END,
    `update_time` = CASE
        WHEN `update_time` < `issued_at` THEN `issued_at`
        ELSE `update_time`
    END
WHERE `create_time` < `issued_at`
   OR `update_time` < `issued_at`;
