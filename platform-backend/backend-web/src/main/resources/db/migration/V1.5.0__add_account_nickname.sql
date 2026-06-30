-- Add nickname column to account table
ALTER TABLE `account`
    ADD COLUMN `nickname` VARCHAR(50) DEFAULT NULL COMMENT 'User nickname' AFTER `avatar`;
