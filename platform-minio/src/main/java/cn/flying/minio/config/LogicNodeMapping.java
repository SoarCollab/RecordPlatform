package cn.flying.minio.config;

import lombok.Data;

import java.util.List;

/**
 * 逻辑节点到物理节点对的映射配置
 */
@Data
public class LogicNodeMapping {
    /**
     * 逻辑节点名称
     */
    private String logicNodeName;
    /**
     * 对应的物理节点名称列表 (至少包含两个物理节点)
     */
    private List<String> physicalNodePair;
} 