package cn.flying.monitor.client.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: monitor
 * @description: 连接配置类
 * @author: 王贝强
 * @create: 2024-07-14 17:39
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConnectionConfig {
    String address;
    String token;
}
