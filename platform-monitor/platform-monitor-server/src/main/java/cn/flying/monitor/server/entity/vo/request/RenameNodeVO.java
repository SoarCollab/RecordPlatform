package cn.flying.monitor.server.entity.vo.request;

import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @program: monitor
 * @description: 重命名节点名VO类
 * @author: 王贝强
 * @create: 2024-07-21 16:54
 */
@Data
public class RenameNodeVO {
    int id;
    @Length(min = 1,max = 10)
    String node;
    @Pattern(regexp = "cn|hk|jp|us|sg|kr|de")
    String location;
}
