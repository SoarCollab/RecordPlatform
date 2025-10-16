package cn.flying.monitor.server.entity.vo.response;

import lombok.Data;

/**
 * @program: monitor
 * @description: 客户端监控信息返回VO类
 * @author: 王贝强
 * @create: 2024-07-18 15:37
 */
@Data
public class ClientPreviewVO {
    int id;
    boolean online;
    String name;
    String location;
    String osName;
    String osVersion;
    String ip;
    String cpuName;
    int cpuCore;
    double memory;
    double cpuUsage;
    double memoryUsage;
    double networkUpload;
    double networkDownload;
}
