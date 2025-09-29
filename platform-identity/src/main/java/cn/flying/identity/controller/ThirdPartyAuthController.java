package cn.flying.identity.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.BindAccountRequest;
import cn.flying.identity.dto.CallbackRequest;
import cn.flying.identity.dto.ValidateTokenRequest;
import cn.flying.identity.service.ThirdPartyAuthService;
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
 * 第三方认证控制器
 * 提供符合RESTful规范的第三方登录相关功能
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@RestController
@RequestMapping("/api/auth/third-party")
@Tag(name = "第三方登录", description = "提供GitHub、Google、微信等第三方登录功能")
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
        Result<Map<String, Object>> result = thirdPartyAuthService.getSupportedProviders();
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
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
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Parameter(description = "回调地址") @RequestParam String redirectUri,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state) {

        Result<String> result = thirdPartyAuthService.getAuthorizationUrl(provider, redirectUri, state);
        RestResponse<String> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
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
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Parameter(description = "回调地址") @RequestParam String redirectUri,
            @Parameter(description = "状态参数") @RequestParam(required = false) String state,
            HttpServletResponse response) throws IOException {

        Result<String> result = thirdPartyAuthService.getAuthorizationUrl(provider, redirectUri, state);
        if (result.isSuccess() && result.getData() != null) {
            response.sendRedirect(result.getData());
        } else {
            response.sendError(400, "获取授权URL失败");
        }
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
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Valid @RequestBody CallbackRequest request) {

        if (request.getError() != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(RestResponse.badRequest(ResultEnum.PARAM_IS_INVALID.getCode(),
                            "第三方登录失败: " + request.getError()));
        }

        if (request.getCode() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(RestResponse.badRequest(ResultEnum.PARAM_IS_INVALID.getCode(),
                            "授权码不能为空"));
        }

        Result<Map<String, Object>> result = thirdPartyAuthService.handleCallback(
                provider, request.getCode(), request.getState());
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
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
    public ResponseEntity<RestResponse<Void>> bindAccount(
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Valid @RequestBody BindAccountRequest request) {

        if (!StpUtil.isLogin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(RestResponse.unauthorized(ResultEnum.USER_NOT_LOGGED_IN.getCode(),
                            "用户未登录"));
        }

        Long userId = StpUtil.getLoginIdAsLong();
        Result<Void> result = thirdPartyAuthService.bindThirdPartyAccount(
                userId, provider, request.getCode());

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(RestResponse.created(null));
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
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
    public ResponseEntity<Void> unbindAccount(
            @Parameter(description = "第三方提供商") @PathVariable String provider) {

        if (!StpUtil.isLogin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Long userId = StpUtil.getLoginIdAsLong();
        Result<Void> result = thirdPartyAuthService.unbindThirdPartyAccount(userId, provider);

        if (result.isSuccess()) {
            return ResponseEntity.noContent().build();
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).build();
        }
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
    public ResponseEntity<RestResponse<Map<String, Object>>> getBindings() {
        if (!StpUtil.isLogin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(RestResponse.unauthorized(ResultEnum.USER_NOT_LOGGED_IN.getCode(),
                            "用户未登录"));
        }

        Long userId = StpUtil.getLoginIdAsLong();
        Result<Map<String, Object>> result = thirdPartyAuthService.getUserThirdPartyAccounts(userId);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
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
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Valid @RequestBody ValidateTokenRequest request) {

        Result<Boolean> result = thirdPartyAuthService.validateThirdPartyToken(
                provider, request.getAccessToken());
        RestResponse<Boolean> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
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
            @Parameter(description = "第三方提供商") @PathVariable String provider,
            @Parameter(description = "访问令牌") @RequestParam String accessToken) {

        Result<Map<String, Object>> result = thirdPartyAuthService.getThirdPartyUserInfo(
                provider, accessToken);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
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
    public ResponseEntity<RestResponse<Map<String, Object>>> refreshToken(
            @Parameter(description = "第三方提供商") @PathVariable String provider) {

        if (!StpUtil.isLogin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(RestResponse.unauthorized(ResultEnum.USER_NOT_LOGGED_IN.getCode(),
                            "用户未登录"));
        }

        Long userId = StpUtil.getLoginIdAsLong();
        Result<Map<String, Object>> result = thirdPartyAuthService.refreshThirdPartyToken(userId, provider);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
