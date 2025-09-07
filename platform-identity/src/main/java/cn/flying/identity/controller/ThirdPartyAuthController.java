package cn.flying.identity.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.service.ThirdPartyAuthService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * 第三方认证控制器
 * 提供第三方登录相关功能
 * 
 * @author 王贝强
 */
@RestController
@RequestMapping("/api/auth/third-party")
@Tag(name = "第三方登录", description = "提供GitHub、Google、微信等第三方登录功能")
public class ThirdPartyAuthController {

    @Autowired
    private ThirdPartyAuthService thirdPartyAuthService;

    /**
     * 获取支持的第三方登录提供商列表
     * 
     * @return 提供商列表
     */
    @GetMapping("/providers")
    @Operation(summary = "获取支持的第三方登录提供商", description = "获取系统支持的所有第三方登录提供商列表")
    public Result<Map<String, Object>> getSupportedProviders() {
        return thirdPartyAuthService.getSupportedProviders();
    }

    /**
     * 获取第三方登录授权URL
     * 
     * @param provider 第三方提供商
     * @param redirectUri 回调地址
     * @param state 状态参数
     * @return 授权URL
     */
    @GetMapping("/{provider}/authorize")
    @Operation(summary = "获取第三方登录授权URL", description = "获取指定第三方提供商的登录授权URL")
    public Result<String> getAuthorizationUrl(
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Parameter(description = "回调地址") @RequestParam String redirectUri,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state) {
        
        return thirdPartyAuthService.getAuthorizationUrl(provider, redirectUri, state);
    }

    /**
     * 第三方登录授权重定向
     * 
     * @param provider 第三方提供商
     * @param redirectUri 回调地址
     * @param state 状态参数
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    @GetMapping("/{provider}/login")
    @Operation(summary = "第三方登录授权重定向", description = "重定向到第三方登录授权页面")
    public void redirectToAuthorize(
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Parameter(description = "回调地址") @RequestParam String redirectUri,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state,
            HttpServletResponse response) throws IOException {
        
        Result<String> result = thirdPartyAuthService.getAuthorizationUrl(provider, redirectUri, state);
        if (result.getCode() == ResultEnum.SUCCESS.getCode() && result.getData() != null) {
            response.sendRedirect(result.getData());
        } else {
            response.sendError(400, "获取授权URL失败");
        }
    }

    /**
     * 处理第三方登录回调
     * 
     * @param provider 第三方提供商
     * @param code 授权码
     * @param state 状态参数
     * @param error 错误信息
     * @return 登录结果
     */
    @GetMapping("/{provider}/callback")
    @Operation(summary = "处理第三方登录回调", description = "处理第三方登录授权后的回调")
    public Result<Map<String, Object>> handleCallback(
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Parameter(description = "授权码") @RequestParam(required = false) String code,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state,
            @Parameter(description = "错误信息") @RequestParam(required = false) String error) {
        
        if (error != null) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }

        if (code == null) {
            return Result.error(ResultEnum.PARAM_IS_INVALID, null);
        }
        
        return thirdPartyAuthService.handleCallback(provider, code, state);
    }

    /**
     * 绑定第三方账号
     * 
     * @param provider 第三方提供商
     * @param code 授权码
     * @return 绑定结果
     */
    @PostMapping("/{provider}/bind")
    @Operation(summary = "绑定第三方账号", description = "将第三方账号绑定到当前用户")
    public Result<Void> bindThirdPartyAccount(
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Parameter(description = "授权码") @RequestParam String code) {
        
        if (!StpUtil.isLogin()) {
            return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
        }
        
        Long userId = StpUtil.getLoginIdAsLong();
        return thirdPartyAuthService.bindThirdPartyAccount(userId, provider, code);
    }

    /**
     * 解绑第三方账号
     * 
     * @param provider 第三方提供商
     * @return 解绑结果
     */
    @DeleteMapping("/{provider}/unbind")
    @Operation(summary = "解绑第三方账号", description = "解除第三方账号与当前用户的绑定关系")
    public Result<Void> unbindThirdPartyAccount(
            @Parameter(description = "第三方提供商") @PathVariable String provider) {
        
        if (!StpUtil.isLogin()) {
            return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
        }

        Long userId = StpUtil.getLoginIdAsLong();
        return thirdPartyAuthService.unbindThirdPartyAccount(userId, provider);
    }

    /**
     * 获取用户绑定的第三方账号列表
     *
     * @return 第三方账号列表
     */
    @GetMapping("/bindings")
    @Operation(summary = "获取用户绑定的第三方账号", description = "获取当前用户绑定的所有第三方账号列表")
    public Result<Map<String, Object>> getUserThirdPartyAccounts() {
        if (!StpUtil.isLogin()) {
            return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
        }
        
        Long userId = StpUtil.getLoginIdAsLong();
        return thirdPartyAuthService.getUserThirdPartyAccounts(userId);
    }

    /**
     * 验证第三方访问令牌
     * 
     * @param provider 第三方提供商
     * @param accessToken 访问令牌
     * @return 验证结果
     */
    @PostMapping("/{provider}/validate")
    @Operation(summary = "验证第三方访问令牌", description = "验证第三方访问令牌的有效性")
    public Result<Boolean> validateThirdPartyToken(
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Parameter(description = "访问令牌") @RequestParam String accessToken) {
        
        return thirdPartyAuthService.validateThirdPartyToken(provider, accessToken);
    }

    /**
     * 获取第三方用户信息
     * 
     * @param provider 第三方提供商
     * @param accessToken 访问令牌
     * @return 用户信息
     */
    @GetMapping("/{provider}/userinfo")
    @Operation(summary = "获取第三方用户信息", description = "通过访问令牌获取第三方用户信息")
    public Result<Map<String, Object>> getThirdPartyUserInfo(
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Parameter(description = "访问令牌") @RequestParam String accessToken) {
        
        return thirdPartyAuthService.getThirdPartyUserInfo(provider, accessToken);
    }

    /**
     * 刷新第三方访问令牌
     * 
     * @param provider 第三方提供商
     * @return 刷新结果
     */
    @PostMapping("/{provider}/refresh")
    @Operation(summary = "刷新第三方访问令牌", description = "刷新指定第三方提供商的访问令牌")
    public Result<Map<String, Object>> refreshThirdPartyToken(
            @Parameter(description = "第三方提供商") @PathVariable String provider) {
        
        if (!StpUtil.isLogin()) {
            return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
        }
        
        Long userId = StpUtil.getLoginIdAsLong();
        return thirdPartyAuthService.refreshThirdPartyToken(userId, provider);
    }
}
