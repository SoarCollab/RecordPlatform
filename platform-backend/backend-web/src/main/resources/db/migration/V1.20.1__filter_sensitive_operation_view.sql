-- Restrict the sensitive-operation view to the established audit categories.
-- This forward migration repairs already-deployed databases without rewriting released history.
CREATE OR REPLACE VIEW `v_sensitive_operations` AS
SELECT
    `id`,
    `tenant_id`,
    `module`,
    `operation_type`,
    `description`,
    `method`,
    `request_url`,
    `request_method`,
    `request_ip`,
    `request_param`,
    `response_result`,
    `status`,
    `error_msg`,
    `user_id`,
    `username`,
    `operation_time`,
    `execution_time`
FROM `sys_operation_log`
WHERE `operation_type` IN ('删除', '授权', '撤销', '备份', '上报');
