package cn.flying.identity.service.impl;

import cn.flying.identity.dto.Account;
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

            // 基于操作日志中的IP地址统计地理分布
            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(30); // 最近30天的数据

            List<Map<String, Object>> ipStats = operationLogMapper.countByClientIp(startTime, endTime, 100);

            Map<String, Integer> countryDistribution = new HashMap<>();
            Map<String, Integer> cityDistribution = new HashMap<>();
            Map<String, Integer> regionDistribution = new HashMap<>();

            for (Map<String, Object> ipStat : ipStats) {
                String ip = (String) ipStat.get("client_ip");
                long count = Long.parseLong(ipStat.get("count").toString());

                // 基于IP地址解析地理位置（简化实现）
                String location = parseIpLocation(ip);
                String[] locationParts = location.split("-");

                if (locationParts.length >= 2) {
                    String country = locationParts[0];
                    String city = locationParts[1];

                    countryDistribution.merge(country, (int) count, Integer::sum);
                    cityDistribution.merge(city, (int) count, Integer::sum);
                    regionDistribution.merge(location, (int) count, Integer::sum);
                }
            }

            stats.put("country_distribution", countryDistribution);
            stats.put("city_distribution", cityDistribution);
            stats.put("region_distribution", regionDistribution);
            stats.put("total_regions", regionDistribution.size());
            stats.put("update_time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户地理分布失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 解析IP地址的地理位置（简化实现）
     * <p>
     * 注意：这是一个模拟实现，仅用于演示目的。
     * 生产环境应该集成真实的IP地理位置查询服务，例如：
     * - MaxMind GeoIP2
     * - IP2Location
     * - 百度地图IP定位API
     * - 高德地图IP定位API
     *
     * @param ip IP地址
     * @return 地理位置字符串（格式：国家-城市）
     */
    private String parseIpLocation(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "未知-未知";
        }

        // 内网IP判断
        if (isInternalIp(ip)) {
            return "内网-本地";
        }

        // 简化的地理位置映射（实际应该使用IP地理位置数据库）
        if (ip.startsWith("127.") || ip.equals("::1")) {
            return "本地-localhost";
        } else if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
            return "内网-局域网";
        } else {
            // 这里应该集成真实的IP地理位置查询服务
            // 目前返回模拟数据
            int hash = ip.hashCode();
            String[] countries = {"中国", "美国", "日本", "韩国", "新加坡", "德国", "英国", "法国"};
            String[] cities = {"北京", "上海", "深圳", "广州", "杭州", "成都", "西安", "武汉"};

            String country = countries[Math.abs(hash) % countries.length];
            String city = cities[Math.abs(hash) % cities.length];

            return country + "-" + city;
        }
    }

    /**
     * 判断是否为内网IP
     */
    private boolean isInternalIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        return ip.startsWith("127.") ||
                ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                (ip.startsWith("172.") && isValidPrivateIp172(ip)) ||
                ip.equals("::1") ||
                ip.equals("localhost");
    }

    /**
     * 检查172网段的私有IP
     */
    private boolean isValidPrivateIp172(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                int secondOctet = Integer.parseInt(parts[1]);
                return secondOctet >= 16 && secondOctet <= 31;
            }
        } catch (NumberFormatException e) {
            // 忽略异常
        }
        return false;
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

            LocalDateTime now = LocalDateTime.now();

            // 计算1日留存率
            double day1Retention = calculateRetentionRate(1, now);
            stats.put("day_1_retention", Math.round(day1Retention * 100.0) / 100.0);

            // 计算7日留存率
            double day7Retention = calculateRetentionRate(7, now);
            stats.put("day_7_retention", Math.round(day7Retention * 100.0) / 100.0);

            // 计算30日留存率
            double day30Retention = calculateRetentionRate(30, now);
            stats.put("day_30_retention", Math.round(day30Retention * 100.0) / 100.0);

            // 计算自定义天数的留存率
            if (days > 0 && days != 1 && days != 7 && days != 30) {
                double customRetention = calculateRetentionRate(days, now);
                stats.put("day_" + days + "_retention", Math.round(customRetention * 100.0) / 100.0);
            }

            stats.put("days", days);
            stats.put("calculation_time", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户留存率失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 计算特定天数的用户留存率
     *
     * @param retentionDays 留存天数
     * @param now           当前时间
     * @return 留存率（百分比）
     */
    private double calculateRetentionRate(int retentionDays, LocalDateTime now) {
        try {
            // 计算注册时间范围
            LocalDateTime registerEndTime = now.minusDays(retentionDays);
            LocalDateTime registerStartTime = registerEndTime.withHour(0).withMinute(0).withSecond(0);
            registerEndTime = registerEndTime.withHour(23).withMinute(59).withSecond(59);

            // 获取在指定日期注册的用户数
            String startStr = registerStartTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String endStr = registerEndTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            long registeredUsers = accountService.query()
                    .ge("register_time", startStr)
                    .le("register_time", endStr)
                    .count();

            if (registeredUsers == 0) {
                return 0.0;
            }

            // 获取这些用户的ID列表
            List<Long> userIds = accountService.query()
                    .select("id")
                    .ge("register_time", startStr)
                    .le("register_time", endStr)
                    .list()
                    .stream()
                    .map(Account::getId)
                    .toList();

            // 计算活跃时间范围（注册后第N天）
            LocalDateTime activeStartTime = now.withHour(0).withMinute(0).withSecond(0);
            LocalDateTime activeEndTime = now.withHour(23).withMinute(59).withSecond(59);

            // 统计在留存日活跃的用户数（基于操作日志）
            long activeUsers = 0;
            for (Long userId : userIds) {
                List<OperationLog> logs = operationLogMapper.findByUserIdAndTimeRange(
                        userId, activeStartTime, activeEndTime);
                if (!logs.isEmpty()) {
                    activeUsers++;
                }
            }

            // 计算留存率
            return (double) activeUsers / registeredUsers * 100.0;
        } catch (Exception e) {
            log.error("计算{}日留存率失败", retentionDays, e);
            return 0.0;
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

            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusDays(days);

            // 从操作日志中获取用户代理字符串进行统计
            List<OperationLog> logs = operationLogMapper.findByOperationTypeAndTimeRange("LOGIN", startTime, endTime);

            Map<String, Integer> deviceTypes = new HashMap<>();
            Map<String, Integer> browsers = new HashMap<>();
            Map<String, Integer> operatingSystems = new HashMap<>();

            for (OperationLog log : logs) {
                String userAgent = log.getUserAgent();
                if (userAgent != null && !userAgent.isEmpty()) {
                    // 解析设备类型
                    String deviceType = parseDeviceType(userAgent);
                    deviceTypes.merge(deviceType, 1, Integer::sum);

                    // 解析浏览器
                    String browser = parseBrowser(userAgent);
                    browsers.merge(browser, 1, Integer::sum);

                    // 解析操作系统
                    String os = parseOperatingSystem(userAgent);
                    operatingSystems.merge(os, 1, Integer::sum);
                }
            }

            // 计算使用率
            int totalSessions = logs.size();
            Map<String, Double> deviceTypeRates = calculateUsageRates(deviceTypes, totalSessions);
            Map<String, Double> browserRates = calculateUsageRates(browsers, totalSessions);
            Map<String, Double> osRates = calculateUsageRates(operatingSystems, totalSessions);

            stats.put("device_types", deviceTypes);
            stats.put("device_type_rates", deviceTypeRates);
            stats.put("browsers", browsers);
            stats.put("browser_rates", browserRates);
            stats.put("operating_systems", operatingSystems);
            stats.put("os_rates", osRates);
            stats.put("total_sessions", totalSessions);
            stats.put("days", days);
            stats.put("update_time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取用户设备统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 解析设备类型
     */
    private String parseDeviceType(String userAgent) {
        if (userAgent == null) return "Unknown";

        userAgent = userAgent.toLowerCase();
        if (userAgent.contains("mobile") || userAgent.contains("android") || userAgent.contains("iphone")) {
            return "Mobile";
        } else if (userAgent.contains("tablet") || userAgent.contains("ipad")) {
            return "Tablet";
        } else {
            return "Desktop";
        }
    }

    /**
     * 解析浏览器
     */
    private String parseBrowser(String userAgent) {
        if (userAgent == null) return "Unknown";

        userAgent = userAgent.toLowerCase();
        if (userAgent.contains("chrome") && !userAgent.contains("edge")) {
            return "Chrome";
        } else if (userAgent.contains("firefox")) {
            return "Firefox";
        } else if (userAgent.contains("safari") && !userAgent.contains("chrome")) {
            return "Safari";
        } else if (userAgent.contains("edge")) {
            return "Edge";
        } else if (userAgent.contains("opera")) {
            return "Opera";
        } else if (userAgent.contains("ie") || userAgent.contains("trident")) {
            return "Internet Explorer";
        } else {
            return "Other";
        }
    }

    /**
     * 解析操作系统
     */
    private String parseOperatingSystem(String userAgent) {
        if (userAgent == null) return "Unknown";

        userAgent = userAgent.toLowerCase();
        if (userAgent.contains("windows")) {
            if (userAgent.contains("windows nt 10")) return "Windows 10/11";
            if (userAgent.contains("windows nt 6.3")) return "Windows 8.1";
            if (userAgent.contains("windows nt 6.2")) return "Windows 8";
            if (userAgent.contains("windows nt 6.1")) return "Windows 7";
            return "Windows";
        } else if (userAgent.contains("iphone") || userAgent.contains("ipad")) {
            // iOS检查必须在macOS之前，因为iPhone User-Agent包含"Mac OS X"
            return "iOS";
        } else if (userAgent.contains("android")) {
            return "Android";
        } else if (userAgent.contains("mac os")) {
            return "macOS";
        } else if (userAgent.contains("linux")) {
            return "Linux";
        } else {
            return "Other";
        }
    }

    /**
     * 计算使用率
     */
    private Map<String, Double> calculateUsageRates(Map<String, Integer> counts, int total) {
        Map<String, Double> rates = new HashMap<>();
        if (total > 0) {
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                double rate = (entry.getValue() * 100.0) / total;
                rates.put(entry.getKey(), Math.round(rate * 100.0) / 100.0); // 保留两位小数
            }
        }
        return rates;
    }
}
