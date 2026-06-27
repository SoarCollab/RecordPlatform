package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.audit.AuditLogVO;
import cn.flying.service.SysAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.flying.common.util.DistributedRateLimiter.RateLimitResult;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

@WebMvcTest(SysAuditController.class)
// @Import(cn.flying.config.WebConfiguration.class) // Import config to pick up any web settings if needed
@ActiveProfiles("test")
public class SysAuditControllerTest {

    static {
        // 在 Spring 初始化日志系统前设置 Nacos 日志目录，避免测试环境写入 ${user.home}/logs 导致权限问题。
        java.io.File logDir = new java.io.File("target/test-logs");
        // Nacos 默认会在 logPath 下创建 nacos 子目录写入 config/naming/remote 日志
        new java.io.File(logDir, "nacos").mkdirs();
        System.setProperty("JM.LOG.PATH", logDir.getAbsolutePath());
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SysAuditService auditService;

    @MockitoBean
    private cn.flying.common.util.DistributedRateLimiter distributedRateLimiter;

    @MockitoBean
    private cn.flying.common.util.JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                IdUtils.class,
                "secureIdCodec",
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234")
        );
    }

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

        String externalId = IdUtils.toExternalId(1L);
        when(auditService.getLogDetail(1L)).thenReturn(log);
        when(distributedRateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.ALLOWED);

        mockMvc.perform(get("/api/v1/system/audit/logs/" + externalId)
                .header("X-Tenant-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.operationTime").value("2023-10-01 12:00:00"));
    }

    /**
     * 验证系统审计控制器的所有对外接口都显式记录操作日志。
     */
    @Test
    @DisplayName("should annotate every audit endpoint with OperationLog")
    void shouldAnnotateEveryAuditEndpointWithOperationLog() {
        Set<Class<?>> mappingAnnotations = Set.of(
                RequestMapping.class,
                GetMapping.class,
                PostMapping.class,
                PutMapping.class,
                DeleteMapping.class,
                PatchMapping.class
        );

        List<String> missingOperationLogs = Arrays.stream(SysAuditController.class.getDeclaredMethods())
                .filter(method -> hasAnyMappingAnnotation(method, mappingAnnotations))
                .filter(method -> !method.isAnnotationPresent(OperationLog.class))
                .map(Method::getName)
                .toList();

        assertTrue(missingOperationLogs.isEmpty(), "Missing @OperationLog: " + missingOperationLogs);
    }

    /**
     * 判断方法是否声明了 Spring MVC 路由注解。
     *
     * @param method             待检查方法
     * @param mappingAnnotations Spring MVC 路由注解集合
     * @return 存在任一路由注解时返回 true
     */
    private boolean hasAnyMappingAnnotation(Method method, Set<Class<?>> mappingAnnotations) {
        return Arrays.stream(method.getAnnotations())
                .anyMatch(annotation -> mappingAnnotations.contains(annotation.annotationType()));
    }
}
