package cn.flying.monitor.client.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @program: monitor
 * @description: 服务器基础信息实体类
 * @author: 王贝强
 * @create: 2024-07-14 22:29
 */
@Data
@Accessors(chain = true)
public class BaseDetail {
    String osArch;
    String osName;
    String osVersion;
    int osBit;
    String cpuName;
    int cpuCore;
    double memory;
    double disk;
    String ip;
}
