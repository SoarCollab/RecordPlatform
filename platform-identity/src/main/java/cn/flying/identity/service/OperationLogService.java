package cn.flying.identity.service;

import cn.flying.identity.dto.OperationLogEntity;
import cn.flying.platformapi.constant.Result;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 操作日志服务接口
 * 提供操作日志的记录、查询、统计等功能
 * 
 * @author 王贝强
 */
public interface OperationLogService extends IService<OperationLogEntity> {
    
    /**
     * 保存操作日志
     * 
     * @param operationLog 操作日志实体
     * @return 保存结果
     */
    Result<Void> saveOperationLog(OperationLogEntity operationLog);
    
    /**
     * 分页查询操作日志
     * 
     * @param page 页码
     * @param size 页大小
     * @param userId 用户ID（可选）
     * @param module 模块（可选）
     * @param operationType 操作类型（可选）
     * @param startTime 开始时间（可选）
     * @param endTime 结束时间（可选）
     * @return 分页结果
     */
    Result<Map<String, Object>> getOperationLogs(int page, int size, Long userId, String module, 
                                                 String operationType, LocalDateTime startTime, LocalDateTime endTime);
    
    /**
     * 获取操作日志统计
     * 
     * @param days 统计天数
     * @return 统计结果
     */
    Result<Map<String, Object>> getOperationLogStats(int days);
    
    /**
     * 获取用户操作统计
     * 
     * @param userId 用户ID
     * @param days 统计天数
     * @return 用户操作统计
     */
    Result<Map<String, Object>> getUserOperationStats(Long userId, int days);
    
    /**
     * 获取高风险操作日志
     * 
     * @param days 查询天数
     * @return 高风险操作日志
     */
    Result<Map<String, Object>> getHighRiskOperations(int days);
    
    /**
     * 清理过期的操作日志
     * 
     * @param retentionDays 保留天数
     * @return 清理结果
     */
    Result<Map<String, Object>> cleanExpiredLogs(int retentionDays);
    
    /**
     * 导出操作日志
     * 
     * @param userId 用户ID（可选）
     * @param module 模块（可选）
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 导出文件路径
     */
    Result<String> exportOperationLogs(Long userId, String module, LocalDateTime startTime, LocalDateTime endTime);
    
    /**
     * 获取操作日志详情
     * 
     * @param logId 日志ID
     * @return 日志详情
     */
    Result<OperationLogEntity> getOperationLogDetail(Long logId);
    
    /**
     * 批量删除操作日志
     * 
     * @param logIds 日志ID列表
     * @return 删除结果
     */
    Result<Void> batchDeleteOperationLogs(java.util.List<Long> logIds);
}
