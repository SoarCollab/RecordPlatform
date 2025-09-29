package cn.flying.identity.vo.request;

import cn.flying.identity.validation.ValidPassword;
import lombok.Getter;
import lombok.Setter;

/**
 * 修改密码请求VO
 * 用于接收用户修改密码请求参数
 */
@Setter
@Getter
public class ChangePasswordVO {
    
    /**
     * 原密码
     */
    @ValidPassword
    private String password;

    /**
     * 新密码
     */
    @ValidPassword
    private String newPassword;

    @Override
    public String toString() {
        return "ChangePasswordVO{" +
                "password='[PROTECTED]'" +
                ", newPassword='[PROTECTED]'" +
                '}';
    }
}