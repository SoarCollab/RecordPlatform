package cn.flying.service.impl;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.mapper.SysOperationLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("SysOperationLogServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
class SysOperationLogServiceImplTest {

    @Mock
    private SysOperationLogMapper baseMapper;

    @InjectMocks
    private SysOperationLogServiceImpl sysOperationLogService;

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                sysOperationLogService, "baseMapper", baseMapper);
    }

    @Nested
    @DisplayName("saveOperationLog Tests")
    class SaveOperationLogTests {

        @Test
        @DisplayName("should insert operation log")
        void shouldInsertOperationLog() {
            SysOperationLog log = createOperationLog();
            when(baseMapper.insert(any(SysOperationLog.class))).thenReturn(1);

            sysOperationLogService.saveOperationLog(log);

            verify(baseMapper).insert(log);
        }

        @Test
        @DisplayName("should handle insert with all fields")
        void shouldHandleInsertWithAllFields() {
            SysOperationLog log = createOperationLog();
            log.setRequestParam("{\"fileId\":123}");
            log.setResponseResult("{\"success\":true}");
            log.setExecutionTime(150L);
            log.setRequestIp("192.168.1.1");
            when(baseMapper.insert(any(SysOperationLog.class))).thenReturn(1);

            sysOperationLogService.saveOperationLog(log);

            verify(baseMapper).insert(argThat((SysOperationLog l) ->
                    l.getRequestParam() != null &&
                    l.getResponseResult() != null &&
                    l.getExecutionTime() == 150L &&
                    l.getRequestIp().equals("192.168.1.1")
            ));
        }
    }

    @Nested
    @DisplayName("getLogDetailById Tests")
    class GetLogDetailByIdTests {

        @Test
        @DisplayName("should return log detail when found")
        void shouldReturnLogDetailWhenFound() {
            SysOperationLog log = createOperationLog();
            log.setId(1L);
            when(baseMapper.selectById(1L)).thenReturn(log);

            SysOperationLog result = sysOperationLogService.getLogDetailById(1L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getUsername()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("should return null when log not found")
        void shouldReturnNullWhenNotFound() {
            when(baseMapper.selectById(999L)).thenReturn(null);

            SysOperationLog result = sysOperationLogService.getLogDetailById(999L);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("cleanOperationLogs Tests")
    class CleanOperationLogsTests {

        @Test
        @DisplayName("should delete all operation logs")
        void shouldDeleteAllOperationLogs() {
            when(baseMapper.delete(null)).thenReturn(100);

            sysOperationLogService.cleanOperationLogs();

            verify(baseMapper).delete(null);
        }
    }

    private SysOperationLog createOperationLog() {
        SysOperationLog log = new SysOperationLog();
        log.setUserId("1");
        log.setUsername("testuser");
        log.setModule("file");
        log.setOperationType("UPLOAD");
        log.setDescription("Upload file");
        log.setStatus(1);
        log.setOperationTime(LocalDateTime.now());
        return log;
    }
}
