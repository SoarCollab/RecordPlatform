package cn.flying.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 验证码验证请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class VerifyCodeRequest {
    @NotBlank(message = "标识符不能为空")
    private String identifier;

    @NotBlank(message = "验证码不能为空")
    private String code;

    @NotBlank(message = "类型不能为空")
    private String type;
}
