package cn.flying.monitor.server.entity.vo.request;

import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @program: monitor
 * @description: 用户更改密码VO类
 * @author: 王贝强
 * @create: 2024-07-24 17:09
 */
@Data
public class ChangePasswordVO {
    @Length(min = 6,max = 20)
    String password;
    @Length(min = 6,max = 20)
    String new_password;
}
