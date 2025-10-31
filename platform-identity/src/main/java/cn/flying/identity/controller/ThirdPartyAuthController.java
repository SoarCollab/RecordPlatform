package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.BindAccountRequest;
import cn.flying.identity.dto.CallbackRequest;
import cn.flying.identity.dto.ValidateTokenRequest;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.service.ThirdPartyAuthService;
import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * 第三方认证控制器
 * 提供符合RESTful规范的第三方登录相关功能
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@RestController
@RequestMapping("/api/auth/third-party")
@Tag(name = "第三方登录", description = "提供GitHub、Google、微信等第三方登录功能")
@Validated
public class ThirdPartyAuthController {

    @Resource
    private ThirdPartyAuthService thirdPartyAuthService;

    /**
     * 获取提供商列表
     * GET /api/auth/third-party/providers - 获取支持的第三方登录提供商
     */
    @GetMapping("/providers")
    @Operation(summary = "获取第三方登录提供商", description = "获取系统支持的所有第三方登录提供商列表")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getProviders() {
        Map<String, Object> data = thirdPartyAuthService.getSupportedProviders();
        return ResponseEntity.ok(RestResponse.ok("获取成功", data));
    }

    /**
     * 获取授权URL
     * GET /api/auth/third-party/providers/{provider}/authorization-url - 获取第三方登录授权URL
     */
    @GetMapping("/providers/{provider}/authorization-url")
    @Operation(summary = "获取第三方登录授权URL", description = "获取指定第三方提供商的登录授权URL")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "404", description = "提供商不存在")
    })
    public ResponseEntity<RestResponse<String>> getAuthorizationUrl(
            @Parameter(description = "第三方提供商") @PathVariable @NotBlank(message = "provider不能为空") String provider,
            @Parameter(description = "回调地址") @RequestParam @NotBlank(message = "redirectUri不能为空") String redirectUri,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state) {

        String url = thirdPartyAuthService.getAuthorizationUrl(provider, redirectUri, state);
        return ResponseEntity.ok(RestResponse.ok("获取成功", url));
    }

    /**
     * 授权重定向
     * GET /api/auth/third-party/providers/{provider}/redirect - 重定向到第三方登录页
     */
    @GetMapping("/providers/{provider}/redirect")
    @Operation(summary = "第三方登录重定向", description = "重定向到第三方登录授权页面")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "重定向成功"),
            @ApiResponse(responseCode = "400", description = "获取授权URL失败")
    })
    public void redirectToProvider(
            @Parameter(description = "第三方提供商") @PathVariable @NotBlank(message = "provider不能为空") String provider,
            @Parameter(description = "回调地址") @RequestParam @NotBlank(message = "redirectUri不能为空") String redirectUri,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state,
            HttpServletResponse response) throws IOException {

        String url = thirdPartyAuthService.getAuthorizationUrl(provider, redirectUri, state);
        response.sendRedirect(url);
    }

    /**
     * 处理回调
     * POST /api/auth/third-party/providers/{provider}/callback - 处理第三方登录回调
     */
    @PostMapping("/providers/{provider}/callback")
    @Operation(summary = "处理第三方登录回调", description = "处理第三方登录授权后的回调")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功"),
            @ApiResponse(responseCode = "400", description = "参数无效或登录失败"),
            @ApiResponse(responseCode = "401", description = "认证失败")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> handleCallback(
            @Parameter(description = "第三方提供商") @PathVariable @NotBlank(message = "provider不能为空") String provider,
            @Valid @RequestBody CallbackRequest request) {

        if (request.getError() != null) {
            throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "第三方登录失败: " + request.getError());
        }

        if (request.getCode() == null) {
            throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "授权码不能为空");
        }

        Map<String, Object> data = thirdPartyAuthService.handleCallback(
                provider, request.getCode(), request.getState());
        return ResponseEntity.ok(RestResponse.ok("登录流程完成", data));
    }

    /**
     * 绑定账号
     * POST /api/auth/third-party/providers/{provider}/bindings - 绑定第三方账号
     */
    @PostMapping("/providers/{provider}/bindings")
    @Operation(summary = "绑定第三方账号", description = "将第三方账号绑定到当前用户")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "绑定成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "409", description = "账号已绑定")
    })
    @SaCheckLogin
    public ResponseEntity<RestResponse<Void>> bindAccount(
            @Parameter(description = "第三方提供商") @PathVariable @NotBlank(message = "provider不能为空") String provider,
            @Valid @RequestBody BindAccountRequest request) {

        Long userId = StpUtil.getLoginIdAsLong();
        thirdPartyAuthService.bindThirdPartyAccount(userId, provider, request.getCode());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestResponse.created(null));
    }

    /**
     * 解绑账号
     * DELETE /api/auth/third-party/providers/{provider}/bindings - 解绑第三方账号
     */
    @DeleteMapping("/providers/{provider}/bindings")
    @Operation(summary = "解绑第三方账号", description = "解除第三方账号与当前用户的绑定关系")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "解绑成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "绑定关系不存在")
    })
    @SaCheckLogin
    public ResponseEntity<Void> unbindAccount(
            @Parameter(description = "第三方提供商") @PathVariable @NotBlank(message = "provider不能为空") String provider) {

        Long userId = StpUtil.getLoginIdAsLong();
        thirdPartyAuthService.unbindThirdPartyAccount(userId, provider);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取绑定列表
     * GET /api/auth/third-party/bindings - 获取用户绑定的第三方账号
     */
    @GetMapping("/bindings")
    @Operation(summary = "获取绑定的第三方账号", description = "获取当前用户绑定的所有第三方账号列表")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    @SaCheckLogin
    public ResponseEntity<RestResponse<Map<String, Object>>> getBindings() {
        Long userId = StpUtil.getLoginIdAsLong();
        Map<String, Object> bindings = thirdPartyAuthService.getUserThirdPartyAccounts(userId);
        return ResponseEntity.ok(RestResponse.ok("获取成功", bindings));
    }

    /**
     * 验证Token
     * POST /api/auth/third-party/providers/{provider}/tokens/validate - 验证第三方访问令牌
     */
    @PostMapping("/providers/{provider}/tokens/validate")
    @Operation(summary = "验证第三方访问令牌", description = "验证第三方访问令牌的有效性")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "验证结果"),
            @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<RestResponse<Boolean>> validateToken(
            @Parameter(description = "第三方提供商") @PathVariable @NotBlank(message = "provider不能为空") String provider,
            @Valid @RequestBody ValidateTokenRequest request) {

        boolean valid = thirdPartyAuthService.validateThirdPartyToken(
                provider, request.getAccessToken());
        return ResponseEntity.ok(RestResponse.ok("验证完成", valid));
    }

    /**
     * 获取用户信息
     * GET /api/auth/third-party/providers/{provider}/users/me - 获取第三方用户信息
     */
    @GetMapping("/providers/{provider}/users/me")
    @Operation(summary = "获取第三方用户信息", description = "通过访问令牌获取第三方用户信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "令牌无效")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserInfo(
            @Parameter(description = "第三方提供商") @PathVariable @NotBlank(message = "provider不能为空") String provider,
            @Parameter(description = "访问令牌") @RequestParam @NotBlank(message = "accessToken不能为空") String accessToken) {

        Map<String, Object> data = thirdPartyAuthService.getThirdPartyUserInfo(
                provider, accessToken);
        return ResponseEntity.ok(RestResponse.ok("获取成功", data));
    }

    /**
     * 刷新Token
     * POST /api/auth/third-party/providers/{provider}/tokens/refresh - 刷新第三方访问令牌
     */
    @PostMapping("/providers/{provider}/tokens/refresh")
    @Operation(summary = "刷新第三方访问令牌", description = "刷新指定第三方提供商的访问令牌")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "刷新成功"),
            @ApiResponse(responseCode = "401", description = "未认证或刷新失败")
    })
    @SaCheckLogin
    public ResponseEntity<RestResponse<Map<String, Object>>> refreshToken(
            @Parameter(description = "第三方提供商") @PathVariable @NotBlank(message = "provider不能为空") String provider) {

        Long userId = StpUtil.getLoginIdAsLong();
        Map<String, Object> data = thirdPartyAuthService.refreshThirdPartyToken(userId, provider);
        return ResponseEntity.ok(RestResponse.ok("刷新成功", data));
    }
}
