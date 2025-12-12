package cn.flying.fisco_bcos.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.fisco.bcos.sdk.v3.BcosSDK;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.config.ConfigOption;
import org.fisco.bcos.sdk.v3.config.model.ConfigProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * BSN FISCO BCOS SDK 配置
 * 用于生产环境，连接 BSN 区块链服务网络托管的 FISCO BCOS 节点
 *
 * <p>激活条件: {@code blockchain.active=bsn-fisco}
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "blockchain.active", havingValue = "bsn-fisco")
@Slf4j
public class BsnSdkBeanConfig {

    private final BsnFiscoConfig bsnConfig;
    private BcosSDK bcosSDK;

    public BsnSdkBeanConfig(BsnFiscoConfig bsnConfig) {
        this.bsnConfig = bsnConfig;
    }

    @Bean
    public Client client() throws Exception {
        log.info("[BSN FISCO] 初始化 BSN 节点连接, nodeId={}", bsnConfig.getNodeId());

        ConfigProperty property = new ConfigProperty();
        configNetwork(property);
        configCryptoMaterial(property);

        ConfigOption configOption = new ConfigOption(property);
        this.bcosSDK = new BcosSDK(configOption);
        Client client = bcosSDK.getClient(bsnConfig.getGroupId());

        BigInteger blockNumber = client.getBlockNumber().getBlockNumber();
        log.info("[BSN FISCO] Chain connect successful. Current block number {}", blockNumber);
        log.info("[BSN FISCO] Crypto type: {}, address: {}",
                client.getCryptoSuite().cryptoTypeConfig == 1 ? "GM" : "ECDSA",
                client.getCryptoSuite().getCryptoKeyPair().getAddress());

        return client;
    }

    private void configNetwork(ConfigProperty configProperty) {
        Map<String, Object> network = new HashMap<>();
        if (bsnConfig.getPeers() != null && bsnConfig.getPeers().length > 0) {
            for (int i = 0; i < bsnConfig.getPeers().length; i++) {
                network.put("peers[" + i + "]", bsnConfig.getPeers()[i]);
            }
        }
        configProperty.setNetwork(network);
    }

    private void configCryptoMaterial(ConfigProperty configProperty) {
        Map<String, Object> cryptoMaterials = new HashMap<>();
        cryptoMaterials.put("certPath", bsnConfig.getCertPath());
        configProperty.setCryptoMaterial(cryptoMaterials);
    }

    @PreDestroy
    public void cleanup() {
        log.info("[BSN FISCO] 正在关闭 BSN SDK 资源...");
        if (bcosSDK != null) {
            try {
                bcosSDK.stopAll();
                log.info("[BSN FISCO] BcosSDK 资源已释放");
            } catch (Exception e) {
                log.warn("[BSN FISCO] 关闭 BcosSDK 时出错: {}", e.getMessage());
            }
        }
    }
}
