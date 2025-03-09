package cn.flying.service;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.audit.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 系统审计服务接口
 */
public interface SysAuditService {

    /**
     * 分页查询操作日志
     * @param queryVO 查询条件
     * @return 分页结果
     */
    IPage<SysOperationLog> queryOperationLogs(AuditLogQueryVO queryVO);
    
    /**
     * 获取高频操作记录
     * @return 高频操作列表
     */
    List<HighFrequencyOperationVO> getHighFrequencyOperations();
    
    /**
     * 获取敏感操作记录
     * @param queryVO 查询条件
     * @return 敏感操作分页结果
     */
    IPage<SysOperationLog> getSensitiveOperations(AuditLogQueryVO queryVO);
    
    /**
     * 获取错误操作统计
     * @return 错误操作统计列表
     */
    List<ErrorOperationStatsVO> getErrorOperationStats();
    
    /**
     * 获取用户操作时间分布
     * @return 用户操作时间分布数据
     */
    List<UserTimeDistributionVO> getUserTimeDistribution();
    
    /**
     * 查询审计配置列表
     * @return 审计配置列表
     */
    List<AuditConfigVO> getAuditConfigs();
    
    /**
     * 更新审计配置
     * @param configVO 配置信息
     * @return 是否成功
     */
    boolean updateAuditConfig(AuditConfigVO configVO);
    
    /**
     * 获取操作日志详情
     * @param id 日志ID
     * @return 日志详情
     */
    SysOperationLog getLogDetail(Long id);
    
    /**
     * 导出操作日志
     * @param queryVO 查询条件
     * @return 日志列表
     */
    List<SysOperationLog> exportOperationLogs(AuditLogQueryVO queryVO);
    
    /**
     * 获取审计概览数据
     * @return 概览数据
     */
    Map<String, Object> getAuditOverview();
    
    /**
     * 手动检查异常操作
     * @return 检查结果
     */
    Map<String, Object> checkAnomalies();
    
    /**
     * 执行日志备份
     * @param days 备份多少天前的日志
     * @param deleteAfterBackup 备份后是否删除
     * @return 备份结果
     */
    String backupLogs(Integer days, Boolean deleteAfterBackup);
} 