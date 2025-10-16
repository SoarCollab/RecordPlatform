package cn.flying.monitor.server.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @program: monitor
 * @description: 客户端ssh连接VO
 * @author: 王贝强
 * @create: 2024-07-27 09:51
 */
@Data
public class SshConnectVO {
    int id;
    String ip;
    int port;
    @NotNull
    @Length(min = 1)
    String username;
    @NotNull
    @Length(min = 1)
    String password;
}
