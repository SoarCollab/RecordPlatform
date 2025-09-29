package cn.flying.identity.service;

import cn.flying.identity.dto.OperationLog;
import cn.flying.identity.mapper.OperationLogMapper;
import cn.flying.identity.service.impl.OperationLogServiceImpl;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 操作日志服务测试类
 * 测试操作日志的记录、查询、统计、清理等功能
 *
 * @author flying
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperationLogServiceTest {

    @Spy
    @InjectMocks
    private OperationLogServiceImpl operationLogService;

    @Mock
    private OperationLogMapper operationLogMapper;

    // 测试常量
    private static final Long TEST_LOG_ID = 1L;
    private static final Long TEST_USER_ID = 10001L;
    private static final String TEST_MODULE = "用户管理";
    private static final String TEST_OPERATION_TYPE = "登录";
    private static final String TEST_RISK_LEVEL = "HIGH";

    private OperationLog mockLog;

    @BeforeEach
    void setUp() {
        // 创建测试用的操作日志对象
        mockLog = createMockOperationLog();
    }

    private OperationLog createMockOperationLog() {
        OperationLog log = new OperationLog();
        log.setId(TEST_LOG_ID);
        log.setUserId(TEST_USER_ID);
        log.setModule(TEST_MODULE);
        log.setOperationType(TEST_OPERATION_TYPE);
        log.setDescription("用户登录操作");
        log.setStatus(0);  // 成功
        log.setExecutionTime(150L);
        log.setRiskLevel("NORMAL");
        log.setOperationTime(LocalDateTime.now());
        log.setCreateTime(LocalDateTime.now());
        log.setUpdateTime(LocalDateTime.now());
        return log;
    }

    @Test
    void testSaveOperationLog_Success() {
        // 准备测试数据
        doReturn(true).when(operationLogService).save(any(OperationLog.class));

        // 执行测试
        Result<Void> result = operationLogService.saveOperationLog(mockLog);

        // 验证结果
        assertTrue(result.isSuccess());
        verify(operationLogService, times(1)).save(any(OperationLog.class));
    }

    @Test
    void testSaveOperationLog_Failed() {
        // 准备测试数据
        doReturn(false).when(operationLogService).save(any(OperationLog.class));

        // 执行测试
        Result<Void> result = operationLogService.saveOperationLog(mockLog);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testSaveOperationLog_Exception() {
        // 准备测试数据
        doThrow(new RuntimeException("Database error")).when(operationLogService).save(any(OperationLog.class));

        // 执行测试
        Result<Void> result = operationLogService.saveOperationLog(mockLog);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testGetOperationLogs_Success() {
        // 准备测试数据
        Page<OperationLog> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Arrays.asList(mockLog));
        mockPage.setTotal(1);
        mockPage.setPages(1);

        doReturn(mockPage).when(operationLogService).page(any(Page.class), any(QueryWrapper.class));

        // 执行测试
        Result<Map<String, Object>> result = operationLogService.getOperationLogs(
                1, 10, TEST_USER_ID, TEST_MODULE, TEST_OPERATION_TYPE,
                LocalDateTime.now().minusDays(1), LocalDateTime.now()
        );

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertEquals(1L, data.get("total"));
        assertEquals(1, data.get("page"));
        assertEquals(10, data.get("size"));
        assertNotNull(data.get("logs"));
    }

    @Test
    void testGetOperationLogStats_Success() {
        // 准备测试数据
        List<OperationLog> mockLogs = Arrays.asList(
                createMockOperationLog(),
                createMockOperationLog(),
                createMockOperationLog()
        );
        mockLogs.get(1).setStatus(1);  // 设置一个失败的日志
        mockLogs.get(2).setModule("文件管理");
        mockLogs.get(2).setOperationType("上传");

        doReturn(3L).when(operationLogService).count(any(QueryWrapper.class));
        doReturn(mockLogs).when(operationLogService).list(any(QueryWrapper.class));

        // Mock成功和失败计数
        when(operationLogService.count(any(QueryWrapper.class)))
                .thenReturn(3L)  // 总数
                .thenReturn(2L)  // 成功数
                .thenReturn(1L); // 失败数

        // 执行测试
        Result<Map<String, Object>> result = operationLogService.getOperationLogStats(7);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(3L, stats.get("total_operations"));
        assertEquals(2L, stats.get("success_operations"));
        assertEquals(1L, stats.get("fail_operations"));
        assertEquals(7, stats.get("days"));
        assertNotNull(stats.get("module_stats"));
        assertNotNull(stats.get("operation_type_stats"));
    }

    @Test
    void testGetUserOperationStats_Success() {
        // 准备测试数据
        List<OperationLog> userLogs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            OperationLog log = createMockOperationLog();
            log.setExecutionTime((long) (100 + i * 50));
            if (i == 2) {
                log.setStatus(1);  // 设置一个失败的
            }
            userLogs.add(log);
        }

        doReturn(userLogs).when(operationLogService).list(any(QueryWrapper.class));

        // 执行测试
        Result<Map<String, Object>> result = operationLogService.getUserOperationStats(TEST_USER_ID, 30);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(TEST_USER_ID, stats.get("user_id"));
        assertEquals(5L, stats.get("total_operations"));
        assertEquals(4L, stats.get("success_operations"));
        assertEquals(1L, stats.get("fail_operations"));
        assertEquals(30, stats.get("days"));
        assertTrue((Double) stats.get("avg_execution_time") > 0);
    }

    @Test
    void testGetHighRiskOperations_Success() {
        // 准备测试数据
        List<OperationLog> highRiskLogs = Arrays.asList(
                createMockOperationLog(),
                createMockOperationLog()
        );
        highRiskLogs.forEach(log -> log.setRiskLevel("HIGH"));

        doReturn(highRiskLogs).when(operationLogService).list(any(QueryWrapper.class));

        // 执行测试
        Result<Map<String, Object>> result = operationLogService.getHighRiskOperations(7);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertEquals(2, data.get("total"));
        assertEquals(7, data.get("days"));
        assertNotNull(data.get("high_risk_operations"));
    }

    @Test
    void testCleanExpiredLogs_Success() {
        // 准备测试数据
        doReturn(100L).when(operationLogService).count(any(QueryWrapper.class));
        doReturn(true).when(operationLogService).remove(any(QueryWrapper.class));

        // 执行测试
        Result<Map<String, Object>> result = operationLogService.cleanExpiredLogs(90);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertEquals(100L, data.get("cleaned_count"));
        assertEquals(90, data.get("retention_days"));
        assertNotNull(data.get("cutoff_time"));
    }

    @Test
    void testCleanExpiredLogs_Failed() {
        // 准备测试数据
        doReturn(50L).when(operationLogService).count(any(QueryWrapper.class));
        doReturn(false).when(operationLogService).remove(any(QueryWrapper.class));

        // 执行测试
        Result<Map<String, Object>> result = operationLogService.cleanExpiredLogs(90);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertEquals(0L, data.get("cleaned_count"));
    }

    @Test
    void testGetOperationLogDetail_Found() {
        // 准备测试数据
        doReturn(mockLog).when(operationLogService).getById(TEST_LOG_ID);

        // 执行测试
        Result<OperationLog> result = operationLogService.getOperationLogDetail(TEST_LOG_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(TEST_LOG_ID, result.getData().getId());
    }

    @Test
    void testGetOperationLogDetail_NotFound() {
        // 准备测试数据
        doReturn(null).when(operationLogService).getById(999L);

        // 执行测试
        Result<OperationLog> result = operationLogService.getOperationLogDetail(999L);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.RESULT_DATA_NONE.getCode(), result.getCode());
    }

    @Test
    void testBatchDeleteOperationLogs_Success() {
        // 准备测试数据
        List<Long> logIds = Arrays.asList(1L, 2L, 3L);
        doReturn(true).when(operationLogService).removeByIds(logIds);

        // 执行测试
        Result<Void> result = operationLogService.batchDeleteOperationLogs(logIds);

        // 验证结果
        assertTrue(result.isSuccess());
        verify(operationLogService, times(1)).removeByIds(logIds);
    }

    @Test
    void testExportOperationLogs_NotImplemented() {
        // 执行测试
        Result<String> result = operationLogService.exportOperationLogs(
                TEST_USER_ID, TEST_MODULE,
                LocalDateTime.now().minusDays(30), LocalDateTime.now()
        );

        // 验证结果
        assertTrue(result.isSuccess());
        assertEquals("导出功能待实现", result.getData());
    }
}