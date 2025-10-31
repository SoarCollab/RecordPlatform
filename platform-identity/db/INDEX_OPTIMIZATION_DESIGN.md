# 数据库索引与约束优化方案

> 更新时间：2025-10-31  
> 执行者：Codex

## 目标概述

阶段四要求对身份服务的核心表补充约束及复合索引，重点解决以下问题：

1. **第三方账号绑定存在数据重复风险**  
   业务层假设同一用户在同一第三方平台仅保留一条有效绑定记录，但 `third_party_account` 表目前只对 `provider + third_party_id` 做唯一约束。若外部回调重复触发，可能写入多条 `user_id + provider` 相同的记录，导致解绑、刷新令牌时出现数据漂移。

2. **用户会话按设备检索缺乏组合索引**  
   `UserSessionMapper#findByUserIdAndDeviceId` 频繁根据 `user_id + device_fingerprint + status` 查询，但表上仅有单字段索引，导致批量下线/会话统计时出现全表扫描。

## 优化方案

| 表名 | 现状问题 | 调整方案 | 预期收益 |
| ---- | -------- | -------- | -------- |
| `third_party_account` | 仅对 `provider + third_party_id` 做唯一约束，无法阻止 `user_id + provider` 重复绑定 | 新增唯一约束 `uk_third_party_user_provider_deleted` (`user_id`, `provider`, `deleted`)，确保单用户单平台仅有一条有效绑定（`deleted = 0`），同时允许保留历史绑定记录 | 保证业务幂等性，避免批量撤销/解绑时出现多条记录 |
| `user_session` | 缺乏 `user_id + device_fingerprint + status` 组合索引，按设备查询或批量下线时性能差 | 新增复合索引 `idx_user_session_user_device_status` (`user_id`, `device_fingerprint`, `status`) | 提升会话幂等校验、按设备踢出等场景的查询效率 |

## 辅助说明

- 新增唯一约束时将同时保留原有的非唯一复合索引 `idx_third_party_user_provider` 以避免变更路径复杂化，调整脚本会先删除旧索引，再创建唯一约束。
- `user_session` 已存在 `idx_user_session_user_status`，新索引主要补充 `device_fingerprint` 维度，不与原索引冲突。
- 所有变更将通过 Flyway 新增版本脚本执行，同时更新 `sql/complete_init.sql` 基线 DDL。

## 执行步骤与验证

1. **备份与巡检**  
   - 执行脚本前先检查 `third_party_account` 是否存在重复的 `user_id + provider` 组合，并清理逻辑删除标记。
   - 建议在业务低峰期维护，避免锁表影响。
2. **执行迁移脚本**  
   - 启动服务时 Flyway 会自动加载 `db/migration/V2.1__add_third_party_unique_and_session_index.sql`。  
   - 若需手动执行，可在数据库主库运行：  
     ```bash
     mysql -u ${DB_USERNAME} -p platform_identity < platform-identity/db/migration/V2.1__add_third_party_unique_and_session_index.sql
     ```
3. **验证结果**  
   - `SHOW INDEX FROM third_party_account;` 确认存在 `uk_third_party_user_provider_deleted`。  
   - `SHOW INDEX FROM user_session;` 确认追加 `idx_user_session_user_device_status`。  
   - 执行撤销功能或相关查询，观察执行计划是否命中新索引。
