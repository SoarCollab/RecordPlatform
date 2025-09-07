package cn.flying.identity.vo.request;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.Length;

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
    @Length(min = 6, max = 20, message = "密码长度必须在6-20个字符之间")
    private String password;
    
    /**
     * 新密码
     */
    @Length(min = 6, max = 20, message = "密码长度必须在6-20个字符之间")
    private String newPassword;

    @Override
    public String toString() {
        return "ChangePasswordVO{" +
                "password='[PROTECTED]'" +
                ", newPassword='[PROTECTED]'" +
                '}';
    }
}