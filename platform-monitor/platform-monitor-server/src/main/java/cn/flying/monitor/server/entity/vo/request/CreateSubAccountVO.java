package cn.flying.monitor.server.entity.vo.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.util.List;

/**
 * @program: monitor
 * @description: 子账户创建VO
 * @author: 王贝强
 * @create: 2024-07-25 14:10
 */
@Data
public class CreateSubAccountVO {
    @Length(min = 6, max = 20)
    String username;
    @Email
    String email;
    @Length(min = 6, max = 20)
    String password;
    @Size(min = 1)
    List<Integer> clients;
}
