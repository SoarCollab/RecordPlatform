-- V1.2 Multi-Tenant baseline (shared DB, tenant_id isolation)

-- Add tenant_id column to existing tables
ALTER TABLE account       ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 AFTER id;
ALTER TABLE file          ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 AFTER id;
ALTER TABLE image_store   ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE file_saga     ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 AFTER id;
ALTER TABLE outbox_event  ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 AFTER id;

-- Indexes for tenant filtering
CREATE INDEX idx_account_tenant_id      ON account(tenant_id);
CREATE INDEX idx_file_tenant_id         ON file(tenant_id);
CREATE INDEX idx_image_store_tenant_id  ON image_store(tenant_id);
CREATE INDEX idx_file_saga_tenant_id    ON file_saga(tenant_id);
CREATE INDEX idx_outbox_event_tenant_id ON outbox_event(tenant_id);

-- Tenant master table
CREATE TABLE tenant (
    id          BIGINT       NOT NULL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL COMMENT 'Tenant display name',
    code        VARCHAR(64)  NOT NULL UNIQUE COMMENT 'Tenant code for identification',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=active, 0=disabled',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tenant master table';

-- Insert default tenant (for existing data)
INSERT INTO tenant (id, name, code, status) VALUES (0, 'Default', 'default', 1);
