package cn.flying.identity.controller;

import cn.flying.identity.dto.SSOLoginRequest;
import cn.flying.identity.dto.TokenValidationRequest;
import cn.flying.identity.service.SSOService;
import cn.flying.identity.util.ResponseConverter;
import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * SSO 单点登录控制器
 * 提供符合RESTful规范的单点登录功能接口
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@RestController
@RequestMapping("/api/sso")
@Tag(name = "SSO单点登录", description = "提供单点登录相关功能")
public class SSOController {

    @Resource
    private SSOService ssoService;

    /**
     * 获取授权信息
     * GET /api/sso/authorization - 获取SSO授权页面信息
     */
    @GetMapping("/authorization")
    @Operation(summary = "获取SSO授权信息", description = "获取SSO登录页面信息或处理已登录用户的授权")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getAuthorization(
            @Parameter(description = "客户端ID") @RequestParam String clientId,
            @Parameter(description = "重定向URI") @RequestParam String redirectUri,
            @Parameter(description = "授权范围") @RequestParam(defaultValue = "read") String scope,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state) {

        Result<Map<String, Object>> result = ssoService.getSSOLoginInfo(clientId, redirectUri, scope, state);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 创建SSO会话（登录）
     * POST /api/sso/sessions - 处理SSO登录
     */
    @PostMapping("/sessions")
    @Operation(summary = "创建SSO会话", description = "处理用户登录并生成SSO Token")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "登录成功，会话已创建"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "用户名或密码错误")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> createSession(
            @Valid @RequestBody SSOLoginRequest request) {

        Result<Map<String, Object>> result = ssoService.processSSOLogin(
                request.getUsername(),
                request.getPassword(),
                request.getClientId(),
                request.getRedirectUri(),
                request.getScope() != null ? request.getScope() : "read",
                request.getState()
        );

        if (result.isSuccess()) {
            RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 获取SSO会话状态
     * GET /api/sso/sessions/status - 检查SSO登录状态
     */
    @GetMapping("/sessions/status")
    @Operation(summary = "检查SSO会话状态", description = "检查用户当前的SSO登录状态")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "状态获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getSessionStatus(
            @Parameter(description = "客户端ID") @RequestParam String clientId,
            @Parameter(description = "重定向URI") @RequestParam String redirectUri,
            @Parameter(description = "授权范围") @RequestParam(defaultValue = "read") String scope,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state) {

        Result<Map<String, Object>> result = ssoService.checkSSOLoginStatus(clientId, redirectUri, scope, state);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 销毁SSO会话（注销）
     * DELETE /api/sso/sessions - 执行单点注销
     */
    @DeleteMapping("/sessions")
    @Operation(summary = "销毁SSO会话", description = "执行单点注销操作")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "注销成功"),
            @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<Void> destroySession(
            @Parameter(description = "注销后重定向URI") @RequestParam(required = false) String redirectUri,
            @Parameter(description = "客户端ID") @RequestParam(required = false) String clientId) {

        Result<Map<String, Object>> result = ssoService.ssoLogout(redirectUri, clientId);

        if (result.isSuccess()) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    /**
     * 获取用户信息
     * GET /api/sso/users/me - 通过SSO Token获取用户信息
     */
    @GetMapping("/users/me")
    @Operation(summary = "获取SSO用户信息", description = "通过SSO Token获取当前用户信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "Token无效或已过期"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserInfo(
            @Parameter(description = "SSO Token") @RequestParam String token) {

        Result<Map<String, Object>> result = ssoService.getSSOUserInfo(token);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 验证Token
     * POST /api/sso/tokens/validate - 验证SSO Token有效性
     */
    @PostMapping("/tokens/validate")
    @Operation(summary = "验证SSO Token", description = "验证SSO Token的有效性")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token有效"),
            @ApiResponse(responseCode = "401", description = "Token无效或已过期")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> validateToken(
            @Valid @RequestBody TokenValidationRequest request) {

        Result<Map<String, Object>> result = ssoService.validateSSOToken(request.getToken());
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取客户端列表
     * GET /api/sso/clients - 获取已登录的客户端列表
     */
    @GetMapping("/clients")
    @Operation(summary = "获取已登录客户端", description = "获取当前用户已登录的所有客户端列表")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getClients() {
        Result<Map<String, Object>> result = ssoService.getLoggedInClients();
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 从客户端注销
     * DELETE /api/sso/clients/{clientId} - 从指定客户端注销
     */
    @DeleteMapping("/clients/{clientId}")
    @Operation(summary = "从指定客户端注销", description = "从指定的客户端注销，但保持其他客户端的登录状态")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "注销成功"),
            @ApiResponse(responseCode = "404", description = "客户端不存在"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<Void> deleteClientSession(
            @Parameter(description = "客户端ID") @PathVariable String clientId) {

        Result<Void> result = ssoService.logoutFromClient(clientId);

        if (result.isSuccess()) {
            return ResponseEntity.noContent().build();
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).build();
        }
    }

    /**
     * 处理SSO回调
     * GET /api/sso/callback - 处理SSO登录成功后的回调
     */
    @GetMapping("/callback")
    @Operation(summary = "SSO回调处理", description = "处理SSO登录成功后的回调")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "重定向到成功页面")
    })
    public void handleCallback(
            @Parameter(description = "授权码") @RequestParam(required = false) String code,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state,
            HttpServletResponse response) throws IOException {

        // 这里可以处理回调逻辑，比如验证授权码、生成最终的访问令牌等
        // 目前简化处理，直接重定向到成功页面
        response.sendRedirect("/sso/success?code=" + code + "&state=" + state);
    }

    /**
     * 获取错误信息
     * GET /api/sso/errors - 获取SSO错误信息
     */
    @GetMapping("/errors")
    @Operation(summary = "获取SSO错误信息", description = "获取SSO过程中的错误详情")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "错误信息")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getError(
            @Parameter(description = "错误类型") @RequestParam(required = false) String error,
            @Parameter(description = "错误描述") @RequestParam(required = false) String errorDescription,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state) {

        Map<String, Object> errorData = Map.of(
                "error", error != null ? error : "unknown_error",
                "error_description", errorDescription != null ? errorDescription : "SSO未知错误",
                "state", state != null ? state : ""
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(RestResponse.badRequest(ResultEnum.SSO_UNKNOWN_ERROR.getCode(),
                        errorDescription != null ? errorDescription : "SSO未知错误"));
    }
}
