package cn.flying.monitor.server.controller;

import cn.flying.monitor.common.entity.Result;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/ask-code")
    @Operation(summary = "请求邮件验证码")
    public ResponseEntity<Result<Void>> askVerifyCode(
            @RequestParam @Email String email,
            @RequestParam @Pattern(regexp = "(reset|modify)") String type,
            HttpServletRequest request) {
        return this.messageHandle(() ->
                accountService.registerEmailVerifyCode(type, String.valueOf(email), request.getRemoteAddr()),
                HttpStatus.OK,
                "验证码已发送");
    }

    private <T> ResponseEntity<Result<T>> messageHandle(Supplier<String> action,
                                                        HttpStatus successStatus,
                                                        String successMessage) {
        String message = action.get();
        if (message == null) {
            return ResponseEntity.status(successStatus)
                    .body(Result.success(null, successMessage));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(message));
    }

    @PostMapping("/reset-confirm")
    @Operation(summary = "密码重置确认")
    public ResponseEntity<Result<Void>> resetConfirm(@RequestBody @Valid ConfirmResetVO vo) {
        return this.messageHandle(() -> accountService.resetConfirm(vo),
                HttpStatus.OK,
                "验证成功");
    }

    @PostMapping("/reset-password")
    @Operation(summary = "密码重置操作")
    public ResponseEntity<Result<Void>> resetPassword(@RequestBody @Valid EmailResetVO vo) {
        return this.messageHandle(() -> accountService.resetEmailAccountPassword(vo),
                HttpStatus.OK,
                "密码重置成功");
    }

    @DeleteMapping("/logout")
    @Operation(summary = "用户登出")
    public ResponseEntity<Result<Void>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        Integer userId = null;
        String authType = null;

        if (session != null) {
            userId = (Integer) session.getAttribute("userId");
            authType = (String) session.getAttribute("authType");
        }

        try {
            if ("oauth".equals(authType) && userId != null) {
                oAuthTokenService.removeToken(userId);
                log.info("OAuth token已清除，userId={}", userId);
            }

            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                boolean invalidated = jwtUtils.invalidateJwt(authorization);
                if (invalidated) {
                    log.info("JWT token已失效");
                }
            }

            if (session != null) {
                session.invalidate();
                log.info("Session已清除");
            }

            return ResponseEntity.ok(Result.success((Void) null, "退出登录成功"));

        } catch (Exception e) {
            log.error("登出过程中发生错误", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.error("登出失败：" + e.getMessage()));
        }
    }
}
