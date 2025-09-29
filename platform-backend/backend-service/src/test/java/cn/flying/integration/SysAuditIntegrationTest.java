package cn.flying.integration;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.audit.*;
import cn.flying.service.SysAuditService;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 系统审计服务集成测试
 * 测试审计系统的完整流程，包括：
 * - 操作日志查询
 * - 高频操作监控
 * - 敏感操作审计
 * - 错误操作统计
 * - 审计配置管理
 * - 日志备份
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class SysAuditIntegrationTest {

    @Autowired
    private SysAuditService auditService;

    @Resource
    private SysOperationLogMapper operationLogMapper;

    @BeforeEach
    void setUp() {
        // 准备测试数据
        // 配置selectPage返回Page对象
        List<SysOperationLog> mockLogs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mockLogs.add(createMockLog("user" + i, "文件操作", "上传"));
        }
        Page<SysOperationLog> page = new Page<>(1, 10);
        page.setRecords(mockLogs);
        page.setTotal(mockLogs.size());
        when(operationLogMapper.selectPage(any(), any())).thenReturn(page);
    }

    private SysOperationLog createMockLog(String username, String module, String description) {
        SysOperationLog log = new SysOperationLog();
        log.setId(new Random().nextLong(1000000));
        log.setUserId("userId_" + username);
        log.setUsername(username);
        log.setModule(module);
        log.setOperationType("操作");
        log.setDescription(description);
        log.setMethod("POST");
        log.setRequestUrl("/api/test");
        log.setRequestIp("127.0.0.1");
        log.setStatus(0); // 成功
        log.setOperationTime(LocalDateTime.now());
        log.setExecutionTime(100L);
        return log;
    }

    @Test
    void testQueryOperationLogs() {
        // Given: 准备查询条件
        AuditLogQueryVO queryVO = new AuditLogQueryVO();
        queryVO.setPageNum(1);
        queryVO.setPageSize(10);
        queryVO.setModule("文件操作");
        queryVO.setUsername("testuser");

        // When: 查询操作日志 (selectPage已在setUp中Mock)
        IPage<SysOperationLog> result = auditService.queryOperationLogs(queryVO);

        // Then: 验证结果
        assertNotNull(result);
        assertThat(result.getRecords()).isNotEmpty();
        assertThat(result.getTotal()).isGreaterThan(0);
    }

    @Test
    void testGetHighFrequencyOperations() {
        // Given: Mock高频操作数据
        when(operationLogMapper.selectList(any())).thenReturn(
                Arrays.asList(
                        createMockLog("user1", "文件操作", "上传"),
                        createMockLog("user1", "文件操作", "上传"),
                        createMockLog("user1", "文件操作", "上传")
                )
        );

        // When: 获取高频操作记录
        List<HighFrequencyOperationVO> result = auditService.getHighFrequencyOperations();

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testGetSensitiveOperations() {
        // Given: 准备查询条件
        AuditLogQueryVO queryVO = new AuditLogQueryVO();
        queryVO.setPageNum(1);
        queryVO.setPageSize(10);

        // Mock敏感操作数据
        List<SysOperationLog> mockLogs = Arrays.asList(
                createMockLog("admin", "系统管理", "删除"),
                createMockLog("admin", "用户管理", "删除用户")
        );
        when(operationLogMapper.selectList(any())).thenReturn(mockLogs);

        // When: 获取敏感操作记录
        IPage<SysOperationLog> result = auditService.getSensitiveOperations(queryVO);

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testGetErrorOperationStats() {
        // Given: Mock错误操作数据
        when(operationLogMapper.selectList(any())).thenReturn(
                Arrays.asList(
                        createFailedLog("user1", "文件操作", "上传失败"),
                        createFailedLog("user2", "文件操作", "删除失败"),
                        createFailedLog("user1", "图片上传", "上传失败")
                )
        );

        // When: 获取错误操作统计
        List<ErrorOperationStatsVO> result = auditService.getErrorOperationStats();

        // Then: 验证结果
        assertNotNull(result);
    }

    private SysOperationLog createFailedLog(String username, String module, String description) {
        SysOperationLog log = createMockLog(username, module, description);
        log.setStatus(1); // 失败
        log.setErrorMsg("操作失败");
        return log;
    }

    @Test
    void testGetUserTimeDistribution() {
        // Given: Mock用户操作时间分布数据
        List<SysOperationLog> mockLogs = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            SysOperationLog log = createMockLog("user1", "文件操作", "上传");
            log.setOperationTime(LocalDateTime.now().withHour(hour));
            mockLogs.add(log);
        }
        when(operationLogMapper.selectList(any())).thenReturn(mockLogs);

        // When: 获取用户操作时间分布
        List<UserTimeDistributionVO> result = auditService.getUserTimeDistribution();

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testGetAuditConfigs() {
        // When: 获取审计配置列表
        List<AuditConfigVO> result = auditService.getAuditConfigs();

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testUpdateAuditConfig() {
        // Given: 准备配置数据
        AuditConfigVO configVO = new AuditConfigVO();
        configVO.setConfigKey("log.retention.days");
        configVO.setConfigValue("180");
        configVO.setDescription("日志保留天数");

        // When: 更新配置
        auditService.updateAuditConfig(configVO);
    }

    @Test
    void testGetLogDetail() {
        // Given: 准备日志ID
        Long logId = 1L;
        SysOperationLog mockLog = createMockLog("testuser", "文件操作", "上传");
        mockLog.setId(logId);

        when(operationLogMapper.selectById(logId)).thenReturn(mockLog);

        // When: 获取日志详情
        SysOperationLog result = auditService.getLogDetail(logId);

        // Then: 验证结果
        assertNotNull(result);
        assertEquals(logId, result.getId());
    }

    @Test
    void testExportOperationLogs() {
        // Given: 准备导出查询条件
        AuditLogQueryVO queryVO = new AuditLogQueryVO();
        queryVO.setModule("文件操作");
        queryVO.setStartTime("2024-01-01 00:00:00");
        queryVO.setEndTime("2024-12-31 23:59:59");

        // Mock数据
        List<SysOperationLog> mockLogs = Arrays.asList(
                createMockLog("user1", "文件操作", "上传"),
                createMockLog("user2", "文件操作", "下载"),
                createMockLog("user3", "文件操作", "删除")
        );
        when(operationLogMapper.selectList(any())).thenReturn(mockLogs);

        // When: 导出操作日志
        List<SysOperationLog> result = auditService.exportOperationLogs(queryVO);

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testGetAuditOverview() {
        // Given: Mock统计数据
        when(operationLogMapper.selectList(any())).thenReturn(
                Arrays.asList(
                        createMockLog("user1", "文件操作", "上传"),
                        createFailedLog("user2", "文件操作", "上传失败"),
                        createMockLog("user3", "图片上传", "上传")
                )
        );

        // When: 获取审计概览
        Map<String, Object> result = auditService.getAuditOverview();

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testCheckAnomalies() {
        // Given: Mock异常操作数据
        List<SysOperationLog> anomalyLogs = Arrays.asList(
                createFailedLog("user1", "文件操作", "上传失败"),
                createFailedLog("user1", "文件操作", "上传失败"),
                createFailedLog("user1", "文件操作", "上传失败")
        );
        when(operationLogMapper.selectList(any())).thenReturn(anomalyLogs);

        // When: 检查异常操作
        Map<String, Object> result = auditService.checkAnomalies();

        // Then: 验证结果
        assertNotNull(result);
    }

    // ============= 辅助方法 =============

    @Test
    void testBackupLogs() {
        // Given: 准备备份参数
        Integer days = 180;
        Boolean deleteAfterBackup = false;

        // Mock旧日志数据
        List<SysOperationLog> oldLogs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            SysOperationLog log = createMockLog("user" + i, "文件操作", "上传");
            log.setOperationTime(LocalDateTime.now().minusDays(200));
            oldLogs.add(log);
        }
        when(operationLogMapper.selectList(any())).thenReturn(oldLogs);

        // When: 执行日志备份
        String result = auditService.backupLogs(days, deleteAfterBackup);

        // Then: 验证结果
        assertNotNull(result);
    }

    @Test
    void testCompleteAuditWorkflow() {
        // 测试完整的审计工作流程

        // Step 1: 查询操作日志 (selectPage已在setUp中Mock)
        AuditLogQueryVO queryVO = new AuditLogQueryVO();
        queryVO.setPageNum(1);
        queryVO.setPageSize(10);

        IPage<SysOperationLog> logs = auditService.queryOperationLogs(queryVO);
        assertNotNull(logs);

        // Step 2: 检查高频操作
        List<HighFrequencyOperationVO> highFreq = auditService.getHighFrequencyOperations();
        assertNotNull(highFreq);

        // Step 3: 检查错误操作
        List<ErrorOperationStatsVO> errors = auditService.getErrorOperationStats();
        assertNotNull(errors);

        // Step 4: 检查异常操作
        Map<String, Object> anomalies = auditService.checkAnomalies();
        assertNotNull(anomalies);

        // Step 5: 获取审计概览
        Map<String, Object> overview = auditService.getAuditOverview();
        assertNotNull(overview);
    }
}
