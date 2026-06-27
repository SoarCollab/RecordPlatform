-- Rename legacy file.contract_hash to transaction_hash for databases created before the column rename.
SET @contract_hash_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'file'
      AND COLUMN_NAME = 'contract_hash'
);

SET @transaction_hash_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'file'
      AND COLUMN_NAME = 'transaction_hash'
);

SET @rename_file_hash_column = IF(
    @contract_hash_exists > 0 AND @transaction_hash_exists = 0,
    'ALTER TABLE `file` CHANGE COLUMN `contract_hash` `transaction_hash` VARCHAR(255) DEFAULT NULL COMMENT ''Blockchain tx hash''',
    'SELECT 1'
);

PREPARE rename_file_hash_column_stmt FROM @rename_file_hash_column;
EXECUTE rename_file_hash_column_stmt;
DEALLOCATE PREPARE rename_file_hash_column_stmt;
