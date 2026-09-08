package cn.flying.controller;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import cn.flying.service.auth.AuthorizationStateService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies SSE frames through initialized Spring MVC emitters and real message converters. */
@ExtendWith(MockitoExtension.class)
class SseEmitterMvcTest {

    private static final long TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;
    private static final long USER_ID = 100L;
    private static final String CONNECTED_FRAME = "event:connected\ndata:连接成功\n\n";
    private static final String HEARTBEAT_FRAME = "event:heartbeat\ndata:null\n\n";
    private static final String NULL_MESSAGE_FRAME = "event:message-received\ndata:null\n\n";
    private static final String BUSINESS_FRAME = "event:message-received\ndata:{\"message\":\"ready\"}\n\n";

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private AuthorizationStateService authorizationStateService;

    private final List<MvcResult> streams = new ArrayList<>();
    private SseEmitterManager manager;
    private MockMvc mvc;

    /** Builds the real SSE boundary while substituting only token and authorization inputs. */
    @BeforeEach
    void setUp() {
        TenantContext.clear();
        MDC.clear();
        manager = new SseEmitterManager();
        mvc = MockMvcBuilders.standaloneSetup(new SseController(manager, jwtUtils, authorizationStateService))
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter())
                .build();
    }

    /** Completes every mock async request even when a failed send removed its manager entry. */
    @AfterEach
    void tearDown() {
        try {
            manager.closeTenantConnections(TENANT_ID);
            manager.closeTenantConnections(OTHER_TENANT_ID);
            for (MvcResult stream : streams) {
                if (stream.getRequest().isAsyncStarted()) {
                    stream.getRequest().getAsyncContext().complete();
                }
            }
        } finally {
            TenantContext.clear();
            MDC.clear();
        }
    }

    /** Requires two complete named heartbeat frames without losing either tenant's connection. */
    @Test
    void heartbeatShouldKeepInitializedConnectionsAndWriteCompleteFrames() throws Exception {
        MvcResult stream = connect(TENANT_ID);
        MvcResult otherTenant = connect(OTHER_TENANT_ID);

        manager.sendHeartbeat();

        assertThat(wire(stream)).isEqualTo(CONNECTED_FRAME + HEARTBEAT_FRAME);
        assertThat(wire(otherTenant)).isEqualTo(CONNECTED_FRAME + HEARTBEAT_FRAME);
        assertConnected(TENANT_ID, 1);
        assertConnected(OTHER_TENANT_ID, 1);

        manager.sendHeartbeat();

        assertThat(wire(stream)).isEqualTo(CONNECTED_FRAME + HEARTBEAT_FRAME + HEARTBEAT_FRAME);
        assertThat(wire(otherTenant)).isEqualTo(CONNECTED_FRAME + HEARTBEAT_FRAME + HEARTBEAT_FRAME);
        assertConnected(TENANT_ID, 1);
        assertConnected(OTHER_TENANT_ID, 1);
    }

    /** Sends explicit null data through each public path while preserving tenant isolation. */
    @ParameterizedTest
    @EnumSource(SendPath.class)
    void publicSendPathsShouldWriteNullFramesToAllTargetConnections(SendPath path) throws Exception {
        MvcResult first = connect(TENANT_ID);
        MvcResult second = connect(TENANT_ID);
        MvcResult otherTenant = connect(OTHER_TENANT_ID);

        send(path, SseEvent.of(SseEventType.NEW_MESSAGE, null));

        assertThat(wire(first)).isEqualTo(CONNECTED_FRAME + NULL_MESSAGE_FRAME);
        assertThat(wire(second)).isEqualTo(CONNECTED_FRAME + NULL_MESSAGE_FRAME);
        assertThat(wire(otherTenant)).isEqualTo(CONNECTED_FRAME);
        assertConnected(TENANT_ID, 2);
        assertConnected(OTHER_TENANT_ID, 1);
    }

    /** Preserves the existing named JSON business event contract through MVC conversion. */
    @Test
    void businessPayloadShouldRemainJsonInsideTheNamedEvent() throws Exception {
        MvcResult stream = connect(TENANT_ID);

        manager.sendToUser(TENANT_ID, USER_ID,
                SseEvent.of(SseEventType.NEW_MESSAGE, Map.of("message", "ready")));

        assertThat(wire(stream)).isEqualTo(CONNECTED_FRAME + BUSINESS_FRAME);
        assertConnected(TENANT_ID, 1);
    }

    /** Rejects failed non-null serialization before writing a frame and keeps the stream usable. */
    @ParameterizedTest
    @EnumSource(SendPath.class)
    void serializationFailureShouldRemainAnExplicitErrorWithoutWritingAFrame(SendPath path) throws Exception {
        MvcResult stream = connect(TENANT_ID);

        assertThatThrownBy(() -> send(path, SseEvent.of(SseEventType.NEW_MESSAGE, new UnserializablePayload())))
                .isInstanceOfSatisfying(GeneralException.class, error -> {
                    assertThat(error.getResultEnum()).isEqualTo(ResultEnum.JSON_PARSE_ERROR);
                    assertThat(error.getData()).isEqualTo("SSE event payload serialization failed");
                    assertThat(error.getCause()).isNull();
                });

        assertThat(wire(stream)).isEqualTo(CONNECTED_FRAME);
        assertConnected(TENANT_ID, 1);

        send(path, SseEvent.of(SseEventType.NEW_MESSAGE, Map.of("message", "ready")));

        assertThat(wire(stream)).isEqualTo(CONNECTED_FRAME + BUSINESS_FRAME);
        assertConnected(TENANT_ID, 1);
    }

    /** Establishes the production controller handshake before any tested event is sent. */
    private MvcResult connect(long tenantId) throws Exception {
        String token = "test-sse-token-" + streams.size();
        when(jwtUtils.validateAndConsumeSseToken(token))
                .thenReturn(new String[]{Long.toString(USER_ID), Long.toString(tenantId), "user", "7"});
        when(authorizationStateService.isSseIdentityAuthorized(USER_ID, tenantId, "user", 7L))
                .thenReturn(true);

        try {
            MvcResult stream = mvc.perform(get("/api/v1/sse/connect")
                            .param("token", token)
                            .requestAttr(Const.ATTR_SSE_TENANT_HINT, tenantId)
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                    .andReturn();
            streams.add(stream);
            assertThat(wire(stream)).isEqualTo(CONNECTED_FRAME);
            return stream;
        } finally {
            TenantContext.clear();
            MDC.clear();
        }
    }

    /** Selects the public delivery path under test without replacing emitter behavior. */
    private void send(SendPath path, SseEvent event) {
        switch (path) {
            case USER -> manager.sendToUser(TENANT_ID, USER_ID, event);
            case USERS -> manager.sendToUsers(TENANT_ID, Set.of(USER_ID), event);
            case TENANT -> manager.broadcastToTenant(TENANT_ID, event);
        }
    }

    /** Reads the actual bytes already written by the MVC message converters. */
    private String wire(MvcResult stream) {
        return new String(stream.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** Checks both online-user bookkeeping and the number of live connection registrations. */
    private void assertConnected(long tenantId, int expectedConnections) {
        assertThat(manager.isOnline(tenantId, USER_ID)).isTrue();
        assertThat(manager.getUserConnectionCount(tenantId, USER_ID)).isEqualTo(expectedConnections);
        assertThat(manager.getOnlineCount(tenantId)).isEqualTo(1);
    }

    private enum SendPath {
        USER, USERS, TENANT
    }

    /** Supplies a deterministic non-null value that the real JSON serializer cannot read. */
    private static final class UnserializablePayload {

        /** Fails during property serialization without involving an external dependency. */
        public String getMessage() {
            throw new IllegalStateException("Synthetic serialization failure");
        }
    }
}
