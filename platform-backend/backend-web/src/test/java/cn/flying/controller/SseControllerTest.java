package cn.flying.controller;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.auth.AuthorizationStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SseController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class SseControllerTest {

    @Mock
    private SseEmitterManager sseEmitterManager;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private AuthorizationStateService authorizationStateService;

    private SseController controller;

    /**
     * 初始化 controller，并为 SSE manager 配置默认 emitter。
     */
    @BeforeEach
    void setUp() {
        controller = new SseController(sseEmitterManager, jwtUtils, authorizationStateService);
        lenient().when(authorizationStateService.isSseIdentityAuthorized(
                        anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(true);
    }

    /**
     * 清理直接调用控制器后留下的可信身份上下文。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        MDC.clear();
    }

    /**
     * 验证合法短令牌使用服务端连接 ID，并把令牌身份写入可信请求上下文。
     */
    @Test
    @DisplayName("connect should use server generated connection id")
    void connectShouldUseServerGeneratedConnectionId() {
        when(jwtUtils.validateAndConsumeSseToken("valid-token"))
                .thenAnswer(invocation -> {
                    assertThat(TenantContext.getTenantId()).isEqualTo(1L);
                    return new String[]{"100", "1", "user", "7"};
                });
        when(sseEmitterManager.createConnection(eq(1L), eq(100L), anyString()))
                .thenReturn(new SseEmitter(60000L));
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<SseEmitter> response = controller.connect("valid-token", 1L, request);

        ArgumentCaptor<String> connectionIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(sseEmitterManager).createConnection(eq(1L), eq(100L), connectionIdCaptor.capture());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(connectionIdCaptor.getValue())
                .isNotBlank()
                .hasSize(32);
        assertThat(request.getAttribute(Const.ATTR_TENANT_ID)).isEqualTo(1L);
        assertThat(request.getAttribute(Const.ATTR_USER_ID)).isEqualTo(100L);
        assertThat(request.getAttribute(Const.ATTR_USER_ROLE)).isEqualTo("user");
        assertThat(request.getAttribute(Const.ATTR_AUTH_VERSION)).isEqualTo(7L);
        verify(authorizationStateService).isSseIdentityAuthorized(100L, 1L, "user", 7L);
        assertThat(TenantContext.getTenantId()).isEqualTo(1L);
        assertThat(MDC.get(Const.ATTR_TENANT_ID)).isEqualTo("1");
        assertThat(MDC.get(Const.ATTR_USER_ID)).isEqualTo("100");
        assertThat(MDC.get(Const.ATTR_USER_ROLE)).isEqualTo("user");
    }

    /**
     * 验证 namespace 提示与短令牌租户不一致时失败关闭且不建立连接。
     */
    @Test
    @DisplayName("connect should reject a token tenant that differs from the hint")
    void connectShouldRejectTokenTenantMismatch() {
        when(jwtUtils.validateAndConsumeSseToken("mismatched-token"))
                .thenReturn(new String[]{"100", "2", "user", "7"});
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<SseEmitter> response = controller.connect("mismatched-token", 1L, request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(sseEmitterManager, never()).createConnection(anyLong(), anyLong(), anyString());
        assertThat(request.getAttribute(Const.ATTR_TENANT_ID)).isNull();
        assertThat(request.getAttribute(Const.ATTR_USER_ID)).isNull();
        assertThat(request.getAttribute(Const.ATTR_USER_ROLE)).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(MDC.get(Const.ATTR_TENANT_ID)).isNull();
        assertThat(MDC.get(Const.ATTR_USER_ID)).isNull();
        assertThat(MDC.get(Const.ATTR_USER_ROLE)).isNull();
    }

    /**
     * 验证损坏角色载荷不会被当成可信身份。
     */
    @Test
    @DisplayName("connect should reject a corrupted token role")
    void connectShouldRejectCorruptedTokenRole() {
        when(jwtUtils.validateAndConsumeSseToken("corrupted-token"))
                .thenReturn(new String[]{"100", "1", "owner", "7"});
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<SseEmitter> response = controller.connect(
                "corrupted-token", 1L, request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(sseEmitterManager, never()).createConnection(anyLong(), anyLong(), anyString());
        assertThat(request.getAttribute(Const.ATTR_TENANT_ID)).isNull();
        assertThat(request.getAttribute(Const.ATTR_USER_ID)).isNull();
        assertThat(request.getAttribute(Const.ATTR_USER_ROLE)).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(MDC.get(Const.ATTR_TENANT_ID)).isNull();
        assertThat(MDC.get(Const.ATTR_USER_ID)).isNull();
        assertThat(MDC.get(Const.ATTR_USER_ROLE)).isNull();
    }

    /**
     * 验证 Redis 异常返回服务不可用且不泄漏临时 hint 上下文。
     */
    @Test
    @DisplayName("connect should fail closed when the token store is unavailable")
    void connectShouldFailClosedWhenTokenStoreUnavailable() {
        when(jwtUtils.validateAndConsumeSseToken("redis-failure-token"))
                .thenThrow(new IllegalStateException("redis key contains sensitive data"));
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<SseEmitter> response = controller.connect(
                "redis-failure-token", 1L, request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verify(sseEmitterManager, never()).createConnection(anyLong(), anyLong(), anyString());
        assertThat(request.getAttribute(Const.ATTR_TENANT_ID)).isNull();
        assertThat(request.getAttribute(Const.ATTR_USER_ID)).isNull();
        assertThat(request.getAttribute(Const.ATTR_USER_ROLE)).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(MDC.get(Const.ATTR_TENANT_ID)).isNull();
        assertThat(MDC.get(Const.ATTR_USER_ID)).isNull();
        assertThat(MDC.get(Const.ATTR_USER_ROLE)).isNull();
    }

    /** Rejects a consumed short token when the account or tenant is no longer authorized. */
    @Test
    @DisplayName("connect should reject stale current authorization state")
    void connectShouldRejectStaleAuthorizationState() {
        when(jwtUtils.validateAndConsumeSseToken("stale-token"))
                .thenReturn(new String[]{"100", "1", "user", "7"});
        when(authorizationStateService.isSseIdentityAuthorized(100L, 1L, "user", 7L))
                .thenReturn(false);

        ResponseEntity<SseEmitter> response = controller.connect(
                "stale-token", 1L, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(sseEmitterManager, never()).createConnection(anyLong(), anyLong(), anyString());
    }

    /** Returns service unavailable when current authorization storage cannot be checked. */
    @Test
    @DisplayName("connect should fail closed when authorization state is unavailable")
    void connectShouldFailClosedWhenAuthorizationStateUnavailable() {
        when(jwtUtils.validateAndConsumeSseToken("state-failure-token"))
                .thenReturn(new String[]{"100", "1", "user", "7"});
        when(authorizationStateService.isSseIdentityAuthorized(100L, 1L, "user", 7L))
                .thenThrow(new IllegalStateException("redis unavailable"));

        ResponseEntity<SseEmitter> response = controller.connect(
                "state-failure-token", 1L, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verify(sseEmitterManager, never()).createConnection(anyLong(), anyLong(), anyString());
    }
}
