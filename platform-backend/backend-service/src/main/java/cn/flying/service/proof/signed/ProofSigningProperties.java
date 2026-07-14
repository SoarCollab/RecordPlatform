package cn.flying.service.proof.signed;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 专用于 proof bundle 的 Ed25519 签名配置，禁止从 JWT 或文件加密密钥回退。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "proof.signing")
public class ProofSigningProperties {

    private boolean enabled = false;

    private String algorithm = "Ed25519";

    private String keyId;

    private Integer keyVersion = 1;

    private String keyStatus = "DISABLED";

    private String privateKeyPkcs8;

    private String publicKeySpki;
}
