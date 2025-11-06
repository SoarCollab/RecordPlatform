package cn.flying.identity.service;

import cn.flying.identity.dto.apigateway.ApiCallLog;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.mapper.apigateway.ApiCallLogMapper;
import cn.flying.identity.service.impl.apigateway.ApiCallLogServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ApiCallLogService 單元測試
 * 驗證異常策略與統計邏輯
 */
@ExtendWith(MockitoExtension.class)
class ApiCallLogServiceTest {

    @Mock
    private ApiCallLogMapper callLogMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ApiCallLogServiceImpl apiCallLogService;

    private ApiCallLog sampleLog;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        sampleLog = new ApiCallLog();
        sampleLog.setId(1L);
        sampleLog.setAppId(1001L);
        sampleLog.setApiKey("ak_test");
        sampleLog.setInterfacePath("/api/test");
        sampleLog.setResponseCode(200);
        sampleLog.setRequestTime(LocalDateTime.now());
        sampleLog.setResponseTime(120);
    }

    @Test
    void recordCallLog_shouldPersistAndUpdateStats() {
        when(callLogMapper.insert(any(ApiCallLog.class))).thenReturn(1);

        assertDoesNotThrow(() -> apiCallLogService.recordCallLog(sampleLog));
        verify(callLogMapper).insert(sampleLog);
    }

    @Test
    void recordCallLog_shouldThrowWhenInsertFails() {
        when(callLogMapper.insert(any(ApiCallLog.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> apiCallLogService.recordCallLog(sampleLog));
        assertEquals("记录调用日志失败", ex.getMessage());
    }

    @Test
    void getCallLogById_shouldReturnEntity() {
        when(callLogMapper.selectById(1L)).thenReturn(sampleLog);

        ApiCallLog result = apiCallLogService.getCallLogById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
    }

    @Test
    void getCallLogsPage_shouldReturnPagedData() {
        Page<ApiCallLog> page = new Page<>(1, 10);
        when(callLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> {
                    Page<ApiCallLog> arg = invocation.getArgument(0);
                    arg.setRecords(List.of(sampleLog));
                    arg.setTotal(1L);
                    return arg;
                });

        Page<ApiCallLog> result = apiCallLogService.getCallLogsPage(
                1, 10, sampleLog.getAppId(), sampleLog.getApiKey(),
                LocalDateTime.now().minusDays(1), LocalDateTime.now(), 200);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
    }

    @Test
    void getAppCallStatistics_shouldReturnCachedData() {
        when(valueOperations.get(anyString())).thenReturn("total_requests:10;success_requests:8;");

        Map<String, Object> stats = apiCallLogService.getAppCallStatistics(sampleLog.getAppId(), 7);

        assertEquals("10", stats.get("total_requests"));
        assertEquals("8", stats.get("success_requests"));
        assertNull(stats.get("stat_days"));
    }

    @Test
    void cleanExpiredLogs_shouldReturnDeletedCount() {
        when(callLogMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(5);

        int deleted = apiCallLogService.cleanExpiredLogs(30);

        assertEquals(5, deleted);
        verify(callLogMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void getRealtimeStatistics_shouldBuildFromRedisData() {
        Map<Object, Object> realtime = new HashMap<>();
        realtime.put("total_requests", "5");
        realtime.put("success_requests", "4");
        realtime.put("failed_requests", "1");
        realtime.put("avg_response_time", "100");
        realtime.put("last_update_time", "2025-10-30T05:00:00");

        when(hashOperations.entries(anyString())).thenReturn(realtime);

        Map<String, Object> stats = apiCallLogService.getRealtimeStatistics();

        assertEquals("5", stats.get("total_requests").toString());
        assertEquals("4", stats.get("success_requests").toString());
        assertEquals("1", stats.get("failed_requests").toString());
        assertEquals("100.0", stats.get("avg_response_time").toString());
        assertEquals("2025-10-30T05:00:00", stats.get("last_update_time"));
    }
}
