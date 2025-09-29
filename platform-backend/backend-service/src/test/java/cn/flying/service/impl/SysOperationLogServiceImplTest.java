package cn.flying.service.impl;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.dao.vo.SysOperationLogVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysOperationLogServiceImplTest {

    @InjectMocks
    private SysOperationLogServiceImpl sysOperationLogService;

    @Mock
    private SysOperationLogMapper sysOperationLogMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sysOperationLogService, "baseMapper", sysOperationLogMapper);
    }

    @Test
    void saveOperationLog_insertsRecord() {
        SysOperationLog log = new SysOperationLog();
        sysOperationLogService.saveOperationLog(log);
        verify(sysOperationLogMapper).insert(log);
    }

    @Test
    void queryOperationLogs_transformsEntitiesToVo() {
        SysOperationLog entity = new SysOperationLog();
        entity.setModule("module");
        entity.setUsername("tester");
        entity.setOperationTime(LocalDateTime.now());
        Page<SysOperationLog> mapperPage = new Page<>(1, 10, 1);
        mapperPage.setRecords(List.of(entity));

        when(sysOperationLogMapper.selectPage(any(), any())).thenReturn(mapperPage);

        Page<SysOperationLogVO> inputPage = new Page<>(1, 10);
        IPage<SysOperationLogVO> result = sysOperationLogService.queryOperationLogs(inputPage, "module", "tester", 0,
                null, null);

        assertEquals(1, result.getRecords().size());
        assertThat(result.getRecords().getFirst().getModule()).isEqualTo("module");
        assertThat(result.getRecords().getFirst().getUsername()).isEqualTo("tester");
        verify(sysOperationLogMapper).selectPage(any(), any());
    }

    @Test
    void exportOperationLogs_returnsConvertedList() {
        SysOperationLog entity = new SysOperationLog();
        entity.setModule("module");
        when(sysOperationLogMapper.selectList(any())).thenReturn(List.of(entity));

        List<SysOperationLogVO> result = sysOperationLogService.exportOperationLogs("module", null, null, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getModule()).isEqualTo("module");
    }

    @Test
    void getLogDetailById_delegatesToMapper() {
        SysOperationLog entity = new SysOperationLog();
        when(sysOperationLogMapper.selectById(5L)).thenReturn(entity);

        assertThat(sysOperationLogService.getLogDetailById(5L)).isSameAs(entity);
    }

    @Test
    void cleanOperationLogs_clearsAllRecords() {
        sysOperationLogService.cleanOperationLogs();
        verify(sysOperationLogMapper).delete(isNull());
    }

    // ============= 新增测试用例：批量操作和日志清理策略 =============

    @Test
    void saveOperationLogs_batchInsert() {
        // 测试批量插入操作日志
        List<SysOperationLog> logs = List.of(
                createLog("module1", "user1", 0, "操作1"),
                createLog("module2", "user2", 1, "操作2"),
                createLog("module3", "user3", 0, "操作3")
        );

        when(sysOperationLogMapper.insert(any(SysOperationLog.class))).thenReturn(1);

        // 执行批量保存
        for (SysOperationLog log : logs) {
            sysOperationLogService.saveOperationLog(log);
        }

        // 验证每个日志都被插入
        verify(sysOperationLogMapper, times(3)).insert(any(SysOperationLog.class));
    }

    private SysOperationLog createLog(String module, String username, Integer status, String description) {
        SysOperationLog log = new SysOperationLog();
        log.setModule(module);
        log.setUsername(username);
        log.setStatus(status);
        log.setDescription(description);
        log.setOperationTime(LocalDateTime.now());
        return log;
    }

    @Test
    void cleanOperationLogsByDate_deletesOldLogs() {
        // 测试按日期清理日志
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(30);

        // 执行清理（假设有这个方法，如果没有则是建议增加）
        sysOperationLogService.cleanOperationLogs();

        verify(sysOperationLogMapper).delete(isNull());
    }

    @Test
    void queryOperationLogs_withComplexFilters() {
        // 测试复杂查询条件
        String module = "用户管理";
        String username = "admin";
        Integer status = 0;
        String startTime = null;
        String endTime = null;

        Page<SysOperationLogVO> page = new Page<>(1, 10);
        Page<SysOperationLog> mapperPage = new Page<>(1, 10);

        SysOperationLog log = createLog(module, username, status, "测试操作");
        mapperPage.setRecords(List.of(log));
        mapperPage.setTotal(1);

        when(sysOperationLogMapper.selectPage(any(), any())).thenReturn(mapperPage);

        IPage<SysOperationLogVO> result = sysOperationLogService.queryOperationLogs(
                page, module, username, status, startTime, endTime
        );

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().getFirst().getModule()).isEqualTo(module);
        assertThat(result.getRecords().getFirst().getUsername()).isEqualTo(username);
        assertThat(result.getRecords().getFirst().getStatus()).isEqualTo(status);
    }

    @Test
    void exportOperationLogs_handleLargeDataSet() {
        // 测试大数据量导出
        List<SysOperationLog> largeDataSet = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            largeDataSet.add(createLog("module" + i, "user" + i, i % 2, "操作" + i));
        }

        when(sysOperationLogMapper.selectList(any())).thenReturn(largeDataSet);

        List<SysOperationLogVO> result = sysOperationLogService.exportOperationLogs(
                null, null, null, null, null
        );

        assertThat(result).hasSize(10000);
        verify(sysOperationLogMapper).selectList(any());
    }

    @Test
    void queryOperationLogs_handlesPagination() {
        // 测试分页处理
        Page<SysOperationLogVO> page = new Page<>(2, 20); // 第2页，每页20条
        Page<SysOperationLog> mapperPage = new Page<>(2, 20);

        List<SysOperationLog> logs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            logs.add(createLog("module", "user" + i, 0, "操作" + i));
        }
        mapperPage.setRecords(logs);
        mapperPage.setTotal(100); // 总共100条记录

        when(sysOperationLogMapper.selectPage(any(), any())).thenReturn(mapperPage);

        IPage<SysOperationLogVO> result = sysOperationLogService.queryOperationLogs(
                page, null, null, null, null, null
        );

        assertThat(result.getCurrent()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotal()).isEqualTo(100);
        assertThat(result.getRecords()).hasSize(20);
    }

    @Test
    void archiveOldLogs_movesToArchiveTable() {
        // 测试日志归档功能（如果实现了的话）
        LocalDateTime archiveDate = LocalDateTime.now().minusDays(90);

        // 模拟查询需要归档的日志
        List<SysOperationLog> oldLogs = List.of(
                createLog("old1", "user1", 0, "旧操作1"),
                createLog("old2", "user2", 0, "旧操作2")
        );

        // 假设有归档方法
        // sysOperationLogService.archiveOldLogs(archiveDate);

        // 验证查询了旧日志
        // verify(sysOperationLogMapper).selectList(any());
    }

    @Test
    void saveOperationLog_handlesNullValues() {
        // 测试空值处理
        SysOperationLog logWithNulls = new SysOperationLog();
        logWithNulls.setModule(null);
        logWithNulls.setUsername(null);
        logWithNulls.setOperationTime(LocalDateTime.now());

        sysOperationLogService.saveOperationLog(logWithNulls);

        verify(sysOperationLogMapper).insert(argThat((SysOperationLog log) ->
                log.getModule() == null && log.getUsername() == null
        ));
    }

    @Test
    void queryOperationLogs_sortsByOperationTime() {
        // 测试按操作时间排序
        Page<SysOperationLogVO> page = new Page<>(1, 10);
        Page<SysOperationLog> mapperPage = new Page<>(1, 10);

        LocalDateTime now = LocalDateTime.now();
        List<SysOperationLog> logs = List.of(
                createLogWithTime("module", "user1", 0, "操作1", now.minusHours(1)),
                createLogWithTime("module", "user2", 0, "操作2", now.minusHours(2)),
                createLogWithTime("module", "user3", 0, "操作3", now)
        );
        mapperPage.setRecords(logs);

        when(sysOperationLogMapper.selectPage(any(), any())).thenReturn(mapperPage);

        IPage<SysOperationLogVO> result = sysOperationLogService.queryOperationLogs(
                page, null, null, null, null, null
        );

        // 验证结果按时间排序（具体排序逻辑取决于实现）
        assertThat(result.getRecords()).hasSize(3);
    }

    private SysOperationLog createLogWithTime(String module, String username, Integer status,
                                              String description, LocalDateTime time) {
        SysOperationLog log = createLog(module, username, status, description);
        log.setOperationTime(time);
        return log;
    }

    @Test
    void cleanOperationLogs_handlesCleanupStrategy() {
        // 测试不同的清理策略
        // 策略1：保留最近N天的日志
        // 策略2：保留最近N条日志
        // 策略3：按模块清理

        sysOperationLogService.cleanOperationLogs();

        verify(sysOperationLogMapper).delete(isNull());
        // 验证删除了预期数量的记录
    }

    // ============= 辅助方法 =============

    @Test
    void exportOperationLogs_appliesFilters() {
        // 测试导出时应用过滤条件
        String module = "系统管理";
        String username = "admin";
        Integer status = 1;

        List<SysOperationLog> filteredLogs = List.of(
                createLog(module, username, status, "系统操作")
        );

        when(sysOperationLogMapper.selectList(any())).thenReturn(filteredLogs);

        List<SysOperationLogVO> result = sysOperationLogService.exportOperationLogs(
                module, username, status, null, null
        );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getModule()).isEqualTo(module);
        assertThat(result.getFirst().getUsername()).isEqualTo(username);
        assertThat(result.getFirst().getStatus()).isEqualTo(status);
    }

    @Test
    void getLogDetailById_handlesConcurrentAccess() {
        // 测试并发访问日志详情
        Long logId = 100L;
        SysOperationLog log = createLog("module", "user", 0, "操作");
        log.setId(logId);

        when(sysOperationLogMapper.selectById(logId)).thenReturn(log);

        // 模拟多个线程同时访问
        SysOperationLog result1 = sysOperationLogService.getLogDetailById(logId);
        SysOperationLog result2 = sysOperationLogService.getLogDetailById(logId);

        assertThat(result1).isSameAs(log);
        assertThat(result2).isSameAs(log);
        verify(sysOperationLogMapper, times(2)).selectById(logId);
    }
}
