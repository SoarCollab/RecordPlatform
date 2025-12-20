-- V1.1.1: 添加复合索引优化查询性能
-- 根据代码审查发现的查询模式添加缺失的索引

DELIMITER //

-- 创建辅助存储过程：安全创建索引（如果不存在）
DROP PROCEDURE IF EXISTS create_index_if_not_exists//
CREATE PROCEDURE create_index_if_not_exists(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_index_columns VARCHAR(256)
)
BEGIN
    DECLARE index_exists INT DEFAULT 0;
    
    SELECT COUNT(*) INTO index_exists
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND INDEX_NAME = p_index_name;
    
    IF index_exists = 0 THEN
        SET @sql = CONCAT('CREATE INDEX ', p_index_name, ' ON ', p_table_name, '(', p_index_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//

DELIMITER ;

-- ============================
-- file 表复合索引
-- ============================

-- 租户+用户复合索引（文件列表查询优化）
-- 用于: getUserFilesList(), getUserFilesPage() 等高频查询
CALL create_index_if_not_exists('file', 'idx_file_tenant_uid', 'tenant_id, uid');

-- 租户+状态复合索引（状态过滤查询优化）
-- 用于: 按状态查询文件（如查找上传中的文件）
CALL create_index_if_not_exists('file', 'idx_file_tenant_status', 'tenant_id, status');

-- 租户+删除标记+创建时间（清理任务优化）
-- 用于: FileCleanupTask 定时清理已删除文件
CALL create_index_if_not_exists('file', 'idx_file_tenant_deleted_time', 'tenant_id, deleted, create_time');

-- ============================
-- sys_operation_log 表索引
-- ============================

-- 用户ID索引（按用户查询日志）
-- 用于: 用户操作历史查询
CALL create_index_if_not_exists('sys_operation_log', 'idx_operation_log_user_id', 'user_id');

-- 时间+状态复合索引（错误日志查询优化）
-- 用于: selectErrorOperationsBetween 等时间范围查询
CALL create_index_if_not_exists('sys_operation_log', 'idx_operation_log_time_status', 'operation_time, status');

-- IP地址索引（安全分析）
-- 用于: 高频操作检测、异常登录分析
CALL create_index_if_not_exists('sys_operation_log', 'idx_operation_log_request_ip', 'request_ip');

-- ============================
-- ticket 表索引
-- ============================

-- 工单编号日期前缀索引（序列号查询优化）
-- 用于: getMaxDailySequence() 查询当日最大序列号
-- 注意: ticket_no 格式为 TKyyyyMMddNNNN
CALL create_index_if_not_exists('ticket', 'idx_ticket_ticket_no', 'ticket_no');

-- 租户+创建者复合索引（用户工单列表）
-- 用于: getUserTickets() 查询用户的工单
CALL create_index_if_not_exists('ticket', 'idx_ticket_tenant_creator', 'tenant_id, creator_id');

-- 租户+处理人复合索引（管理员工单列表）
-- 用于: getAdminTickets() 查询分配给管理员的工单
CALL create_index_if_not_exists('ticket', 'idx_ticket_tenant_assignee', 'tenant_id, assignee_id');

-- 清理辅助存储过程
DROP PROCEDURE IF EXISTS create_index_if_not_exists;
