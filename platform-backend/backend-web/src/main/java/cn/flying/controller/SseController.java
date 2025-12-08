package cn.flying.controller;

import cn.flying.common.util.Const;
import cn.flying.service.sse.SseEmitterManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * SSE 实时推送控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sse")
@Tag(name = "SSE推送", description = "服务端推送事件连接管理")
public class SseController {

    @Resource
    private SseEmitterManager sseEmitterManager;

    /**
     * 建立 SSE 连接
     */
    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "建立SSE连接", description = "客户端通过此端点建立SSE长连接，接收实时消息推送")
    public SseEmitter connect(@RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        log.info("SSE 连接请求: userId={}", userId);
        return sseEmitterManager.createConnection(userId);
    }

    /**
     * 断开 SSE 连接
     */
    @DeleteMapping("/disconnect")
    @Operation(summary = "断开SSE连接")
    public void disconnect(@RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        log.info("SSE 断开请求: userId={}", userId);
        sseEmitterManager.removeConnection(userId);
    }

    /**
     * 获取连接状态
     */
    @GetMapping("/status")
    @Operation(summary = "获取SSE连接状态")
    public Map<String, Object> getStatus(@RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        boolean online = sseEmitterManager.isOnline(userId);
        int totalOnline = sseEmitterManager.getOnlineCount();
        return Map.of(
                "connected", online,
                "onlineCount", totalOnline
        );
    }
}
