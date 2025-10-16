package cn.flying.monitor.server.controller;

import cn.flying.monitor.server.entity.RestBean;
import cn.flying.monitor.server.entity.vo.request.ConfirmResetVO;
import cn.flying.monitor.server.entity.vo.request.EmailResetVO;
import cn.flying.monitor.server.service.AccountService;
import cn.flying.monitor.server.service.OAuthTokenService;
import cn.flying.monitor.server.utils.JwtUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.function.Supplier;

/**
 * 用于验证相关Controller包含用户的注册、重置密码等操作
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/auth")
@Tag(name = "登录校验相关", description = "包括用户登录、注册、验证码请求等操作。")
public class AuthorizeController {

    @Resource
    AccountService accountService;

    @Resource
    OAuthTokenService oAuthTokenService;

    @Resource
    JwtUtils jwtUtils;

    /**
     * 请求邮件验证码
     *
     * @param email   请求邮件
     * @param type    类型
     * @param request 请求
     * @return 是否请求成功
     */
    @GetMapping("/ask-code")
    @Operation(summary = "请求邮件验证码")
    public RestBean<Void> askVerifyCode(@RequestParam @Email String email,
                                        @RequestParam @Pattern(regexp = "(reset|modify)") String type,
                                        HttpServletRequest request) {
        return this.messageHandle(() ->
                accountService.registerEmailVerifyCode(type, String.valueOf(email), request.getRemoteAddr()));
    }

    /**
     * 针对于返回值为String作为错误信息的方法进行统一处理
     *
     * @param action 具体操作
     * @param <T>    响应结果类型
     * @return 响应结果
     */
    private <T> RestBean<T> messageHandle(Supplier<String> action) {
        String message = action.get();
        if (message == null)
            return RestBean.success();
        else
            return RestBean.failure(400, message);
    }

    /**
     * 执行密码重置确认，检查验证码是否正确
     *
     * @param vo 密码重置信息
     * @return 是否操作成功
     */
    @PostMapping("/reset-confirm")
    @Operation(summary = "密码重置确认")
    public RestBean<Void> resetConfirm(@RequestBody @Valid ConfirmResetVO vo) {
        return this.messageHandle(() -> accountService.resetConfirm(vo));
    }

    /**
     * 执行密码重置操作
     *
     * @param vo 密码重置信息
     * @return 是否操作成功
     */
    @PostMapping("/reset-password")
    @Operation(summary = "密码重置操作")
    public RestBean<Void> resetPassword(@RequestBody @Valid EmailResetVO vo) {
        return this.messageHandle(() ->
                accountService.resetEmailAccountPassword(vo));
    }

    /**
     * 用户登出
     * 支持本地JWT登出和OAuth2登出
     *
     * @param request HTTP请求对象
     * @return 登出结果
     */
    @DeleteMapping("/logout")
    @Operation(summary = "用户登出")
    public RestBean<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        // 获取用户ID和认证类型（从session中）
        Integer userId = null;
        String authType = null;

        if (session != null) {
            userId = (Integer) session.getAttribute("userId");
            authType = (String) session.getAttribute("authType");
        }

        try {
            // 1. 清除OAuth token（如果是OAuth登录）
            if ("oauth".equals(authType) && userId != null) {
                oAuthTokenService.removeToken(userId);
                log.info("OAuth token已清除，userId={}", userId);
            }

            // 2. 清除本地JWT（如果使用JWT）
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                boolean invalidated = jwtUtils.invalidateJwt(authorization);
                if (invalidated) {
                    log.info("JWT token已失效");
                }
            }

            // 3. 清除session
            if (session != null) {
                session.invalidate();
                log.info("Session已清除");
            }

            return RestBean.success();

        } catch (Exception e) {
            log.error("登出过程中发生错误", e);
            return RestBean.failure(500, "登出失败：" + e.getMessage());
        }
    }
}
