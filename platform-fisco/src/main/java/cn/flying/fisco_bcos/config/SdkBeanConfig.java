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
import java.util.List;
import java.util.Map;

/**
 * 本地 FISCO BCOS SDK 配置
 * 用于开发和测试环境，连接本地部署的 FISCO BCOS 节点
 *
 * <p>激活条件: {@code blockchain.active=local-fisco} (默认)
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "blockchain.active", havingValue = "local-fisco", matchIfMissing = true)
@Slf4j
public class SdkBeanConfig {

    private final SystemConfig systemConfig;
    private final BcosConfig bcosConfig;

    // 保存 BcosSDK 实例以便后续关闭
    private BcosSDK bcosSDK;

    public SdkBeanConfig(SystemConfig systemConfig, BcosConfig bcosConfig) {
        this.systemConfig = systemConfig;
        this.bcosConfig = bcosConfig;
    }

    @Bean
    public Client client() throws Exception {
        ConfigProperty property = new ConfigProperty();
        configNetwork(property);
        configCryptoMaterial(property);

        ConfigOption configOption = new ConfigOption(property);
        this.bcosSDK = new BcosSDK(configOption);
        Client client = bcosSDK.getClient(systemConfig.getGroupId());

        BigInteger blockNumber = client.getBlockNumber().getBlockNumber();
        log.info("Chain connect successful. Current block number {}", blockNumber);

        configCryptoKeyPair(client);
        log.info("is Gm:{}, address:{}", client.getCryptoSuite().cryptoTypeConfig == 1, client.getCryptoSuite().getCryptoKeyPair().getAddress());
        return client;
    }

    public void configNetwork(ConfigProperty configProperty) {
        Map<String, Object> peers = new java.util.HashMap<>(bcosConfig.getNetwork());
        configProperty.setNetwork(peers);
    }

    public void configCryptoMaterial(ConfigProperty configProperty) {
        Map<String, Object> cryptoMaterials = bcosConfig.getCryptoMaterial();
        configProperty.setCryptoMaterial(cryptoMaterials);
    }

    public void configCryptoKeyPair(Client client) {
        String hexPrivateKey = systemConfig.getHexPrivateKey();
        if (hexPrivateKey == null || hexPrivateKey.isEmpty()) {
            return;
        }
        // 使用局部变量处理前缀，不修改原配置对象
        if (hexPrivateKey.startsWith("0x") || hexPrivateKey.startsWith("0X")) {
            hexPrivateKey = hexPrivateKey.substring(2);
        }
        client.getCryptoSuite().setCryptoKeyPair(client.getCryptoSuite().loadKeyPair(hexPrivateKey));
    }

    /**
     * 应用关闭时释放 BcosSDK 资源
     */
    @PreDestroy
    public void cleanup() {
        log.info("正在关闭 FISCO BCOS SDK 资源...");
        if (bcosSDK != null) {
            try {
                bcosSDK.stopAll();
                log.info("BcosSDK 资源已释放");
            } catch (Exception e) {
                log.warn("关闭 BcosSDK 时出错: {}", e.getMessage());
            }
        }
    }
}
