package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SqlUtils;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.audit.*;
import cn.flying.service.SysAuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@RequiredArgsConstructor
public class SysAuditServiceImpl implements SysAuditService {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_HIGH_FREQ_THRESHOLD = 100;
    private static final int DEFAULT_FAILED_LOGIN_THRESHOLD = 5;
    private static final int DEFAULT_ERROR_RATE_THRESHOLD = 10;
    private static final int MIN_BACKUP_RETENTION_DAYS = 1;

    private final SysOperationLogMapper operationLogMapper;
    
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
        return pageSize != null && pageSize > 0 ? Math.min(pageSize, 100) : defaultSize;
    }
    
    /**
     * 手动触发一次异常操作检测，并返回检测结果。
     *
     * @return 包含 hasAnomalies/anomalyDetails/checkTime/success 的结果 Map
     */
    @Override
    public Map<String, Object> checkAnomalies() {
        Long tenantId = requireTenantIdForAudit();
        Map<String, Object> result = new HashMap<>();
        
        try {
            int highFreqThreshold = getPositiveAuditConfig("HIGH_FREQ_THRESHOLD", DEFAULT_HIGH_FREQ_THRESHOLD);
            int failedLoginThreshold = getPositiveAuditConfig("FAILED_LOGIN_THRESHOLD", DEFAULT_FAILED_LOGIN_THRESHOLD);
            int errorRateThreshold = getPositiveAuditConfig("ERROR_RATE_THRESHOLD", DEFAULT_ERROR_RATE_THRESHOLD);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime highFrequencyStartTime = now.minusMinutes(5);
            LocalDateTime failedLoginStartTime = now.minusHours(1);

            int highFreqCount = nvl(operationLogMapper.countHighFrequencyUsers(
                    tenantId,
                    highFrequencyStartTime,
                    highFreqThreshold
            ));
            int failedLoginCount = nvl(operationLogMapper.countFailedLoginUsers(
                    tenantId,
                    failedLoginStartTime,
                    failedLoginThreshold
            ));
            double errorRate = nvl(operationLogMapper.selectErrorRatePercent(tenantId, failedLoginStartTime));

            Boolean hasAnomalies = highFreqCount > 0 || failedLoginCount > 0 || errorRate > errorRateThreshold;
            Map<String, Object> thresholds = new HashMap<>();
            thresholds.put("highFrequency", highFreqThreshold);
            thresholds.put("failedLogin", failedLoginThreshold);
            thresholds.put("errorRate", errorRateThreshold);

            Map<String, Object> details = new HashMap<>();
            details.put("tenantId", tenantId);
            details.put("highFrequencyUsers", highFreqCount);
            details.put("failedLoginUsers", failedLoginCount);
            details.put("errorRatePercent", errorRate);
            details.put("thresholds", thresholds);

            String anomalyDetails = JsonConverter.toJson(details);
            
            result.put("hasAnomalies", hasAnomalies);
            result.put("anomalyDetails", anomalyDetails == null ? "{}" : anomalyDetails);
            result.put("checkTime", LocalDateTime.now().toString());
            result.put("success", true);
        } catch (Exception e) {
            log.error("执行租户审计异常检测失败: tenantId={}", tenantId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String backupLogs(Integer days, Boolean deleteAfterBackup) {
        int retentionDays = validateBackupRetentionDays(days);
        Long tenantId = requireTenantIdForAudit();
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
        try {
            int backupRows = operationLogMapper.insertOperationLogBackup(tenantId, cutoffTime);
            int deletedRows = 0;
            if (Boolean.TRUE.equals(deleteAfterBackup)) {
                deletedRows = operationLogMapper.deleteOperationLogsBefore(tenantId, cutoffTime);
            }
            return "成功备份租户" + tenantId + "中" + retentionDays + "天前的日志"
                    + "，备份行数=" + backupRows
                    + (Boolean.TRUE.equals(deleteAfterBackup) ? "，清理原表行数=" + deletedRows : "");
        } catch (Exception e) {
            log.error("执行租户审计日志备份失败: tenantId={}, retentionDays={}, deleteAfterBackup={}",
                    tenantId, retentionDays, deleteAfterBackup, e);
            return "备份失败：" + e.getMessage();
        }
    }

    /**
     * 获取当前租户上下文，审计异常检测和备份均禁止无租户全局执行。
     *
     * @return 当前租户ID
     */
    private Long requireTenantIdForAudit() {
        try {
            return TenantContext.requireTenantId();
        } catch (IllegalStateException ex) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "当前租户不能为空");
        }
    }

    /**
     * 读取正整数审计配置，非法或缺失时回退默认值。
     *
     * @param key 配置键
     * @param fallback 默认值
     * @return 正整数配置值
     */
    private int getPositiveAuditConfig(String key, int fallback) {
        String value = getAuditConfigValue(key, String.valueOf(fallback));
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            log.warn("审计配置不是有效正整数: key={}, value={}", key, value);
            return fallback;
        }
    }

    /**
     * 校验备份保留天数，禁止 0 或负数导致整表备份/删除。
     *
     * @param days 保留天数
     * @return 规范化后的保留天数
     */
    private int validateBackupRetentionDays(Integer days) {
        if (days == null || days < MIN_BACKUP_RETENTION_DAYS) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "days 必须大于等于 1");
        }
        return days;
    }

    /**
     * Integer 空值转 0。
     *
     * @param value 输入值
     * @return 非空 int
     */
    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Double 空值转 0。
     *
     * @param value 输入值
     * @return 非空 double
     */
    private double nvl(Double value) {
        return value == null ? 0D : value;
    }
} 
