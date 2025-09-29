package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.service.OAuth2ClientService;
import cn.flying.service.OAuth2UserInfoCacheService;
import cn.flying.service.TokenStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SSO单点登录控制器
 * 处理OAuth2授权码模式的SSO登录流程
 *
 * @author Claude Code
 * @since 2025-01-16
 */
@Slf4j
@RestController
@RequestMapping("/api/oauth2")
@Tag(name = "SSO单点登录", description = "OAuth2授权码模式的SSO单点登录接口")
public class SSOController {

    @Resource
    private OAuth2ClientService oauth2ClientService;

    @Resource
    private TokenStorageService tokenStorageService;

    @Resource
    private OAuth2UserInfoCacheService userInfoCacheService;

    @Value("${oauth2.client.redirect-uri}")
    private String redirectUri;

    /**
     * 发起OAuth2登录
     * 重定向到Identity授权页面
     *
     * @param returnUrl 登录成功后要返回的URL
     * @param response  HTTP响应
     */
    @GetMapping("/login")
    @Operation(summary = "发起SSO登录", description = "重定向到Identity授权页面进行OAuth2登录")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "重定向到授权页面")
    })
    public void login(
            @Parameter(description = "登录成功后返回的URL") @RequestParam(required = false) String returnUrl,
            HttpServletResponse response) throws IOException {

        // 生成state参数用于CSRF防护
        String state = UUID.randomUUID().toString();

        // 如果有returnUrl，将其编码到state中（简化实现，生产环境应该用更安全的方式）
        if (returnUrl != null && !returnUrl.isEmpty()) {
            state = state + ":" + java.util.Base64.getEncoder().encodeToString(returnUrl.getBytes());
        }

        // 生成授权URL
        String authorizationUrl = oauth2ClientService.getAuthorizationUrl(state);

        log.info("发起OAuth2登录，重定向到: {}", authorizationUrl);

        // 重定向到Identity授权页面
        response.sendRedirect(authorizationUrl);
    }

    /**
     * 处理OAuth2回调
     * Identity授权成功后会回调此接口，携带authorization_code
     *
     * @param code     授权码
     * @param state    状态参数（用于CSRF防护）
     * @param request  HTTP请求
     * @param response HTTP响应
     */
    @GetMapping("/callback")
    @Operation(summary = "处理OAuth2回调", description = "接收授权码并换取访问令牌")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "登录成功，重定向到应用首页"),
            @ApiResponse(responseCode = "400", description = "回调参数错误"),
            @ApiResponse(responseCode = "500", description = "Token交换失败")
    })
    public void callback(
            @Parameter(description = "授权码") @RequestParam String code,
            @Parameter(description = "状态参数") @RequestParam String state,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        log.info("收到OAuth2回调: code={}, state={}",
                code.substring(0, Math.min(8, code.length())) + "...", state);

        try {
            // TODO: 验证state参数防止CSRF攻击
            // 生产环境应该将state存储在session中进行验证

            // 用授权码换取访问令牌
            OAuth2ClientService.TokenResponse tokenResponse =
                    oauth2ClientService.exchangeCodeForToken(code, null);

            if (!tokenResponse.isSuccess()) {
                log.error("Token交换失败: {}", tokenResponse.getErrorMessage());
                response.sendRedirect("/error?message=" +
                        java.net.URLEncoder.encode("登录失败: " + tokenResponse.getErrorMessage(), StandardCharsets.UTF_8));
                return;
            }

            // 存储Token到Session
            String sessionId = tokenStorageService.storeToken(
                    response,
                    tokenResponse.getAccessToken(),
                    tokenResponse.getRefreshToken(),
                    tokenResponse.getExpiresIn(),
                    tokenResponse.getScope()
            );

            if (sessionId == null) {
                log.error("存储Token失败");
                response.sendRedirect("/error?message=" +
                        java.net.URLEncoder.encode("登录失败: 无法创建会话", StandardCharsets.UTF_8));
                return;
            }

            log.info("OAuth2登录成功: sessionId={}", sessionId);

            // 解析returnUrl从state中
            String returnUrl = "/";
            if (state.contains(":")) {
                try {
                    String encodedUrl = state.substring(state.indexOf(":") + 1);
                    returnUrl = new String(java.util.Base64.getDecoder().decode(encodedUrl));
                } catch (Exception e) {
                    log.warn("解析returnUrl失败", e);
                }
            }

            // 重定向到应用首页或指定页面
            response.sendRedirect(returnUrl);

        } catch (Exception e) {
            log.error("处理OAuth2回调异常", e);
            response.sendRedirect("/error?message=" +
                    java.net.URLEncoder.encode("登录失败: " + e.getMessage(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 用户登出
     * 清除本地Session并撤销Identity的Token
     *
     * @param request  HTTP请求
     * @param response HTTP响应
     * @return 登出结果
     */
    @PostMapping("/logout")
    @Operation(summary = "SSO登出", description = "清除本地会话并撤销Identity的访问令牌")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登出成功"),
            @ApiResponse(responseCode = "500", description = "登出失败")
    })
    public Result<String> logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            log.info("用户请求登出");

            // 获取Token信息
            TokenStorageService.TokenInfo tokenInfo = tokenStorageService.getToken(request);

            if (tokenInfo != null) {
                // 撤销access_token
                boolean revokeSuccess = oauth2ClientService.revokeToken(
                        tokenInfo.getAccessToken(), "access_token");

                if (revokeSuccess) {
                    log.info("访问令牌撤销成功");
                } else {
                    log.warn("访问令牌撤销失败");
                }

                // 清除用户信息缓存
                userInfoCacheService.evictCache(tokenInfo.getAccessToken());
            }

            // 清除本地Session
            boolean clearSuccess = tokenStorageService.clearToken(request, response);

            if (clearSuccess) {
                log.info("本地会话清除成功");
                return Result.success("登出成功");
            } else {
                log.warn("本地会话清除失败");
                return Result.error(ResultEnum.SYSTEM_ERROR, "登出失败");
            }

        } catch (Exception e) {
            log.error("登出异常", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, "登出失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前登录用户信息
     *
     * @param request HTTP请求
     * @return 用户信息
     */
    @GetMapping("/user")
    @Operation(summary = "获取当前用户", description = "获取当前登录用户的基本信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未登录")
    })
    public Result<Map<String, Object>> getCurrentUser(HttpServletRequest request) {
        try {
            // 从Session中获取Token
            TokenStorageService.TokenInfo tokenInfo = tokenStorageService.getToken(request);

            if (tokenInfo == null) {
                return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
            }

            // 如果token已过期但有refresh_token，尝试刷新
            if (tokenInfo.isExpired() && tokenInfo.getRefreshToken() != null) {
                log.info("Token已过期，尝试刷新");
                OAuth2ClientService.TokenResponse refreshResponse =
                        oauth2ClientService.refreshAccessToken(tokenInfo.getRefreshToken());

                if (refreshResponse.isSuccess()) {
                    // 更新Session中的Token
                    tokenStorageService.updateToken(
                            request,
                            refreshResponse.getAccessToken(),
                            refreshResponse.getRefreshToken(),
                            refreshResponse.getExpiresIn()
                    );
                    tokenInfo.setAccessToken(refreshResponse.getAccessToken());
                    tokenInfo.setExpired(false);
                    log.info("Token刷新成功");
                } else {
                    log.warn("Token刷新失败: {}", refreshResponse.getErrorMessage());
                    return Result.error(ResultEnum.PERMISSION_TOKEN_EXPIRED, null);
                }
            }

            // 获取用户信息（通过OAuth2UserInfoCacheService，支持缓存）
            OAuth2UserInfoCacheService.CachedUserInfo userInfo =
                    userInfoCacheService.getUserInfo(tokenInfo.getAccessToken());

            if (userInfo == null || !userInfo.isValid()) {
                String errorMsg = userInfo != null ? userInfo.getErrorMessage() : "获取用户信息失败";
                log.warn("获取用户信息失败: {}", errorMsg);
                return Result.error(ResultEnum.PERMISSION_TOKEN_INVALID, null);
            }

            // 构建返回数据
            Map<String, Object> userData = new HashMap<>();
            userData.put("userId", userInfo.getUserId());
            userData.put("username", userInfo.getUsername());
            userData.put("email", userInfo.getEmail());
            userData.put("role", userInfo.getRole());
            userData.put("scope", tokenInfo.getScope());
            userData.put("expiresIn", tokenInfo.getRemainingTime());
            userData.put("shouldRefresh", tokenInfo.shouldRefresh());

            return Result.success(userData);

        } catch (Exception e) {
            log.error("获取当前用户异常", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 刷新访问令牌
     * 前端可以在token快过期时主动调用此接口刷新
     *
     * @param request HTTP请求
     * @return 刷新结果
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新访问令牌", description = "使用refresh_token获取新的access_token")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "刷新成功"),
            @ApiResponse(responseCode = "401", description = "刷新令牌无效或已过期")
    })
    public Result<Map<String, Object>> refreshToken(HttpServletRequest request) {
        try {
            // 获取当前Token
            TokenStorageService.TokenInfo tokenInfo = tokenStorageService.getToken(request);

            if (tokenInfo == null) {
                return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
            }

            if (tokenInfo.getRefreshToken() == null || tokenInfo.getRefreshToken().isEmpty()) {
                return Result.error(ResultEnum.PERMISSION_TOKEN_INVALID, null);
            }

            // 调用Identity刷新token
            OAuth2ClientService.TokenResponse refreshResponse =
                    oauth2ClientService.refreshAccessToken(tokenInfo.getRefreshToken());

            if (!refreshResponse.isSuccess()) {
                log.warn("刷新Token失败: {}", refreshResponse.getErrorMessage());
                return Result.error(ResultEnum.PERMISSION_TOKEN_EXPIRED, null);
            }

            // 更新Session中的Token
            boolean updateSuccess = tokenStorageService.updateToken(
                    request,
                    refreshResponse.getAccessToken(),
                    refreshResponse.getRefreshToken(),
                    refreshResponse.getExpiresIn()
            );

            if (!updateSuccess) {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }

            // 返回新的token信息
            Map<String, Object> data = new HashMap<>();
            data.put("expiresIn", refreshResponse.getExpiresIn());
            data.put("scope", refreshResponse.getScope());
            data.put("message", "Token刷新成功");

            log.info("Token刷新成功");
            return Result.success(data);

        } catch (Exception e) {
            log.error("刷新Token异常", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 检查登录状态
     *
     * @param request HTTP请求
     * @return 登录状态
     */
    @GetMapping("/status")
    @Operation(summary = "检查登录状态", description = "检查当前用户是否已登录")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "检查成功")
    })
    public Result<Map<String, Object>> checkLoginStatus(HttpServletRequest request) {
        try {
            TokenStorageService.TokenInfo tokenInfo = tokenStorageService.getToken(request);

            Map<String, Object> status = new HashMap<>();

            if (tokenInfo != null && !tokenInfo.isExpired()) {
                status.put("loggedIn", true);
                status.put("expiresIn", tokenInfo.getRemainingTime());
                status.put("shouldRefresh", tokenInfo.shouldRefresh());
            } else {
                status.put("loggedIn", false);
            }

            return Result.success(status);

        } catch (Exception e) {
            log.error("检查登录状态异常", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }
}
