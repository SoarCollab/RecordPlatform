package cn.flying.identity.vo.request;

import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.validator.constraints.Length;

/**
 * 确认重置密码请求VO
 * 用于接收用户确认重置密码请求参数
 * 从 platform-backend 迁移而来
 */
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmResetVO {
    
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
        return "ConfirmResetVO{" +
                "email='" + email + '\'' +
                ", code='" + code + '\'' +
                '}';
    }
}
