package cn.flying.monitor.client.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @program: monitor
 * @description: 服务器运行时数据实体类
 * @author: 王贝强
 * @create: 2024-07-15 17:40
 */
@Data
@Accessors(chain = true)
public class RuntimeDetail {
    long timestamp;
    double cpuUsage;
    double memoryUsage;
    double diskUsage;
    double networkUpload;
    double networkDownload;
    double diskRead;
    double diskWrite;
}


