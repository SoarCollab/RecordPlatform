package cn.flying.controller;

import cn.flying.dao.entity.IntegrityAlert;
import cn.flying.dao.mapper.IntegrityAlertMapper;
import cn.flying.dao.vo.file.IntegrityCheckStatsVO;
import cn.flying.dao.vo.file.ResolveAlertVO;
import cn.flying.service.integrity.IntegrityCheckService;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for IntegrityAlertController.
 */
@Transactional
@DisplayName("IntegrityAlertController Integration Tests")
@TestPropertySource(properties = "test.context=IntegrityAlertControllerIT")
class IntegrityAlertControllerIT extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/admin/integrity-alerts";

    @MockitoBean
    private IntegrityCheckService integrityCheckService;

    @MockitoBean
    private IntegrityAlertMapper integrityAlertMapper;

    @BeforeEach
    void setUp() {
        setTestAdmin(100L, 1L);
    }

    @Nested
    @DisplayName("Auth Branches")
    class AuthBranches {

        @Test
        @DisplayName("should return 401 when unauthenticated")
        void shouldReturn401WhenUnauthenticated() throws Exception {
            mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get(BASE_URL)
                            .header(HEADER_TENANT_ID, testTenantId)
            ).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 403 when user is not admin")
        void shouldReturn403WhenNotAdmin() throws Exception {
            setTestUser(200L, 1L);

            performGet(BASE_URL)
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/integrity-alerts")
    class ListAlerts {

        @Test
        @DisplayName("should return empty page initially")
        void shouldReturnEmptyPage() throws Exception {
            when(integrityAlertMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));

            performGet(BASE_URL + "?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should support status filter")
        void shouldSupportStatusFilter() throws Exception {
            when(integrityAlertMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));

            performGet(BASE_URL + "?status=0&pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should support alertType filter")
        void shouldSupportAlertTypeFilter() throws Exception {
            when(integrityAlertMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20));

            performGet(BASE_URL + "?alertType=HASH_MISMATCH&pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/integrity-alerts/check")
    class TriggerManualCheck {

        @Test
        @DisplayName("should trigger manual check and return stats")
        void shouldTriggerManualCheck() throws Exception {
            when(integrityCheckService.triggerManualCheck(1L))
                    .thenReturn(new IntegrityCheckStatsVO(100, 2, 1));

            performPost(BASE_URL + "/check", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.totalChecked").value(100))
                    .andExpect(jsonPath("$.data.mismatchesFound").value(2))
                    .andExpect(jsonPath("$.data.errorsEncountered").value(1));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/integrity-alerts/{id}/acknowledge")
    class AcknowledgeAlert {

        @Test
        @DisplayName("should acknowledge alert")
        void shouldAcknowledgeAlert() throws Exception {
            doNothing().when(integrityCheckService).acknowledgeAlert(eq(1L), eq(100L));

            performPut(BASE_URL + "/1/acknowledge", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("Alert acknowledged"));

            verify(integrityCheckService).acknowledgeAlert(1L, 100L);
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/integrity-alerts/{id}/resolve")
    class ResolveAlert {

        @Test
        @DisplayName("should resolve alert with note")
        void shouldResolveAlert() throws Exception {
            doNothing().when(integrityCheckService).resolveAlert(eq(1L), eq(100L), eq("File re-uploaded"));

            ResolveAlertVO body = new ResolveAlertVO("File re-uploaded");

            performPut(BASE_URL + "/1/resolve", body)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("Alert resolved"));

            verify(integrityCheckService).resolveAlert(1L, 100L, "File re-uploaded");
        }
    }
}
