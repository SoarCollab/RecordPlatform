package cn.flying.service.key;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for file data-key envelope storage and wrapping.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "file.key-envelope")
public class FileKeyEnvelopeProperties {

    private String provider = "local";

    private String kmsKeyId = "local-file-key-v1";

    private String localMasterKey;

    private Integer keyVersion = 1;

    private String algorithmSuite = "RP-AES256-GCM-CHUNK-CHAIN-V1";

    private String encryptionAlgorithm = "CHUNK_KEY_CHAIN";

    private String wrappingAlgorithm = "AES-256-GCM";
}
