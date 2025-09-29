package cn.flying.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 登录请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class LoginRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(max = 100, message = "用户名长度不能超过100")
    private String username;
    
    @NotBlank(message = "密码不能为空")
    @Size(max = 128, message = "密码长度不能超过128")
    private String password;
}
