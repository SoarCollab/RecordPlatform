package cn.flying.fisco_bcos.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.StaticGasProvider;

import java.math.BigInteger;

/**
 * BSN Besu Web3j 配置
 * 用于生产环境，连接 BSN 区块链服务网络托管的 Hyperledger Besu 节点
 *
 * <p>激活条件: {@code blockchain.active=bsn-besu}
 */
@Configuration
@ConditionalOnProperty(name = "blockchain.active", havingValue = "bsn-besu")
@Slf4j
public class Web3jConfig {

    private final BsnBesuConfig besuConfig;
    private Web3j web3jInstance;

    public Web3jConfig(BsnBesuConfig besuConfig) {
        this.besuConfig = besuConfig;
    }

    @Bean
    public Web3j web3j() {
        log.info("[BSN Besu] 初始化 Web3j 连接, rpcUrl={}", besuConfig.getRpcUrl());
        this.web3jInstance = Web3j.build(new HttpService(besuConfig.getRpcUrl()));
        return this.web3jInstance;
    }

    @Bean
    public Credentials credentials() {
        String privateKey = besuConfig.getWallet().getPrivateKey();
        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalStateException("[BSN Besu] 私钥未配置");
        }

        // 移除可能的 0x 前缀
        if (privateKey.startsWith("0x") || privateKey.startsWith("0X")) {
            privateKey = privateKey.substring(2);
        }

        Credentials creds = Credentials.create(privateKey);
        log.info("[BSN Besu] 钱包地址: {}", creds.getAddress());
        return creds;
    }

    @Bean
    public StaticGasProvider gasProvider() {
        BigInteger gasPrice = BigInteger.valueOf(besuConfig.getGas().getGasPrice());
        BigInteger gasLimit = BigInteger.valueOf(besuConfig.getGas().getGasLimit());
        log.info("[BSN Besu] Gas 配置: price={}, limit={}", gasPrice, gasLimit);
        return new StaticGasProvider(gasPrice, gasLimit);
    }

    @PreDestroy
    public void cleanup() {
        log.info("[BSN Besu] 正在关闭 Web3j 连接...");
        if (web3jInstance != null) {
            try {
                web3jInstance.shutdown();
                log.info("[BSN Besu] Web3j 连接已关闭");
            } catch (Exception e) {
                log.warn("[BSN Besu] 关闭 Web3j 连接时出错: {}", e.getMessage());
            }
        }
    }
}
