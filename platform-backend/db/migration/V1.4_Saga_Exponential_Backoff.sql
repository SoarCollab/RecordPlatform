-- V1.4_Saga_Exponential_Backoff.sql
-- 为 Saga 添加指数退避重试支持

-- 添加下次重试时间字段
ALTER TABLE file_saga
ADD COLUMN next_retry_at DATETIME NULL COMMENT '下次重试时间（指数退避调度）' AFTER last_error;

-- 添加索引支持待重试 Saga 查询
CREATE INDEX idx_status_next_retry ON file_saga (status, next_retry_at);

-- 更新状态字段注释，添加新状态说明
ALTER TABLE file_saga
MODIFY COLUMN status VARCHAR(32) NOT NULL
COMMENT 'Status: RUNNING, SUCCEEDED, COMPENSATING, COMPENSATED, PENDING_COMPENSATION, FAILED';
