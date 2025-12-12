package cn.flying.controller;

import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import cn.flying.service.sse.SseEmitterManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @Resource
    private JwtUtils jwtUtils;

    /**
     * 建立 SSE 连接（使用短期令牌）
     * 该端点使用一次性短期令牌进行认证，不使用常规 JWT
     */
    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "建立SSE连接", description = "使用短期SSE令牌建立长连接，接收实时消息推送")
    public ResponseEntity<SseEmitter> connect(@RequestParam("token") String sseToken) {
        // 验证并消费 SSE 短期令牌
        String[] userInfo = jwtUtils.validateAndConsumeSseToken(sseToken);
        if (userInfo == null || userInfo.length < 2) {
            log.warn("SSE 连接失败: 无效或已过期的 SSE 令牌");
            return ResponseEntity.status(401).build();
        }

        // 解析用户信息，处理潜在的格式异常
        Long userId;
        Long tenantId;
        try {
            userId = Long.parseLong(userInfo[0]);
            tenantId = Long.parseLong(userInfo[1]);
        } catch (NumberFormatException e) {
            log.error("SSE 连接失败: 令牌中的用户ID或租户ID格式无效, userInfo={}", (Object) userInfo);
            return ResponseEntity.status(401).build();
        }

        log.info("SSE 连接请求: tenantId={}, userId={}", tenantId, userId);
        SseEmitter emitter = sseEmitterManager.createConnection(tenantId, userId);
        return ResponseEntity.ok(emitter);
    }

    @DeleteMapping("/disconnect")
    @Operation(summary = "断开SSE连接")
    public void disconnect(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                           @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId) {
        log.info("SSE 断开请求: tenantId={}, userId={}", tenantId, userId);
        sseEmitterManager.removeConnection(tenantId, userId);
    }

    @GetMapping("/status")
    @Operation(summary = "获取SSE连接状态")
    public Map<String, Object> getStatus(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                         @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId) {
        boolean online = sseEmitterManager.isOnline(tenantId, userId);
        int totalOnline = sseEmitterManager.getOnlineCount(tenantId);
        return Map.of(
                "connected", online,
                "onlineCount", totalOnline
        );
    }
}
