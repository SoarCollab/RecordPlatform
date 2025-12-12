package cn.flying.fisco_bcos.adapter.model;

import lombok.Getter;

/**
 * 区块链类型枚举
 * 支持多链切换的核心配置
 */
@Getter
public enum ChainType {

    /**
     * 本地 FISCO BCOS 节点
     * 用于开发和测试环境
     */
    LOCAL_FISCO("local-fisco", "本地 FISCO BCOS"),

    /**
     * BSN 托管的 FISCO BCOS 节点
     * 生产环境推荐
     */
    BSN_FISCO("bsn-fisco", "BSN FISCO BCOS"),

    /**
     * BSN 托管的 Hyperledger Besu 节点
     * 以太坊兼容链
     */
    BSN_BESU("bsn-besu", "BSN Hyperledger Besu");

    private final String configValue;
    private final String displayName;

    ChainType(String configValue, String displayName) {
        this.configValue = configValue;
        this.displayName = displayName;
    }

    /**
     * 根据配置值获取链类型
     */
    public static ChainType fromConfigValue(String configValue) {
        for (ChainType type : values()) {
            if (type.configValue.equals(configValue)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown chain type: " + configValue);
    }
}
