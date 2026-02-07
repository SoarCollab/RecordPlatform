package cn.flying.controller;

import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
@DisplayName("SystemController Integration Tests")
@TestPropertySource(properties = "test.context=SystemControllerIntegrationTest")
class SystemControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/system";

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
    }

    @Nested
    @DisplayName("GET /stats")
    class GetSystemStatsTests {

        @Test
        @DisplayName("should require admin role")
        void shouldRequireAdminRole() throws Exception {
            performGet(BASE_URL + "/stats")
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("should return stats for admin")
        void shouldReturnStatsForAdmin() throws Exception {
            setTestAdmin(100L, 1L);

            performGet(BASE_URL + "/stats")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(BASE_URL + "/stats")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /chain-status")
    class GetChainStatusTests {

        @Test
        @DisplayName("should require admin role")
        void shouldRequireAdminRole() throws Exception {
            performGet(BASE_URL + "/chain-status")
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("should return chain status for admin")
        void shouldReturnChainStatusForAdmin() throws Exception {
            setTestAdmin(100L, 1L);

            performGet(BASE_URL + "/chain-status")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @Nested
    @DisplayName("GET /health")
    class GetSystemHealthTests {

        @Test
        @DisplayName("should require admin role")
        void shouldRequireAdminRole() throws Exception {
            performGet(BASE_URL + "/health")
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("should return health for admin")
        void shouldReturnHealthForAdmin() throws Exception {
            setTestAdmin(100L, 1L);

            performGet(BASE_URL + "/health")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.status").exists());
        }
    }

    @Nested
    @DisplayName("GET /storage-capacity")
    class GetStorageCapacityTests {

        @Test
        @DisplayName("should require admin role")
        void shouldRequireAdminRole() throws Exception {
            performGet(BASE_URL + "/storage-capacity")
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("should return storage capacity for admin")
        void shouldReturnStorageCapacityForAdmin() throws Exception {
            setTestAdmin(100L, 1L);

            performGet(BASE_URL + "/storage-capacity")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists())
                    .andExpect(jsonPath("$.data.usedCapacityBytes").exists())
                    .andExpect(jsonPath("$.data.degraded").exists());
        }
    }

    @Nested
    @DisplayName("GET /monitor")
    class GetMonitorMetricsTests {

        @Test
        @DisplayName("should require admin role")
        void shouldRequireAdminRole() throws Exception {
            performGet(BASE_URL + "/monitor")
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("should return aggregated metrics for admin")
        void shouldReturnAggregatedMetricsForAdmin() throws Exception {
            setTestAdmin(100L, 1L);

            performGet(BASE_URL + "/monitor")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.systemStats").exists())
                    .andExpect(jsonPath("$.data.chainStatus").exists())
                    .andExpect(jsonPath("$.data.health").exists());
        }
    }
}
