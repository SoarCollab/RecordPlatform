package cn.flying.identity.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 图形验证码请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class CaptchaRequest {
    private String sessionId;
}
