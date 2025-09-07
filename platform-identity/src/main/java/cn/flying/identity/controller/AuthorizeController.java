package cn.flying.identity.controller;

import cn.flying.identity.service.AccountService;
import cn.flying.identity.util.IpUtils;
import cn.flying.identity.vo.request.ConfirmResetVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.platformapi.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 用户认证授权控制器
 * 从 platform-backend 迁移而来，包含用户的注册、重置密码等操作
 * 适配 SA-Token 框架
 * 
 * @author 王贝强
 */
@Validated
@RestController
@RequestMapping("/api/auth")
@Tag(name = "登录校验相关", description = "包括用户登录、注册、验证码请求等操作。")
public class AuthorizeController {

    @Resource
    private AccountService accountService;

    /**
     * 请求邮件验证码
     * 
     * @param email 请求邮件
     * @param type 类型
     * @param request 请求
     * @return 是否请求成功
     */
    @GetMapping("/ask-code")
    @Operation(summary = "请求邮件验证码")
    public Result<Void> askVerifyCode(
            @RequestParam @Email String email,
            @Schema(description = "类型 (register -> 注册 | reset -> 重置密码 | modify -> 修改密码 )") 
            @RequestParam @Pattern(regexp = "(register|reset|modify)") String type,
            HttpServletRequest request) {
        
        String clientIp = IpUtils.getClientIp(request);
        return accountService.registerEmailVerifyCode(type, email, clientIp);
    }

    /**
     * 进行用户注册操作，需要先请求邮件验证码
     * 
     * @param vo 注册信息
     * @return 是否注册成功
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册操作")
    public Result<Void> register(@RequestBody @Valid EmailRegisterVO vo) {
        return accountService.registerEmailAccount(vo);
    }

    /**
     * 执行密码重置确认，检查验证码是否正确
     * 
     * @param vo 密码重置信息
     * @return 是否操作成功
     */
    @PostMapping("/reset-confirm")
    @Operation(summary = "密码重置确认")
    public Result<Void> resetConfirm(@RequestBody @Valid ConfirmResetVO vo) {
        return accountService.resetConfirm(vo);
    }

    /**
     * 执行密码重置操作
     * 
     * @param vo 密码重置信息
     * @return 是否操作成功
     */
    @PostMapping("/reset-password")
    @Operation(summary = "密码重置操作")
    public Result<Void> resetPassword(@RequestBody @Valid EmailResetVO vo) {
        return accountService.resetEmailAccountPassword(vo);
    }
}
