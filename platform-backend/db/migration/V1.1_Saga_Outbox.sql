-- V1.1_Saga_Outbox.sql
-- Saga state machine and Outbox pattern tables for distributed transaction consistency

-- =============================================
-- Saga State Table
-- Tracks file upload saga state transitions
-- =============================================
CREATE TABLE IF NOT EXISTS file_saga (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Saga ID',
    file_id BIGINT COMMENT 'Associated file ID (nullable before file record created)',
    request_id VARCHAR(64) NOT NULL COMMENT 'Unique request identifier for idempotency',
    user_id BIGINT NOT NULL COMMENT 'User who initiated the upload',
    file_name VARCHAR(255) NOT NULL COMMENT 'Original file name',
    current_step VARCHAR(32) NOT NULL COMMENT 'Current step: PENDING, MINIO_UPLOADING, MINIO_UPLOADED, CHAIN_STORING, COMPLETED',
    status VARCHAR(32) NOT NULL COMMENT 'Status: RUNNING, SUCCEEDED, FAILED, COMPENSATING, COMPENSATED',
    payload JSON COMMENT 'Step-specific data (file hashes, paths, etc.)',
    retry_count INT DEFAULT 0 COMMENT 'Number of compensation retries',
    last_error TEXT COMMENT 'Last error message if failed',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Saga creation time',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update time',

    UNIQUE KEY uk_request_id (request_id),
    INDEX idx_status_step (status, current_step),
    INDEX idx_file_id (file_id),
    INDEX idx_user_id (user_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='File upload saga state';

-- =============================================
-- Outbox Event Table
-- Stores events to be published to message queue
-- =============================================
CREATE TABLE IF NOT EXISTS outbox_event (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Event UUID',
    aggregate_type VARCHAR(64) NOT NULL COMMENT 'Aggregate type: FILE, USER, etc.',
    aggregate_id BIGINT NOT NULL COMMENT 'Aggregate ID (e.g., file_id)',
    event_type VARCHAR(64) NOT NULL COMMENT 'Event type: file.stored, file.deleted, etc.',
    payload JSON NOT NULL COMMENT 'Event payload in JSON format',
    status VARCHAR(16) DEFAULT 'PENDING' COMMENT 'Status: PENDING, SENT, FAILED',
    next_attempt_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Next publish attempt time',
    retry_count INT DEFAULT 0 COMMENT 'Number of publish retries',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Event creation time',
    sent_time DATETIME COMMENT 'Time when event was successfully sent',

    INDEX idx_status_next (status, next_attempt_at),
    INDEX idx_aggregate (aggregate_type, aggregate_id),
    INDEX idx_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Outbox events for reliable messaging';

-- =============================================
-- Processed Message Table
-- Ensures idempotent message consumption
-- =============================================
CREATE TABLE IF NOT EXISTS processed_message (
    message_id VARCHAR(64) PRIMARY KEY COMMENT 'Message UUID from RabbitMQ',
    event_type VARCHAR(64) COMMENT 'Event type that was processed',
    processed_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Processing time',

    INDEX idx_processed_at (processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Processed messages for idempotency';

-- =============================================
-- Cleanup old processed messages (optional job)
-- Run periodically to prevent table bloat
-- =============================================
-- DELETE FROM processed_message WHERE processed_at < DATE_SUB(NOW(), INTERVAL 7 DAY);
