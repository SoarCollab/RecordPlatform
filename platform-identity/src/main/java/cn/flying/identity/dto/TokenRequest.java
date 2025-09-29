package cn.flying.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Token请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class TokenRequest {
    @NotBlank(message = "授权类型不能为空")
    private String grantType;

    private String code;
    private String redirectUri;

    @NotBlank(message = "客户端ID不能为空")
    private String clientId;

    @NotBlank(message = "客户端密钥不能为空")
    private String clientSecret;

    private String refreshToken;
    private String scope;
}
