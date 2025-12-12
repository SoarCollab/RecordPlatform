package cn.flying.fisco_bcos.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * BSN Hyperledger Besu 配置类
 * 用于连接 BSN 区块链服务网络托管的 Hyperledger Besu 节点
 *
 * <p>激活条件: {@code blockchain.active=bsn-besu}
 */
@Data
@Configuration
@ConditionalOnProperty(name = "blockchain.active", havingValue = "bsn-besu")
@ConfigurationProperties(prefix = "blockchain.bsn-besu")
public class BsnBesuConfig {

    /**
     * BSN Besu RPC 地址
     */
    private String rpcUrl;

    /**
     * 链 ID
     */
    private Long chainId;

    /**
     * 钱包配置
     */
    private Wallet wallet = new Wallet();

    /**
     * 合约地址配置
     */
    private ContractAddresses contracts = new ContractAddresses();

    /**
     * Gas 配置
     */
    private GasConfig gas = new GasConfig();

    @Data
    public static class Wallet {
        /**
         * 钱包类型: local (私钥配置) 或 kms (后续支持)
         */
        private String type = "local";

        /**
         * 签名私钥 (type=local 时使用)
         */
        private String privateKey;
    }

    @Data
    public static class ContractAddresses {
        /**
         * Storage 合约地址
         */
        private String storage;

        /**
         * Sharing 合约地址
         */
        private String sharing;
    }

    @Data
    public static class GasConfig {
        /**
         * Gas 价格 (单位: wei)
         */
        private Long gasPrice = 0L;

        /**
         * Gas 限制
         */
        private Long gasLimit = 4_500_000L;
    }
}
