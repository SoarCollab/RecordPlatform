package cn.flying.identity.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.service.OAuthService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * OAuth2.0控制器
 * 提供SSO单点登录和第三方应用接入的RESTful API
 */
@RestController
@RequestMapping("/oauth")
@Tag(name = "OAuth2.0认证", description = "SSO单点登录和第三方应用接入")
public class OAuthController {

    @Resource
    private OAuthService oauthService;

    /**
     * 用户授权确认
     *
     * @param clientId    客户端ID
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @param approved    是否同意授权
     * @return 授权结果
     */
    @PostMapping("/authorize")
    @Operation(summary = "用户授权确认")
    public Result<String> doAuthorize(
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam(value = "scope", defaultValue = "read") String scope,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam("approved") boolean approved) {
        return oauthService.authorize(clientId, redirectUri, scope, state, approved);
    }

    /**
     * 获取访问令牌
     *
     * @param grantType    授权类型
     * @param code         授权码
     * @param redirectUri  重定向URI
     * @param clientId     客户端ID
     * @param clientSecret 客户端密钥
     * @param refreshToken 刷新令牌
     * @param scope        授权范围
     * @return 访问令牌信息
     */
    @PostMapping("/token")
    @Operation(summary = "获取访问令牌")
    public Result<Map<String, Object>> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam(value = "refresh_token", required = false) String refreshToken,
            @RequestParam(value = "scope", required = false) String scope) {

        return switch (grantType) {
            case "authorization_code" ->
                    oauthService.getAccessToken(grantType, code, redirectUri, clientId, clientSecret);
            case "refresh_token" -> oauthService.refreshAccessToken(grantType, refreshToken, clientId, clientSecret);
            case "client_credentials" ->
                    oauthService.getClientCredentialsToken(grantType, scope, clientId, clientSecret);
            default -> Result.error(ResultEnum.PARAM_IS_INVALID, null);
        };
    }

    /**
     * 获取用户信息
     *
     * @param authorization 授权头
     * @return 用户信息
     */
    @GetMapping("/userinfo")
    @Operation(summary = "获取用户信息")
    public Result<Map<String, Object>> userinfo(@RequestHeader("Authorization") String authorization) {
        String accessToken = authorization.replace("Bearer ", "");
        return oauthService.getUserInfo(accessToken);
    }

    /**
     * 撤销令牌
     *
     * @param token         令牌
     * @param tokenTypeHint 令牌类型提示
     * @param clientId      客户端ID
     * @param clientSecret  客户端密钥
     * @return 撤销结果
     */
    @PostMapping("/revoke")
    @Operation(summary = "撤销令牌")
    public Result<Void> revoke(
            @RequestParam("token") String token,
            @RequestParam(value = "token_type_hint", required = false) String tokenTypeHint,
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret) {
        return oauthService.revokeToken(token, tokenTypeHint, clientId, clientSecret);
    }

    /**
     * 注册OAuth客户端
     *
     * @param client 客户端信息
     * @return 注册结果
     */
    @PostMapping("/client/register")
    @Operation(summary = "注册OAuth客户端")
    public Result<OAuthClient> registerClient(@RequestBody OAuthClient client) {
        return oauthService.registerClient(client);
    }

    /**
     * 更新OAuth客户端
     *
     * @param client 客户端信息
     * @return 更新结果
     */
    @PutMapping("/client/update")
    @Operation(summary = "更新OAuth客户端")
    public Result<OAuthClient> updateClient(@RequestBody OAuthClient client) {
        return oauthService.updateClient(client);
    }

    /**
     * 删除OAuth客户端
     *
     * @param clientId 客户端ID
     * @return 删除结果
     */
    @DeleteMapping("/client/{clientId}")
    @Operation(summary = "删除OAuth客户端")
    public Result<Void> deleteClient(@PathVariable String clientId) {
        return oauthService.deleteClient(clientId);
    }

    /**
     * 获取客户端信息
     *
     * @param clientId 客户端ID
     * @return 客户端信息
     */
    @GetMapping("/client/{clientId}")
    @Operation(summary = "获取客户端信息")
    public Result<OAuthClient> getClient(@PathVariable String clientId) {
        return oauthService.getClient(clientId);
    }

    /**
     * SSO单点登录页面
     *
     * @param clientId    客户端ID
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @return 登录页面或授权页面
     */
    @GetMapping("/sso/login")
    @Operation(summary = "SSO单点登录")
    public Result<Map<String, Object>> ssoLogin(
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam(value = "scope", defaultValue = "read") String scope,
            @RequestParam(value = "state", required = false) String state) {

        // 检查用户是否已登录
        if (StpUtil.isLogin()) {
            // 已登录，直接跳转到授权页面
            return authorize(clientId, redirectUri, scope, state);
        } else {
            // 未登录，返回登录页面信息
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }
    }

    /**
     * 获取授权页面信息
     *
     * @param clientId    客户端ID
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @return 授权页面信息
     */
    @GetMapping("/authorize")
    @Operation(summary = "获取授权页面信息")
    public Result<Map<String, Object>> authorize(
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam(value = "scope", defaultValue = "read") String scope,
            @RequestParam(value = "state", required = false) String state) {
        return oauthService.getAuthorizeInfo(clientId, redirectUri, scope, state);
    }

    /**
     * SSO单点注销
     *
     * @param redirectUri 注销后重定向URI
     * @return 注销结果
     */
    @PostMapping("/sso/logout")
    @Operation(summary = "SSO单点注销")
    public Result<String> ssoLogout(@RequestParam(value = "redirect_uri", required = false) String redirectUri) {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }

        return Result.success(Objects.requireNonNullElse(redirectUri, "注销成功"));
    }
}