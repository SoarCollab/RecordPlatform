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
import java.util.UUID;

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
     * 支持同一用户多个连接（多设备/多标签页），通过 connectionId 区分
     */
    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "建立SSE连接", description = "使用短期SSE令牌建立长连接，接收实时消息推送。支持多设备/多标签页连接。")
    public ResponseEntity<SseEmitter> connect(
            @RequestParam("token") String sseToken,
            @RequestParam(value = "connectionId", required = false) String connectionId) {
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

        // 如果未提供 connectionId，则生成一个（向后兼容）
        if (connectionId == null || connectionId.isBlank()) {
            connectionId = UUID.randomUUID().toString().replace("-", "");
        }

        log.info("SSE 连接请求: tenantId={}, userId={}, connectionId={}", tenantId, userId, connectionId);
        SseEmitter emitter = sseEmitterManager.createConnection(tenantId, userId, connectionId);
        return ResponseEntity.ok(emitter);
    }

    @DeleteMapping("/disconnect")
    @Operation(summary = "断开SSE连接", description = "断开指定的SSE连接。如不提供connectionId，则由客户端维护。")
    public void disconnect(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @RequestParam(value = "connectionId", required = false) String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            // 未提供 connectionId，记录警告并跳过（连接会在超时或客户端关闭时自动清理）
            log.warn("SSE 断开请求缺少 connectionId: tenantId={}, userId={}", tenantId, userId);
            return;
        }
        log.info("SSE 断开请求: tenantId={}, userId={}, connectionId={}", tenantId, userId, connectionId);
        sseEmitterManager.removeConnection(tenantId, userId, connectionId);
    }

    @GetMapping("/status")
    @Operation(summary = "获取SSE连接状态")
    public Map<String, Object> getStatus(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                         @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId) {
        boolean online = sseEmitterManager.isOnline(tenantId, userId);
        int totalOnline = sseEmitterManager.getOnlineCount(tenantId);
        int userConnections = sseEmitterManager.getUserConnectionCount(tenantId, userId);
        return Map.of(
                "connected", online,
                "connectionCount", userConnections,
                "onlineCount", totalOnline
        );
    }
}
