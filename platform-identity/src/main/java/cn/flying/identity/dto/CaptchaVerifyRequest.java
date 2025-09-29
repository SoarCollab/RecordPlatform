package cn.flying.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 图形验证码验证请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class CaptchaVerifyRequest {
    @NotBlank(message = "会话ID不能为空")
    private String sessionId;
    
    @NotBlank(message = "验证码不能为空")
    private String code;
}
