package cn.flying.dao.vo.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

@Data
@AllArgsConstructor
@Schema(description = "确认重置密码VO类")
public class ConfirmResetVO {
    @Email
    @Schema(description = "邮箱")
    String email;
    @Length(max = 6, min = 6)
    @Schema(description = "验证码")
    String code;
}
