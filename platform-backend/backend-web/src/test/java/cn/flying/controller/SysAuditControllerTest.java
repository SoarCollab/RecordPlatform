package cn.flying.controller;

import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.audit.AuditLogVO;
import cn.flying.service.SysAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.flying.common.util.DistributedRateLimiter.RateLimitResult;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

@WebMvcTest(SysAuditController.class)
// @Import(cn.flying.config.WebConfiguration.class) // Import config to pick up any web settings if needed
public class SysAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SysAuditService auditService;

    @MockBean
    private cn.flying.common.util.DistributedRateLimiter distributedRateLimiter;

    @MockBean
    private cn.flying.common.util.JwtUtils jwtUtils;

    @Test
    @DisplayName("should serialize operationTime in SysOperationLog correctly")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void shouldSerializeOperationTimeCorrectly() throws Exception {
        SysOperationLog log = new SysOperationLog();
        log.setId(1L);
        log.setOperationTime(LocalDateTime.of(2023, 10, 1, 12, 0, 0));
        log.setUserId("1");
        log.setUsername("admin");
        log.setModule("system");
        log.setOperationType("test");
        log.setStatus(0);

        when(auditService.getLogDetail(1L)).thenReturn(log);
        when(distributedRateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.ALLOWED);

        mockMvc.perform(get("/api/v1/system/audit/logs/1")
                .header("X-Tenant-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.operationTime").value("2023-10-01 12:00:00"));
    }
}
