-- V1.3_TraceId.sql
-- 为 outbox_event 表添加 trace_id 列，支持分布式追踪

ALTER TABLE outbox_event ADD COLUMN trace_id VARCHAR(64) AFTER id;

-- 添加索引以支持按 trace_id 查询
CREATE INDEX idx_outbox_event_trace_id ON outbox_event(trace_id);
