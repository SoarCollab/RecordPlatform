package cn.flying.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 撤销Token请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class RevokeTokenRequest {
    @NotBlank(message = "Token不能为空")
    private String token;

    private String tokenTypeHint;

    @NotBlank(message = "客户端ID不能为空")
    private String clientId;

    @NotBlank(message = "客户端密钥不能为空")
    private String clientSecret;
}
