package cn.flying.identity.controller;

import cn.flying.identity.dto.LoginRequest;
import cn.flying.identity.dto.VerificationCodeRequest;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.service.AuthService;
import cn.flying.identity.util.InputValidator;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.LoginStatusVO;
import cn.flying.identity.vo.RestResponse;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * 认证控制器
 * 提供符合RESTful规范的用户认证、注册、密码管理API
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证管理", description = "用户认证、注册、密码管理相关接口")
@Validated
public class AuthController {

    @Resource
    private AuthService authService;

    /**
     * 创建会话（用户登录）
     * POST /api/auth/sessions - 创建新的认证会话
     */
    @PostMapping("/sessions")
    @Operation(summary = "用户登录", description = "通过用户名/邮箱和密码创建认证会话")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功"),
            @ApiResponse(responseCode = "401", description = "用户名或密码错误"),
            @ApiResponse(responseCode = "403", description = "账号被禁用"),
            @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<RestResponse<String>> createSession(@Valid @RequestBody LoginRequest request) {
        if (request.getUsername().length() > 100 || request.getPassword().length() > 128) {
            throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "用户名或密码长度超出限制");
        }

        String token = authService.login(request.getUsername(), request.getPassword());
        RestResponse<String> response = RestResponse.ok("登录成功", token);
        return ResponseEntity.ok(response);
    }

    /**
     * 销毁会话（用户登出）
     * DELETE /api/auth/sessions/current - 销毁当前认证会话
     */
    @DeleteMapping("/sessions/current")
    @Operation(summary = "用户登出", description = "销毁当前用户的认证会话")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "登出成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<Void> destroySession() {
        authService.logout();
        return ResponseEntity.noContent().build();
    }

    /**
     * 创建用户（用户注册）
     * POST /api/auth/users - 创建新用户账号
     */
    @PostMapping("/users")
    @Operation(summary = "用户注册", description = "通过邮箱验证码创建新用户")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "注册成功"),
            @ApiResponse(responseCode = "400", description = "参数无效或验证码错误"),
            @ApiResponse(responseCode = "409", description = "用户已存在")
    })
    public ResponseEntity<RestResponse<Void>> createUser(@Valid @RequestBody EmailRegisterVO vo) {
        authService.register(vo);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestResponse.created(null));
    }

    /**
     * 发送验证码
     * POST /api/auth/verification-codes - 发送验证码
     */
    @PostMapping("/verification-codes")
    @Operation(summary = "发送验证码", description = "向指定邮箱发送验证码")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "验证码已发送"),
            @ApiResponse(responseCode = "400", description = "邮箱格式错误"),
            @ApiResponse(responseCode = "429", description = "请求过于频繁")
    })
    public ResponseEntity<RestResponse<Void>> sendVerificationCode(@Valid @RequestBody VerificationCodeRequest request) {
        // 验证邮箱格式
        if (!InputValidator.isValidEmail(request.getEmail())) {
            throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "邮箱格式不正确");
        }

        // 验证type参数
        if (!"register".equals(request.getType()) && !"reset".equals(request.getType())) {
            throw new BusinessException(ResultEnum.PARAM_IS_INVALID, "验证码类型无效");
        }

        authService.askVerifyCode(request.getEmail(), request.getType());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(RestResponse.accepted());
    }

    /**
     * 重置密码
     * PUT /api/auth/passwords/reset - 重置密码
     */
    @PutMapping("/passwords/reset")
    @Operation(summary = "重置密码", description = "通过邮箱验证码重置密码")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "密码重置成功"),
            @ApiResponse(responseCode = "400", description = "参数无效或验证码错误"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    public ResponseEntity<RestResponse<Void>> resetPassword(@Valid @RequestBody EmailResetVO vo) {
        authService.resetConfirm(vo);
        return ResponseEntity.ok(RestResponse.ok("密码重置成功", null));
    }

    /**
     * 修改当前用户密码
     * PUT /api/auth/passwords/current - 修改当前用户密码
     */
    @PutMapping("/passwords/current")
    @Operation(summary = "修改密码", description = "修改当前登录用户的密码")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "密码修改成功"),
            @ApiResponse(responseCode = "400", description = "原密码错误"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordVO vo) {
        authService.changePassword(vo);
        return ResponseEntity.ok(RestResponse.ok("密码修改成功", null));
    }

    /**
     * 获取当前用户信息
     * GET /api/auth/me - 获取当前登录用户信息
     */
    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户的详细信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<AccountVO>> getCurrentUser() {
        AccountVO account = authService.getUserInfo();
        return ResponseEntity.ok(RestResponse.ok("获取成功", account));
    }

    /**
     * 查找用户
     * GET /api/auth/users/search - 搜索用户（管理员功能）
     */
    @GetMapping("/users/search")
    @Operation(summary = "搜索用户", description = "根据用户名或邮箱搜索用户（需要管理员权限）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查找成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "用户未找到")
    })
    public ResponseEntity<RestResponse<AccountVO>> searchUser(@RequestParam @NotBlank(message = "查询条件不能为空") String query) {
        AccountVO accountVO = authService.findUserWithMasking(query);
        return ResponseEntity.ok(RestResponse.ok("查找成功", accountVO));
    }

    /**
     * 获取会话状态
     * GET /api/auth/sessions/status - 检查当前会话状态
     */
    @GetMapping("/sessions/status")
    @Operation(summary = "检查会话状态", description = "检查当前用户的认证会话状态")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "会话有效"),
            @ApiResponse(responseCode = "401", description = "会话无效或已过期")
    })
    public ResponseEntity<RestResponse<LoginStatusVO>> getSessionStatus() {
        LoginStatusVO status = authService.checkLoginStatus();
        return ResponseEntity.ok(RestResponse.ok("会话状态获取成功", status));
    }

    /**
     * 获取令牌信息
     * GET /api/auth/tokens/info - 获取当前令牌信息
     */
    @GetMapping("/tokens/info")
    @Operation(summary = "获取令牌信息", description = "获取当前用户的认证令牌详细信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getTokenInfo() {
        Map<String, Object> tokenInfo = authService.getTokenInfo();
        return ResponseEntity.ok(RestResponse.ok("获取成功", tokenInfo));
    }
}
