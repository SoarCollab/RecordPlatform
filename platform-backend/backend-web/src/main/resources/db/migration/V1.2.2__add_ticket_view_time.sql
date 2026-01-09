-- Add view time columns to ticket table for unread tracking
ALTER TABLE `ticket`
ADD COLUMN `creator_last_view_time` DATETIME DEFAULT NULL COMMENT 'Creator last view time',
ADD COLUMN `assignee_last_view_time` DATETIME DEFAULT NULL COMMENT 'Assignee last view time';
