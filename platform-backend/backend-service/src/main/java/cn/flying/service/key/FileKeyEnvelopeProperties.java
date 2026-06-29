package cn.flying.service.key;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

    private Map<Integer, String> localMasterKeys = new LinkedHashMap<>();

    private Integer keyVersion = 1;

    private String algorithmSuite = "RP-AES256-GCM-CHUNK-CHAIN-V1";

    private String signatureSuite = "UNSIGNED-V1";

    private String kemSuite = "NONE-V1";

    private String proofSuite = "RP-MERKLE-SHA256-V1";

    private String encryptionAlgorithm = "CHUNK_KEY_CHAIN";

    private String wrappingAlgorithm = "AES-256-GCM";

    private Instant deprecatedAfter;

    private Set<String> supportedAlgorithmSuites = new LinkedHashSet<>(
            Set.of("RP-AES256-GCM-CHUNK-CHAIN-V1")
    );

    private Set<String> supportedSignatureSuites = new LinkedHashSet<>(
            Set.of("UNSIGNED-V1")
    );

    private Set<String> supportedKemSuites = new LinkedHashSet<>(
            Set.of("NONE-V1")
    );

    private Set<String> supportedProofSuites = new LinkedHashSet<>(
            Set.of("RP-MERKLE-SHA256-V1")
    );

    private Set<String> deprecatedSuites = new LinkedHashSet<>();
}
