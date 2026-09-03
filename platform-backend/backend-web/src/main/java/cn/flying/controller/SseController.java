package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.UserRole;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.auth.AuthorizationStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
@RequiredArgsConstructor
public class SseController {

    private final SseEmitterManager sseEmitterManager;

    private final JwtUtils jwtUtils;

    private final AuthorizationStateService authorizationStateService;

    /**
     * 建立 SSE 连接（使用短期令牌）
     * 该端点使用一次性短期令牌进行认证，不使用常规 JWT
     * 支持同一用户多个连接（多设备/多标签页），连接 ID 由服务端生成
     *
     * @param sseToken 一次性 SSE 短令牌
     * @param tenantHint TenantFilter 解析的不可信 Redis namespace 提示
     * @param request 当前 HTTP 请求，用于在令牌验证后写入可信审计属性
     * @return SSE emitter 或失败状态
     */
    @OperationLog(module = "实时推送", operationType = "新增", description = "建立SSE连接", saveRequestData = false)
    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "建立SSE连接",
            description = "使用一次性短期SSE令牌建立长连接。X-Tenant-ID请求头、x-tenant-id query"
                    + "（旧客户端可使用tenantId）"
                    + "仅用于Redis namespace查找，连接与审计身份以短令牌中的tenant/user/role为准。")
    @Parameters({
            @Parameter(
                    name = "X-Tenant-ID",
                    in = ParameterIn.HEADER,
                    description = "可设置自定义请求头的客户端使用的Redis namespace提示，不作为可信租户身份",
                    schema = @Schema(type = "integer", format = "int64", minimum = "0")),
            @Parameter(
                    name = "x-tenant-id",
                    in = ParameterIn.QUERY,
                    description = "Redis namespace提示；与旧tenantId参数二选一，不作为可信租户身份",
                    schema = @Schema(type = "integer", format = "int64", minimum = "0")),
            @Parameter(
                    name = "tenantId",
                    in = ParameterIn.QUERY,
                    description = "旧客户端兼容的Redis namespace提示；建议改用x-tenant-id",
                    deprecated = true,
                    schema = @Schema(type = "integer", format = "int64", minimum = "0"))
    })
    public ResponseEntity<SseEmitter> connect(
            @RequestParam("token") String sseToken,
            @RequestAttribute(Const.ATTR_SSE_TENANT_HINT) Long tenantHint,
            HttpServletRequest request) {
        String[] userInfo;
        try {
            // hint 只在既有 Redis namespace 查找期间临时生效，返回后立即恢复为空
            userInfo = TenantContext.callWithTenant(
                    tenantHint,
                    () -> jwtUtils.validateAndConsumeSseToken(sseToken));
        } catch (RuntimeException e) {
            log.error("SSE 连接失败: 短令牌存储不可用, exceptionType={}", e.getClass().getSimpleName());
            return ResponseEntity.status(503).build();
        }

        SseIdentity identity = parseSseIdentity(userInfo);
        if (identity == null) {
            log.warn("SSE 连接失败: 无效或已过期的 SSE 令牌");
            return ResponseEntity.status(401).build();
        }

        if (!tenantHint.equals(identity.tenantId())) {
            log.warn("SSE 连接失败: 租户提示与短令牌身份不一致");
            return ResponseEntity.status(401).build();
        }

        try {
            if (!authorizationStateService.isSseIdentityAuthorized(
                    identity.userId(), identity.tenantId(), identity.role(), identity.authVersion())) {
                log.warn("SSE 连接失败: 当前授权状态无效");
                return ResponseEntity.status(401).build();
            }
        } catch (RuntimeException exception) {
            log.error("SSE 连接失败: 授权状态存储不可用, exceptionType={}",
                    exception.getClass().getSimpleName());
            return ResponseEntity.status(503).build();
        }

        establishTrustedIdentity(request, identity);
        String connectionId = UUID.randomUUID().toString().replace("-", "");

        log.info("SSE 连接请求: tenantId={}, userId={}, connectionId={}",
                identity.tenantId(), identity.userId(), connectionId);
        SseEmitter emitter = sseEmitterManager.createConnection(
                identity.tenantId(), identity.userId(), connectionId);
        return ResponseEntity.ok(emitter);
    }

    /**
     * 解析并校验 Redis 中的一次性短令牌身份载荷。
     *
     * @param userInfo 短令牌载荷 [userId, tenantId, role, authVersion]
     * @return 完整且受支持的身份；载荷损坏时返回 null
     */
    private SseIdentity parseSseIdentity(String[] userInfo) {
        if (userInfo == null || userInfo.length != 4) {
            return null;
        }
        try {
            long userId = Long.parseLong(userInfo[0]);
            long tenantId = Long.parseLong(userInfo[1]);
            long authVersion = Long.parseLong(userInfo[3]);
            UserRole role = UserRole.getRole(userInfo[2]);
            if (userId <= 0 || tenantId < 0 || authVersion < 0 || !role.isTenantRole()) {
                return null;
            }
            return new SseIdentity(userId, tenantId, role.getRole(), authVersion);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 在短令牌验证完成后建立本次握手的可信租户、用户、角色与 MDC 身份。
     *
     * @param request 当前 HTTP 请求
     * @param identity 已验证且与 namespace 提示一致的短令牌身份
     */
    private void establishTrustedIdentity(HttpServletRequest request, SseIdentity identity) {
        TenantContext.setTenantId(identity.tenantId());
        request.setAttribute(Const.ATTR_TENANT_ID, identity.tenantId());
        request.setAttribute(Const.ATTR_USER_ID, identity.userId());
        request.setAttribute(Const.ATTR_USER_ROLE, identity.role());
        request.setAttribute(Const.ATTR_AUTH_VERSION, identity.authVersion());
        MDC.put(Const.ATTR_TENANT_ID, identity.tenantId().toString());
        MDC.put(Const.ATTR_USER_ID, identity.userId().toString());
        MDC.put(Const.ATTR_USER_ROLE, identity.role());
    }

    /**
     * 短令牌中经校验的 SSE 身份。
     */
    private record SseIdentity(Long userId, Long tenantId, String role, Long authVersion) {
    }

    @OperationLog(module = "实时推送", operationType = "删除", description = "断开SSE连接")
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

    @OperationLog(module = "实时推送", operationType = "查询", description = "获取SSE连接状态")
    @GetMapping("/status")
    @Operation(summary = "获取SSE连接状态")
    public Result<Map<String, Object>> getStatus(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                                 @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId) {
        boolean online = sseEmitterManager.isOnline(tenantId, userId);
        int totalOnline = sseEmitterManager.getOnlineCount(tenantId);
        int userConnections = sseEmitterManager.getUserConnectionCount(tenantId, userId);
        return Result.success(Map.of(
                "connected", online,
                "connectionCount", userConnections,
                "onlineCount", totalOnline
        ));
    }
}
