package cn.flying.fisco_bcos.registry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 合约注册表 catalog 位置与可选部署证据配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contract-registry")
public class ContractRegistryProperties {

    /** classpath 或文件系统中的 artifact catalog。 */
    private String catalogLocation = "classpath:contract-registry/artifacts.json";

    /** 已知部署交易元数据；tx、block、effectiveAt 必须同时提供或同时保持为空。 */
    private Deployment deployment = new Deployment();

    /**
     * Sharing 与 Storage 的部署证据。
     */
    @Data
    public static class Deployment {
        private DeploymentEvidence sharing = new DeploymentEvidence();
        private DeploymentEvidence storage = new DeploymentEvidence();
    }

    /**
     * 单个合约的可选部署交易与实际生效时间证据。
     */
    @Data
    public static class DeploymentEvidence {
        private String transactionHash;
        private Long blockNumber;
        private String effectiveAt;
    }
}
