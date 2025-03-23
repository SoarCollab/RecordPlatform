package cn.flying.service.impl;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.mapper.SysAuditMapper;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.audit.*;
import cn.flying.service.SysAuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统审计服务实现类 - 使用MyBatis Plus
 */
@Slf4j
@Service
public class SysAuditServiceImpl implements SysAuditService {

    @Resource
    private SysOperationLogMapper operationLogMapper;
    
    @Resource
    private SysAuditMapper sysAuditMapper;
    
    @Override
    public IPage<SysOperationLog> queryOperationLogs(AuditLogQueryVO queryVO) {
        // 使用MyBatis Plus的LambdaQueryWrapper构建查询条件
        LambdaQueryWrapper<SysOperationLog> queryWrapper = new LambdaQueryWrapper<>();
        
        // 构建查询条件
        if (StringUtils.isNotBlank(queryVO.getUserId())) {
            queryWrapper.eq(SysOperationLog::getUserId, queryVO.getUserId());
        }
        
        if (StringUtils.isNotBlank(queryVO.getUsername())) {
            queryWrapper.like(SysOperationLog::getUsername, queryVO.getUsername());
        }
        
        if (StringUtils.isNotBlank(queryVO.getModule())) {
            queryWrapper.like(SysOperationLog::getModule, queryVO.getModule());
        }
        
        if (StringUtils.isNotBlank(queryVO.getOperationType())) {
            queryWrapper.eq(SysOperationLog::getOperationType, queryVO.getOperationType());
        }
        
        if (queryVO.getStatus() != null) {
            queryWrapper.eq(SysOperationLog::getStatus, queryVO.getStatus());
        }
        
        if (StringUtils.isNotBlank(queryVO.getRequestIp())) {
            queryWrapper.eq(SysOperationLog::getRequestIp, queryVO.getRequestIp());
        }
        
        // 处理时间范围查询
        if (StringUtils.isNotBlank(queryVO.getStartTime()) && StringUtils.isNotBlank(queryVO.getEndTime())) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime startTime = LocalDateTime.parse(queryVO.getStartTime(), formatter);
            LocalDateTime endTime = LocalDateTime.parse(queryVO.getEndTime(), formatter);
            queryWrapper.between(SysOperationLog::getOperationTime, startTime, endTime);
        }
        
        // 默认按操作时间降序排序
        queryWrapper.orderByDesc(SysOperationLog::getOperationTime);
        
