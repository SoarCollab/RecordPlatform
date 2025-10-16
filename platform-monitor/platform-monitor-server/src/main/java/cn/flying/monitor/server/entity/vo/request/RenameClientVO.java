package cn.flying.monitor.server.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @program: monitor
 * @description: 服务器改名请求VO类
 * @author: 王贝强
 * @create: 2024-07-19 15:31
 */
@Data
public class RenameClientVO {
    @NotNull
    int id;
    @Length(min = 1,max = 20)
    String name;
}
