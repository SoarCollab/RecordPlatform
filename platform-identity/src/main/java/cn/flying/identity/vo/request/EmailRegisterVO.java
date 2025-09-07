package cn.flying.identity.vo.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.Length;

/**
 * 邮箱注册请求VO
 * 用于接收用户注册请求参数
 */
@Setter
@Getter
public class EmailRegisterVO {
    
    /**
     * 用户名
     */
    @Pattern(regexp = "^[a-zA-Z0-9\u4e00-\u9fa5]+$", message = "用户名不能包含特殊字符，只能是中文/英文")
    @Length(min = 1, max = 10, message = "用户名长度必须在1-10个字符之间")
    private String username;
    
    /**
     * 密码
     */
    @Length(min = 6, max = 20, message = "密码长度必须在6-20个字符之间")
    private String password;
    
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

    @Override
    public String toString() {
        return "EmailRegisterVO{" +
                "username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", code='" + code + '\'' +
                '}';
    }
}