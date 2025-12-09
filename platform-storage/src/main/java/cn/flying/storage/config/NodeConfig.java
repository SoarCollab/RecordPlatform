package cn.flying.storage.config;

import lombok.Data;

/**
 * 单个 MinIO 物理节点配置
 */
@Data
public class NodeConfig {
    /**
     * 物理节点唯一名称
     */
    private String name;
    /**
     * 节点访问地址
     */
    private String endpoint;
    /**
     * 访问密钥
     */
    private String accessKey;
    /**
     * 私有密钥
     */
    private String secretKey;
} 