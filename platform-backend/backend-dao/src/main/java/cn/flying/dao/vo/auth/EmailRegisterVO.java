package cn.flying.dao.vo.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * 用户注册表单信息
 */
@Data
@Schema(description = "用户注册表单信息")
public class EmailRegisterVO {
    @Email
    @Schema(description = "邮箱")
    String email;
    @Length(max = 6, min = 6)
    @Schema(description = "验证码")
    String code;
    @Pattern(regexp = "^[a-zA-Z0-9\\u4e00-\\u9fa5]+$")
    @Length(min = 1, max = 10)
    @Schema(description = "用户名")
    String username;
    @Length(min = 6, max = 20)
    @Schema(description = "密码")
    String password;

    @Length(max = 50)
    @Schema(description = "昵称")
    String nickname;
}
