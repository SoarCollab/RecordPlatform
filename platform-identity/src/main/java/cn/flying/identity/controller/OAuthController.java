package cn.flying.identity.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.AuthorizationRequest;
import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.dto.RevokeTokenRequest;
import cn.flying.identity.dto.TokenRequest;
import cn.flying.identity.service.OAuthService;
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
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * OAuth2.0控制器
 * 提供符合RESTful规范的OAuth2.0认证和授权API
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@RestController
@RequestMapping("/api/oauth")
@Tag(name = "OAuth2.0认证", description = "OAuth2.0标准认证授权接口")
public class OAuthController {

    @Resource
    private OAuthService oauthService;

    @Resource
    private SSOService ssoService;

    /**
     * 创建授权
     * POST /api/oauth/authorizations - 用户授权确认
     */
    @PostMapping("/authorizations")
    @Operation(summary = "创建授权", description = "用户确认或拒绝应用授权请求")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "授权成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "授权被拒绝")
    })
    public ResponseEntity<RestResponse<String>> createAuthorization(@Valid @RequestBody AuthorizationRequest request) {
        Result<String> result = oauthService.authorize(
                request.getClientId(),
                request.getRedirectUri(),
                request.getScope() != null ? request.getScope() : "read",
                request.getState(),
                request.isApproved()
        );

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(RestResponse.created(result.getData()));
        } else {
            RestResponse<String> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 创建访问令牌
     * POST /api/oauth/tokens - 获取访问令牌
     */
    @PostMapping("/tokens")
    @Operation(summary = "创建访问令牌", description = "根据不同的授权类型获取访问令牌")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "认证失败")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> createToken(@Valid @RequestBody TokenRequest request) {
        Result<Map<String, Object>> result = switch (request.getGrantType()) {
            case "authorization_code" -> oauthService.getAccessToken(
                    request.getGrantType(),
                    request.getCode(),
                    request.getRedirectUri(),
                    request.getClientId(),
                    request.getClientSecret()
            );
            case "refresh_token" -> oauthService.refreshAccessToken(
                    request.getGrantType(),
                    request.getRefreshToken(),
                    request.getClientId(),
                    request.getClientSecret()
            );
            case "client_credentials" -> oauthService.getClientCredentialsToken(
                    request.getGrantType(),
                    request.getScope(),
                    request.getClientId(),
                    request.getClientSecret()
            );
            default -> Result.error(ResultEnum.PARAM_IS_INVALID, null);
        };

        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取用户信息
     * GET /api/oauth/users/me - 获取当前用户信息
     */
    @GetMapping("/users/me")
    @Operation(summary = "获取OAuth用户信息", description = "通过访问令牌获取用户信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "令牌无效或已过期"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserInfo(
            @RequestHeader("Authorization") String authorization) {

        // 验证Authorization头格式
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(RestResponse.unauthorized(ResultEnum.PARAM_IS_INVALID.getCode(),
                            "Authorization头格式错误"));
        }

        String accessToken = authorization.substring(7);
        if (accessToken.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(RestResponse.unauthorized(ResultEnum.PARAM_IS_INVALID.getCode(),
                            "访问令牌不能为空"));
        }

        Result<Map<String, Object>> result = oauthService.getUserInfo(accessToken);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 撤销令牌
     * POST /api/oauth/tokens/revoke - 撤销访问令牌或刷新令牌
     */
    @PostMapping("/tokens/revoke")
    @Operation(summary = "撤销令牌", description = "撤销访问令牌或刷新令牌")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "撤销成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "客户端认证失败")
    })
    public ResponseEntity<RestResponse<Void>> revokeToken(@Valid @RequestBody RevokeTokenRequest request) {
        Result<Void> result = oauthService.revokeToken(
                request.getToken(),
                request.getTokenTypeHint(),
                request.getClientId(),
                request.getClientSecret()
        );

        RestResponse<Void> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 创建客户端
     * POST /api/oauth/clients - 注册新的OAuth客户端
     */
    @PostMapping("/clients")
    @Operation(summary = "创建OAuth客户端", description = "注册新的OAuth客户端应用")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "创建成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "409", description = "客户端已存在")
    })
    public ResponseEntity<RestResponse<OAuthClient>> createClient(@Valid @RequestBody OAuthClient client) {
        // 仅管理员可创建客户端
        if (!StpUtil.isLogin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(RestResponse.unauthorized(ResultEnum.USER_NOT_LOGGED_IN.getCode(), "用户未登录"));
        }
        String role = (String) StpUtil.getSession().get("role");
        if (!"admin".equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(RestResponse.forbidden(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), "无权限"));
        }

        Result<OAuthClient> result = oauthService.registerClient(client);

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(RestResponse.created(result.getData()));
        } else {
            RestResponse<OAuthClient> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 更新客户端
     * PUT /api/oauth/clients/{clientId} - 更新OAuth客户端信息
     */
    @PutMapping("/clients/{clientId}")
    @Operation(summary = "更新OAuth客户端", description = "更新现有OAuth客户端的配置")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "404", description = "客户端不存在")
    })
    public ResponseEntity<RestResponse<OAuthClient>> updateClient(
            @Parameter(description = "客户端ID") @PathVariable String clientId,
            @Valid @RequestBody OAuthClient client) {

        // 仅管理员可更新客户端
        if (!StpUtil.isLogin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(RestResponse.unauthorized(ResultEnum.USER_NOT_LOGGED_IN.getCode(), "用户未登录"));
        }
        String role = (String) StpUtil.getSession().get("role");
        if (!"admin".equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(RestResponse.forbidden(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), "无权限"));
        }

        // 先根据路径参数查询现有客户端，确保更新的是同一条记录
        Result<OAuthClient> existing = oauthService.getClient(clientId);
        if (!existing.isSuccess() || existing.getData() == null) {
            RestResponse<OAuthClient> resp = ResponseConverter.convert(existing);
            return ResponseEntity.status(resp.getStatus()).body(resp);
        }
        OAuthClient dbClient = existing.getData();
        // 对齐主键与标识符，避免更新到错误记录
        client.setClientId(dbClient.getClientId());
        client.setClientKey(dbClient.getClientKey());

        Result<OAuthClient> result = oauthService.updateClient(client);
        RestResponse<OAuthClient> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 删除客户端
     * DELETE /api/oauth/clients/{clientId} - 删除OAuth客户端
     */
    @DeleteMapping("/clients/{clientId}")
    @Operation(summary = "删除OAuth客户端", description = "删除指定的OAuth客户端")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "客户端不存在")
    })
    public ResponseEntity<Void> deleteClient(
            @Parameter(description = "客户端ID") @PathVariable String clientId) {

        // 仅管理员可删除客户端
        if (!StpUtil.isLogin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String role = (String) StpUtil.getSession().get("role");
        if (!"admin".equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Result<Void> result = oauthService.deleteClient(clientId);

        if (result.isSuccess()) {
            return ResponseEntity.noContent().build();
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).build();
        }
    }

    /**
     * 获取客户端信息
     * GET /api/oauth/clients/{clientId} - 获取OAuth客户端详情
     */
    @GetMapping("/clients/{clientId}")
    @Operation(summary = "获取OAuth客户端信息", description = "获取指定OAuth客户端的详细信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "404", description = "客户端不存在")
    })
    public ResponseEntity<RestResponse<OAuthClient>> getClient(
            @Parameter(description = "客户端ID") @PathVariable String clientId) {

        // 仅管理员可查看客户端详情
        if (!StpUtil.isLogin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(RestResponse.unauthorized(ResultEnum.USER_NOT_LOGGED_IN.getCode(), "用户未登录"));
        }
        String role = (String) StpUtil.getSession().get("role");
        if (!"admin".equalsIgnoreCase(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(RestResponse.forbidden(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), "无权限"));
        }

        Result<OAuthClient> result = oauthService.getClient(clientId);
        if (result.isSuccess() && result.getData() != null) {
            // 脱敏返回，避免泄露加密后的密钥
            OAuthClient data = result.getData();
            data.setClientSecret(null);
            return ResponseEntity.ok(RestResponse.ok(result.getMessage(), data));
        }
        RestResponse<OAuthClient> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取SSO登录信息
     * GET /api/oauth/sso/sessions - 获取SSO登录页面信息
     */
    @GetMapping("/sso/sessions")
    @Operation(summary = "获取SSO登录信息", description = "获取SSO登录或授权页面信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "需要登录")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getSSOSession(
            @Parameter(description = "客户端ID") @RequestParam("client_id") String clientId,
            @Parameter(description = "重定向URI") @RequestParam("redirect_uri") String redirectUri,
            @Parameter(description = "授权范围") @RequestParam(value = "scope", defaultValue = "read") String scope,
            @Parameter(description = "状态参数") @RequestParam(value = "state", required = false) String state) {

        // 检查用户是否已登录
        if (StpUtil.isLogin()) {
            // 已登录，返回授权页面信息
            Result<Map<String, Object>> result = oauthService.getAuthorizeInfo(clientId, redirectUri, scope, state);
            RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        } else {
            // 未登录，返回401
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(RestResponse.unauthorized(ResultEnum.USER_NOT_LOGGED_IN.getCode(),
                            "用户未登录"));
        }
    }

    /**
     * 获取授权信息
     * GET /api/oauth/authorizations - 获取授权页面信息
     */
    @GetMapping("/authorizations")
    @Operation(summary = "获取授权信息", description = "获取OAuth授权页面所需信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getAuthorization(
            @Parameter(description = "客户端ID") @RequestParam("client_id") String clientId,
            @Parameter(description = "重定向URI") @RequestParam("redirect_uri") String redirectUri,
            @Parameter(description = "授权范围") @RequestParam(value = "scope", defaultValue = "read") String scope,
            @Parameter(description = "状态参数") @RequestParam(value = "state", required = false) String state) {

        Result<Map<String, Object>> result = oauthService.getAuthorizeInfo(clientId, redirectUri, scope, state);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 销毁SSO会话
     * DELETE /api/oauth/sso/sessions - 执行SSO单点注销
     */
    @DeleteMapping("/sso/sessions")
    @Operation(summary = "销毁SSO会话", description = "执行SSO单点注销")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "注销成功"),
            @ApiResponse(responseCode = "400", description = "注销失败")
    })
    public ResponseEntity<Void> destroySSOSession(
            @Parameter(description = "注销后重定向URI") @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @Parameter(description = "客户端ID") @RequestParam(value = "client_id", required = false) String clientId) {

        Result<Map<String, Object>> result = ssoService.ssoLogout(redirectUri, clientId);

        if (result.isSuccess()) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }
}