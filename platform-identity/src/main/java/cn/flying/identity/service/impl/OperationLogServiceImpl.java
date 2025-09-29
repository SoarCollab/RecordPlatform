package cn.flying.identity.service.impl;

import cn.flying.identity.dto.OperationLog;
import cn.flying.identity.mapper.OperationLogMapper;
import cn.flying.identity.service.OperationLogService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 操作日志服务实现类
 * 提供操作日志的记录、查询、统计等功能
 *
 * @author 王贝强
 */
@Slf4j
@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog> implements OperationLogService {

    private final OperationLogMapper operationLogMapper;

    public OperationLogServiceImpl(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
        super.baseMapper = operationLogMapper;
    }

    @Override
    public Result<Void> saveOperationLog(OperationLog operationLog) {
        try {
            operationLog.setCreateTime(LocalDateTime.now());
            operationLog.setUpdateTime(LocalDateTime.now());

            boolean saved = this.save(operationLog);
            return saved ? Result.success(null) : Result.error(ResultEnum.SYSTEM_ERROR, null);
        } catch (Exception e) {
            log.error("保存操作日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getOperationLogs(int page, int size, Long userId, String module,
                                                        String operationType, LocalDateTime startTime, LocalDateTime endTime) {
        try {
            QueryWrapper<OperationLog> queryWrapper = new QueryWrapper<>();

            // 构建查询条件
            if (userId != null) {
                queryWrapper.eq("user_id", userId);
            }
            if (module != null && !module.trim().isEmpty()) {
                queryWrapper.like("module", module);
            }
            if (operationType != null && !operationType.trim().isEmpty()) {
                queryWrapper.eq("operation_type", operationType);
            }
            if (startTime != null) {
                queryWrapper.ge("operation_time", startTime);
            }
            if (endTime != null) {
                queryWrapper.le("operation_time", endTime);
            }

            // 按操作时间倒序
            queryWrapper.orderByDesc("operation_time");

            // 分页查询
            Page<OperationLog> pageResult = this.page(new Page<>(page, size), queryWrapper);

            Map<String, Object> result = new HashMap<>();
            result.put("logs", pageResult.getRecords());
            result.put("total", pageResult.getTotal());
            result.put("page", page);
            result.put("size", size);
            result.put("pages", pageResult.getPages());

            return Result.success(result);
        } catch (Exception e) {
            log.error("查询操作日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getOperationLogStats(int days) {
        try {
            Map<String, Object> stats = new HashMap<>();

            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(days);

            QueryWrapper<OperationLog> queryWrapper = new QueryWrapper<>();
            queryWrapper.ge("operation_time", startTime);
            queryWrapper.le("operation_time", endTime);

            // 总操作数
            long totalOperations = this.count(queryWrapper);

            // 成功操作数
            QueryWrapper<OperationLog> successWrapper = new QueryWrapper<>();
            successWrapper.ge("operation_time", startTime).le("operation_time", endTime);
            successWrapper.eq("status", 0);
            long successOperations = this.count(successWrapper);

            // 失败操作数
            QueryWrapper<OperationLog> failWrapper = new QueryWrapper<>();
            failWrapper.ge("operation_time", startTime).le("operation_time", endTime);
            failWrapper.eq("status", 1);
            long failOperations = this.count(failWrapper);

            // 操作用户数
            List<OperationLog> logs = this.list(queryWrapper);
            long uniqueUsers = logs.stream()
                    .filter(log -> log.getUserId() != null)
                    .map(OperationLog::getUserId)
                    .distinct()
                    .count();

            // 模块统计
            Map<String, Long> moduleStats = logs.stream()
                    .filter(log -> log.getModule() != null && !log.getModule().trim().isEmpty())
                    .collect(Collectors.groupingBy(OperationLog::getModule, Collectors.counting()));

            // 操作类型统计
            Map<String, Long> operationTypeStats = logs.stream()
                    .filter(log -> log.getOperationType() != null && !log.getOperationType().trim().isEmpty())
                    .collect(Collectors.groupingBy(OperationLog::getOperationType, Collectors.counting()));

            stats.put("total_operations", totalOperations);
            stats.put("success_operations", successOperations);
            stats.put("fail_operations", failOperations);
            stats.put("success_rate", totalOperations > 0 ? (double) successOperations / totalOperations * 100 : 0);
            stats.put("unique_users", uniqueUsers);
            stats.put("module_stats", moduleStats);
            stats.put("operation_type_stats", operationTypeStats);
            stats.put("days", days);
            stats.put("start_time", startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            stats.put("end_time", endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取操作日志统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserOperationStats(Long userId, int days) {
        try {
            Map<String, Object> stats = new HashMap<>();

            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(days);

            QueryWrapper<OperationLog> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", userId);
            queryWrapper.ge("operation_time", startTime);
            queryWrapper.le("operation_time", endTime);

            List<OperationLog> userLogs = this.list(queryWrapper);

            // 总操作数
            long totalOperations = userLogs.size();

            // 成功操作数
            long successOperations = userLogs.stream()
                    .filter(log -> log.getStatus() != null && log.getStatus() == 0)
                    .count();

            // 失败操作数
            long failOperations = totalOperations - successOperations;

            // 模块统计
            Map<String, Long> moduleStats = userLogs.stream()
                    .filter(log -> log.getModule() != null && !log.getModule().trim().isEmpty())
                    .collect(Collectors.groupingBy(OperationLog::getModule, Collectors.counting()));

            // 操作类型统计
            Map<String, Long> operationTypeStats = userLogs.stream()
                    .filter(log -> log.getOperationType() != null && !log.getOperationType().trim().isEmpty())
                    .collect(Collectors.groupingBy(OperationLog::getOperationType, Collectors.counting()));

            // 平均执行时间
            double avgExecutionTime = userLogs.stream()
                    .filter(log -> log.getExecutionTime() != null)
                    .mapToLong(OperationLog::getExecutionTime)
                    .average()
                    .orElse(0.0);

            stats.put("user_id", userId);
            stats.put("total_operations", totalOperations);
            stats.put("success_operations", successOperations);
            stats.put("fail_operations", failOperations);
            stats.put("success_rate", totalOperations > 0 ? (double) successOperations / totalOperations * 100 : 0);
            stats.put("avg_execution_time", avgExecutionTime);
            stats.put("module_stats", moduleStats);
            stats.put("operation_type_stats", operationTypeStats);
            stats.put("days", days);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户操作统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getHighRiskOperations(int days) {
        try {
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(days);

            QueryWrapper<OperationLog> queryWrapper = new QueryWrapper<>();
            queryWrapper.ge("operation_time", startTime);
            queryWrapper.le("operation_time", endTime);
            queryWrapper.in("risk_level", Arrays.asList("HIGH", "CRITICAL"));
            queryWrapper.orderByDesc("operation_time");

            List<OperationLog> highRiskLogs = this.list(queryWrapper);

            Map<String, Object> result = new HashMap<>();
            result.put("high_risk_operations", highRiskLogs);
            result.put("total", highRiskLogs.size());
            result.put("days", days);

            return Result.success(result);
        } catch (Exception e) {
            log.error("获取高风险操作日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> cleanExpiredLogs(int retentionDays) {
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);

            QueryWrapper<OperationLog> queryWrapper = new QueryWrapper<>();
            queryWrapper.lt("operation_time", cutoffTime);

            long expiredCount = this.count(queryWrapper);
            boolean removed = this.remove(queryWrapper);

            Map<String, Object> result = new HashMap<>();
            result.put("cleaned_count", removed ? expiredCount : 0);
            result.put("retention_days", retentionDays);
            result.put("cutoff_time", cutoffTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            return Result.success(result);
        } catch (Exception e) {
            log.error("清理过期操作日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<String> exportOperationLogs(Long userId, String module, LocalDateTime startTime, LocalDateTime endTime) {
        try {
            // 这里可以实现导出功能，比如生成CSV或Excel文件
            // 目前简化处理，返回提示信息
            return Result.success("导出功能待实现");
        } catch (Exception e) {
            log.error("导出操作日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<OperationLog> getOperationLogDetail(Long logId) {
        try {
            OperationLog log = this.getById(logId);
            return log != null ? Result.success(log) : Result.error(ResultEnum.RESULT_DATA_NONE, null);
        } catch (Exception e) {
            log.error("获取操作日志详情失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Void> batchDeleteOperationLogs(List<Long> logIds) {
        try {
            boolean removed = this.removeByIds(logIds);
            return removed ? Result.success(null) : Result.error(ResultEnum.SYSTEM_ERROR, null);
        } catch (Exception e) {
            log.error("批量删除操作日志失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }
}
