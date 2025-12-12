package cn.flying.fisco_bcos.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * BSN FISCO BCOS 配置类
 * 用于连接 BSN 区块链服务网络托管的 FISCO BCOS 节点
 *
 * <p>激活条件: {@code blockchain.active=bsn-fisco}
 */
@Data
@Configuration
@ConditionalOnProperty(name = "blockchain.active", havingValue = "bsn-fisco")
@ConfigurationProperties(prefix = "blockchain.bsn-fisco")
public class BsnFiscoConfig {

    /**
     * BSN 城市节点 ID (如: city-node-shanghai)
     */
    private String nodeId;

    /**
     * 群组 ID
     */
    private String groupId = "group0";

    /**
     * BSN 颁发的证书路径
     */
    private String certPath = "bsn-certs";

    /**
     * BSN 节点地址列表 (格式: host:port)
     */
    private String[] peers;

    /**
     * BSN 应用配置
     */
    private BsnApp app = new BsnApp();

    /**
     * 合约地址配置
     */
    private ContractAddresses contracts = new ContractAddresses();

    @Data
    public static class BsnApp {
        /**
         * BSN 应用 ID
         */
        private String id;

        /**
         * BSN 应用密钥
         */
        private String secret;
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
}
