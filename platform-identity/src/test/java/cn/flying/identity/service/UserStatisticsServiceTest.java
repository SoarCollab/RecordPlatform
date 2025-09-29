package cn.flying.identity.service;

import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.OperationLog;
import cn.flying.identity.mapper.OperationLogMapper;
import cn.flying.identity.service.impl.UserStatisticsServiceImpl;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户统计服务单元测试
 * 测试范围：用户统计、活跃度分析、留存分析、行为分析、设备统计
 *
 * @author 王贝强
 * @create 2025-01-14
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserStatisticsServiceTest {

    // 测试数据常量
    private static final Long TEST_USER_ID = 789L;

    private static final String TEST_USER_AGENT_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    private static final String TEST_USER_AGENT_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 14_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0 Mobile/15E148 Safari/604.1";

    private static final String TEST_CLIENT_IP = "120.79.32.165";

    private static final String TEST_INTERNAL_IP = "192.168.1.100";

    private static final int TEST_DAYS = 7;

    @InjectMocks
    private UserStatisticsServiceImpl userStatisticsService;

    @Mock
    private AccountService accountService;

    @Mock
    private OperationLogMapper operationLogMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private QueryChainWrapper<Account> queryChainWrapper;

    @BeforeEach
    void setUp() {
        // 配置AccountService Mock
        when(accountService.query()).thenReturn(queryChainWrapper);
        when(accountService.count()).thenReturn(1000L);

        // 配置QueryChainWrapper默认行为
        when(queryChainWrapper.eq(anyString(), any())).thenReturn(queryChainWrapper);
        when(queryChainWrapper.ne(anyString(), any())).thenReturn(queryChainWrapper);
        when(queryChainWrapper.ge(anyString(), any())).thenReturn(queryChainWrapper);
        when(queryChainWrapper.le(anyString(), any())).thenReturn(queryChainWrapper);
        when(queryChainWrapper.select(anyString())).thenReturn(queryChainWrapper);
        when(queryChainWrapper.count()).thenReturn(100L);
        when(queryChainWrapper.list()).thenReturn(createMockAccounts());
    }

    // 辅助方法：创建模拟的账户列表
    private List<Account> createMockAccounts() {
        List<Account> accounts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Account account = new Account();
            account.setId((long) i);
            account.setUsername("user" + i);
            account.setEmail("user" + i + "@example.com");
            account.setRole("user");
            account.setDeleted(0);
            accounts.add(account);
        }
        return accounts;
    }

    @Test
    void testGetUserCountStats_Success() {
        // Mock各种用户数量查询
        when(queryChainWrapper.count())
                .thenReturn(800L)  // active users
                .thenReturn(50L)   // disabled users
                .thenReturn(20L)   // today new users
                .thenReturn(150L); // month new users

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserCountStats();

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(1000L, stats.get("total_users"));
        assertEquals(800L, stats.get("active_users"));
        assertEquals(50L, stats.get("disabled_users"));
        assertEquals(20L, stats.get("today_new_users"));
        assertEquals(150L, stats.get("month_new_users"));
        assertTrue(stats.containsKey("update_time"));

        // 验证查询被正确调用
        verify(accountService).count();
        verify(queryChainWrapper, times(4)).count();
    }

    @Test
    void testGetUserCountStats_Exception() {
        // Mock异常
        when(accountService.count()).thenThrow(new RuntimeException("数据库连接失败"));

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserCountStats();

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testGetRegistrationTrend_Success() {
        // Mock每日注册数据
        when(queryChainWrapper.count())
                .thenReturn(5L)   // day 1
                .thenReturn(8L)   // day 2
                .thenReturn(12L)  // day 3
                .thenReturn(6L)   // day 4
                .thenReturn(10L)  // day 5
                .thenReturn(15L)  // day 6
                .thenReturn(9L);  // day 7

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getRegistrationTrend(7);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(7, stats.get("days"));
        assertTrue(stats.containsKey("start_date"));
        assertTrue(stats.containsKey("end_date"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trendData = (List<Map<String, Object>>) stats.get("trend_data");
        assertEquals(7, trendData.size());

        // 验证第一天的数据
        Map<String, Object> firstDayData = trendData.getFirst();
        assertTrue(firstDayData.containsKey("date"));
        assertEquals(5L, firstDayData.get("count"));
    }

    @Test
    void testGetUserActivityStats_Success() {
        // Mock活跃用户统计
        List<Map<String, Object>> todayActiveUsers = Arrays.asList(
                Map.of("user_id", 1L), Map.of("user_id", 2L), Map.of("user_id", 3L)
        );
        List<Map<String, Object>> weekActiveUsers = Arrays.asList(
                Map.of("user_id", 1L), Map.of("user_id", 2L), Map.of("user_id", 3L),
                Map.of("user_id", 4L), Map.of("user_id", 5L)
        );
        List<Map<String, Object>> monthActiveUsers = Arrays.asList(
                Map.of("user_id", 1L), Map.of("user_id", 2L), Map.of("user_id", 3L),
                Map.of("user_id", 4L), Map.of("user_id", 5L), Map.of("user_id", 6L),
                Map.of("user_id", 7L), Map.of("user_id", 8L)
        );

        when(operationLogMapper.countByUser(any(LocalDateTime.class), any(LocalDateTime.class), eq(1000)))
                .thenReturn(todayActiveUsers)
                .thenReturn(weekActiveUsers)
                .thenReturn(monthActiveUsers);

        // Mock操作统计
        List<Map<String, Object>> operationStats = Arrays.asList(
                Map.of("operation_type", "LOGIN", "count", 100),
                Map.of("operation_type", "LOGOUT", "count", 80),
                Map.of("operation_type", "UPDATE", "count", 50)
        );
        when(operationLogMapper.countByOperationType(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(operationStats);

        // Mock每日统计
        List<Map<String, Object>> dailyStats = Arrays.asList(
                Map.of("date", "2024-01-01", "count", 50),
                Map.of("date", "2024-01-02", "count", 45),
                Map.of("date", "2024-01-03", "count", 60)
        );
        when(operationLogMapper.countByDate(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(dailyStats);

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserActivityStats(TEST_DAYS);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(3L, stats.get("active_users_today"));
        assertEquals(5L, stats.get("active_users_week"));
        assertEquals(8L, stats.get("active_users_month"));
        assertEquals(230L, stats.get("operation_count")); // 100 + 80 + 50
        assertEquals(TEST_DAYS, stats.get("days"));
        assertTrue(stats.containsKey("avg_session_duration"));
        assertTrue(stats.containsKey("calculation_time"));
    }

    @Test
    void testGetUserRoleDistribution_Success() {
        // Mock不同角色的用户数量
        when(queryChainWrapper.count())
                .thenReturn(5L)    // admin count
                .thenReturn(800L)  // user count
                .thenReturn(15L);  // other count

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserRoleDistribution();

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();

        @SuppressWarnings("unchecked")
        Map<String, Long> roleDistribution = (Map<String, Long>) stats.get("role_distribution");
        assertEquals(5L, roleDistribution.get("admin"));
        assertEquals(800L, roleDistribution.get("user"));
        assertEquals(15L, roleDistribution.get("other"));
        assertEquals(820L, stats.get("total_active_users")); // 5 + 800 + 15
    }

    @Test
    void testGetUserGeographicDistribution_Success() {
        // Mock IP统计数据
        List<Map<String, Object>> ipStats = Arrays.asList(
                Map.of("client_ip", "120.79.32.165", "count", 50),
                Map.of("client_ip", "8.8.8.8", "count", 30),
                Map.of("client_ip", "192.168.1.100", "count", 20),
                Map.of("client_ip", "1.1.1.1", "count", 40)
        );
        when(operationLogMapper.countByClientIp(any(LocalDateTime.class), any(LocalDateTime.class), eq(100)))
                .thenReturn(ipStats);

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserGeographicDistribution();

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertTrue(stats.containsKey("country_distribution"));
        assertTrue(stats.containsKey("city_distribution"));
        assertTrue(stats.containsKey("region_distribution"));
        assertTrue(stats.containsKey("total_regions"));
        assertTrue(stats.containsKey("update_time"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> countryDistribution = (Map<String, Integer>) stats.get("country_distribution");
        assertFalse(countryDistribution.isEmpty());
    }

    @Test
    void testGetUserLoginStats_Success() {
        // Mock登录操作日志
        List<OperationLog> loginOperations = createMockLoginOperations(50);
        when(operationLogMapper.findByOperationTypeAndTimeRange(eq("LOGIN"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(loginOperations);

        // Mock独立用户统计
        List<Map<String, Object>> uniqueUsers = Arrays.asList(
                Map.of("user_id", 1L), Map.of("user_id", 2L), Map.of("user_id", 3L),
                Map.of("user_id", 4L), Map.of("user_id", 5L)
        );
        when(operationLogMapper.countByUser(any(LocalDateTime.class), any(LocalDateTime.class), eq(10000)))
                .thenReturn(uniqueUsers);

        // Mock每小时统计
        List<Map<String, Object>> hourlyStats = Arrays.asList(
                Map.of("hour", 9, "count", 15),
                Map.of("hour", 14, "count", 25),
                Map.of("hour", 20, "count", 10)
        );
        when(operationLogMapper.countByHour(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(hourlyStats);

        // Mock失败登录
        List<OperationLog> failedOperations = createMockFailedOperations(5);
        when(operationLogMapper.findFailedOperations(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(failedOperations);

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserLoginStats(TEST_DAYS);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(50L, stats.get("total_logins"));
        assertEquals(5L, stats.get("unique_users"));
        assertEquals(10.0, stats.get("avg_logins_per_user")); // 50 / 5
        assertEquals(14, stats.get("peak_login_hour")); // 最高25次的14点
        assertEquals(5L, stats.get("failed_logins"));
        assertEquals(90.0, stats.get("login_success_rate")); // (50-5)/50 * 100
        assertEquals(TEST_DAYS, stats.get("days"));
    }

    // 辅助方法：创建模拟的登录操作日志
    private List<OperationLog> createMockLoginOperations(int count) {
        List<OperationLog> logs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            OperationLog log = new OperationLog();
            log.setId((long) i);
            log.setUserId((long) (i % 10 + 1)); // 模拟不同用户
            log.setOperationType("LOGIN");
            log.setOperationTime(LocalDateTime.now().minusDays(i % 7));
            log.setClientIp("192.168.1." + (i % 254 + 1));
            log.setUserAgent(TEST_USER_AGENT_CHROME);
            log.setStatus(1); // 1表示成功
            logs.add(log);
        }
        return logs;
    }

    // 辅助方法：创建模拟的失败操作日志
    private List<OperationLog> createMockFailedOperations(int count) {
        List<OperationLog> logs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            OperationLog log = new OperationLog();
            log.setId((long) (100 + i));
            log.setUserId((long) (i % 5 + 1));
            log.setOperationType("LOGIN");
            log.setOperationTime(LocalDateTime.now().minusDays(i % 3));
            log.setStatus(0); // 0表示失败
            log.setRiskLevel("LOW");
            logs.add(log);
        }
        return logs;
    }

    @Test
    void testGetUserRetentionRate_Success() {
        // Mock注册用户查询
        when(queryChainWrapper.count())
                .thenReturn(100L)  // 1日前注册用户
                .thenReturn(50L)   // 7日前注册用户
                .thenReturn(200L); // 30日前注册用户

        // Mock用户ID列表
        List<Account> accounts = createMockAccounts();
        when(queryChainWrapper.list()).thenReturn(accounts);

        // Mock操作日志查询 - 模拟部分用户有活动
        when(operationLogMapper.findByUserIdAndTimeRange(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(createMockOperationLogs(1)) // 有活动
                .thenReturn(new ArrayList<>())          // 无活动
                .thenReturn(createMockOperationLogs(1)) // 有活动
                .thenReturn(new ArrayList<>())          // 无活动
                .thenReturn(createMockOperationLogs(1)); // 有活动

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserRetentionRate(TEST_DAYS);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertTrue(stats.containsKey("day_1_retention"));
        assertTrue(stats.containsKey("day_7_retention"));
        assertTrue(stats.containsKey("day_30_retention"));
        assertEquals(TEST_DAYS, stats.get("days"));
        assertTrue(stats.containsKey("calculation_time"));

        // 验证留存率计算（基于Mock的返回值）
        Double day1Retention = (Double) stats.get("day_1_retention");
        assertNotNull(day1Retention);
        assertTrue(day1Retention >= 0.0 && day1Retention <= 100.0);
    }

    // 辅助方法：创建模拟的操作日志
    private List<OperationLog> createMockOperationLogs(int count) {
        List<OperationLog> logs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            OperationLog log = new OperationLog();
            log.setId((long) (200 + i));
            log.setUserId((long) (i % 3 + 1));
            log.setOperationType("VIEW");
            log.setOperationTime(LocalDateTime.now().minusHours(i));
            log.setStatus(1); // 1表示成功
            logs.add(log);
        }
        return logs;
    }

    @Test
    void testGetUserGrowthRate_Success() {
        // Mock当前期间和上一期间的用户数
        when(queryChainWrapper.count())
                .thenReturn(120L)  // current period users
                .thenReturn(100L); // previous period users

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserGrowthRate(TEST_DAYS);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(120L, stats.get("current_period_users"));
        assertEquals(100L, stats.get("prev_period_users"));
        assertEquals(20.0, (Double) stats.get("growth_rate"), 0.01); // (120-100)/100 * 100 = 20%
        assertEquals(TEST_DAYS, stats.get("days"));
    }

    @Test
    void testGetUserGrowthRate_ZeroPrevPeriod() {
        // Mock当前期间有用户，上一期间无用户
        when(queryChainWrapper.count())
                .thenReturn(50L)  // current period users
                .thenReturn(0L);  // previous period users

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserGrowthRate(TEST_DAYS);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(50L, stats.get("current_period_users"));
        assertEquals(0L, stats.get("prev_period_users"));
        assertEquals(0.0, stats.get("growth_rate")); // 上一期间为0时，增长率为0
    }

    @Test
    void testGetUserBehaviorStats_Success() {
        // Mock用户操作日志
        List<OperationLog> userLogs = createMockUserBehaviorLogs();
        when(operationLogMapper.findByUserIdAndTimeRange(eq(TEST_USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(userLogs);

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserBehaviorStats(TEST_USER_ID, TEST_DAYS);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(TEST_USER_ID, stats.get("user_id"));
        assertEquals(3L, stats.get("login_count")); // 3个LOGIN操作
        assertEquals(6L, stats.get("actions_count")); // 总共6个操作
        assertEquals(6L, stats.get("operation_count"));
        assertEquals(30L, stats.get("avg_session_duration")); // 平均会话时长
        assertTrue(stats.containsKey("last_login_time"));
        assertEquals(1L, stats.get("high_risk_operations")); // 1个高风险操作
        assertEquals(TEST_DAYS, stats.get("days"));

        @SuppressWarnings("unchecked")
        Map<String, Long> operationTypeDistribution = (Map<String, Long>) stats.get("operation_type_distribution");
        assertEquals(3L, operationTypeDistribution.get("LOGIN"));
        assertEquals(2L, operationTypeDistribution.get("UPDATE"));
        assertEquals(1L, operationTypeDistribution.get("DELETE"));
    }

    // 辅助方法：创建模拟的用户行为日志
    private List<OperationLog> createMockUserBehaviorLogs() {
        List<OperationLog> logs = new ArrayList<>();

        // 3个LOGIN操作
        for (int i = 0; i < 3; i++) {
            OperationLog log = new OperationLog();
            log.setId((long) (300 + i));
            log.setUserId(TEST_USER_ID);
            log.setOperationType("LOGIN");
            log.setOperationTime(LocalDateTime.now().minusDays(i));
            log.setStatus(1); // 1表示成功
            log.setRiskLevel("LOW");
            logs.add(log);
        }

        // 2个UPDATE操作
        for (int i = 0; i < 2; i++) {
            OperationLog log = new OperationLog();
            log.setId((long) (310 + i));
            log.setUserId(TEST_USER_ID);
            log.setOperationType("UPDATE");
            log.setOperationTime(LocalDateTime.now().minusDays(i));
            log.setStatus(1); // 1表示成功
            log.setRiskLevel("MEDIUM");
            logs.add(log);
        }

        // 1个DELETE操作（高风险）
        OperationLog deleteLog = new OperationLog();
        deleteLog.setId(320L);
        deleteLog.setUserId(TEST_USER_ID);
        deleteLog.setOperationType("DELETE");
        deleteLog.setOperationTime(LocalDateTime.now().minusDays(1));
        deleteLog.setStatus(1);
        deleteLog.setRiskLevel("HIGH");
        logs.add(deleteLog);

        return logs;
    }

    @Test
    void testGetUserDeviceStats_Success() {
        // Mock登录操作日志（包含User-Agent）
        List<OperationLog> loginLogs = createMockLoginLogsWithUserAgent();
        when(operationLogMapper.findByOperationTypeAndTimeRange(eq("LOGIN"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(loginLogs);

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserDeviceStats(TEST_DAYS);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(4, stats.get("total_sessions")); // 4个登录会话
        assertEquals(TEST_DAYS, stats.get("days"));
        assertTrue(stats.containsKey("update_time"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> deviceTypes = (Map<String, Integer>) stats.get("device_types");
        assertTrue(deviceTypes.containsKey("Desktop"));
        assertTrue(deviceTypes.containsKey("Mobile"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> browsers = (Map<String, Integer>) stats.get("browsers");
        assertTrue(browsers.containsKey("Chrome"));
        assertTrue(browsers.containsKey("Safari"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> operatingSystems = (Map<String, Integer>) stats.get("operating_systems");
        assertTrue(operatingSystems.containsKey("Windows 10/11"));
        assertTrue(operatingSystems.containsKey("iOS"));

        // 验证使用率计算
        @SuppressWarnings("unchecked")
        Map<String, Double> deviceTypeRates = (Map<String, Double>) stats.get("device_type_rates");
        assertFalse(deviceTypeRates.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Double> browserRates = (Map<String, Double>) stats.get("browser_rates");
        assertFalse(browserRates.isEmpty());
    }

    // 辅助方法：创建包含User-Agent的登录日志
    private List<OperationLog> createMockLoginLogsWithUserAgent() {
        List<OperationLog> logs = new ArrayList<>();

        // Chrome Desktop
        OperationLog log1 = new OperationLog();
        log1.setId(400L);
        log1.setUserId(1L);
        log1.setOperationType("LOGIN");
        log1.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        logs.add(log1);

        // iPhone Safari
        OperationLog log2 = new OperationLog();
        log2.setId(401L);
        log2.setUserId(2L);
        log2.setOperationType("LOGIN");
        log2.setUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 14_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0 Mobile/15E148 Safari/604.1");
        logs.add(log2);

        // Chrome Desktop (另一个)
        OperationLog log3 = new OperationLog();
        log3.setId(402L);
        log3.setUserId(3L);
        log3.setOperationType("LOGIN");
        log3.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.107 Safari/537.36");
        logs.add(log3);

        // Firefox Desktop
        OperationLog log4 = new OperationLog();
        log4.setId(403L);
        log4.setUserId(4L);
        log4.setOperationType("LOGIN");
        log4.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:89.0) Gecko/20100101 Firefox/89.0");
        logs.add(log4);

        return logs;
    }

    @Test
    void testGetUserDeviceStats_Exception() {
        // Mock异常
        when(operationLogMapper.findByOperationTypeAndTimeRange(anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("数据库查询失败"));

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserDeviceStats(TEST_DAYS);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testGetRegistrationTrend_Exception() {
        // Mock异常
        when(queryChainWrapper.count()).thenThrow(new RuntimeException("查询失败"));

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getRegistrationTrend(7);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testGetUserActivityStats_Exception() {
        // Mock异常
        when(operationLogMapper.countByUser(any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenThrow(new RuntimeException("统计查询失败"));

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserActivityStats(TEST_DAYS);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testGetUserRoleDistribution_Exception() {
        // Mock异常
        when(queryChainWrapper.count()).thenThrow(new RuntimeException("角色统计失败"));

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserRoleDistribution();

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testGetUserBehaviorStats_Exception() {
        // Mock异常
        when(operationLogMapper.findByUserIdAndTimeRange(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("行为统计失败"));

        // 执行测试
        Result<Map<String, Object>> result = userStatisticsService.getUserBehaviorStats(TEST_USER_ID, TEST_DAYS);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }
}