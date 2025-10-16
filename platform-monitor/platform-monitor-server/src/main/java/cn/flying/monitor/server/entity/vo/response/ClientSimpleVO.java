package cn.flying.monitor.server.entity.vo.response;

import lombok.Data;

/**
 * @program: monitor
 * @description: 客户端简单信息VO
 * @author: 王贝强
 * @create: 2024-07-25 18:39
 */
@Data
public class ClientSimpleVO {
    int id;
    String name;
    String location;
    String osName;
    String osVersion;
    String ip;
}
