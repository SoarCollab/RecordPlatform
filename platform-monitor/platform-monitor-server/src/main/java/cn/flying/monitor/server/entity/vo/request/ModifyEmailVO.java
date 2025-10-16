package cn.flying.monitor.server.entity.vo.request;

import jakarta.validation.constraints.Email;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @program: monitor
 * @description: 修改邮箱VO
 * @author: 王贝强
 * @create: 2024-07-26 19:04
 */
@Data
public class ModifyEmailVO {
    @Email
    String email;
    @Length(min = 6,max = 6)
    String code;
}
