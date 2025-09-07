package cn.flying.identity.controller;

import cn.flying.identity.dto.Account;
import cn.flying.identity.service.AuthService;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.platformapi.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 * 提供用户认证、注册、密码管理等RESTful API
 * 保持与原Spring Security接口的兼容性
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证管理", description = "用户认证、注册、密码管理相关接口")
public class AuthController {

    @Resource
    private AuthService authService;

    /**
     * 用户登录
     *
     * @param username 用户名或邮箱
     * @param password 密码
     * @return 登录结果，包含Token信息
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "通过用户名/邮箱和密码进行登录")
    public Result<String> login(@RequestParam String username, @RequestParam String password) {
        return authService.login(username, password);
    }

    /**
     * 用户注销
     *
     * @return 注销结果
     */
    @PostMapping("/logout")
    @Operation(summary = "用户注销", description = "注销当前登录用户")
    public Result<Void> logout() {
        return authService.logout();
    }

    /**
     * 用户注册
     *
     * @param vo 注册信息
     * @return 注册结果
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "通过邮箱验证码进行用户注册")
    public Result<Void> register(@Valid @RequestBody EmailRegisterVO vo) {
        return authService.register(vo);
    }

    /**
     * 发送邮箱验证码
     *
     * @param email 邮箱地址
     * @param type  验证码类型（register/reset）
     * @return 发送结果
     */
    @PostMapping("/verify-code")
    @Operation(summary = "发送验证码", description = "向指定邮箱发送验证码")
    public Result<Void> sendVerifyCode(@RequestParam String email, @RequestParam String type) {
        return authService.askVerifyCode(email, type);
    }

    /**
     * 重置密码确认
     *
     * @param vo 重置密码信息
     * @return 重置结果
     */
    @PostMapping("/reset-password")
    @Operation(summary = "重置密码", description = "通过邮箱验证码重置密码")
    public Result<Void> resetPassword(@Valid @RequestBody EmailResetVO vo) {
        return authService.resetConfirm(vo);
    }

    /**
     * 修改密码
     *
     * @param vo 修改密码信息
     * @return 修改结果
     */
    @PostMapping("/change-password")
    @Operation(summary = "修改密码", description = "修改当前用户密码")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordVO vo) {
        return authService.changePassword(vo);
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 用户信息
     */
    @GetMapping("/user-info")
    @Operation(summary = "获取用户信息", description = "获取当前登录用户的详细信息")
    public Result<AccountVO> getUserInfo() {
        return authService.getUserInfo();
    }

    /**
     * 根据用户名或邮箱查找用户
     *
     * @param text 用户名或邮箱
     * @return 用户实体
     */
    @GetMapping("/find-user")
    @Operation(summary = "查找用户", description = "根据用户名或邮箱查找用户")
    public Account findUser(@RequestParam String text) {
        return authService.findAccountByNameOrEmail(text);
    }

    /**
     * 检查登录状态
     *
     * @return 登录状态信息
     */
    @GetMapping("/status")
    @Operation(summary = "检查登录状态", description = "检查当前用户的登录状态")
    public Result<Object> checkLoginStatus() {
        return authService.checkLoginStatus();
    }

    /**
     * 获取Token信息
     *
     * @return Token详细信息
     */
    @GetMapping("/token-info")
    @Operation(summary = "获取Token信息", description = "获取当前用户的Token详细信息")
    public Result<Object> getTokenInfo() {
        return authService.getTokenInfo();
    }

    // 兼容原Spring Security接口的额外端点

    /**
     * 兼容原登录接口
     *
     * @param username 用户名
     * @param password 密码
     * @return 登录结果
     */
    @PostMapping("/signin")
    @Operation(summary = "登录（兼容接口）", description = "兼容原Spring Security的登录接口")
    public Result<String> signin(@RequestParam String username, @RequestParam String password) {
        return authService.login(username, password);
    }

    /**
     * 兼容原注册接口
     *
     * @param vo 注册信息
     * @return 注册结果
     */
    @PostMapping("/signup")
    @Operation(summary = "注册（兼容接口）", description = "兼容原Spring Security的注册接口")
    public Result<Void> signup(@Valid @RequestBody EmailRegisterVO vo) {
        return authService.register(vo);
    }

    /**
     * 兼容原用户信息接口
     *
     * @return 用户信息
     */
    @GetMapping("/me")
    @Operation(summary = "获取用户信息（兼容接口）", description = "兼容原Spring Security的用户信息接口")
    public Result<AccountVO> getCurrentUser() {
        return authService.getUserInfo();
    }
}