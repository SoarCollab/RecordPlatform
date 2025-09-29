package cn.flying.identity.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 用户状态更新请求DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class UserStatusRequest {
    private boolean disabled;
}
