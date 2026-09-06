package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.dao.dto.SysOperationLog;
import cn.flying.dao.vo.audit.AuditConfigVO;
import cn.flying.security.CustomMethodSecurityExpressionHandler;
import cn.flying.service.PermissionService;
import cn.flying.service.SysAuditService;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.flying.common.util.DistributedRateLimiter.RateLimitResult;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

@WebMvcTest(SysAuditController.class)
@Import({cn.flying.config.PrometheusScrapeSecurity.class,
        SysAuditControllerTest.MethodSecurityTestConfiguration.class})
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

    @Autowired
    private SysAuditController controller;

    @MockitoBean
    private SysAuditService auditService;

    @MockitoBean
    private cn.flying.common.util.DistributedRateLimiter distributedRateLimiter;

    @MockitoBean
    private cn.flying.common.util.JwtUtils jwtUtils;

    @MockitoBean
    private cn.flying.service.auth.AuthorizationStateService authorizationStateService;

    private Object previousCodec;

    /** Installs an isolated codec and permits request throttling so assertions reach method security. */
    @BeforeEach
    void setUp() {
        previousCodec = ReflectionTestUtils.getField(IdUtils.class, "secureIdCodec");
        ReflectionTestUtils.setField(
                IdUtils.class,
                "secureIdCodec",
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234")
        );
        when(distributedRateLimiter.tryAcquireWithBlock(anyString(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.ALLOWED);
    }

    /** Restores shared ID state and clears the identities consumed by custom method expressions. */
    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", previousCodec);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    /** Existing audit reads remain usable with the real method-security proxy enabled. */
    @Test
    @DisplayName("should serialize operationTime in SysOperationLog correctly")
    @WithMockUser(username = "admin", roles = {"admin"})
    void shouldSerializeOperationTimeCorrectly() throws Exception {
        MDC.put(Const.ATTR_USER_ROLE, "admin");
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
        mockMvc.perform(get("/api/v1/system/audit/logs/" + externalId)
                .header("X-Tenant-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.operationTime").value("2023-10-01 12:00:00"));
    }

    /** A valid authenticated request with CSRF protection satisfied still cannot reach the retired writer. */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "monitor", "user", "platform_admin"})
    void shouldDenyLegacyAuditConfigWritesForEveryRole(String role) throws Exception {
        User principal = (User) User.withUsername("audit-operator").password("unused")
                .authorities("ROLE_" + role, "system:admin", PlatformPermissions.CONFIGURATION_WRITE)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        MDC.put(Const.ATTR_USER_ROLE, role);
        AuditConfigVO config = new AuditConfigVO();
        config.setConfigKey("HIGH_FREQ_THRESHOLD");
        config.setConfigValue("100");

        assertThatThrownBy(() -> controller.updateAuditConfig(config))
                .isInstanceOf(AccessDeniedException.class);

        mockMvc.perform(put("/api/v1/system/audit/configs")
                        .with(user(principal)).with(csrf())
                        .header("X-Tenant-ID", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configKey\":\"HIGH_FREQ_THRESHOLD\",\"configValue\":\"100\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultEnum.PERMISSION_UNAUTHORIZED.getCode()))
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(AccessDeniedException.class));

        verifyNoInteractions(auditService);
    }

    /** Both tenant audit reader roles retain access to the safe configuration projection. */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "monitor"})
    void shouldKeepSafeConfigReadsAvailableToTenantAuditRoles(String role) throws Exception {
        MDC.put(Const.ATTR_USER_ROLE, role);
        AuditConfigVO config = new AuditConfigVO();
        config.setConfigKey("HIGH_FREQ_THRESHOLD");
        config.setConfigValue("100");
        when(auditService.getAuditConfigs()).thenReturn(List.of(config));

        mockMvc.perform(get("/api/v1/system/audit/configs")
                        .with(user("audit-reader").roles(role))
                        .header("X-Tenant-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].configKey").value("HIGH_FREQ_THRESHOLD"))
                .andExpect(jsonPath("$.data[0].configValue").value("100"));
    }

    /** The retired endpoint advertises deprecation and keeps audit metadata without capturing request bodies. */
    @Test
    void shouldDeclareLegacyConfigRetirementAndRequestBodyOmission() throws NoSuchMethodException {
        Method method = SysAuditController.class.getDeclaredMethod("updateAuditConfig", AuditConfigVO.class);

        assertThat(method.getAnnotation(PreAuthorize.class)).isNotNull();
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("denyAll()");
        assertThat(method.getAnnotation(Operation.class)).isNotNull();
        assertThat(method.getAnnotation(Operation.class).deprecated()).isTrue();
        assertThat(method.getAnnotation(OperationLog.class)).isNotNull();
        assertThat(method.getAnnotation(OperationLog.class).saveRequestData()).isFalse();
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

    /** Enables the production expression vocabulary and real method-security advisors in the MVC slice. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {

        /** Supplies the same custom expression handler used by ordinary tenant audit endpoints. */
        @Bean
        static MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
            return new CustomMethodSecurityExpressionHandler(mock(PermissionService.class));
        }
    }
}
