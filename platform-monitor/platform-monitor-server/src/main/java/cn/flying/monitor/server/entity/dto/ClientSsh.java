package cn.flying.monitor.server.entity.dto;

import cn.flying.monitor.server.entity.BaseData;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @program: monitor
 * @description: 客户端远程SSH信息实体类
 * @author: 王贝强
 * @create: 2024-07-27 09:39
 */
@Data
@TableName("client_ssh")
public class ClientSsh implements BaseData {
    @TableId
    Integer id;
    String ip;
    Integer port;
    String username;
    String password;
}
