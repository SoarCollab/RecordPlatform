package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.ControllerUtils;
import cn.flying.common.util.JwtUtils;
import cn.flying.dao.vo.auth.ConfirmResetVO;
import cn.flying.dao.vo.auth.EmailRegisterVO;
import cn.flying.dao.vo.auth.EmailResetVO;
import cn.flying.dao.vo.auth.RefreshTokenVO;
import cn.flying.dao.vo.auth.SseTokenVO;
import cn.flying.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用于验证相关Controller包含用户的注册、重置密码等操作
 */
@Validated
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "登录校验相关", description = "包括用户登录、注册、验证码请求等操作。")
public class AuthorizeController {

    @Resource
    AccountService accountService;
    @Resource
    ControllerUtils utils;
    @Resource
    JwtUtils jwtUtils;

    /**
     * 请求邮件验证码（REST 新路径）。
     *
     * @param email   请求邮箱
     * @param type    验证码类型
     * @param request 当前请求
     * @return 处理结果
     */
    @PostMapping("/verification-codes")
    @Operation(summary = "请求邮件验证码（REST）")
    public Result<String> createVerificationCode(@RequestParam @Email String email,
                                                 @Schema(description = "类型 (register -> 注册 | reset -> 重置密码 | modify -> 修改密码 )")
                                                 @RequestParam @Pattern(regexp = "(register|reset|modify)") String type,
                                                 HttpServletRequest request) {
        return utils.messageHandle(() ->
                accountService.registerEmailVerifyCode(type, String.valueOf(email), request.getRemoteAddr()));
    }

    /**
     * 进行用户注册操作，需要先请求邮件验证码
     * @param vo 注册信息
     * @return 是否注册成功
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册操作")
    @OperationLog(module = "登录校验模块", operationType = "提交", description = "用户注册操作")
    public Result<String> register(@RequestBody @Valid EmailRegisterVO vo){
        return utils.messageHandle(() ->
                accountService.registerEmailAccount(vo));
    }

    /**
     * 执行密码重置确认（REST 新路径）。
     *
     * @param vo 重置确认参数
     * @return 操作结果
     */
    @PostMapping("/password-resets/confirm")
    @Operation(summary = "密码重置确认（REST）")
    public Result<String> confirmPasswordReset(@RequestBody @Valid ConfirmResetVO vo) {
        return utils.messageHandle(() -> accountService.resetConfirm(vo));
    }

    /**
     * 执行密码重置（REST 新路径）。
     *
     * @param vo 密码重置信息
     * @return 操作结果
     */
    @PutMapping("/password-resets")
    @Operation(summary = "密码重置操作（REST）")
    public Result<String> updatePasswordByReset(@RequestBody @Valid EmailResetVO vo) {
        return utils.messageHandle(() ->
                accountService.resetEmailAccountPassword(vo));
    }

    /**
     * 刷新访问令牌（REST 新路径）。
     *
     * @param request 当前请求
     * @return 新令牌信息
     */
    @PostMapping("/tokens/refresh")
    @Operation(summary = "刷新访问令牌（REST）")
    public Result<RefreshTokenVO> refreshAccessToken(HttpServletRequest request) {
        String headerToken = request.getHeader("Authorization");
        String newToken = jwtUtils.refreshJwt(headerToken);
        if (newToken == null) {
            return Result.error(cn.flying.common.constant.ResultEnum.PERMISSION_TOKEN_EXPIRED, null);
        }
        return Result.success(new RefreshTokenVO(newToken, jwtUtils.expireTime()));
    }

    /**
     * 获取 SSE 短期令牌（REST 新路径）。
     *
     * @param request 当前请求
     * @return SSE 短期令牌
     */
    @PostMapping("/tokens/sse")
    @Operation(summary = "获取SSE短期令牌（REST）")
    public Result<SseTokenVO> issueSseToken(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(Const.ATTR_USER_ID);
        Long tenantId = (Long) request.getAttribute(Const.ATTR_TENANT_ID);
        String role = (String) request.getAttribute(Const.ATTR_USER_ROLE);

        String sseToken = jwtUtils.createSseToken(userId, tenantId, role);
        return Result.success(new SseTokenVO(sseToken, Const.SSE_TOKEN_TTL));
    }

}
