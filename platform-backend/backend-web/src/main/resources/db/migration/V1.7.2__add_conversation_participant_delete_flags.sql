-- V1.7.2: Track conversation deletion per participant.
-- A conversation delete should hide the conversation only from the requesting user.

ALTER TABLE `conversation`
    ADD COLUMN `participant_a_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '参与者A是否已隐藏该会话 0=可见 1=隐藏' AFTER `deleted`,
    ADD COLUMN `participant_b_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '参与者B是否已隐藏该会话 0=可见 1=隐藏' AFTER `participant_a_deleted`;

CREATE INDEX `idx_conversation_participant_a_visible`
    ON `conversation` (`tenant_id`, `participant_a`, `participant_a_deleted`, `last_message_at`);

CREATE INDEX `idx_conversation_participant_b_visible`
    ON `conversation` (`tenant_id`, `participant_b`, `participant_b_deleted`, `last_message_at`);
