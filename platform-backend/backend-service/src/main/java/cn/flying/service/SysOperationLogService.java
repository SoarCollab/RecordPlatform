package cn.flying.service;

import cn.flying.dao.dto.SysOperationLog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 系统操作日志服务接口
 * 注：查询和导出功能已迁移至 SysAuditService，避免重复
 */
public interface SysOperationLogService extends IService<SysOperationLog> {

    /**
     * 保存操作日志
     *
     * @param operationLog 操作日志信息
     */
    void saveOperationLog(SysOperationLog operationLog);

    /**
     * 根据ID获取操作日志详情
     *
     * @param id 日志ID
     * @return 日志详情
     */
    SysOperationLog getLogDetailById(Long id);

    /**
     * 清空操作日志
     */
    void cleanOperationLogs();
} 