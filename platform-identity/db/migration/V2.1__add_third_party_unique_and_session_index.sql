-- V2.1 统一第三方账号唯一约束 & 用户会话索引
-- 1. 将同一用户在同一 provider 下的重复绑定逻辑删除，保留最新记录
UPDATE third_party_account t
SET t.deleted = 1,
    t.update_time = NOW()
WHERE t.deleted = 0
  AND EXISTS (
    SELECT 1
    FROM third_party_account other
    WHERE other.user_id = t.user_id
      AND other.provider = t.provider
      AND other.deleted = 0
      AND other.id > t.id
  );

-- 2. 替换索引为唯一约束 (user_id, provider, deleted)
DROP INDEX idx_third_party_user_provider ON third_party_account;
ALTER TABLE third_party_account
    ADD UNIQUE INDEX uk_third_party_user_provider_deleted (user_id, provider, deleted);

-- 3. 新增用户会话按设备的复合索引
ALTER TABLE user_session
    ADD INDEX idx_user_session_user_device_status (user_id, device_fingerprint, status);
