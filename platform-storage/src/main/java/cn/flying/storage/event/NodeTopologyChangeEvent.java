package cn.flying.storage.event;

import org.springframework.context.ApplicationEvent;

/**
 * 节点拓扑变更事件
 * 当节点上线、下线或配置变更时触发
 */
public class NodeTopologyChangeEvent extends ApplicationEvent {

    private final String nodeName;
    private final TopologyChangeType changeType;
    private final String faultDomain;

    public NodeTopologyChangeEvent(Object source, String nodeName, TopologyChangeType changeType, String faultDomain) {
        super(source);
        this.nodeName = nodeName;
        this.changeType = changeType;
        this.faultDomain = faultDomain;
    }

    public String getNodeName() {
        return nodeName;
    }

    public TopologyChangeType getChangeType() {
        return changeType;
    }

    public String getFaultDomain() {
        return faultDomain;
    }

    /**
     * 拓扑变更类型
     */
    public enum TopologyChangeType {
        /**
         * 节点上线（健康检查通过）
         */
        NODE_ONLINE,

        /**
         * 节点下线（健康检查失败）
         */
        NODE_OFFLINE,

        /**
         * 节点添加（配置中新增）
         */
        NODE_ADDED,

        /**
         * 节点移除（从配置中删除）
         */
        NODE_REMOVED,

        /**
         * 节点域变更（从备用提升到活跃域）
         */
        NODE_DOMAIN_CHANGED
    }

    @Override
    public String toString() {
        return "NodeTopologyChangeEvent{" +
                "nodeName='" + nodeName + '\'' +
                ", changeType=" + changeType +
                ", faultDomain='" + faultDomain + '\'' +
                '}';
    }
}
