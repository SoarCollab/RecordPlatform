package cn.flying.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 验证Token请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class ValidateTokenRequest {
    @NotBlank(message = "访问令牌不能为空")
    private String accessToken;
}