        // 使用MyBatis Plus的分页查询
        Page<SysOperationLog> page = new Page<>(queryVO.getPageNum(), queryVO.getPageSize());
        return operationLogMapper.selectPage(page, queryWrapper);
    }
    
    @Override
    public List<HighFrequencyOperationVO> getHighFrequencyOperations() {
        // 直接使用Mapper查询视图
        return sysAuditMapper.selectHighFrequencyOperations();
    }
    
    @Override
    public IPage<SysOperationLog> getSensitiveOperations(AuditLogQueryVO queryVO) {
        // 计算分页偏移量
        int offset = (queryVO.getPageNum() - 1) * queryVO.getPageSize();
        
        // 查询数据
        List<SysOperationLog> records = sysAuditMapper.selectSensitiveOperations(
                queryVO.getUserId(),
                queryVO.getUsername(),
                queryVO.getModule(),
                queryVO.getOperationType(),
                queryVO.getStartTime(),
                queryVO.getEndTime(),
                queryVO.getPageSize(),
                offset
        );
        
        // 查询总数
        Long total = sysAuditMapper.countSensitiveOperations(
                queryVO.getUserId(),
                queryVO.getUsername(),
                queryVO.getModule(),
                queryVO.getOperationType(),
                queryVO.getStartTime(),
                queryVO.getEndTime()
        );
        
        // 构建分页结果
        Page<SysOperationLog> page = new Page<>(queryVO.getPageNum(), queryVO.getPageSize(), total);
        page.setRecords(records);
        return page;
    }
    
    @Override
    public List<ErrorOperationStatsVO> getErrorOperationStats() {
        return sysAuditMapper.selectErrorOperationStats();
    }
    
    @Override
    public List<UserTimeDistributionVO> getUserTimeDistribution() {
        return sysAuditMapper.selectUserTimeDistribution();
    }
    
    @Override
    public List<AuditConfigVO> getAuditConfigs() {
        return sysAuditMapper.selectAuditConfigs();
    }
    
    @Override
    public boolean updateAuditConfig(AuditConfigVO configVO) {
        int rows = sysAuditMapper.updateAuditConfig(
                configVO.getConfigKey(),
                configVO.getConfigValue(),
                configVO.getDescription()
        );
        return rows > 0;
    }
    
    @Override
    public SysOperationLog getLogDetail(Long id) {
        // 使用MyBatis Plus的方法查询单条记录
        return operationLogMapper.selectById(id);
    }
    
    @Override
    public List<SysOperationLog> exportOperationLogs(AuditLogQueryVO queryVO) {
        // 使用与分页查询相同的条件构建器，但不分页
        LambdaQueryWrapper<SysOperationLog> queryWrapper = new LambdaQueryWrapper<>();
        
        // 构建查询条件
        if (StringUtils.isNotBlank(queryVO.getUserId())) {
            queryWrapper.eq(SysOperationLog::getUserId, queryVO.getUserId());
        }
        
        if (StringUtils.isNotBlank(queryVO.getUsername())) {
            queryWrapper.like(SysOperationLog::getUsername, queryVO.getUsername());
        }
        
        if (StringUtils.isNotBlank(queryVO.getModule())) {
            queryWrapper.like(SysOperationLog::getModule, queryVO.getModule());
        }
        
        if (StringUtils.isNotBlank(queryVO.getOperationType())) {
            queryWrapper.eq(SysOperationLog::getOperationType, queryVO.getOperationType());
        }
        
        if (queryVO.getStatus() != null) {
            queryWrapper.eq(SysOperationLog::getStatus, queryVO.getStatus());
        }
        
        if (StringUtils.isNotBlank(queryVO.getRequestIp())) {
            queryWrapper.eq(SysOperationLog::getRequestIp, queryVO.getRequestIp());
        }
        
        // 处理时间范围查询
        if (StringUtils.isNotBlank(queryVO.getStartTime()) && StringUtils.isNotBlank(queryVO.getEndTime())) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime startTime = LocalDateTime.parse(queryVO.getStartTime(), formatter);
            LocalDateTime endTime = LocalDateTime.parse(queryVO.getEndTime(), formatter);
            queryWrapper.between(SysOperationLog::getOperationTime, startTime, endTime);
        }
        
        // 默认按操作时间降序排序
        queryWrapper.orderByDesc(SysOperationLog::getOperationTime);
        
        // 最多导出10000条记录，防止内存溢出
        queryWrapper.last("LIMIT 10000");
        
        return operationLogMapper.selectList(queryWrapper);
    }
    
    @Override
    public Map<String, Object> getAuditOverview() {
        Map<String, Object> overview = new HashMap<>();

        overview.put("totalOperations", sysAuditMapper.selectTotalOperations());
        overview.put("todayOperations", sysAuditMapper.selectTodayOperations());
        overview.put("errorOperations", sysAuditMapper.selectErrorOperations());
        overview.put("sensitiveOperations", sysAuditMapper.selectSensitiveOperationCount());
        overview.put("activeUsers", sysAuditMapper.selectActiveUsers());
        
        // 获取每日统计数据
        List<Map<String, Object>> dailyStats = sysAuditMapper.selectDailyStats();
        overview.put("dailyStats", dailyStats);
        
        return overview;
    }
    
    @Override
    public Map<String, Object> checkAnomalies() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Map<String, Object> procedureResult = sysAuditMapper.checkAnomalies();
            
            Boolean hasAnomalies = (Boolean) procedureResult.get("hasAnomalies");
            String anomalyDetails = (String) procedureResult.get("anomalyDetails");
            
            result.put("hasAnomalies", hasAnomalies);
            result.put("anomalyDetails", anomalyDetails);
            result.put("checkTime", LocalDateTime.now().toString());
            result.put("success", true);
        } catch (Exception e) {
            log.error("调用异常检测存储过程失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public String backupLogs(Integer days, Boolean deleteAfterBackup) {
        try {
            sysAuditMapper.backupLogs(days, deleteAfterBackup);
            return "成功备份" + days + "天前的日志" + (deleteAfterBackup ? "并清理原表数据" : "");
        } catch (Exception e) {
            log.error("调用日志备份存储过程失败", e);
            return "备份失败：" + e.getMessage();
        }
    }
} 