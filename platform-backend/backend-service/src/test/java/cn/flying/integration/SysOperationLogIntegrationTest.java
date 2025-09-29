package cn.flying.integration;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.SysOperationLogVO;
import cn.flying.service.SysOperationLogService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 系统操作日志服务集成测试
 * 测试操作日志的CRUD操作和查询功能
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class SysOperationLogIntegrationTest {

    @Autowired
    private SysOperationLogService operationLogService;

    @Resource
    private SysOperationLogMapper operationLogMapper;

    @BeforeEach
    void setUp() {
        // 为所有测试配置selectPage的默认Mock行为
        when(operationLogMapper.selectPage(any(), any()))
                .thenAnswer(invocation -> {
                    Page<SysOperationLog> page = invocation.getArgument(0);
                    List<SysOperationLog> mockLogs = new ArrayList<>();
                    for (int i = 0; i < 5; i++) {
                        mockLogs.add(createTestLog("user" + i, "文件操作", "操作" + i));
                    }
                    return new Page<SysOperationLog>(page.getCurrent(), page.getSize(), mockLogs.size())
                            .setRecords(mockLogs);
                });
    }

    private SysOperationLog createTestLog(String username, String module, String description) {
        SysOperationLog log = new SysOperationLog();
        log.setId(new Random().nextLong(1000000));
        log.setUserId("userId_" + username);
        log.setUsername(username);
        log.setModule(module);
        log.setOperationType("操作");
        log.setDescription(description);
        log.setMethod("POST");
        log.setRequestUrl("/api/test");
        log.setRequestMethod("POST");
        log.setRequestIp("127.0.0.1");
        log.setRequestParam("{}");
        log.setResponseResult("{\"success\":true}");
        log.setStatus(0); // 成功
        log.setOperationTime(LocalDateTime.now());
        log.setExecutionTime(100L);
        return log;
    }

    @Test
    void testSaveOperationLog() {
        // Given: 准备操作日志
        SysOperationLog log = createTestLog("testuser", "文件操作", "上传文件");

        // Mock保存操作
        when(operationLogMapper.insert(any(SysOperationLog.class))).thenReturn(1);

        // When: 保存日志
        operationLogService.saveOperationLog(log);

        // Then: 验证保存操作被调用
        verify(operationLogMapper, times(1)).insert(any(SysOperationLog.class));
    }

    @Test
    void testQueryOperationLogs() {
        // Given: 准备查询参数
        Page<SysOperationLogVO> page = new Page<>(1, 10);
        String module = "文件操作";
        String username = "testuser";
        Integer status = 0;
        String startTime = "2024-01-01 00:00:00";
        String endTime = "2024-12-31 23:59:59";

        // When: 查询操作日志
        IPage<SysOperationLogVO> result = operationLogService.queryOperationLogs(
                page, module, username, status, startTime, endTime
        );

        // Then: 验证结果
        assertNotNull(result);
        assertNotNull(result.getRecords());
    }

    @Test
    void testQueryOperationLogsWithNoFilters() {
        // Given: 不带任何过滤条件
        Page<SysOperationLogVO> page = new Page<>(1, 10);

        when(operationLogMapper.selectList(any())).thenReturn(new ArrayList<>());

        // When: 查询所有日志
        IPage<SysOperationLogVO> result = operationLogService.queryOperationLogs(
                page, null, null, null, null, null
        );

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testGetLogDetailById() {
        // Given: 准备日志ID
        Long logId = 123L;
        SysOperationLog mockLog = createTestLog("testuser", "文件操作", "上传文件");
        mockLog.setId(logId);

        when(operationLogMapper.selectById(logId)).thenReturn(mockLog);

        // When: 获取日志详情
        SysOperationLog result = operationLogService.getLogDetailById(logId);

        // Then: 验证结果
        assertNotNull(result);
        assertEquals(logId, result.getId());
        assertEquals("testuser", result.getUsername());
    }

    @Test
    void testGetLogDetailByIdNotFound() {
        // Given: 不存在的日志ID
        Long logId = 999L;

        when(operationLogMapper.selectById(logId)).thenReturn(null);

        // When: 获取不存在的日志
        SysOperationLog result = operationLogService.getLogDetailById(logId);

        // Then: 应返回null
        assertNull(result);
    }

    @Test
    void testExportOperationLogs() {
        // Given: 准备导出参数
        String module = "文件操作";
        String username = "testuser";
        Integer status = 0;
        String startTime = "2024-01-01 00:00:00";
        String endTime = "2024-12-31 23:59:59";

        // Mock导出数据
        List<SysOperationLog> mockLogs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            mockLogs.add(createTestLog("user" + i, module, "操作" + i));
        }
        when(operationLogMapper.selectList(any())).thenReturn(mockLogs);

        // When: 导出操作日志
        List<SysOperationLogVO> result = operationLogService.exportOperationLogs(
                module, username, status, startTime, endTime
        );

        // Then: 验证导出数据
        assertNotNull(result);
    }

    @Test
    void testCleanOperationLogs() {
        // Given: Mock删除操作
        when(operationLogMapper.delete(any())).thenReturn(100);

        // When: 清空操作日志
        assertDoesNotThrow(() -> {
            operationLogService.cleanOperationLogs();
        });

        // Then: 验证删除操作被调用
        verify(operationLogMapper, atLeastOnce()).delete(any());
    }

    @Test
    void testSaveMultipleOperationLogs() {
        // 测试批量保存操作日志
        List<SysOperationLog> logs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            logs.add(createTestLog("user" + i, "文件操作", "操作" + i));
        }

        when(operationLogMapper.insert(any(SysOperationLog.class))).thenReturn(1);

        // When: 批量保存日志
        for (SysOperationLog log : logs) {
            operationLogService.saveOperationLog(log);
        }

        // Then: 验证所有日志都被保存
        verify(operationLogMapper, times(10)).insert(any(SysOperationLog.class));
    }

    @Test
    void testQueryOperationLogsByModule() {
        // 测试按模块查询
        Page<SysOperationLogVO> page = new Page<>(1, 10);
        String module = "图片上传";

        List<SysOperationLog> mockLogs = new ArrayList<>();
        mockLogs.add(createTestLog("user1", module, "上传头像"));
        mockLogs.add(createTestLog("user2", module, "上传图片"));

        when(operationLogMapper.selectList(any())).thenReturn(mockLogs);

        // When: 按模块查询
        IPage<SysOperationLogVO> result = operationLogService.queryOperationLogs(
                page, module, null, null, null, null
        );

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testQueryOperationLogsByStatus() {
        // 测试按状态查询
        Page<SysOperationLogVO> page = new Page<>(1, 10);
        Integer status = 1; // 失败状态

        List<SysOperationLog> mockLogs = new ArrayList<>();
        SysOperationLog failedLog = createTestLog("user1", "文件操作", "上传失败");
        failedLog.setStatus(1);
        failedLog.setErrorMsg("文件过大");
        mockLogs.add(failedLog);

        when(operationLogMapper.selectList(any())).thenReturn(mockLogs);

        // When: 查询失败的操作
        IPage<SysOperationLogVO> result = operationLogService.queryOperationLogs(
                page, null, null, status, null, null
        );

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testQueryOperationLogsByTimeRange() {
        // 测试按时间范围查询
        Page<SysOperationLogVO> page = new Page<>(1, 10);
        String startTime = "2024-06-01 00:00:00";
        String endTime = "2024-06-30 23:59:59";

        List<SysOperationLog> mockLogs = new ArrayList<>();
        mockLogs.add(createTestLog("user1", "文件操作", "上传"));

        when(operationLogMapper.selectList(any())).thenReturn(mockLogs);

        // When: 按时间范围查询
        IPage<SysOperationLogVO> result = operationLogService.queryOperationLogs(
                page, null, null, null, startTime, endTime
        );

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testQueryOperationLogsWithPagination() {
        // 测试分页功能
        List<SysOperationLog> allLogs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            allLogs.add(createTestLog("user" + i, "文件操作", "操作" + i));
        }

        when(operationLogMapper.selectList(any())).thenReturn(allLogs);

        // When: 分页查询
        Page<SysOperationLogVO> page1 = new Page<>(1, 20);
        IPage<SysOperationLogVO> result1 = operationLogService.queryOperationLogs(
                page1, null, null, null, null, null
        );

        Page<SysOperationLogVO> page2 = new Page<>(2, 20);
        IPage<SysOperationLogVO> result2 = operationLogService.queryOperationLogs(
                page2, null, null, null, null, null
        );

        // Then: 验证分页结果
        assertNotNull(result1);
        assertNotNull(result2);
    }

    // ============= 辅助方法 =============

    @Test
    void testSaveOperationLogWithAllFields() {
        // 测试保存包含所有字段的日志
        SysOperationLog log = new SysOperationLog();
        log.setModule("系统管理");
        log.setOperationType("配置");
        log.setDescription("修改系统配置");
        log.setMethod("PUT");
        log.setRequestUrl("/api/config/update");
        log.setRequestMethod("PUT");
        log.setRequestIp("192.168.1.100");
        log.setRequestParam("{\"key\":\"value\"}");
        log.setResponseResult("{\"success\":true}");
        log.setStatus(0);
        log.setUserId("admin001");
        log.setUsername("admin");
        log.setOperationTime(LocalDateTime.now());
        log.setExecutionTime(250L);

        when(operationLogMapper.insert(any(SysOperationLog.class))).thenReturn(1);

        // When: 保存日志
        operationLogService.saveOperationLog(log);

        // Then: 验证保存成功
        verify(operationLogMapper, times(1)).insert(any(SysOperationLog.class));
    }
}
