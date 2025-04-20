package cn.flying.dao.vo.auth;

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
public class ModifyEmailVO {
    @Email
    String email;
    @Length(min = 6, max = 6)
    String code;
}
