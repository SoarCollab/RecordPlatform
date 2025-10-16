package cn.flying.monitor.server.entity.vo.response;

import lombok.Data;

/**
 * @program: monitor
 * @description: 服务器详细数据返回VO类
 * @author: 王贝强
 * @create: 2024-07-19 17:46
 */
@Data
public class ClientDetailsVO {
    int id;
    String name;
    boolean online;
    String node;
    String location;
    String ip;
    String cpuName;
    String osName;
    String osVersion;
    double memory;
    int cpuCore;
    double disk;
}
