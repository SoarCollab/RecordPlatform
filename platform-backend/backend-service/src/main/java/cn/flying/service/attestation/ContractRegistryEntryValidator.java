package cn.flying.service.attestation;

import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 统一校验批次创建与历史证明读取使用的 Sharing 合约注册表结构。
 */
public final class ContractRegistryEntryValidator {

    private static final String REGISTRY_SCHEMA = "record-platform-contract-registry-entry.v1";
    private static final String ABI_FINGERPRINT_ALGORITHM = "ABI-CANONICAL-JSON-SHA256-V1";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final Pattern SHA256_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("0x[0-9a-f]{40}");
    private static final Pattern TRANSACTION_HASH_PATTERN = Pattern.compile("0x[0-9a-f]{64}");
    private static final Pattern SEMANTIC_VERSION_PATTERN = Pattern.compile(
            "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?");
    private static final Pattern EFFECTIVE_AT_PATTERN = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
                    + "(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})");
    private static final Set<String> FISCO_CHAIN_TYPES = Set.of("LOCAL_FISCO", "BSN_FISCO");
    private static final Set<String> ALLOWED_CHAIN_TYPES = Set.of(
            "LOCAL_FISCO", "BSN_FISCO", "BSN_BESU");
    private static final Set<String> ACTIVE_STATUS = Set.of("ACTIVE");
    private static final Set<String> ISSUABLE_STATUSES = Set.of("ACTIVE", "DEPRECATED");
    private static final Set<String> PERSISTED_STATUSES = Set.of(
            "ACTIVE", "DEPRECATED", "REVOKED");

    /**
     * 禁止实例化无状态校验器。
     */
    private ContractRegistryEntryValidator() {
    }

    /**
     * 校验 provider 当前写入入口只能使用结构完整且已经生效的 ACTIVE Sharing 条目。
     */
    public static boolean isValidActiveSharingRegistry(ContractRegistryEntryResponse registry) {
        return isValidSharingRegistry(registry, ACTIVE_STATUS);
    }

    /**
     * 校验签名证明只能引用结构完整且处于历史可签发状态的 Sharing 条目。
     */
    public static boolean isValidIssuableSharingRegistry(ContractRegistryEntryResponse registry) {
        return isValidSharingRegistry(registry, ISSUABLE_STATUSES);
    }

    /**
     * 校验历史批次快照在支持的生命周期状态下仍满足完整 Sharing 结构合同。
     */
    public static boolean isValidPersistedSharingRegistry(ContractRegistryEntryResponse registry) {
        return isValidSharingRegistry(registry, PERSISTED_STATUSES);
    }

    /**
     * 按允许状态集合校验 schema、链身份、地址、指纹、部署证据和生效时间。
     */
    private static boolean isValidSharingRegistry(
            ContractRegistryEntryResponse registry,
            Set<String> allowedStatuses
    ) {
        if (registry == null) {
            return false;
        }
        boolean fiscoChain = registry.chainType() != null
                && FISCO_CHAIN_TYPES.contains(registry.chainType());
        boolean deploymentEvidencePaired =
                StringUtils.hasText(registry.deploymentTransactionHash())
                        == (registry.deploymentBlockNumber() != null);
        if (!REGISTRY_SCHEMA.equals(registry.schemaVersion())
                || !hasSha256(registry.registryFingerprint())
                || !registry.hasValidRegistryFingerprint()
                || !"Sharing".equals(registry.contractName())
                || !StringUtils.hasText(registry.semanticVersion())
                || !SEMANTIC_VERSION_PATTERN.matcher(registry.semanticVersion()).matches()
                || registry.chainType() == null
                || !ALLOWED_CHAIN_TYPES.contains(registry.chainType())
                || !StringUtils.hasText(registry.chainId())
                || (fiscoChain && !StringUtils.hasText(registry.groupId()))
                || (!fiscoChain && StringUtils.hasText(registry.groupId()))
                || registry.contractAddress() == null
                || !ADDRESS_PATTERN.matcher(registry.contractAddress()).matches()
                || ZERO_ADDRESS.equals(registry.contractAddress())
                || !ABI_FINGERPRINT_ALGORITHM.equals(registry.abiFingerprintAlgorithm())
                || !hasSha256(registry.abiSha256())
                || !hasSha256(registry.artifactBytecodeSha256())
                || !hasSha256(registry.onChainCodeSha256())
                || !deploymentEvidencePaired
                || (registry.deploymentTransactionHash() != null
                        && !TRANSACTION_HASH_PATTERN.matcher(
                                registry.deploymentTransactionHash()).matches())
                || (registry.deploymentBlockNumber() != null
                        && registry.deploymentBlockNumber() < 0)
                || registry.status() == null
                || !allowedStatuses.contains(registry.status())
                || !"REDEPLOY_ADDRESS".equals(registry.upgradeStrategy())
                || registry.effectiveAt() == null
                || !EFFECTIVE_AT_PATTERN.matcher(registry.effectiveAt()).matches()) {
            return false;
        }
        return isEffectiveAtValid(registry.effectiveAt());
    }

    /**
     * 校验 RFC 3339 生效时间可解析且不晚于当前时间。
     */
    private static boolean isEffectiveAtValid(String effectiveAtValue) {
        try {
            return !OffsetDateTime.parse(effectiveAtValue).toInstant().isAfter(Instant.now());
        } catch (DateTimeParseException | NullPointerException ignored) {
            return false;
        }
    }

    /**
     * 判断文本是否为统一的 lowercase SHA-256 指纹。
     */
    private static boolean hasSha256(String value) {
        return value != null && SHA256_PATTERN.matcher(value).matches();
    }
}
