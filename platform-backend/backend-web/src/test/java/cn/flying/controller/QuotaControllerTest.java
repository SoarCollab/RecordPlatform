package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.vo.file.QuotaStatusVO;
import cn.flying.service.QuotaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuotaController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class QuotaControllerTest {

    @Mock
    private QuotaService quotaService;

    private QuotaController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        controller = new QuotaController();
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
    }

    /**
     * 清理租户上下文，避免测试间污染。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 验证优先使用请求中的 tenantId 查询配额状态。
     */
    @Test
    void shouldQueryQuotaStatusWithRequestTenant() {
        QuotaStatusVO vo = new QuotaStatusVO(
                10L,
                20L,
                "SHADOW",
                100L,
                1000L,
                1L,
                10L,
                1000L,
                10000L,
                10L,
                100L
        );
        when(quotaService.getCurrentQuotaStatus(10L, 20L)).thenReturn(vo);

        Result<QuotaStatusVO> result = controller.getQuotaStatus(20L, 10L);

        assertEquals(10L, result.getData().tenantId());
        verify(quotaService).getCurrentQuotaStatus(10L, 20L);
    }

    /**
     * 验证请求未带 tenantId 时会回退到 TenantContext。
     */
    @Test
    void shouldFallbackToTenantContextWhenRequestTenantMissing() {
        TenantContext.setTenantId(99L);
        QuotaStatusVO vo = new QuotaStatusVO(
                99L,
                30L,
                "SHADOW",
                200L,
                2000L,
                2L,
                20L,
                2000L,
                20000L,
                20L,
                200L
        );
        when(quotaService.getCurrentQuotaStatus(99L, 30L)).thenReturn(vo);

        Result<QuotaStatusVO> result = controller.getQuotaStatus(30L, null);

        assertEquals(99L, result.getData().tenantId());
        verify(quotaService).getCurrentQuotaStatus(99L, 30L);
    }
}
