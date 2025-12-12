-- V1.1.1: 添加复合索引优化查询性能
-- 根据代码审查发现的查询模式添加缺失的索引

-- ============================
-- file 表复合索引
-- ============================

-- 租户+用户复合索引（文件列表查询优化）
-- 用于: getUserFilesList(), getUserFilesPage() 等高频查询
CREATE INDEX IF NOT EXISTS idx_file_tenant_uid ON file(tenant_id, uid);

-- 租户+状态复合索引（状态过滤查询优化）
-- 用于: 按状态查询文件（如查找上传中的文件）
CREATE INDEX IF NOT EXISTS idx_file_tenant_status ON file(tenant_id, status);

-- 租户+删除标记+创建时间（清理任务优化）
-- 用于: FileCleanupTask 定时清理已删除文件
CREATE INDEX IF NOT EXISTS idx_file_tenant_deleted_time ON file(tenant_id, deleted, create_time);

-- ============================
-- sys_operation_log 表索引
-- ============================

-- 用户ID索引（按用户查询日志）
-- 用于: 用户操作历史查询
CREATE INDEX IF NOT EXISTS idx_operation_log_user_id ON sys_operation_log(user_id);

-- 时间+状态复合索引（错误日志查询优化）
-- 用于: selectErrorOperationsBetween 等时间范围查询
CREATE INDEX IF NOT EXISTS idx_operation_log_time_status ON sys_operation_log(operation_time, status);

-- IP地址索引（安全分析）
-- 用于: 高频操作检测、异常登录分析
CREATE INDEX IF NOT EXISTS idx_operation_log_request_ip ON sys_operation_log(request_ip);

-- ============================
-- ticket 表索引
-- ============================

-- 工单编号日期前缀索引（序列号查询优化）
-- 用于: getMaxDailySequence() 查询当日最大序列号
-- 注意: ticket_no 格式为 TKyyyyMMddNNNN
CREATE INDEX IF NOT EXISTS idx_ticket_ticket_no ON ticket(ticket_no);

-- 租户+创建者复合索引（用户工单列表）
-- 用于: getUserTickets() 查询用户的工单
CREATE INDEX IF NOT EXISTS idx_ticket_tenant_creator ON ticket(tenant_id, creator_id);

-- 租户+处理人复合索引（管理员工单列表）
-- 用于: getAdminTickets() 查询分配给管理员的工单
CREATE INDEX IF NOT EXISTS idx_ticket_tenant_assignee ON ticket(tenant_id, assignee_id);
