package cn.flying.monitor.server.entity.vo.response;

import lombok.Data;

/**
 * @program: monitor
 * @description: 获取客户端SSH信息VO
 * @author: 王贝强
 * @create: 2024-07-27 10:01
 */
@Data
public class SshSettingsVO {
    String ip;
    Integer port=22;
    String username;
    String password;
}
