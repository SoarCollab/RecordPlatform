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
import org.springframework.web.bind.annotation.*;

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
     * 请求邮件验证码
     * @param email 请求邮件
     * @param type 类型
     * @param request 请求
     * @return 是否请求成功
     */
    @GetMapping("/ask-code")
    @Operation(summary = "请求邮件验证码")
    //@OperationLog(module = "登录校验模块", operationType = "查询", description = "请求邮件验证码")
    public Result<String> askVerifyCode(@RequestParam @Email String email,
                                        @Schema(description = "类型 (register -> 注册 | reset -> 重置密码 | modify -> 修改密码 )") @RequestParam @Pattern(regexp = "(register|reset|modify)")  String type,
                                      HttpServletRequest request){
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
     * 执行密码重置确认，检查验证码是否正确
     * @param vo 密码重置信息
     * @return 是否操作成功
     */
    @PostMapping("/reset-confirm")
    @Operation(summary = "密码重置确认")
    public Result<String> resetConfirm(@RequestBody @Valid ConfirmResetVO vo){
        return utils.messageHandle(() -> accountService.resetConfirm(vo));
    }

    /**
     * 执行密码重置操作
     * @param vo 密码重置信息
     * @return 是否操作成功
     */
    @PostMapping("/reset-password")
    @Operation(summary = "密码重置操作")
    @OperationLog(module = "登录校验模块", operationType = "提交", description = "密码重置操作")
    public Result<String> resetPassword(@RequestBody @Valid EmailResetVO vo){
        return utils.messageHandle(() ->
                accountService.resetEmailAccountPassword(vo));
    }

    /**
     * 刷新访问令牌
     * 基于当前有效的令牌生成新的令牌，同时使旧令牌失效
     * @param request HTTP请求（包含Authorization头）
     * @return 新的令牌信息
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新访问令牌", description = "基于当前有效令牌生成新令牌，旧令牌将失效")
    public Result<RefreshTokenVO> refreshToken(HttpServletRequest request) {
        String headerToken = request.getHeader("Authorization");
        String newToken = jwtUtils.refreshJwt(headerToken);
        if (newToken == null) {
            return Result.failure(401, "令牌刷新失败，请重新登录");
        }
        return Result.success(new RefreshTokenVO(newToken, jwtUtils.expireTime()));
    }

    /**
     * 获取 SSE 短期令牌
     * 用于建立 SSE 连接，有效期 30 秒，一次性使用
     * @param request HTTP 请求（包含用户认证信息）
     * @return SSE 短期令牌
     */
    @PostMapping("/sse-token")
    @Operation(summary = "获取SSE短期令牌", description = "生成用于建立SSE连接的短期一次性令牌，有效期30秒")
    public Result<SseTokenVO> getSseToken(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(Const.ATTR_USER_ID);
        Long tenantId = (Long) request.getAttribute(Const.ATTR_TENANT_ID);
        String role = (String) request.getAttribute(Const.ATTR_USER_ROLE);

        String sseToken = jwtUtils.createSseToken(userId, tenantId, role);
        return Result.success(new SseTokenVO(sseToken, Const.SSE_TOKEN_TTL));
    }

}
