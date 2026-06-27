package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.dao.vo.file.QuotaRolloutAuditUpsertVO;
import cn.flying.dao.vo.file.QuotaRolloutAuditVO;
import cn.flying.service.QuotaRolloutAuditService;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuotaAdminController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class QuotaAdminControllerTest {

    @Mock
    private QuotaRolloutAuditService quotaRolloutAuditService;

    private QuotaAdminController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        controller = new QuotaAdminController(quotaRolloutAuditService);
    }

    /**
     * 验证审计写入接口会委托到服务层。
     */
    @Test
    void shouldDelegateUpsertAuditToService() {
        QuotaRolloutAuditUpsertVO request = new QuotaRolloutAuditUpsertVO(
                "batch-a",
                1L,
                LocalDateTime.of(2026, 2, 1, 0, 0),
                LocalDateTime.of(2026, 2, 7, 0, 0),
                1000L,
                120L,
                6L,
                "KEEP_ENFORCE",
                "持续观察",
                "https://example.com/evidence/1"
        );
        QuotaRolloutAuditVO response = new QuotaRolloutAuditVO(
                9L,
                "batch-a",
                1L,
                request.observationStartTime(),
                request.observationEndTime(),
                1000L,
                120L,
                6L,
                0.05D,
                "KEEP_ENFORCE",
                "持续观察",
                "https://example.com/evidence/1",
                "uid:88",
                LocalDateTime.of(2026, 2, 7, 1, 0),
                LocalDateTime.of(2026, 2, 7, 1, 30)
        );

        when(quotaRolloutAuditService.upsertAudit(88L, 1L, request)).thenReturn(response);

        Result<QuotaRolloutAuditVO> result = controller.upsertQuotaRolloutAudit(88L, 1L, request);

        assertEquals(9L, result.getData().id());
        verify(quotaRolloutAuditService).upsertAudit(88L, 1L, request);
    }

    /**
     * 验证审计查询接口会按当前租户和批次查询。
     */
    @Test
    void shouldDelegateGetAuditToService() {
        QuotaRolloutAuditVO response = new QuotaRolloutAuditVO(
                9L,
                "batch-a",
                1L,
                LocalDateTime.of(2026, 2, 1, 0, 0),
                LocalDateTime.of(2026, 2, 7, 0, 0),
                1000L,
                120L,
                6L,
                0.05D,
                "KEEP_ENFORCE",
                "持续观察",
                "https://example.com/evidence/1",
                "uid:88",
                LocalDateTime.of(2026, 2, 7, 1, 0),
                LocalDateTime.of(2026, 2, 7, 1, 30)
        );

        when(quotaRolloutAuditService.getLatestAudit("batch-a", 1L)).thenReturn(response);

        Result<QuotaRolloutAuditVO> result = controller.getQuotaRolloutAudit(1L, "batch-a");

        assertEquals("batch-a", result.getData().batchId());
        verify(quotaRolloutAuditService).getLatestAudit("batch-a", 1L);
    }

    /**
     * 验证控制器声明了管理员权限校验注解。
     */
    @Test
    void shouldDeclareAdminPermissionGuard() {
        PreAuthorize preAuthorize = QuotaAdminController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(preAuthorize);
        assertEquals("isAdmin()", preAuthorize.value());
    }

    /**
     * 验证 POST 接口声明了用户上下文与请求体校验注解。
     *
     * @throws Exception 方法反射失败时抛出
     */
    @Test
    void shouldDeclareUpsertParameterAnnotations() throws Exception {
        Method method = QuotaAdminController.class.getDeclaredMethod(
                "upsertQuotaRolloutAudit",
                Long.class,
                Long.class,
                QuotaRolloutAuditUpsertVO.class
        );
        Parameter[] parameters = method.getParameters();

        assertEquals(3, parameters.length);
        RequestAttribute requestAttribute = parameters[0].getAnnotation(RequestAttribute.class);
        assertNotNull(requestAttribute);
        assertEquals(Const.ATTR_USER_ID, requestAttribute.value());
        RequestAttribute tenantAttribute = parameters[1].getAnnotation(RequestAttribute.class);
        assertNotNull(tenantAttribute);
        assertEquals(Const.ATTR_TENANT_ID, tenantAttribute.value());
        assertTrue(parameters[2].isAnnotationPresent(RequestBody.class));
        assertTrue(parameters[2].isAnnotationPresent(Valid.class));
    }

    /**
     * 验证 GET 接口声明了租户上下文与 batchId 查询参数注解。
     *
     * @throws Exception 方法反射失败时抛出
     */
    @Test
    void shouldDeclareQueryParameterAnnotations() throws Exception {
        Method method = QuotaAdminController.class.getDeclaredMethod(
                "getQuotaRolloutAudit",
                Long.class,
                String.class
        );
        Parameter[] parameters = method.getParameters();

        assertEquals(2, parameters.length);
        RequestAttribute tenantAttribute = parameters[0].getAnnotation(RequestAttribute.class);
        assertNotNull(tenantAttribute);
        assertEquals(Const.ATTR_TENANT_ID, tenantAttribute.value());
        assertTrue(parameters[1].isAnnotationPresent(RequestParam.class));
    }
}
