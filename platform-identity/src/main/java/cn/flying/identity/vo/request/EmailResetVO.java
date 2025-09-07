package cn.flying.identity.vo.request;

import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.Length;

/**
 * 邮箱重置密码请求VO
 * 用于接收用户重置密码请求参数
 */
@Setter
@Getter
public class EmailResetVO {
    
    /**
     * 邮箱
     */
    @Email(message = "请输入正确的邮箱地址")
    private String email;
    
    /**
     * 邮箱验证码
     */
    @Length(min = 6, max = 6, message = "验证码长度不正确")
    private String code;
    
    /**
     * 新密码
     */
    @Length(min = 6, max = 20, message = "密码长度必须在6-20个字符之间")
    private String password;

    @Override
    public String toString() {
        return "EmailResetVO{" +
                "email='" + email + '\'' +
                ", code='" + code + '\'' +
                '}';
    }
}