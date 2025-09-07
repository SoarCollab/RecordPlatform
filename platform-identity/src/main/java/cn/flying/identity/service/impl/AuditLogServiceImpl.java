package cn.flying.identity.service.impl;

import cn.flying.identity.dto.AuditLog;
import cn.flying.identity.mapper.AuditLogMapper;
import cn.flying.identity.service.AuditLogService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 操作审计日志服务实现类
 * 实现审计日志的业务逻辑处理
 *
 * @author flying
 * @date 2024
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 记录操作日志
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> recordLog(AuditLog auditLog) {
        try {
            // 设置默认值
            if (auditLog.getOperationTime() == null) {
                auditLog.setOperationTime(LocalDateTime.now());
            }
            if (auditLog.getCreateTime() == null) {
                auditLog.setCreateTime(LocalDateTime.now());
            }
            if (auditLog.getUpdateTime() == null) {
                auditLog.setUpdateTime(LocalDateTime.now());
            }

            // 计算执行时间
            if (auditLog.getExecutionTime() == null) {
                auditLog.setExecutionTime(0L);
            }

            // 风险评估
            if (StrUtil.isBlank(auditLog.getRiskLevel())) {
                auditLog.setRiskLevel(assessRiskLevel(auditLog));
            }

            auditLogMapper.insert(auditLog);
            return Result.success("操作日志记录成功");
        } catch (Exception e) {
            log.error("记录操作日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 异步记录操作日志
     */
    @Override
    @Async
    public void recordLogAsync(AuditLog auditLog) {
        recordLog(auditLog);
    }

    /**
     * 记录登录日志
     */
    @Override
    public Result<String> recordLoginLog(Long userId, String username, String clientIp,
                                         String userAgent, boolean success, String errorMessage) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setUsername(username);
        auditLog.setOperationType(AuditLog.OperationType.LOGIN.name());
        auditLog.setModule(AuditLog.Module.AUTH.name());
        auditLog.setOperationDesc(success ? "用户登录成功" : "用户登录失败");
        auditLog.setClientIp(clientIp);
        auditLog.setUserAgent(userAgent);
        auditLog.setOperationStatus(success ? 1 : 0);
        auditLog.setErrorMessage(errorMessage);
        auditLog.setRiskLevel(success ? AuditLog.RiskLevel.LOW.name() : AuditLog.RiskLevel.MEDIUM.name());

        return recordLog(auditLog);
    }

    /**
     * 记录登出日志
     */
    @Override
    public Result<String> recordLogoutLog(Long userId, String username, String clientIp, String userAgent) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setUsername(username);
        auditLog.setOperationType(AuditLog.OperationType.LOGOUT.name());
        auditLog.setModule(AuditLog.Module.AUTH.name());
        auditLog.setOperationDesc("用户登出");
        auditLog.setClientIp(clientIp);
        auditLog.setUserAgent(userAgent);
        auditLog.setOperationStatus(1);
        auditLog.setRiskLevel(AuditLog.RiskLevel.LOW.name());

        return recordLog(auditLog);
    }

    /**
     * 记录权限操作日志
     */
    @Override
    public Result<String> recordPermissionLog(Long userId, String username, String operationType,
                                              String description, String clientIp, String userAgent, boolean success) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setUsername(username);
        auditLog.setOperationType(operationType);
        auditLog.setModule(AuditLog.Module.PERMISSION.name());
        auditLog.setOperationDesc(description);
        auditLog.setClientIp(clientIp);
        auditLog.setUserAgent(userAgent);
        auditLog.setOperationStatus(success ? 1 : 0);
        auditLog.setRiskLevel(AuditLog.RiskLevel.MEDIUM.name());

        return recordLog(auditLog);
    }

    /**
     * 记录数据操作日志
     */
    @Override
    public Result<String> recordDataLog(Long userId, String username, String operationType, String module,
                                        String businessId, String businessType, String description,
                                        String clientIp, boolean success) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setUsername(username);
        auditLog.setOperationType(operationType);
        auditLog.setModule(module);
        auditLog.setOperationDesc(description);
        auditLog.setClientIp(clientIp);
        auditLog.setBusinessId(businessId);
        auditLog.setBusinessType(businessType);
        auditLog.setOperationStatus(success ? 1 : 0);

        return recordLog(auditLog);
    }

    /**
     * 记录系统操作日志
     */
    @Override
    public Result<String> recordSystemLog(String operationType, String module, String description,
                                          boolean success, String errorMessage) {
        AuditLog auditLog = new AuditLog();
        auditLog.setOperationType(operationType);
        auditLog.setModule(module);
        auditLog.setOperationDesc(description);
        auditLog.setOperationStatus(success ? 1 : 0);
        auditLog.setErrorMessage(errorMessage);
        auditLog.setRiskLevel(AuditLog.RiskLevel.LOW.name());

        return recordLog(auditLog);
    }

    /**
     * 根据用户ID查询操作日志
     */
    @Override
    public Result<List<AuditLog>> getLogsByUserId(Long userId, LocalDateTime startTime,
                                                  LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<AuditLog> logs = auditLogMapper.findByUserIdAndTimeRange(userId, startTime, endTime);
            return Result.success(logs);
        } catch (Exception e) {
            log.error("查询用户操作日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据操作类型查询日志
     */
    @Override
    public Result<List<AuditLog>> getLogsByOperationType(String operationType, LocalDateTime startTime,
                                                         LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<AuditLog> logs = auditLogMapper.findByOperationTypeAndTimeRange(operationType, startTime, endTime);
            return Result.success(logs);
        } catch (Exception e) {
            log.error("查询操作类型日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据模块查询日志
     */
    @Override
    public Result<List<AuditLog>> getLogsByModule(String module, LocalDateTime startTime,
                                                  LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<AuditLog> logs = auditLogMapper.findByModuleAndTimeRange(module, startTime, endTime);
            return Result.success(logs);
        } catch (Exception e) {
            log.error("查询模块日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据IP地址查询日志
     */
    @Override
    public Result<List<AuditLog>> getLogsByClientIp(String clientIp, LocalDateTime startTime,
                                                    LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<AuditLog> logs = auditLogMapper.findByClientIpAndTimeRange(clientIp, startTime, endTime);
            return Result.success(logs);
        } catch (Exception e) {
            log.error("查询IP日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 查询失败的操作日志
     */
    @Override
    public Result<List<AuditLog>> getFailedLogs(LocalDateTime startTime, LocalDateTime endTime,
                                                int pageNum, int pageSize) {
        try {
            List<AuditLog> logs = auditLogMapper.findFailedOperations(startTime, endTime);
            return Result.success(logs);
        } catch (Exception e) {
            log.error("查询失败操作日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据风险等级查询日志
     */
    @Override
    public Result<List<AuditLog>> getLogsByRiskLevel(String riskLevel, LocalDateTime startTime,
                                                     LocalDateTime endTime, int pageNum, int pageSize) {
        try {
            List<AuditLog> logs = auditLogMapper.findByRiskLevelAndTimeRange(riskLevel, startTime, endTime);
            return Result.success(logs);
        } catch (Exception e) {
            log.error("查询风险等级日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 查询高风险操作
     */
    @Override
    public Result<List<AuditLog>> getHighRiskLogs(LocalDateTime startTime, LocalDateTime endTime,
                                                  int pageNum, int pageSize) {
        try {
            List<AuditLog> logs = auditLogMapper.findHighRiskOperations(startTime, endTime);
            return Result.success(logs);
        } catch (Exception e) {
            log.error("查询高风险操作失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 查询异常登录记录
     */
    @Override
    public Result<List<AuditLog>> getAbnormalLogins(LocalDateTime startTime, LocalDateTime endTime,
                                                    int pageNum, int pageSize) {
        try {
            List<AuditLog> logs = auditLogMapper.findAbnormalLogins(startTime, endTime);
            return Result.success(logs);
        } catch (Exception e) {
            log.error("查询异常登录记录失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 统计操作类型分布
     */
    @Override
    public Result<List<Map<String, Object>>> getOperationTypeStats(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            List<Map<String, Object>> stats = auditLogMapper.countByOperationType(startTime, endTime);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("统计操作类型分布失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 统计用户操作次数
     */
    @Override
    public Result<List<Map<String, Object>>> getUserOperationStats(LocalDateTime startTime, LocalDateTime endTime, Integer limit) {
        try {
            List<Map<String, Object>> stats = auditLogMapper.countByUser(startTime, endTime, limit);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("统计用户操作次数失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 统计IP访问次数
     */
    @Override
    public Result<List<Map<String, Object>>> getIpAccessStats(LocalDateTime startTime, LocalDateTime endTime, Integer limit) {
        try {
            List<Map<String, Object>> stats = auditLogMapper.countByClientIp(startTime, endTime, limit);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("统计IP访问次数失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 统计每日操作数量
     */
    @Override
    public Result<List<Map<String, Object>>> getDailyOperationStats(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            List<Map<String, Object>> stats = auditLogMapper.countByDate(startTime, endTime);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("统计每日操作数量失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 统计每小时操作数量
     */
    @Override
    public Result<List<Map<String, Object>>> getHourlyOperationStats(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            List<Map<String, Object>> stats = auditLogMapper.countByHour(startTime, endTime);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("统计每小时操作数量失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 导出审计日志
     */
    @Override
    public Result<String> exportLogs(LocalDateTime startTime, LocalDateTime endTime,
                                     String operationType, String module, Long userId) {
        try {
            // TODO: 实现日志导出逻辑
            String filePath = "/tmp/audit_logs_" + DateUtil.format(new Date(), "yyyyMMdd_HHmmss") + ".xlsx";
            return Result.success(filePath);
        } catch (Exception e) {
            log.error("导出审计日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR);
        }
    }

    /**
     * 清理过期的审计日志
     */
    @Override
    @Transactional
    public Result<Integer> cleanExpiredLogs(int retentionDays) {
        try {
            LocalDateTime expireTime = LocalDateTime.now().minusDays(retentionDays);
            int deletedCount = auditLogMapper.deleteExpiredLogs(expireTime);
            log.info("清理过期审计日志完成，删除记录数: {}", deletedCount);
            return Result.success(deletedCount);
        } catch (Exception e) {
            log.error("清理过期审计日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 检测异常操作模式
     */
    @Override
    public Result<Map<String, Object>> detectAbnormalPatterns(Long userId, int timeWindow) {
        try {
            Map<String, Object> result = new HashMap<>();
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusMinutes(timeWindow);

            // 查询用户在时间窗口内的操作
            List<AuditLog> logs = auditLogMapper.findByUserIdAndTimeRange(userId, startTime, endTime);

            // 分析异常模式
            result.put("totalOperations", logs.size());
            result.put("failedOperations", logs.stream().mapToInt(log -> log.getOperationStatus() == 0 ? 1 : 0).sum());
            result.put("highRiskOperations", logs.stream().filter(log ->
                    AuditLog.RiskLevel.HIGH.name().equals(log.getRiskLevel()) ||
                            AuditLog.RiskLevel.CRITICAL.name().equals(log.getRiskLevel())).count());

            // 判断是否异常
            boolean isAbnormal = logs.size() > 100 || // 操作频率过高
                    (logs.size() > 0 && logs.stream().mapToInt(log -> log.getOperationStatus() == 0 ? 1 : 0).sum() / (double) logs.size() > 0.3); // 失败率过高

            result.put("isAbnormal", isAbnormal);
            result.put("riskLevel", isAbnormal ? "HIGH" : "LOW");

            return Result.success(result);
        } catch (Exception e) {
            log.error("检测异常操作模式失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 生成审计报告
     */
    @Override
    public Result<Map<String, Object>> generateAuditReport(LocalDateTime startTime, LocalDateTime endTime, String reportType) {
        try {
            Map<String, Object> report = new HashMap<>();

            // 基础统计
            List<Map<String, Object>> operationTypeStats = auditLogMapper.countByOperationType(startTime, endTime);
            List<Map<String, Object>> userStats = auditLogMapper.countByUser(startTime, endTime, 10);
            List<Map<String, Object>> ipStats = auditLogMapper.countByClientIp(startTime, endTime, 10);
            List<Map<String, Object>> dailyStats = auditLogMapper.countByDate(startTime, endTime);

            report.put("operationTypeStats", operationTypeStats);
            report.put("userStats", userStats);
            report.put("ipStats", ipStats);
            report.put("dailyStats", dailyStats);

            // 安全统计
            List<AuditLog> failedLogs = auditLogMapper.findFailedOperations(startTime, endTime);
            List<AuditLog> highRiskLogs = auditLogMapper.findHighRiskOperations(startTime, endTime);
            List<AuditLog> abnormalLogins = auditLogMapper.findAbnormalLogins(startTime, endTime);

            report.put("failedOperationsCount", failedLogs.size());
            report.put("highRiskOperationsCount", highRiskLogs.size());
            report.put("abnormalLoginsCount", abnormalLogins.size());

            report.put("reportType", reportType);
            report.put("startTime", startTime);
            report.put("endTime", endTime);
            report.put("generateTime", LocalDateTime.now());

            return Result.success(report);
        } catch (Exception e) {
            log.error("生成审计报告失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 实时监控告警
     */
    @Override
    public Result<List<Map<String, Object>>> realtimeAlert(String riskLevel, int timeWindow) {
        try {
            List<Map<String, Object>> alerts = new ArrayList<>();
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusMinutes(timeWindow);

            // 查询高风险操作
            List<AuditLog> highRiskLogs = auditLogMapper.findByRiskLevelAndTimeRange(riskLevel, startTime, endTime);

            for (AuditLog log : highRiskLogs) {
                Map<String, Object> alert = new HashMap<>();
                alert.put("alertType", "HIGH_RISK_OPERATION");
                alert.put("userId", log.getUserId());
                alert.put("username", log.getUsername());
                alert.put("operationType", log.getOperationType());
                alert.put("description", log.getOperationDesc());
                alert.put("clientIp", log.getClientIp());
                alert.put("operationTime", log.getOperationTime());
                alert.put("riskLevel", log.getRiskLevel());
                alerts.add(alert);
            }

            return Result.success(alerts);
        } catch (Exception e) {
            log.error("实时监控告警失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 评估风险等级
     */
    private String assessRiskLevel(AuditLog auditLog) {
        // 根据操作类型和其他因素评估风险等级
        String operationType = auditLog.getOperationType();

        if ("DELETE".equals(operationType) || "PERMISSION_CHANGE".equals(operationType)) {
            return AuditLog.RiskLevel.HIGH.name();
        } else if ("UPDATE".equals(operationType) || "LOGIN".equals(operationType)) {
            return AuditLog.RiskLevel.MEDIUM.name();
        } else {
            return AuditLog.RiskLevel.LOW.name();
        }
    }
}