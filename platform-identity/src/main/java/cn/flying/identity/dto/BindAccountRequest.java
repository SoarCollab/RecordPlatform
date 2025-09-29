package cn.flying.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 绑定账号请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class BindAccountRequest {
    @NotBlank(message = "授权码不能为空")
    private String code;
}
