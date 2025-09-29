package cn.flying.identity.service;

import cn.flying.identity.service.impl.GatewayMonitorServiceImpl;
import cn.flying.identity.util.CacheUtils;
import cn.flying.identity.util.FlowUtils;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 网关监控服务单元测试
 * 测试范围：请求监控、流量统计、性能分析、异常检测、系统健康检查
 *
 * @author 王贝强
 * @create 2025-01-14
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GatewayMonitorServiceTest {

    // 测试数据常量
    private static final String TEST_REQUEST_ID = "req_123456789";

    private static final String TEST_METHOD = "POST";

    private static final String TEST_URI = "/api/auth/login";

    private static final String TEST_CLIENT_IP = "192.168.1.100";

    private static final String TEST_USER_AGENT = "Mozilla/5.0 Chrome/91.0";

    private static final Long TEST_USER_ID = 123L;

    private static final String TEST_ERROR_MESSAGE = "用户名或密码错误";

    private static final int TEST_STATUS_CODE_SUCCESS = 200;

    private static final int TEST_STATUS_CODE_ERROR = 401;

    private static final long TEST_RESPONSE_SIZE = 1024L;

    private static final long TEST_EXECUTION_TIME = 150L;

    @InjectMocks
    private GatewayMonitorServiceImpl gatewayMonitorService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private FlowUtils flowUtils;

    @Mock
    private CacheUtils cacheUtils;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ListOperations<String, String> listOperations;

    @BeforeEach
    void setUp() {
        // 配置Redis Mock
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        // 设置配置参数
        ReflectionTestUtils.setField(gatewayMonitorService, "redisPrefix", "gateway:");
        ReflectionTestUtils.setField(gatewayMonitorService, "dataRetentionHours", 24);
        ReflectionTestUtils.setField(gatewayMonitorService, "maxErrorDetails", 100);
        ReflectionTestUtils.setField(gatewayMonitorService, "requestsPerMinute", 60);
        ReflectionTestUtils.setField(gatewayMonitorService, "requestsPerHour", 1000);
        ReflectionTestUtils.setField(gatewayMonitorService, "blockTime", 300);

        // 配置Redis通用操作
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.delete(anyString())).thenReturn(true);
        when(redisTemplate.keys(anyString())).thenReturn(new HashSet<>());
        when(redisTemplate.getExpire(anyString())).thenReturn(3600L);

        // 配置CacheUtils默认行为（默认情况：不在黑名单、不在临时封禁）
        when(cacheUtils.exists(anyString())).thenReturn(false);
        doNothing().when(cacheUtils).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testRecordRequestStart_Success() {
        // 执行测试
        Result<Void> result = gatewayMonitorService.recordRequestStart(
                TEST_REQUEST_ID, TEST_METHOD, TEST_URI, TEST_CLIENT_IP, TEST_USER_AGENT, TEST_USER_ID
        );

        // 验证结果
        assertTrue(result.isSuccess());

        // 验证Redis操作
        verify(hashOperations).putAll(eq("gateway:request:" + TEST_REQUEST_ID), anyMap());
        verify(redisTemplate).expire("gateway:request:" + TEST_REQUEST_ID, 1, TimeUnit.HOURS);
        verify(hashOperations, atLeastOnce()).increment(anyString(), eq("total"), eq(1L));
    }

    @Test
    void testRecordRequestStart_BlankRequestId() {
        // 执行测试 - 空请求ID
        Result<Void> result = gatewayMonitorService.recordRequestStart(
                "", TEST_METHOD, TEST_URI, TEST_CLIENT_IP, TEST_USER_AGENT, TEST_USER_ID
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("请求ID不能为空"));

        // 验证Redis操作未被调用
        verify(hashOperations, never()).putAll(anyString(), anyMap());
    }

    @Test
    void testRecordRequestStart_BlankMethod() {
        // 执行测试 - 空方法
        Result<Void> result = gatewayMonitorService.recordRequestStart(
                TEST_REQUEST_ID, "", TEST_URI, TEST_CLIENT_IP, TEST_USER_AGENT, TEST_USER_ID
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("请求方法不能为空"));
    }

    @Test
    void testRecordRequestStart_BlankUri() {
        // 执行测试 - 空URI
        Result<Void> result = gatewayMonitorService.recordRequestStart(
                TEST_REQUEST_ID, TEST_METHOD, "", TEST_CLIENT_IP, TEST_USER_AGENT, TEST_USER_ID
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("请求URI不能为空"));
    }

    @Test
    void testRecordRequestStart_BlankClientIp() {
        // 执行测试 - 空客户端IP
        Result<Void> result = gatewayMonitorService.recordRequestStart(
                TEST_REQUEST_ID, TEST_METHOD, TEST_URI, "", TEST_USER_AGENT, TEST_USER_ID
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("客户端IP不能为空"));
    }

    @Test
    void testRecordRequestStart_NullUserAgent() {
        // 执行测试 - null UserAgent
        Result<Void> result = gatewayMonitorService.recordRequestStart(
                TEST_REQUEST_ID, TEST_METHOD, TEST_URI, TEST_CLIENT_IP, null, TEST_USER_ID
        );

        // 验证结果
        assertTrue(result.isSuccess());

        // 验证Redis操作正常进行
        verify(hashOperations).putAll(eq("gateway:request:" + TEST_REQUEST_ID), anyMap());
    }

    @Test
    void testRecordRequestStart_NullUserId() {
        // 执行测试 - null用户ID
        Result<Void> result = gatewayMonitorService.recordRequestStart(
                TEST_REQUEST_ID, TEST_METHOD, TEST_URI, TEST_CLIENT_IP, TEST_USER_AGENT, null
        );

        // 验证结果
        assertTrue(result.isSuccess());

        // 验证Redis操作正常进行
        verify(hashOperations).putAll(eq("gateway:request:" + TEST_REQUEST_ID), anyMap());
    }

    @Test
    void testRecordRequestStart_RedisException() {
        // Mock Redis异常
        doThrow(new RuntimeException("Redis连接失败"))
                .when(hashOperations).putAll(anyString(), anyMap());

        // 执行测试
        Result<Void> result = gatewayMonitorService.recordRequestStart(
                TEST_REQUEST_ID, TEST_METHOD, TEST_URI, TEST_CLIENT_IP, TEST_USER_AGENT, TEST_USER_ID
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testRecordRequestEnd_Success() {
        // Mock请求信息存在
        Map<Object, Object> requestInfo = new HashMap<>();
        requestInfo.put("method", TEST_METHOD);
        requestInfo.put("uri", TEST_URI);
        when(hashOperations.entries("gateway:request:" + TEST_REQUEST_ID)).thenReturn(requestInfo);

        // 执行测试
        Result<Void> result = gatewayMonitorService.recordRequestEnd(
                TEST_REQUEST_ID, TEST_STATUS_CODE_SUCCESS, TEST_RESPONSE_SIZE, TEST_EXECUTION_TIME, null
        );

        // 验证结果
        assertTrue(result.isSuccess());

        // 验证Redis操作
        verify(hashOperations).putAll(eq("gateway:request:" + TEST_REQUEST_ID), anyMap());
        verify(hashOperations).entries("gateway:request:" + TEST_REQUEST_ID);
        verify(hashOperations).increment(anyString(), eq("success"), eq(1L));
    }

    @Test
    void testRecordRequestEnd_WithErrorMessage() {
        // Mock请求信息存在
        Map<Object, Object> requestInfo = new HashMap<>();
        requestInfo.put("method", TEST_METHOD);
        requestInfo.put("uri", TEST_URI);
        when(hashOperations.entries("gateway:request:" + TEST_REQUEST_ID)).thenReturn(requestInfo);

        // 执行测试 - 包含错误消息
        Result<Void> result = gatewayMonitorService.recordRequestEnd(
                TEST_REQUEST_ID, TEST_STATUS_CODE_ERROR, TEST_RESPONSE_SIZE, TEST_EXECUTION_TIME, TEST_ERROR_MESSAGE
        );

        // 验证结果
        assertTrue(result.isSuccess());

        // 验证错误统计被更新
        verify(hashOperations, atLeastOnce()).increment(anyString(), eq("HTTP_401"), eq(1L));
        verify(listOperations).leftPush(anyString(), anyString());
    }

    @Test
    void testRecordRequestEnd_InvalidStatusCode() {
        // 执行测试 - 无效状态码
        Result<Void> result = gatewayMonitorService.recordRequestEnd(
                TEST_REQUEST_ID, 999, TEST_RESPONSE_SIZE, TEST_EXECUTION_TIME, null
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("状态码必须在100-599之间"));
    }

    @Test
    void testRecordRequestEnd_NegativeResponseSize() {
        // 执行测试 - 负数响应大小
        Result<Void> result = gatewayMonitorService.recordRequestEnd(
                TEST_REQUEST_ID, TEST_STATUS_CODE_SUCCESS, -1L, TEST_EXECUTION_TIME, null
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("响应大小不能为负数"));
    }

    @Test
    void testRecordRequestEnd_NegativeExecutionTime() {
        // 执行测试 - 负数执行时间
        Result<Void> result = gatewayMonitorService.recordRequestEnd(
                TEST_REQUEST_ID, TEST_STATUS_CODE_SUCCESS, TEST_RESPONSE_SIZE, -10L, null
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("执行时间不能为负数"));
    }

    @Test
    void testCheckRateLimit_Success() {
        // Mock流量限制检查通过
        when(flowUtils.limitPeriodCountCheck(anyString(), eq(60), eq(60))).thenReturn(true);    // IP分钟限流
        when(flowUtils.limitPeriodCountCheck(anyString(), eq(1000), eq(3600))).thenReturn(true); // IP小时限流
        when(flowUtils.limitPeriodCountCheck(anyString(), eq(120), eq(60))).thenReturn(true);    // 用户分钟限流
        when(flowUtils.limitPeriodCountCheck(anyString(), eq(2000), eq(3600))).thenReturn(true); // 用户小时限流
        when(flowUtils.limitPeriodCountCheck(anyString(), eq(600), eq(60))).thenReturn(true);    // API分钟限流

        // 执行测试
        Result<Boolean> result = gatewayMonitorService.checkRateLimit(TEST_CLIENT_IP, TEST_USER_ID, TEST_URI);

        // 验证结果
        assertTrue(result.isSuccess());
        assertTrue(result.getData());

        // 验证流量检查被调用
        verify(flowUtils, atLeast(3)).limitPeriodCountCheck(anyString(), anyInt(), anyInt());
    }

    @Test
    void testCheckRateLimit_BlankClientIp() {
        // 执行测试 - 空客户端IP
        Result<Boolean> result = gatewayMonitorService.checkRateLimit("", TEST_USER_ID, TEST_URI);

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testCheckRateLimit_InBlacklist() {
        // Mock IP在黑名单中
        when(cacheUtils.exists("gateway:limit:blacklist:" + TEST_CLIENT_IP)).thenReturn(true);

        // 执行测试
        Result<Boolean> result = gatewayMonitorService.checkRateLimit(TEST_CLIENT_IP, TEST_USER_ID, TEST_URI);

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testCheckRateLimit_ExceedMinuteLimit() {
        // Mock超出分钟限制
        when(flowUtils.limitPeriodCountCheck(contains("ip:minute"), eq(60), eq(60))).thenReturn(false);

        // 执行测试
        Result<Boolean> result = gatewayMonitorService.checkRateLimit(TEST_CLIENT_IP, TEST_USER_ID, TEST_URI);

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());

        // 验证临时封禁
        verify(cacheUtils).set(eq("gateway:limit:temp_ban:" + TEST_CLIENT_IP), eq("1"), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    void testCheckRateLimit_ExceedHourLimit() {
        // Mock超出小时限制
        when(flowUtils.limitPeriodCountCheck(contains("ip:minute"), eq(60), eq(60))).thenReturn(true);
        when(flowUtils.limitPeriodCountCheck(contains("ip:hour"), eq(1000), eq(3600))).thenReturn(false);

        // 执行测试
        Result<Boolean> result = gatewayMonitorService.checkRateLimit(TEST_CLIENT_IP, TEST_USER_ID, TEST_URI);

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testCheckRateLimit_InTempBan() {
        // Mock不超限制但在临时封禁中
        when(flowUtils.limitPeriodCountCheck(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(cacheUtils.exists("gateway:limit:temp_ban:" + TEST_CLIENT_IP)).thenReturn(true);

        // 执行测试
        Result<Boolean> result = gatewayMonitorService.checkRateLimit(TEST_CLIENT_IP, TEST_USER_ID, TEST_URI);

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testCheckRateLimit_UserExceedLimit() {
        // Mock IP通过但用户超限
        when(flowUtils.limitPeriodCountCheck(contains("ip:"), anyInt(), anyInt())).thenReturn(true);
        when(flowUtils.limitPeriodCountCheck(contains("user:minute"), eq(120), eq(60))).thenReturn(false);

        // 执行测试
        Result<Boolean> result = gatewayMonitorService.checkRateLimit(TEST_CLIENT_IP, TEST_USER_ID, TEST_URI);

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testCheckRateLimit_ApiExceedLimit() {
        // Mock IP和用户通过但API超限
        when(flowUtils.limitPeriodCountCheck(contains("ip:"), anyInt(), anyInt())).thenReturn(true);
        when(flowUtils.limitPeriodCountCheck(contains("user:"), anyInt(), anyInt())).thenReturn(true);
        when(flowUtils.limitPeriodCountCheck(contains("api:minute"), eq(600), eq(60))).thenReturn(false);

        // 执行测试
        Result<Boolean> result = gatewayMonitorService.checkRateLimit(TEST_CLIENT_IP, TEST_USER_ID, TEST_URI);

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testCheckRateLimit_Exception() {
        // Mock异常
        when(flowUtils.limitPeriodCountCheck(anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Redis连接失败"));

        // 执行测试
        Result<Boolean> result = gatewayMonitorService.checkRateLimit(TEST_CLIENT_IP, TEST_USER_ID, TEST_URI);

        // 验证结果：异常情况下应允许通过
        assertTrue(result.isSuccess());
        assertTrue(result.getData());
    }

    @Test
    void testGetRealTimeTrafficStats_Success() {
        // Mock流量数据
        Map<Object, Object> trafficData = new HashMap<>();
        trafficData.put("total", "100");
        trafficData.put("error", "5");
        trafficData.put("method:POST", "60");
        trafficData.put("method:GET", "40");

        when(hashOperations.entries(anyString())).thenReturn(trafficData);

        // Mock独立IP和用户
        Set<String> ips = Set.of("192.168.1.1", "192.168.1.2", "10.0.0.1");
        Set<String> users = Set.of("user1", "user2");
        when(setOperations.members(anyString()))
                .thenReturn(ips)
                .thenReturn(users);

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getRealTimeTrafficStats(5);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(500L, stats.get("total_requests")); // 5分钟 * 100请求/分钟
        assertEquals(475L, stats.get("success_requests")); // 500 - 25错误
        assertEquals(25L, stats.get("error_requests")); // 5 * 5分钟
        assertEquals(95.0, (Double) stats.get("success_rate"), 0.1);
        assertEquals(5.0, (Double) stats.get("error_rate"), 0.1);
        assertTrue(stats.containsKey("method_stats"));
        assertTrue(stats.containsKey("time_series"));
    }

    @Test
    void testGetRealTimeTrafficStats_NoData() {
        // Mock无流量数据
        when(hashOperations.entries(anyString())).thenReturn(new HashMap<>());
        when(setOperations.members(anyString())).thenReturn(new HashSet<>());

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getRealTimeTrafficStats(5);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(0L, stats.get("total_requests"));
        assertEquals(0L, stats.get("success_requests"));
        assertEquals(0L, stats.get("error_requests"));
        assertEquals(0.0, stats.get("success_rate"));
        assertEquals(0.0, stats.get("error_rate"));
    }

    @Test
    void testGetRealTimeTrafficStats_Exception() {
        // Mock Redis异常
        when(hashOperations.entries(anyString()))
                .thenThrow(new RuntimeException("Redis连接失败"));

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getRealTimeTrafficStats(5);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testGetApiCallStats_Success() {
        // Mock API调用数据
        Map<Object, Object> apiData = new HashMap<>();
        apiData.put("POST /api/auth/login", "50");
        apiData.put("GET /api/user/info", "30");
        apiData.put("POST /api/auth/logout", "20");

        when(hashOperations.entries(anyString())).thenReturn(apiData);

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getApiCallStats(10, 5);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertTrue(stats.containsKey("top_apis"));
        assertEquals(3, stats.get("total_apis"));
        assertEquals(10, stats.get("time_range"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topApis = (List<Map<String, Object>>) stats.get("top_apis");
        assertEquals(3, topApis.size());

        // 验证排序：第一个应该是调用次数最多的
        assertEquals("POST /api/auth/login", topApis.getFirst().get("api"));
        assertEquals(500L, topApis.getFirst().get("count")); // 50 * 10分钟
    }

    @Test
    void testGetErrorStats_Success() {
        // Mock错误统计数据
        Map<Object, Object> errorData = new HashMap<>();
        errorData.put("HTTP_401", "10");
        errorData.put("HTTP_404", "5");
        errorData.put("api:POST /api/auth/login", "8");
        errorData.put("api:GET /api/user/info", "7");

        when(hashOperations.entries(anyString())).thenReturn(errorData);

        // Mock错误详情
        List<String> errorDetails = Arrays.asList(
                "POST /api/auth/login:401:用户名或密码错误",
                "GET /api/user/info:404:用户不存在"
        );
        when(listOperations.range(anyString(), eq(0L), eq(19L))).thenReturn(errorDetails);

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getErrorStats(5);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertTrue(stats.containsKey("error_types"));
        assertTrue(stats.containsKey("api_errors"));
        assertTrue(stats.containsKey("recent_errors"));
        assertEquals(150L, stats.get("total_errors")); // (10+5+8+7) * 5分钟 = 150

        @SuppressWarnings("unchecked")
        Map<String, Long> errorTypes = (Map<String, Long>) stats.get("error_types");
        assertTrue(errorTypes.containsKey("HTTP_401"));
        assertTrue(errorTypes.containsKey("HTTP_404"));
    }

    @Test
    void testGetPerformanceStats_Success() {
        // Mock性能数据
        Map<Object, Object> performanceData = new HashMap<>();
        performanceData.put("POST /api/auth/login", "150");
        performanceData.put("GET /api/user/info", "80");
        performanceData.put("POST /api/auth/logout", "120");
        performanceData.put("GET /api/auth/status", "200");

        when(hashOperations.entries(anyString())).thenReturn(performanceData);

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getPerformanceStats(5);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(137.5, (Double) stats.get("avg_response_time"), 0.1); // (150+80+120+200)/4
        assertEquals(80L, stats.get("min_response_time"));
        assertEquals(200L, stats.get("max_response_time"));
        assertEquals(20, stats.get("total_requests")); // 4个API * 5分钟
        assertTrue(stats.containsKey("p95_response_time"));
        assertTrue(stats.containsKey("p99_response_time"));
    }

    @Test
    void testGetPerformanceStats_NoData() {
        // Mock无性能数据
        when(hashOperations.entries(anyString())).thenReturn(new HashMap<>());

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getPerformanceStats(5);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(0.0, stats.get("avg_response_time"));
        assertEquals(0L, stats.get("min_response_time"));
        assertEquals(0L, stats.get("max_response_time"));
        assertEquals(0, stats.get("total_requests"));
    }

    @Test
    void testGetUserActivityStats_Success() {
        // Mock用户活动数据
        Set<String> activeUsers = Set.of("user1", "user2", "user3", "user4");
        when(setOperations.members(anyString())).thenReturn(activeUsers);

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getUserActivityStats(10);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(4, stats.get("active_users"));
        assertEquals(10, stats.get("time_range"));
    }

    @Test
    void testDetectAbnormalTraffic_Normal() {
        // Mock正常流量
        when(valueOperations.get(anyString())).thenReturn("30"); // 低于阈值

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.detectAbnormalTraffic(TEST_CLIENT_IP, TEST_USER_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertFalse((Boolean) data.get("is_abnormal"));
        assertEquals(TEST_CLIENT_IP, data.get("client_ip"));
        assertEquals(TEST_USER_ID, data.get("user_id"));

        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) data.get("reasons");
        assertTrue(reasons.isEmpty());
    }

    @Test
    void testDetectAbnormalTraffic_HighIpTraffic() {
        // Mock IP流量过高（超过阈值的80%）
        when(valueOperations.get(contains("ip:minute"))).thenReturn("50"); // 60 * 0.8 = 48，50 > 48

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.detectAbnormalTraffic(TEST_CLIENT_IP, TEST_USER_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertTrue((Boolean) data.get("is_abnormal"));

        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) data.get("reasons");
        assertTrue(reasons.contains("IP请求频率过高"));
    }

    @Test
    void testDetectAbnormalTraffic_HighUserTraffic() {
        // Mock用户流量过高（超过阈值 requestsPerMinute * 1.6 = 60 * 1.6 = 96）
        when(valueOperations.get(contains("ip:minute"))).thenReturn("30"); // 低于IP阈值 60 * 0.8 = 48
        when(valueOperations.get(contains("user:minute"))).thenReturn("100"); // 100 > 96，触发用户流量过高

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.detectAbnormalTraffic(TEST_CLIENT_IP, TEST_USER_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertTrue((Boolean) data.get("is_abnormal"));

        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) data.get("reasons");
        assertTrue(reasons.contains("用户请求频率过高"));
    }

    @Test
    void testDetectAbnormalTraffic_BothHigh() {
        // Mock IP和用户流量都过高
        when(valueOperations.get(contains("ip:minute"))).thenReturn("50");  // 50 > 48（IP阈值）
        when(valueOperations.get(contains("user:minute"))).thenReturn("100"); // 100 > 96（用户阈值）

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.detectAbnormalTraffic(TEST_CLIENT_IP, TEST_USER_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertTrue((Boolean) data.get("is_abnormal"));

        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) data.get("reasons");
        assertEquals(2, reasons.size());
        assertTrue(reasons.contains("IP请求频率过高"));
        assertTrue(reasons.contains("用户请求频率过高"));
    }

    @Test
    void testGetSystemHealth_Success() {
        // Mock Redis正常
        when(valueOperations.get("health_check")).thenReturn(null);

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getSystemHealth();

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> health = result.getData();
        assertEquals("healthy", health.get("redis_status"));
        assertEquals("healthy", health.get("status"));
        assertTrue(health.containsKey("memory_total"));
        assertTrue(health.containsKey("memory_used"));
        assertTrue(health.containsKey("memory_free"));
        assertTrue(health.containsKey("memory_usage_percent"));
        assertTrue(health.containsKey("current_time"));
    }

    @Test
    void testGetSystemHealth_RedisUnhealthy() {
        // Mock Redis异常
        when(valueOperations.get("health_check"))
                .thenThrow(new RuntimeException("连接超时"));

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getSystemHealth();

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> health = result.getData();
        assertEquals("unhealthy", health.get("redis_status"));
        assertEquals("连接超时", health.get("redis_error"));
    }

    @Test
    void testCleanExpiredData_Success() {
        // Mock存在一些键
        Set<String> requestKeys = Set.of("gateway:request:req1", "gateway:request:req2");
        Set<String> rateLimitKeys = Set.of("gateway:limit:ip1", "gateway:limit:ip2");

        when(redisTemplate.keys("gateway:request:*")).thenReturn(requestKeys);
        when(redisTemplate.keys("gateway:limit:*")).thenReturn(rateLimitKeys);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(redisTemplate.getExpire(anyString())).thenReturn(-1L); // 表示已过期

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.cleanExpiredData(7);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> cleanResult = result.getData();
        assertTrue(cleanResult.containsKey("cleaned_count"));
        assertEquals(7, cleanResult.get("retention_days"));
        assertTrue(cleanResult.containsKey("cutoff_time"));
        assertTrue(cleanResult.containsKey("cleanup_time"));

        // 验证清理操作被调用
        verify(redisTemplate, atLeastOnce()).delete(anyString());
    }

    @Test
    void testGetHotApiRanking_Success() {
        // 这个方法复用getApiCallStats，Mock相同的数据
        Map<Object, Object> apiData = new HashMap<>();
        apiData.put("POST /api/auth/login", "50");
        apiData.put("GET /api/user/info", "30");

        when(hashOperations.entries(anyString())).thenReturn(apiData);

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getHotApiRanking(10, 5);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertTrue(stats.containsKey("top_apis"));
    }

    @Test
    void testGetSlowQueryStats_Success() {
        // Mock性能数据，包含一些慢查询
        Map<Object, Object> performanceData = new HashMap<>();
        performanceData.put("POST /api/auth/login", "1500"); // 慢查询
        performanceData.put("GET /api/user/info", "80");     // 正常
        performanceData.put("POST /api/data/export", "2000"); // 慢查询

        when(hashOperations.entries(anyString())).thenReturn(performanceData);

        // 执行测试 - 阈值1000ms
        Result<Map<String, Object>> result = gatewayMonitorService.getSlowQueryStats(5, 1000);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(1000L, stats.get("threshold"));
        assertEquals(5, stats.get("time_range"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slowQueries = (List<Map<String, Object>>) stats.get("slow_queries");
        assertEquals(10, slowQueries.size()); // 2个慢查询 * 5分钟
        assertEquals(10, stats.get("total_count"));
    }

    @Test
    void testGetGeographicStats_Success() {
        // Mock流量数据包含IP统计
        Map<Object, Object> trafficData = new HashMap<>();
        trafficData.put("ip:192.168.1.1", "10");    // 内网IP
        trafficData.put("ip:1.1.1.1", "20");        // 中国IP
        trafficData.put("ip:8.8.8.8", "15");        // 美国IP
        trafficData.put("total", "100");

        when(hashOperations.entries(anyString())).thenReturn(trafficData);

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getGeographicStats(5);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertTrue(stats.containsKey("top_countries"));
        assertTrue(stats.containsKey("top_cities"));
        assertEquals(3, stats.get("total_ips"));
        assertEquals(5, stats.get("time_range"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topCountries = (List<Map<String, Object>>) stats.get("top_countries");
        assertFalse(topCountries.isEmpty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topCities = (List<Map<String, Object>>) stats.get("top_cities");
        assertFalse(topCities.isEmpty());
    }

    @Test
    void testGetGeographicStats_Exception() {
        // Mock Redis异常
        when(hashOperations.entries(anyString()))
                .thenThrow(new RuntimeException("Redis连接失败"));

        // 执行测试
        Result<Map<String, Object>> result = gatewayMonitorService.getGeographicStats(5);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }
}