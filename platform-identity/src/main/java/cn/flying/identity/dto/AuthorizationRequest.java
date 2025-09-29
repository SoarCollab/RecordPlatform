package cn.flying.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 授权请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class AuthorizationRequest {
    @NotBlank(message = "客户端ID不能为空")
    private String clientId;
    
    @NotBlank(message = "重定向URI不能为空")
    private String redirectUri;
    
    private String scope;
    private String state;
    private boolean approved;
}
