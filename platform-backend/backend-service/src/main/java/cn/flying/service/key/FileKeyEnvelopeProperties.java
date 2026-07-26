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
 * 文件数据密钥信封存储与包封配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "file.key-envelope")
public class FileKeyEnvelopeProperties {

    private String activeProvider = LocalKeyWrappingService.PROVIDER_ID;

    private Integer activeProviderContractVersion = 1;

    private Providers providers = new Providers();

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

    /**
     * 返回兼容旧调用方的 active provider 配置。
     */
    public String getProvider() {
        return activeProvider;
    }

    /**
     * 将旧 provider setter 映射到新的 active provider 配置。
     */
    public void setProvider(String provider) {
        this.activeProvider = provider;
    }

    /**
     * 返回兼容旧调用方的 local key id。
     */
    public String getKmsKeyId() {
        return providers.local.keyId;
    }

    /**
     * 将旧 key id setter 映射到 local provider 配置。
     */
    public void setKmsKeyId(String kmsKeyId) {
        providers.local.keyId = kmsKeyId;
    }

    /**
     * 返回兼容旧调用方的 local master key。
     */
    public String getLocalMasterKey() {
        return providers.local.masterKey;
    }

    /**
     * 将旧 master key setter 映射到 local provider 配置。
     */
    public void setLocalMasterKey(String localMasterKey) {
        providers.local.masterKey = localMasterKey;
    }

    /**
     * 返回兼容旧调用方的 local 历史 master key 映射。
     */
    public Map<Integer, String> getLocalMasterKeys() {
        return providers.local.masterKeys;
    }

    /**
     * 将旧历史 master key setter 映射到 local provider 配置。
     */
    public void setLocalMasterKeys(Map<Integer, String> localMasterKeys) {
        providers.local.masterKeys = localMasterKeys == null ? new LinkedHashMap<>() : localMasterKeys;
    }

    /**
     * 按 provider 隔离的包封配置集合。
     */
    @Getter
    @Setter
    public static class Providers {

        private Local local = new Local();

        private VaultTransit vaultTransit = new VaultTransit();
    }

    /**
     * 用于开发和历史读取的 local provider 配置。
     */
    @Getter
    @Setter
    public static class Local {

        private String keyId = "local-file-key-v1";

        private Set<String> historicalKeyIds = new LinkedHashSet<>();

        private String masterKey;

        private Map<Integer, String> masterKeys = new LinkedHashMap<>();
    }

    /**
     * Vault Transit provider 配置。
     */
    @Getter
    @Setter
    public static class VaultTransit {

        private String address;

        private String token;

        private String namespace;

        private String mount = "transit";

        private String keyName;

        private Integer keyVersion = 1;

        private boolean allowHttp;

        private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(2);

        private java.time.Duration requestTimeout = java.time.Duration.ofSeconds(5);

        private int maxRequestBytes = 65_536;

        private int maxResponseBytes = 65_536;
    }
}
