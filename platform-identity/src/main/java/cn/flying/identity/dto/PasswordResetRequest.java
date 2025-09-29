package cn.flying.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 密码重置请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class PasswordResetRequest {
    @NotBlank(message = "新密码不能为空")
    private String newPassword;
}
