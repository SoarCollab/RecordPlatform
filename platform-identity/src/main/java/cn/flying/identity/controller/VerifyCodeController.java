package cn.flying.identity.controller;

import cn.flying.identity.service.VerifyCodeService;
import cn.flying.identity.util.IdUtils;
import cn.flying.identity.util.IpUtils;
import cn.flying.platformapi.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 验证码控制器
 * 提供邮件验证码、短信验证码、图形验证码等功能
 *
 * @author 王贝强
 */
@RestController
@RequestMapping("/api/verify")
@Tag(name = "验证码管理", description = "提供各种类型的验证码服务")
public class VerifyCodeController {

    @Resource
    private VerifyCodeService verifyCodeService;

    /**
     * 发送邮件验证码
     *
     * @param email   邮箱地址
     * @param type    验证码类型
     * @param request HTTP请求
     * @return 发送结果
     */
    @PostMapping("/email/send")
    @Operation(summary = "发送邮件验证码", description = "向指定邮箱发送验证码")
    public Result<Void> sendEmailVerifyCode(
            @Parameter(description = "邮箱地址") @RequestParam @Email String email,
            @Parameter(description = "验证码类型") @RequestParam @Pattern(regexp = "(register|reset|modify|login)") String type,
            HttpServletRequest request) {

        String clientIp = IpUtils.getClientIp(request);
        return verifyCodeService.sendEmailVerifyCode(email, type, clientIp);
    }

    /**
     * 验证邮件验证码
     *
     * @param email 邮箱地址
     * @param code  验证码
     * @param type  验证码类型
     * @return 验证结果
     */
    @PostMapping("/email/verify")
    @Operation(summary = "验证邮件验证码", description = "验证邮件验证码是否正确")
    public Result<Boolean> verifyEmailCode(
            @Parameter(description = "邮箱地址") @RequestParam @Email String email,
            @Parameter(description = "验证码") @RequestParam String code,
            @Parameter(description = "验证码类型") @RequestParam @Pattern(regexp = "(register|reset|modify|login)") String type) {

        return verifyCodeService.verifyEmailCode(email, code, type);
    }

    /**
     * 发送短信验证码
     *
     * @param phone   手机号码
     * @param type    验证码类型
     * @param request HTTP请求
     * @return 发送结果
     */
    @PostMapping("/sms/send")
    @Operation(summary = "发送短信验证码", description = "向指定手机号发送验证码")
    public Result<Void> sendSmsVerifyCode(
            @Parameter(description = "手机号码") @RequestParam @Pattern(regexp = "^1[3-9]\\d{9}$") String phone,
            @Parameter(description = "验证码类型") @RequestParam @Pattern(regexp = "(register|reset|modify|login)") String type,
            HttpServletRequest request) {

        String clientIp = IpUtils.getClientIp(request);
        return verifyCodeService.sendSmsVerifyCode(phone, type, clientIp);
    }

    /**
     * 验证短信验证码
     *
     * @param phone 手机号码
     * @param code  验证码
     * @param type  验证码类型
     * @return 验证结果
     */
    @PostMapping("/sms/verify")
    @Operation(summary = "验证短信验证码", description = "验证短信验证码是否正确")
    public Result<Boolean> verifySmsCode(
            @Parameter(description = "手机号码") @RequestParam @Pattern(regexp = "^1[3-9]\\d{9}$") String phone,
            @Parameter(description = "验证码") @RequestParam String code,
            @Parameter(description = "验证码类型") @RequestParam @Pattern(regexp = "(register|reset|modify|login)") String type) {

        return verifyCodeService.verifySmsCode(phone, code, type);
    }

    /**
     * 生成图形验证码
     *
     * @param sessionId 会话ID（可选）
     * @return 验证码图片数据
     */
    @GetMapping("/image/generate")
    @Operation(summary = "生成图形验证码", description = "生成图形验证码并返回Base64编码的图片")
    public Result<Map<String, Object>> generateImageCaptcha(
            @Parameter(description = "会话ID") @RequestParam(required = false) String sessionId) {

        if (sessionId == null) {
            sessionId = IdUtils.nextIdWithPrefix("CAPTCHA");
        }

        return verifyCodeService.generateImageCaptcha(sessionId);
    }

