package cn.flying.identity.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 回调请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class CallbackRequest {
    private String code;
    private String state;
    private String error;
    private String errorDescription;
}
