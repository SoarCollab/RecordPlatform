package cn.flying.identity.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 登录状态视图对象
 * 统一封装当前登录态信息
 *
 * @author Codex
 * @since 2025-10-29
 */
@Getter
@Setter
@AllArgsConstructor
public class LoginStatusVO {

    /**
     * 是否已登录
     */
    private boolean loggedIn;

    /**
     * 状态描述
     */
    private String message;

    /**
     * 当前登录用户ID，可为空
     */
    private Long userId;
}