    /**
     * 验证图形验证码
     *
     * @param sessionId 会话ID
     * @param code      验证码
     * @return 验证结果
     */
    @PostMapping("/image/verify")
    @Operation(summary = "验证图形验证码", description = "验证图形验证码是否正确")
    public Result<Boolean> verifyImageCaptcha(
            @Parameter(description = "会话ID") @RequestParam String sessionId,
            @Parameter(description = "验证码") @RequestParam String code) {

        return verifyCodeService.verifyImageCaptcha(sessionId, code);
    }

    /**
     * 清除验证码
     *
     * @param identifier 标识符（邮箱、手机号等）
     * @param type       验证码类型
     * @return 清除结果
     */
    @DeleteMapping("/clear")
    @Operation(summary = "清除验证码", description = "清除指定标识符和类型的验证码")
    public Result<Void> clearVerifyCode(
            @Parameter(description = "标识符") @RequestParam String identifier,
            @Parameter(description = "验证码类型") @RequestParam String type) {

        return verifyCodeService.clearVerifyCode(identifier, type);
    }

    /**
     * 获取验证码发送统计
     *
     * @param identifier 标识符
     * @param timeRange  时间范围（小时）
     * @return 发送统计
     */
    @GetMapping("/stats")
    @Operation(summary = "获取验证码统计", description = "获取指定标识符的验证码发送统计")
    public Result<Map<String, Object>> getVerifyCodeStats(
            @Parameter(description = "标识符") @RequestParam String identifier,
            @Parameter(description = "时间范围（小时）") @RequestParam(defaultValue = "24") int timeRange) {

        return verifyCodeService.getVerifyCodeStats(identifier, timeRange);
    }

    /**
     * 检查验证码发送频率限制
     *
     * @param identifier 标识符
     * @param type       验证码类型
     * @param request    HTTP请求
     * @return 是否允许发送
     */
    @GetMapping("/check-limit")
    @Operation(summary = "检查发送限制", description = "检查是否可以发送验证码")
    public Result<Boolean> checkSendLimit(
            @Parameter(description = "标识符") @RequestParam String identifier,
            @Parameter(description = "验证码类型") @RequestParam String type,
            HttpServletRequest request) {

        String clientIp = IpUtils.getClientIp(request);
        return verifyCodeService.checkSendLimit(identifier, type, clientIp);
    }

    /**
     * 获取验证码剩余有效时间
     *
     * @param identifier 标识符
     * @param type       验证码类型
     * @return 剩余时间（秒）
     */
    @GetMapping("/ttl")
    @Operation(summary = "获取验证码TTL", description = "获取验证码剩余有效时间")
    public Result<Long> getVerifyCodeTtl(
            @Parameter(description = "标识符") @RequestParam String identifier,
            @Parameter(description = "验证码类型") @RequestParam String type) {

        return verifyCodeService.getVerifyCodeTtl(identifier, type);
    }

    /**
     * 获取验证码配置信息
     *
     * @return 配置信息
     */
    @GetMapping("/config")
    @Operation(summary = "获取验证码配置", description = "获取验证码相关配置信息")
    public Result<Map<String, Object>> getVerifyCodeConfig() {
        return verifyCodeService.getVerifyCodeConfig();
    }

    /**
     * 批量清理过期验证码（管理员接口）
     *
     * @return 清理结果
     */
    @DeleteMapping("/admin/cleanup")
    @Operation(summary = "清理过期验证码", description = "批量清理过期的验证码（管理员功能）")
    public Result<Map<String, Object>> cleanExpiredCodes() {
        return verifyCodeService.cleanExpiredCodes();
    }

    // 兼容原有接口

    /**
     * 兼容原有的邮件验证码接口
     *
     * @param email   邮箱地址
     * @param type    验证码类型
     * @param request HTTP请求
     * @return 发送结果
     */
    @GetMapping("/ask-code")
    @Operation(summary = "请求邮件验证码（兼容接口）", description = "兼容原有的邮件验证码请求接口")
    public Result<Void> askVerifyCode(
            @Parameter(description = "邮箱地址") @RequestParam @Email String email,
            @Parameter(description = "验证码类型") @RequestParam @Pattern(regexp = "(register|reset|modify)") String type,
            HttpServletRequest request) {

        String clientIp = IpUtils.getClientIp(request);
        return verifyCodeService.sendEmailVerifyCode(email, type, clientIp);
    }
}
