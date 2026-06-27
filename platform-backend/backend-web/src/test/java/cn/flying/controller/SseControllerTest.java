package cn.flying.controller;

import cn.flying.common.util.JwtUtils;
import cn.flying.service.sse.SseEmitterManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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

    private SseController controller;

    /**
     * 初始化 controller，并为 SSE manager 配置默认 emitter。
     */
    @BeforeEach
    void setUp() {
        controller = new SseController(sseEmitterManager, jwtUtils);
        when(sseEmitterManager.createConnection(eq(1L), eq(100L), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new SseEmitter(60000L));
    }

    @Test
    @DisplayName("connect should use server generated connection id")
    void connectShouldUseServerGeneratedConnectionId() {
        when(jwtUtils.validateAndConsumeSseToken("valid-token"))
                .thenReturn(new String[]{"100", "1"});

        ResponseEntity<SseEmitter> response = controller.connect("valid-token");

        ArgumentCaptor<String> connectionIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(sseEmitterManager).createConnection(eq(1L), eq(100L), connectionIdCaptor.capture());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(connectionIdCaptor.getValue())
                .isNotBlank()
                .hasSize(32);
    }
}
