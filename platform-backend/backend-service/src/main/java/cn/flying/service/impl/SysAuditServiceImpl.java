package cn.flying.service.impl;

import cn.flying.common.util.SqlUtils;
import cn.flying.dao.dto.SysOperationLog;
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

import java.time.LocalDate;
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

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private SysOperationLogMapper operationLogMapper;
    
    @Override
    public IPage<SysOperationLog> queryOperationLogs(AuditLogQueryVO queryVO) {
        if (queryVO == null) {
            queryVO = new AuditLogQueryVO();
        }
        LambdaQueryWrapper<SysOperationLog> queryWrapper = buildOperationLogQueryWrapper(queryVO);

        // 使用 MyBatis Plus 的分页查询
        long pageNum = normalizePageNum(queryVO.getPageNum());
        long pageSize = normalizePageSize(queryVO.getPageSize(), 10);
        Page<SysOperationLog> page = new Page<>(pageNum, pageSize);
        return operationLogMapper.selectPage(page, queryWrapper);
    }
    
    @Override
    public List<HighFrequencyOperationVO> getHighFrequencyOperations() {
        // 直接使用Mapper查询视图
        return operationLogMapper.selectHighFrequencyOperations();
    }
    
    @Override
    public IPage<SysOperationLog> getSensitiveOperations(AuditLogQueryVO queryVO) {
        // 计算分页偏移量
        int offset = (queryVO.getPageNum() - 1) * queryVO.getPageSize();

        // 转义 LIKE 查询参数，防止通配符注入
        String escapedUsername = SqlUtils.escapeLikeParameter(queryVO.getUsername());
        String escapedModule = SqlUtils.escapeLikeParameter(queryVO.getModule());

        // 查询数据
        List<SysOperationLog> records = operationLogMapper.selectSensitiveOperations(
                queryVO.getUserId(),
                escapedUsername,
                escapedModule,
                queryVO.getOperationType(),
                queryVO.getStartTime(),
                queryVO.getEndTime(),
                queryVO.getPageSize(),
                offset
        );

        // 查询总数
        Long total = operationLogMapper.countSensitiveOperations(
                queryVO.getUserId(),
                escapedUsername,
                escapedModule,
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
        return operationLogMapper.selectErrorOperationStats();
    }
    
    @Override
    public List<UserTimeDistributionVO> getUserTimeDistribution() {
        return operationLogMapper.selectUserTimeDistribution();
    }
    
    @Override
    public List<AuditConfigVO> getAuditConfigs() {
        return operationLogMapper.selectAuditConfigs();
    }
    
    @Override
    public boolean updateAuditConfig(AuditConfigVO configVO) {
        int rows = operationLogMapper.updateAuditConfig(
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
        if (queryVO == null) {
            queryVO = new AuditLogQueryVO();
        }
        LambdaQueryWrapper<SysOperationLog> queryWrapper = buildOperationLogQueryWrapper(queryVO);

        // 最多导出10000条记录，防止内存溢出
        queryWrapper.last("LIMIT 10000");
        
        return operationLogMapper.selectList(queryWrapper);
    }

    @Override
    public Map<String, Object> getAuditOverview() {
        Map<String, Object> overview = new HashMap<>();
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay(); // Exclusive end

        try {
            overview.put("totalOperations", operationLogMapper.selectTotalOperations());
            overview.put("todayOperations", operationLogMapper.selectOperationsBetween(startOfDay, endOfDay));
            overview.put("totalErrorOperations", operationLogMapper.selectTotalErrorOperations());
            overview.put("todayErrorOperations", operationLogMapper.selectErrorOperationsBetween(startOfDay, endOfDay));
            overview.put("todaySensitiveOperations", operationLogMapper.selectSensitiveOperationsCountBetween(startOfDay, endOfDay));
            overview.put("todayActiveUsers", operationLogMapper.selectActiveUsersBetween(startOfDay, endOfDay));
            overview.put("highFrequencyAlerts", operationLogMapper.selectHighFrequencyAlertCount());

            // 获取过去7天的每日统计数据
            int daysForStats = 7;
            List<Map<String, Object>> dailyStats = operationLogMapper.selectDailyStats(daysForStats);
            overview.put("dailyStats", dailyStats); // Example: [{date: '2024-04-01', count: 150}, ...]

            // 获取审计配置状态
            overview.put("auditEnabled", getAuditConfigValue("AUDIT_ENABLED", "true")); // Default to true if not found
            overview.put("logRetentionDays", getAuditConfigValue("LOG_RETENTION_DAYS", "180")); // Default retention

        } catch (Exception e) {
            log.error("获取审计概览数据失败", e);
            // Optionally return partial data or throw a custom exception
            overview.put("error", "获取概览数据时发生错误: " + e.getMessage());
        }

        return overview;
    }

    // Helper method to get specific audit config value
    private String getAuditConfigValue(String key, String defaultValue) {
        try {
            AuditConfigVO config = operationLogMapper.selectAuditConfigByKey(key);
            return (config != null && config.getConfigValue() != null) ? config.getConfigValue() : defaultValue;
        } catch (Exception e) {
            log.warn("无法获取审计配置项 '{}': {}", key, e.getMessage());
            return defaultValue;
        }
    }

    /**
     * 构建操作日志查询条件（用于分页查询与导出）。
     *
     * @param queryVO 查询条件
     * @return MyBatis Plus 查询构造器
     */
    private LambdaQueryWrapper<SysOperationLog> buildOperationLogQueryWrapper(AuditLogQueryVO queryVO) {
        LambdaQueryWrapper<SysOperationLog> queryWrapper = new LambdaQueryWrapper<>();

        if (queryVO == null) {
            return queryWrapper.orderByDesc(SysOperationLog::getOperationTime);
        }

        if (StringUtils.isNotBlank(queryVO.getUserId())) {
            queryWrapper.eq(SysOperationLog::getUserId, queryVO.getUserId());
        }

        if (StringUtils.isNotBlank(queryVO.getUsername())) {
            String escapedUsername = SqlUtils.escapeLikeParameter(queryVO.getUsername());
            queryWrapper.like(SysOperationLog::getUsername, escapedUsername);
        }

        if (StringUtils.isNotBlank(queryVO.getModule())) {
            String escapedModule = SqlUtils.escapeLikeParameter(queryVO.getModule());
            queryWrapper.like(SysOperationLog::getModule, escapedModule);
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

        LocalDateTime startTime = parseDateTimeOrNull(queryVO.getStartTime());
        LocalDateTime endTime = parseDateTimeOrNull(queryVO.getEndTime());
        if (startTime != null && endTime != null) {
            queryWrapper.between(SysOperationLog::getOperationTime, startTime, endTime);
        } else if (startTime != null) {
            queryWrapper.ge(SysOperationLog::getOperationTime, startTime);
        } else if (endTime != null) {
            queryWrapper.le(SysOperationLog::getOperationTime, endTime);
        }

        return queryWrapper.orderByDesc(SysOperationLog::getOperationTime);
    }

    /**
     * 解析前端传入的日期时间字符串，失败则返回 null（避免直接抛出导致查询接口 500）。
     *
     * @param value 日期时间字符串（格式：yyyy-MM-dd HH:mm:ss）
     * @return 解析后的 LocalDateTime，解析失败返回 null
     */
    private LocalDateTime parseDateTimeOrNull(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DATETIME_FORMATTER);
        } catch (Exception e) {
            log.warn("解析日期时间失败: value={}, pattern={}", value, DATETIME_FORMATTER);
            return null;
        }
    }

    /**
     * 规范化分页页码，避免出现 null/0/负数导致的分页异常。
     *
     * @param pageNum 页码
     * @return 规范化后的页码（最小为 1）
     */
    private long normalizePageNum(Integer pageNum) {
        return pageNum != null && pageNum > 0 ? pageNum : 1L;
    }

    /**
     * 规范化分页大小，避免出现 null/0/负数导致的分页异常。
     *
     * @param pageSize 分页大小
     * @param defaultSize 默认分页大小
     * @return 规范化后的分页大小（最小为 1）
     */
    private long normalizePageSize(Integer pageSize, int defaultSize) {
        return pageSize != null && pageSize > 0 ? pageSize : defaultSize;
    }
    
    /**
     * 手动触发一次异常操作检测，并返回检测结果。
     *
     * @return 包含 hasAnomalies/anomalyDetails/checkTime/success 的结果 Map
     */
    @Override
    public Map<String, Object> checkAnomalies() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Map<String, Object> outParams = new HashMap<>();
            operationLogMapper.checkAnomalies(outParams);

            Boolean hasAnomalies = toBooleanOrNull(outParams.get("hasAnomalies"));
            String anomalyDetails = toStringOrNull(outParams.get("anomalyDetails"));
            
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

    /**
     * 将存储过程 OUT 参数返回值转换为布尔值。
     *
     * @param value OUT 参数值（可能为 Boolean/Number/String）
     * @return 转换后的布尔值，无法识别时返回 null
     */
    private Boolean toBooleanOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue() != 0;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        if ("1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)) {
            return true;
        }
        if ("0".equals(text) || "false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }

    /**
     * 将任意对象安全转换为字符串。
     *
     * @param value 可能为 null 的对象
     * @return 字符串值，value 为 null 时返回 null
     */
    private String toStringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
    
    @Override
    public String backupLogs(Integer days, Boolean deleteAfterBackup) {
        try {
            operationLogMapper.backupLogs(days, deleteAfterBackup);
            return "成功备份" + days + "天前的日志" + (deleteAfterBackup ? "并清理原表数据" : "");
        } catch (Exception e) {
            log.error("调用日志备份存储过程失败", e);
            return "备份失败：" + e.getMessage();
        }
    }
} 
