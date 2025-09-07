package cn.flying.identity.service.impl;

import cn.flying.identity.dto.OperationLog;
import cn.flying.identity.mapper.OperationLogMapper;
import cn.flying.identity.service.AccountService;
import cn.flying.identity.service.UserStatisticsService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 用户统计服务实现类
 * 提供用户相关的统计分析功能
 *
 * @author 王贝强
 */
@Slf4j
@Service
public class UserStatisticsServiceImpl implements UserStatisticsService {

    @Resource
    private AccountService accountService;

    @Resource
    private OperationLogMapper operationLogMapper;


    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Override
    public Result<Map<String, Object>> getUserCountStats() {
        try {
            Map<String, Object> stats = new HashMap<>();

            // 总用户数
            long totalUsers = accountService.count();

            // 活跃用户数（未被删除的用户）
            long activeUsers = accountService.query().eq("deleted", 0).count();

            // 禁用用户数
            long disabledUsers = accountService.query().eq("deleted", 1).count();

            // 今日新增用户数
            LocalDate today = LocalDate.now();
            String todayStr = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            long todayNewUsers = accountService.query()
                    .ge("register_time", todayStr + " 00:00:00")
                    .le("register_time", todayStr + " 23:59:59")
                    .count();

            // 本月新增用户数
            LocalDate monthStart = today.withDayOfMonth(1);
            String monthStartStr = monthStart.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            long monthNewUsers = accountService.query()
                    .ge("register_time", monthStartStr + " 00:00:00")
                    .count();

            stats.put("total_users", totalUsers);
            stats.put("active_users", activeUsers);
            stats.put("disabled_users", disabledUsers);
            stats.put("today_new_users", todayNewUsers);
            stats.put("month_new_users", monthNewUsers);
            stats.put("update_time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户总数统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getRegistrationTrend(int days) {
        try {
            Map<String, Object> stats = new HashMap<>();
            List<Map<String, Object>> trendData = new ArrayList<>();

            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(days - 1);

            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                String dateStr = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

                long count = accountService.query()
                        .ge("register_time", dateStr + " 00:00:00")
                        .le("register_time", dateStr + " 23:59:59")
                        .count();

                Map<String, Object> dayData = new HashMap<>();
                dayData.put("date", dateStr);
                dayData.put("count", count);
                trendData.add(dayData);
            }

            stats.put("trend_data", trendData);
            stats.put("days", days);
            stats.put("start_date", startDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            stats.put("end_date", endDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户注册趋势失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserActivityStats(int days) {
        try {
            Map<String, Object> stats = new HashMap<>();

            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(days);

            // 今日活跃用户（基于审计日志LOGIN操作）
            LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
            LocalDateTime todayEnd = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59);
            
            List<Map<String, Object>> todayActiveUsers = operationLogMapper.countByUser(todayStart, todayEnd, 1000);
            long activeUsersToday = todayActiveUsers.size();

            // 本周活跃用户
            LocalDateTime weekStart = endTime.minusDays(7);
            List<Map<String, Object>> weekActiveUsers = operationLogMapper.countByUser(weekStart, endTime, 1000);
            long activeUsersWeek = weekActiveUsers.size();

            // 本月活跃用户
            LocalDateTime monthStart = endTime.minusDays(30);
            List<Map<String, Object>> monthActiveUsers = operationLogMapper.countByUser(monthStart, endTime, 1000);
            long activeUsersMonth = monthActiveUsers.size();

            // 操作统计（作为活动指标）
            List<Map<String, Object>> operationStats = operationLogMapper.countByOperationType(startTime, endTime);
            long operationCount = operationStats.stream()
                    .mapToLong(stat -> Long.parseLong(stat.get("count").toString()))
                    .sum();

            // 计算平均会话时长（基于Token生命周期）
            List<Map<String, Object>> dailyStats = operationLogMapper.countByDate(startTime, endTime);
            double avgSessionDuration = dailyStats.stream()
                    .mapToDouble(stat -> Double.parseDouble(stat.get("count").toString()))
                    .average()
                    .orElse(0.0) * 30; // 估算为分钟

            stats.put("active_users_today", activeUsersToday);
            stats.put("active_users_week", activeUsersWeek);
            stats.put("active_users_month", activeUsersMonth);
            stats.put("avg_session_duration", Math.round(avgSessionDuration));
            stats.put("operation_count", operationCount);
            stats.put("days", days);
            stats.put("calculation_time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户活跃度统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserRoleDistribution() {
        try {
            Map<String, Object> stats = new HashMap<>();

            // 统计各角色用户数量
            Map<String, Long> roleDistribution = new HashMap<>();

            // 管理员数量
            long adminCount = accountService.query().eq("role", "admin").eq("deleted", 0).count();
            roleDistribution.put("admin", adminCount);

            // 普通用户数量
            long userCount = accountService.query().eq("role", "user").eq("deleted", 0).count();
            roleDistribution.put("user", userCount);

            // 其他角色数量
            long otherCount = accountService.query()
                    .ne("role", "admin")
                    .ne("role", "user")
                    .eq("deleted", 0)
                    .count();
            roleDistribution.put("other", otherCount);

            stats.put("role_distribution", roleDistribution);
            stats.put("total_active_users", adminCount + userCount + otherCount);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户角色分布失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserGeographicDistribution() {
        try {
            Map<String, Object> stats = new HashMap<>();

            // 这里可以根据用户IP地址或注册时的地理信息来统计
            // 目前简化处理，返回空数据

            Map<String, Integer> countryDistribution = new HashMap<>();
            Map<String, Integer> cityDistribution = new HashMap<>();

            stats.put("country_distribution", countryDistribution);
            stats.put("city_distribution", cityDistribution);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户地理分布失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserLoginStats(int days) {
        try {
            Map<String, Object> stats = new HashMap<>();

            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(days);

            // 总登录次数（基于审计日志LOGIN操作）
            List<OperationLog> loginOperations = operationLogMapper.findByOperationTypeAndTimeRange("LOGIN", startTime, endTime);
            long totalLogins = loginOperations.size();

            // 独立用户数（基于审计日志LOGIN操作的用户统计）
            List<Map<String, Object>> uniqueLoginUsers = operationLogMapper.countByUser(startTime, endTime, 10000);
            long uniqueUsers = uniqueLoginUsers.stream()
                    .filter(user -> user.get("user_id") != null)
                    .count();

            // 平均每用户登录次数
            double avgLoginsPerUser = uniqueUsers > 0 ? (double) totalLogins / uniqueUsers : 0.0;

            // 每小时登录统计
            List<Map<String, Object>> hourlyStats = operationLogMapper.countByHour(startTime, endTime);
            int peakLoginHour = hourlyStats.stream()
                    .max((h1, h2) -> Long.compare(
                            Long.parseLong(h1.get("count").toString()),
                            Long.parseLong(h2.get("count").toString())
                    ))
                    .map(hour -> Integer.parseInt(hour.get("hour").toString()))
                    .orElse(0);

            // 失败登录统计
            long failedLogins = operationLogMapper.findFailedOperations(startTime, endTime).stream()
                    .filter(log -> "LOGIN".equals(log.getOperationType()))
                    .count();

            // 登录成功率
            double loginSuccessRate = totalLogins > 0 ? ((double) (totalLogins - failedLogins) / totalLogins) * 100 : 0.0;

            stats.put("total_logins", totalLogins);
            stats.put("unique_users", uniqueUsers);
            stats.put("avg_logins_per_user", Math.round(avgLoginsPerUser * 100.0) / 100.0);
            stats.put("peak_login_hour", peakLoginHour);
            stats.put("failed_logins", failedLogins);
            stats.put("login_success_rate", Math.round(loginSuccessRate * 100.0) / 100.0);
            stats.put("days", days);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户登录统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserRetentionRate(int days) {
        try {
            Map<String, Object> stats = new HashMap<>();

            // 计算用户留存率
            // 这里需要根据实际的用户活动数据来计算
            // 目前简化处理

            stats.put("day_1_retention", 0.0);
            stats.put("day_7_retention", 0.0);
            stats.put("day_30_retention", 0.0);
            stats.put("days", days);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户留存率失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserGrowthRate(int days) {
        try {
            Map<String, Object> stats = new HashMap<>();

            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(days);

            // 当前期间新增用户
            String startDateStr = startDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String endDateStr = endDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            long currentPeriodUsers = accountService.query()
                    .ge("register_time", startDateStr + " 00:00:00")
                    .le("register_time", endDateStr + " 23:59:59")
                    .count();

            // 上一期间新增用户
            LocalDate prevStartDate = startDate.minusDays(days);
            LocalDate prevEndDate = startDate.minusDays(1);

            String prevStartDateStr = prevStartDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String prevEndDateStr = prevEndDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            long prevPeriodUsers = accountService.query()
                    .ge("register_time", prevStartDateStr + " 00:00:00")
                    .le("register_time", prevEndDateStr + " 23:59:59")
                    .count();

            // 计算增长率
            double growthRate = 0.0;
            if (prevPeriodUsers > 0) {
                growthRate = ((double) (currentPeriodUsers - prevPeriodUsers) / prevPeriodUsers) * 100;
            }

            stats.put("current_period_users", currentPeriodUsers);
            stats.put("prev_period_users", prevPeriodUsers);
            stats.put("growth_rate", growthRate);
            stats.put("days", days);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户增长率失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserBehaviorStats(Long userId, int days) {
        try {
            Map<String, Object> stats = new HashMap<>();

            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(days);

            // 获取用户在指定时间范围内的审计日志
            List<OperationLog> userLogs = operationLogMapper.findByUserIdAndTimeRange(userId, startTime, endTime);

            // 登录次数
            long loginCount = userLogs.stream()
                    .filter(log -> "LOGIN".equals(log.getOperationType()))
                    .count();

            // 总操作次数
            long actionsCount = userLogs.size();

            // 最后登录时间
            String lastLoginTime = userLogs.stream()
                    .filter(log -> "LOGIN".equals(log.getOperationType()))
                    .map(OperationLog::getOperationTime)
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .max(String::compareTo)
                    .orElse(null);

            // 估算会话时长（基于操作频率）
            double avgSessionDuration = userLogs.stream()
                    .filter(operation -> "LOGIN".equals(operation.getOperationType()))
                    .mapToDouble(operation -> {
                        // 简化计算，假设每个会话平均30分钟
                        return 30.0;
                    })
                    .average()
                    .orElse(0.0);

            // 操作类型分布
            Map<String, Long> operationTypeDistribution = userLogs.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            OperationLog::getOperationType,
                            java.util.stream.Collectors.counting()
                    ));

            // 风险操作统计
            long highRiskOperations = userLogs.stream()
                    .filter(log -> {
                        String riskLevel = log.getRiskLevel();
                        return "HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel);
                    })
                    .count();

            stats.put("user_id", userId);
            stats.put("login_count", loginCount);
            stats.put("actions_count", actionsCount);
            stats.put("operation_count", actionsCount);
            stats.put("avg_session_duration", Math.round(avgSessionDuration));
            stats.put("last_login_time", lastLoginTime);
            stats.put("high_risk_operations", highRiskOperations);
            stats.put("operation_type_distribution", operationTypeDistribution);
            stats.put("days", days);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户行为统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getUserDeviceStats(int days) {
        try {
            Map<String, Object> stats = new HashMap<>();

            // 这里可以根据用户设备信息来统计
            // 目前简化处理，返回空数据

            Map<String, Integer> deviceTypes = new HashMap<>();
            Map<String, Integer> browsers = new HashMap<>();
            Map<String, Integer> operatingSystems = new HashMap<>();

            stats.put("device_types", deviceTypes);
            stats.put("browsers", browsers);
            stats.put("operating_systems", operatingSystems);
            stats.put("days", days);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户设备统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }
}
