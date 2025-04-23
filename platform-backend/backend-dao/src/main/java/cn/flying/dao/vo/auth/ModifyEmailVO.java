package cn.flying.dao.vo.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @program: RecordPlatform
 * @description: 修改邮箱请求类
 * @author: flyingcoding
 * @create: 2025-01-16 14:27
 */
@Data
@Schema(description = "修改邮箱请求类")
public class ModifyEmailVO {
    @Email
    @Schema(description = "邮箱")
    String email;
    @Length(min = 6, max = 6)
    @Schema(description = "验证码")
    String code;
}
