package cn.flying.minio.config;

import com.alibaba.nacos.api.config.annotation.NacosConfigurationProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * MinIO 相关配置属性 - 从 Nacos 加载并支持动态刷新
 */
@Data
@Component
// 使用 NacosConfigurationProperties 注解加载数据
@NacosConfigurationProperties(dataId = "platform-minio.yaml", // Nacos 中的 Data ID
                              autoRefreshed = true // 启用自动刷新
)
// 使用 ConfigurationProperties 指定绑定前缀
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    /**
     * 配置的所有物理节点列表
     * 注意: 每个节点的name字段将直接用作该节点的MinIO桶名
     */
    private List<NodeConfig> nodes;

    /**
     * 配置的逻辑节点到物理节点的映射列表
     */
    private List<LogicNodeMapping> logicalMapping;

} 