package cn.flying.monitor.server.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: monitor
 * @description: 服务器基础数据VO
 * @author: 王贝强
 * @create: 2024-07-15 12:19
 */
@Data
public class ClientDetailVO {
    @NotNull
    String osArch;
    @NotNull
    String osName;
    @NotNull
    String osVersion;
    @NotNull
    int osBit;
    @NotNull
    String cpuName;
    @NotNull
    int cpuCore;
    @NotNull
    double memory;
    @NotNull
    double disk;
    @NotNull
    String ip;
}
