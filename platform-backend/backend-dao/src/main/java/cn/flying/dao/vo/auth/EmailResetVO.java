package cn.flying.dao.vo.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * 密码重置表单实体
 */
@Data
@Schema(description = "密码重置表单信息")
public class EmailResetVO {
    @Email
    @Schema(description = "邮箱")
    String email;
    @Length(max = 6, min = 6)
    @Schema(description = "验证码")
    String code;
    @Length(min = 6, max = 20)
    @Schema(description = "新密码")
    String password;
}
