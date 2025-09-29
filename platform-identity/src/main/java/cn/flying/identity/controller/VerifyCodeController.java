package cn.flying.identity.controller;

import cn.flying.identity.dto.*;
import cn.flying.identity.service.VerifyCodeService;
import cn.flying.identity.util.IdUtils;
import cn.flying.identity.util.IpUtils;
import cn.flying.identity.util.ResponseConverter;
import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 验证码控制器
 * 提供符合RESTful规范的验证码服务API
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@RestController
@RequestMapping("/api/verification")
@Tag(name = "验证码管理", description = "提供各种类型的验证码服务")
public class VerifyCodeController {

    @Resource
    private VerifyCodeService verifyCodeService;

    /**
     * 发送邮件验证码
     * POST /api/verification/email-codes - 发送邮件验证码
     */
    @PostMapping("/email-codes")
    @Operation(summary = "发送邮件验证码", description = "向指定邮箱发送验证码")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "验证码已发送"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "429", description = "请求过于频繁")
    })
    public ResponseEntity<RestResponse<Void>> sendEmailCode(
            @Valid @RequestBody EmailCodeRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = IpUtils.getClientIp(httpRequest);
        Result<Void> result = verifyCodeService.sendEmailVerifyCode(
                request.getEmail(), request.getType(), clientIp);

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(RestResponse.accepted());
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 验证邮件验证码
     * POST /api/verification/email-codes/verify - 验证邮件验证码
     */
    @PostMapping("/email-codes/verify")
    @Operation(summary = "验证邮件验证码", description = "验证邮件验证码是否正确")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "验证结果"),
            @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<RestResponse<Boolean>> verifyEmailCode(
            @Valid @RequestBody VerifyCodeRequest request) {

        Result<Boolean> result = verifyCodeService.verifyEmailCode(
                request.getIdentifier(), request.getCode(), request.getType());
        RestResponse<Boolean> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 发送短信验证码
     * POST /api/verification/sms-codes - 发送短信验证码
     */
    @PostMapping("/sms-codes")
    @Operation(summary = "发送短信验证码", description = "向指定手机号发送验证码")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "验证码已发送"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "429", description = "请求过于频繁")
    })
    public ResponseEntity<RestResponse<Void>> sendSmsCode(
            @Valid @RequestBody SmsCodeRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = IpUtils.getClientIp(httpRequest);
        Result<Void> result = verifyCodeService.sendSmsVerifyCode(
                request.getPhone(), request.getType(), clientIp);

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(RestResponse.accepted());
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 验证短信验证码
     * POST /api/verification/sms-codes/verify - 验证短信验证码
     */
    @PostMapping("/sms-codes/verify")
    @Operation(summary = "验证短信验证码", description = "验证短信验证码是否正确")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "验证结果"),
            @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<RestResponse<Boolean>> verifySmsCode(
            @Valid @RequestBody VerifyCodeRequest request) {

        Result<Boolean> result = verifyCodeService.verifySmsCode(
                request.getIdentifier(), request.getCode(), request.getType());
        RestResponse<Boolean> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 创建图形验证码
     * POST /api/verification/image-captchas - 生成图形验证码
     */
    @PostMapping("/image-captchas")
    @Operation(summary = "创建图形验证码", description = "生成图形验证码并返回Base64编码的图片")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "验证码已生成"),
            @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> createImageCaptcha(
            @Valid @RequestBody(required = false) CaptchaRequest request) {

        String sessionId = (request != null && request.getSessionId() != null)
                ? request.getSessionId()
                : IdUtils.nextIdWithPrefix("CAPTCHA");

        Result<Map<String, Object>> result = verifyCodeService.generateImageCaptcha(sessionId);

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(RestResponse.created(result.getData()));
        } else {
            RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 验证图形验证码
     * POST /api/verification/image-captchas/verify - 验证图形验证码
     */
    @PostMapping("/image-captchas/verify")
    @Operation(summary = "验证图形验证码", description = "验证图形验证码是否正确")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "验证结果"),
            @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<RestResponse<Boolean>> verifyImageCaptcha(
            @Valid @RequestBody CaptchaVerifyRequest request) {

        Result<Boolean> result = verifyCodeService.verifyImageCaptcha(
                request.getSessionId(), request.getCode());
        RestResponse<Boolean> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 删除验证码
     * DELETE /api/verification/codes - 清除验证码
     */
    @DeleteMapping("/codes")
    @Operation(summary = "删除验证码", description = "清除指定标识符和类型的验证码")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "清除成功"),
            @ApiResponse(responseCode = "400", description = "参数无效")
    })
    public ResponseEntity<Void> deleteCode(
            @Parameter(description = "标识符") @RequestParam String identifier,
            @Parameter(description = "验证码类型") @RequestParam String type) {

        Result<Void> result = verifyCodeService.clearVerifyCode(identifier, type);

        if (result.isSuccess()) {
            return ResponseEntity.noContent().build();
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).build();
        }
    }

    /**
     * 获取验证码统计
     * GET /api/verification/statistics - 获取验证码统计
     */
    @GetMapping("/statistics")
    @Operation(summary = "获取验证码统计", description = "获取指定标识符的验证码发送统计")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getStatistics(
            @Parameter(description = "标识符") @RequestParam String identifier,
            @Parameter(description = "时间范围（小时）") @RequestParam(defaultValue = "24") int timeRange) {

        Result<Map<String, Object>> result = verifyCodeService.getVerifyCodeStats(identifier, timeRange);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 检查发送限制
     * GET /api/verification/limits - 检查验证码发送频率限制
     */
    @GetMapping("/limits")
    @Operation(summary = "检查发送限制", description = "检查是否可以发送验证码")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回检查结果")
    })
    public ResponseEntity<RestResponse<Boolean>> checkLimit(
            @Parameter(description = "标识符") @RequestParam String identifier,
            @Parameter(description = "验证码类型") @RequestParam String type,
            HttpServletRequest request) {

        String clientIp = IpUtils.getClientIp(request);
        Result<Boolean> result = verifyCodeService.checkSendLimit(identifier, type, clientIp);
        RestResponse<Boolean> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取验证码TTL
     * GET /api/verification/codes/ttl - 获取验证码剩余有效时间
     */
    @GetMapping("/codes/ttl")
    @Operation(summary = "获取验证码TTL", description = "获取验证码剩余有效时间")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回剩余时间")
    })
    public ResponseEntity<RestResponse<Long>> getCodeTtl(
            @Parameter(description = "标识符") @RequestParam String identifier,
            @Parameter(description = "验证码类型") @RequestParam String type) {

        Result<Long> result = verifyCodeService.getVerifyCodeTtl(identifier, type);
        RestResponse<Long> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取配置信息
     * GET /api/verification/configurations - 获取验证码配置
     */
    @GetMapping("/configurations")
    @Operation(summary = "获取验证码配置", description = "获取验证码相关配置信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getConfigurations() {
        Result<Map<String, Object>> result = verifyCodeService.getVerifyCodeConfig();
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 清理过期验证码
     * DELETE /api/verification/admin/cleanup - 批量清理过期验证码
     */
    @DeleteMapping("/admin/cleanup")
    @Operation(summary = "清理过期验证码", description = "批量清理过期的验证码（管理员功能）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "清理成功"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> cleanupExpiredCodes() {
        Result<Map<String, Object>> result = verifyCodeService.cleanExpiredCodes();
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
