package cn.flying.identity.controller;

import cn.flying.identity.service.SSOService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * SSO 单点登录控制器
 * 提供完整的单点登录功能接口
 *
 * @author 王贝强
 */
@RestController
@RequestMapping("/api/sso")
@Tag(name = "SSO单点登录", description = "提供单点登录相关功能")
public class SSOController {

    @Resource
    private SSOService ssoService;

    /**
     * SSO 登录授权页面
     *
     * @param clientId    客户端ID
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @return 登录页面信息或重定向信息
     */
    @GetMapping("/authorize")
    @Operation(summary = "SSO登录授权", description = "获取SSO登录页面信息或处理已登录用户的授权")
    public Result<Map<String, Object>> authorize(
            @Parameter(description = "客户端ID") @RequestParam String clientId,
            @Parameter(description = "重定向URI") @RequestParam String redirectUri,
            @Parameter(description = "授权范围") @RequestParam(defaultValue = "read") String scope,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state) {

        return ssoService.getSSOLoginInfo(clientId, redirectUri, scope, state);
    }

    /**
     * SSO 登录处理
     *
     * @param username    用户名
     * @param password    密码
     * @param clientId    客户端ID
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @return 登录结果
     */
    @PostMapping("/login")
    @Operation(summary = "SSO登录处理", description = "处理用户登录并生成SSO Token")
    public Result<Map<String, Object>> login(
            @Parameter(description = "用户名") @RequestParam String username,
            @Parameter(description = "密码") @RequestParam String password,
            @Parameter(description = "客户端ID") @RequestParam String clientId,
            @Parameter(description = "重定向URI") @RequestParam String redirectUri,
            @Parameter(description = "授权范围") @RequestParam(defaultValue = "read") String scope,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state) {

        return ssoService.processSSOLogin(username, password, clientId, redirectUri, scope, state);
    }

    /**
     * 检查 SSO 登录状态
     *
     * @param clientId    客户端ID
     * @param redirectUri 重定向URI
     * @param scope       授权范围
     * @param state       状态参数
     * @return 登录状态信息
     */
    @GetMapping("/check")
    @Operation(summary = "检查SSO登录状态", description = "检查用户当前的SSO登录状态")
    public Result<Map<String, Object>> checkLoginStatus(
            @Parameter(description = "客户端ID") @RequestParam String clientId,
            @Parameter(description = "重定向URI") @RequestParam String redirectUri,
            @Parameter(description = "授权范围") @RequestParam(defaultValue = "read") String scope,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state) {

        return ssoService.checkSSOLoginStatus(clientId, redirectUri, scope, state);
    }

    /**
     * SSO 单点注销
     *
     * @param redirectUri 注销后重定向URI
     * @param clientId    客户端ID（可选，指定则只从该客户端注销）
     * @return 注销结果
     */
    @PostMapping("/logout")
    @Operation(summary = "SSO单点注销", description = "执行单点注销操作")
    public Result<Map<String, Object>> logout(
            @Parameter(description = "注销后重定向URI") @RequestParam(required = false) String redirectUri,
            @Parameter(description = "客户端ID") @RequestParam(required = false) String clientId) {

        return ssoService.ssoLogout(redirectUri, clientId);
    }

    /**
     * 获取 SSO 用户信息
     *
     * @param token SSO Token
     * @return 用户信息
     */
    @GetMapping("/userinfo")
    @Operation(summary = "获取SSO用户信息", description = "通过SSO Token获取用户信息")
    public Result<Map<String, Object>> getUserInfo(
            @Parameter(description = "SSO Token") @RequestParam String token) {

        return ssoService.getSSOUserInfo(token);
    }

    /**
     * 验证 SSO Token
     *
     * @param token SSO Token
     * @return 验证结果
     */
    @GetMapping("/validate")
    @Operation(summary = "验证SSO Token", description = "验证SSO Token的有效性")
    public Result<Map<String, Object>> validateToken(
            @Parameter(description = "SSO Token") @RequestParam String token) {

        return ssoService.validateSSOToken(token);
    }

    /**
     * 获取已登录的客户端列表
     *
     * @return 客户端列表
     */
    @GetMapping("/clients")
    @Operation(summary = "获取已登录客户端", description = "获取当前用户已登录的所有客户端列表")
    public Result<Map<String, Object>> getLoggedInClients() {
        return ssoService.getLoggedInClients();
    }

    /**
     * 从指定客户端注销
     *
     * @param clientId 客户端ID
     * @return 注销结果
     */
    @DeleteMapping("/clients/{clientId}")
    @Operation(summary = "从指定客户端注销", description = "从指定的客户端注销，但保持其他客户端的登录状态")
    public Result<Void> logoutFromClient(
            @Parameter(description = "客户端ID") @PathVariable String clientId) {

        return ssoService.logoutFromClient(clientId);
    }

    /**
     * SSO 回调处理（用于重定向）
     *
     * @param code     授权码
     * @param state    状态参数
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    @GetMapping("/callback")
    @Operation(summary = "SSO回调处理", description = "处理SSO登录成功后的回调")
    public void callback(
            @Parameter(description = "授权码") @RequestParam(required = false) String code,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state,
            HttpServletResponse response) throws IOException {

        // 这里可以处理回调逻辑，比如验证授权码、生成最终的访问令牌等
        // 目前简化处理，直接重定向到成功页面
        response.sendRedirect("/sso/success?code=" + code + "&state=" + state);
    }

    /**
     * SSO 错误处理
     *
     * @param error            错误类型
     * @param errorDescription 错误描述
     * @param state            状态参数
     * @return 错误信息
     */
    @GetMapping("/error")
    @Operation(summary = "SSO错误处理", description = "处理SSO过程中的错误")
    public Result<Map<String, Object>> error(
            @Parameter(description = "错误类型") @RequestParam(required = false) String error,
            @Parameter(description = "错误描述") @RequestParam(required = false) String errorDescription,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state) {

        Map<String, Object> result = Map.of(
                "error", error != null ? error : "unknown_error",
                "error_description", errorDescription != null ? errorDescription : "SSO未知错误",
                "state", state != null ? state : ""
        );

        return Result.error(ResultEnum.SSO_UNKNOWN_ERROR, result);
    }
}
