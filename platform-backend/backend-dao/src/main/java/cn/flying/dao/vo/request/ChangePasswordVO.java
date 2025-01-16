package cn.flying.dao.vo.request;

import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @program: RecordPlatform
 * @description: 修改密码VO类
 * @author: flyingcoding
 * @create: 2024-06-09 16:50
 */
@Data
public class ChangePasswordVO {
    @Length(min = 6, max = 20, message = "密码长度必须在6-20之间")
    private String password;
    @Length(min = 6, max = 20, message = "密码长度必须在6-20之间")
    private String new_password;
}
