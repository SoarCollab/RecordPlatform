-- Add optimistic versions without replacing released quota or configuration rows.
SET @platform_quota_version_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'quota_policy' AND column_name = 'version'
);
SET @platform_quota_version_sql = IF(@platform_quota_version_exists = 0,
    'ALTER TABLE quota_policy ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT ''Optimistic override version''',
    'SELECT 1');
PREPARE platform_quota_version_statement FROM @platform_quota_version_sql;
EXECUTE platform_quota_version_statement;
DEALLOCATE PREPARE platform_quota_version_statement;

SET @platform_config_version_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sys_audit_config' AND column_name = 'version'
);
SET @platform_config_version_sql = IF(@platform_config_version_exists = 0,
    'ALTER TABLE sys_audit_config ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT ''Optimistic configuration version''',
    'SELECT 1');
PREPARE platform_config_version_statement FROM @platform_config_version_sql;
EXECUTE platform_config_version_statement;
DEALLOCATE PREPARE platform_config_version_statement;

-- An operation claim survives process interruption. Never automatically recycle an ambiguous claim.
CREATE TABLE IF NOT EXISTS platform_operation_log (
    id                  BIGINT NOT NULL COMMENT 'Snowflake operation ID',
    tenant_id           BIGINT NOT NULL DEFAULT 0 COMMENT 'Fixed system ownership',
    actor_id            BIGINT NOT NULL COMMENT 'Platform actor account ID',
    idempotency_key     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Canonical UUID',
    request_hash        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256 canonical command digest',
    operation           VARCHAR(64) NOT NULL COMMENT 'Code-owned operation name',
    target_tenant_id    BIGINT NULL COMMENT 'Explicit target, never caller identity',
    resource_type       VARCHAR(32) NOT NULL COMMENT 'Code-owned resource type',
    resource_id         BIGINT NULL COMMENT 'Typed target internal ID',
    resource_key        VARCHAR(64) NULL COMMENT 'Allowlisted configuration key',
    reason              VARCHAR(255) NOT NULL COMMENT 'Sanitized mandatory reason',
    before_summary      VARCHAR(1024) NULL COMMENT 'Bounded sanitized before state',
    after_summary       VARCHAR(1024) NULL COMMENT 'Bounded sanitized after state',
    status              VARCHAR(16) NOT NULL COMMENT 'PROCESSING, SUCCESS or FAILURE',
    result_json         JSON NULL COMMENT 'Allowlisted successful response only',
    error_code          INT NULL COMMENT 'Stable business failure code only',
    trace_id            VARCHAR(64) CHARACTER SET ascii NULL COMMENT 'Validated trace identifier',
    started_at          DATETIME(3) NOT NULL,
    completed_at        DATETIME(3) NULL,
    duration_ms         BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_operation_actor_key (actor_id, idempotency_key),
    KEY idx_platform_operation_started (started_at, id),
    KEY idx_platform_operation_target (target_tenant_id, started_at, id),
    KEY idx_platform_operation_status (status, started_at, id),
    CONSTRAINT chk_platform_operation_owner CHECK (tenant_id = 0 AND actor_id > 0),
    CONSTRAINT chk_platform_operation_state CHECK (
        (status = 'PROCESSING' AND completed_at IS NULL AND duration_ms IS NULL
            AND result_json IS NULL AND error_code IS NULL)
        OR (status = 'SUCCESS' AND completed_at IS NOT NULL AND duration_ms IS NOT NULL AND duration_ms >= 0
            AND result_json IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILURE' AND completed_at IS NOT NULL AND duration_ms IS NOT NULL AND duration_ms >= 0
            AND result_json IS NULL AND error_code IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='System-owned platform command outcomes and idempotency evidence';
