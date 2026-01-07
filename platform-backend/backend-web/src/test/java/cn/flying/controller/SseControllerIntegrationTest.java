package cn.flying.controller;

import cn.flying.common.util.JwtUtils;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
@DisplayName("SseController Integration Tests")
class SseControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/sse";

    @SpyBean
    private JwtUtils jwtUtils;

    @MockBean
    private SseEmitterManager sseEmitterManager;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
        setupDefaultMocks();
    }

    private void setupDefaultMocks() {
        when(sseEmitterManager.createConnection(anyLong(), anyLong(), anyString()))
                .thenReturn(new SseEmitter(60000L));
        when(sseEmitterManager.isOnline(anyLong(), anyLong())).thenReturn(true);
        when(sseEmitterManager.getOnlineCount(anyLong())).thenReturn(5);
        when(sseEmitterManager.getUserConnectionCount(anyLong(), anyLong())).thenReturn(2);
    }

    @Nested
    @DisplayName("SSE Connect Operations")
    class ConnectTests {

        @Test
        @DisplayName("GET /connect - Should establish SSE connection with valid token")
        void connect_shouldEstablishConnectionWithValidToken() throws Exception {
            String validSseToken = "valid_sse_token";
            doReturn(new String[]{"100", "1"})
                    .when(jwtUtils).validateAndConsumeSseToken(validSseToken);

            mockMvc.perform(get(BASE_URL + "/connect")
                            .param("token", validSseToken)
                            .param("tenantId", String.valueOf(testTenantId))
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted());

            verify(sseEmitterManager).createConnection(eq(1L), eq(100L), anyString());
        }

        @Test
        @DisplayName("GET /connect - Should establish connection with custom connectionId")
        void connect_shouldEstablishConnectionWithCustomConnectionId() throws Exception {
            String validSseToken = "valid_sse_token";
            String connectionId = "custom_connection_123";
            doReturn(new String[]{"100", "1"})
                    .when(jwtUtils).validateAndConsumeSseToken(validSseToken);

            mockMvc.perform(get(BASE_URL + "/connect")
                            .param("token", validSseToken)
                            .param("tenantId", String.valueOf(testTenantId))
                            .param("connectionId", connectionId)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isOk());

            verify(sseEmitterManager).createConnection(1L, 100L, connectionId);
        }

        @Test
        @DisplayName("GET /connect - Should return 401 for invalid SSE token")
        void connect_shouldReturn401ForInvalidToken() throws Exception {
            String invalidToken = "invalid_token";
            doReturn(null).when(jwtUtils).validateAndConsumeSseToken(invalidToken);

            mockMvc.perform(get(BASE_URL + "/connect")
                            .param("token", invalidToken)
                            .param("tenantId", String.valueOf(testTenantId))
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isUnauthorized());

            verify(sseEmitterManager, never()).createConnection(anyLong(), anyLong(), anyString());
        }

        @Test
        @DisplayName("GET /connect - Should return 401 for expired SSE token")
        void connect_shouldReturn401ForExpiredToken() throws Exception {
            String expiredToken = "expired_token";
            doReturn(new String[]{})
                    .when(jwtUtils).validateAndConsumeSseToken(expiredToken);

            mockMvc.perform(get(BASE_URL + "/connect")
                            .param("token", expiredToken)
                            .param("tenantId", String.valueOf(testTenantId))
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /connect - Should return 401 for malformed user ID in token")
        void connect_shouldReturn401ForMalformedUserId() throws Exception {
            String malformedToken = "malformed_token";
            doReturn(new String[]{"invalid_user_id", "1"})
                    .when(jwtUtils).validateAndConsumeSseToken(malformedToken);

            mockMvc.perform(get(BASE_URL + "/connect")
                            .param("token", malformedToken)
                            .param("tenantId", String.valueOf(testTenantId))
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /connect - Should generate connectionId if not provided")
        void connect_shouldGenerateConnectionIdIfNotProvided() throws Exception {
            String validSseToken = "valid_sse_token";
            doReturn(new String[]{"100", "1"})
                    .when(jwtUtils).validateAndConsumeSseToken(validSseToken);

            mockMvc.perform(get(BASE_URL + "/connect")
                            .param("token", validSseToken)
                            .param("tenantId", String.valueOf(testTenantId))
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isOk());

            verify(sseEmitterManager).createConnection(eq(1L), eq(100L), argThat(id -> 
                    id != null && !id.isBlank() && id.length() == 32
            ));
        }
    }

    @Nested
    @DisplayName("SSE Disconnect Operations")
    class DisconnectTests {

        @Test
        @DisplayName("DELETE /disconnect - Should disconnect with connectionId")
        void disconnect_shouldDisconnectWithConnectionId() throws Exception {
            String connectionId = "connection_to_disconnect";

            mockMvc.perform(withAuth(delete(BASE_URL + "/disconnect")
                            .param("connectionId", connectionId)))
                    .andExpect(status().isOk());

            verify(sseEmitterManager).removeConnection(testTenantId, testUserId, connectionId);
        }

        @Test
        @DisplayName("DELETE /disconnect - Should skip if connectionId not provided")
        void disconnect_shouldSkipIfConnectionIdNotProvided() throws Exception {
            mockMvc.perform(withAuth(delete(BASE_URL + "/disconnect")))
                    .andExpect(status().isOk());

            verify(sseEmitterManager, never()).removeConnection(anyLong(), anyLong(), anyString());
        }

        @Test
        @DisplayName("DELETE /disconnect - Should return 401 for unauthenticated request")
        void disconnect_shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/disconnect")
                            .param("connectionId", "some_id")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("SSE Status Operations")
    class StatusTests {

        @Test
        @DisplayName("GET /status - Should return connection status")
        void getStatus_shouldReturnConnectionStatus() throws Exception {
            performGet(BASE_URL + "/status")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.connected").value(true))
                    .andExpect(jsonPath("$.connectionCount").value(2))
                    .andExpect(jsonPath("$.onlineCount").value(5));

            verify(sseEmitterManager).isOnline(testTenantId, testUserId);
            verify(sseEmitterManager).getOnlineCount(testTenantId);
            verify(sseEmitterManager).getUserConnectionCount(testTenantId, testUserId);
        }

        @Test
        @DisplayName("GET /status - Should return offline status when not connected")
        void getStatus_shouldReturnOfflineStatus() throws Exception {
            when(sseEmitterManager.isOnline(testTenantId, testUserId)).thenReturn(false);
            when(sseEmitterManager.getUserConnectionCount(testTenantId, testUserId)).thenReturn(0);

            performGet(BASE_URL + "/status")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.connected").value(false))
                    .andExpect(jsonPath("$.connectionCount").value(0));
        }

        @Test
        @DisplayName("GET /status - Should return 401 for unauthenticated request")
        void getStatus_shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(BASE_URL + "/status")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Multi-device Support Tests")
    class MultiDeviceTests {

        @Test
        @DisplayName("Should support multiple connections for same user")
        void shouldSupportMultipleConnectionsForSameUser() throws Exception {
            String validSseToken1 = "valid_sse_token_1";
            String validSseToken2 = "valid_sse_token_2";
            String connectionId1 = "device_1";
            String connectionId2 = "device_2";

            doReturn(new String[]{"100", "1"})
                    .when(jwtUtils).validateAndConsumeSseToken(validSseToken1);
            doReturn(new String[]{"100", "1"})
                    .when(jwtUtils).validateAndConsumeSseToken(validSseToken2);

            mockMvc.perform(get(BASE_URL + "/connect")
                            .param("token", validSseToken1)
                            .param("tenantId", String.valueOf(testTenantId))
                            .param("connectionId", connectionId1)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isOk());

            mockMvc.perform(get(BASE_URL + "/connect")
                            .param("token", validSseToken2)
                            .param("tenantId", String.valueOf(testTenantId))
                            .param("connectionId", connectionId2)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isOk());

            verify(sseEmitterManager).createConnection(1L, 100L, connectionId1);
            verify(sseEmitterManager).createConnection(1L, 100L, connectionId2);
        }
    }

    @Nested
    @DisplayName("Tenant Isolation Tests")
    class TenantIsolationTests {

        @Test
        @DisplayName("GET /status - Should return status for current tenant only")
        void getStatus_shouldReturnStatusForCurrentTenantOnly() throws Exception {
            performGet(BASE_URL + "/status")
                    .andExpect(status().isOk());

            verify(sseEmitterManager).isOnline(testTenantId, testUserId);
            verify(sseEmitterManager).getOnlineCount(testTenantId);
            verify(sseEmitterManager, never()).getOnlineCount(eq(2L));
        }
    }
}
