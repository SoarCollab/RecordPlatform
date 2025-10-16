package cn.flying.monitor.server.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: monitor
 * @description: 服务器实时数据VO类
 * @author: 王贝强
 * @create: 2024-07-15 18:09
 */
@Data
public class RuntimeDetailVO {
    @NotNull
    long timestamp;
    @NotNull
    double cpuUsage;
    @NotNull
    double memoryUsage;
    @NotNull
    double diskUsage;
    @NotNull
    double networkUpload;
    @NotNull
    double networkDownload;
    @NotNull
    double diskRead;
    @NotNull
    double diskWrite;
}
