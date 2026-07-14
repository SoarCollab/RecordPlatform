package cn.flying.platformapi.response;

import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 可持久化的合约注册表快照，用于把一次链写与确定的链、地址和 ABI 绑定。
 *
 * @param schemaVersion 注册表条目 schema
 * @param registryFingerprint 条目关键字段的稳定 SHA-256 指纹
 * @param contractName 合约名称
 * @param semanticVersion 合约语义版本
 * @param chainType 链适配器类型
 * @param chainId 节点实际返回的链 ID
 * @param groupId FISCO 群组 ID；非 FISCO 链为空
 * @param contractAddress 规范化后的合约地址
 * @param abiFingerprintAlgorithm ABI 指纹算法
 * @param abiSha256 ABI 指纹
 * @param artifactBytecodeSha256 签入构建产物的 creation bytecode 指纹
 * @param onChainCodeSha256 节点实际返回的 runtime code 指纹
 * @param deploymentTransactionHash 部署交易哈希；未登记时为空
 * @param deploymentBlockNumber 部署区块号；未登记时为空
 * @param status 生命周期状态
 * @param effectiveAt 生效时间
 * @param upgradeStrategy 升级策略
 */
public record ContractRegistryEntryResponse(
        String schemaVersion,
        String registryFingerprint,
        String contractName,
        String semanticVersion,
        String chainType,
        String chainId,
        String groupId,
        String contractAddress,
        String abiFingerprintAlgorithm,
        String abiSha256,
        String artifactBytecodeSha256,
        String onChainCodeSha256,
        String deploymentTransactionHash,
        Long deploymentBlockNumber,
        String status,
        String effectiveAt,
        String upgradeStrategy
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /**
     * 按 entry.v1 字段顺序重算注册表指纹，供 provider、backend 与 verifier 共享。
     *
     * @return sha256 前缀的小写十六进制指纹
     */
    public String calculateRegistryFingerprint() {
        String payload = String.join("\n",
                "schemaVersion=" + schemaVersion,
                "contractName=" + contractName,
                "semanticVersion=" + semanticVersion,
                "chainType=" + chainType,
                "chainId=" + chainId,
                "groupId=" + nullToEmpty(groupId),
                "contractAddress=" + contractAddress,
                "abiFingerprintAlgorithm=" + abiFingerprintAlgorithm,
                "abiSha256=" + abiSha256,
                "artifactBytecodeSha256=" + artifactBytecodeSha256,
                "onChainCodeSha256=" + onChainCodeSha256,
                "deploymentTransactionHash=" + nullToEmpty(deploymentTransactionHash),
                "deploymentBlockNumber=" + Objects.toString(deploymentBlockNumber, ""),
                "status=" + status,
                "effectiveAt=" + effectiveAt,
                "upgradeStrategy=" + upgradeStrategy);
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not support SHA-256", e);
        }
    }

    /**
     * 校验快照携带的指纹是否与当前全部关键字段一致。
     *
     * @return 字段指纹完全一致时返回 true
     */
    public boolean hasValidRegistryFingerprint() {
        return Objects.equals(registryFingerprint, calculateRegistryFingerprint());
    }

    /**
     * 返回写入当前字段计算结果的新快照，避免调用方重复维护 payload 顺序。
     *
     * @return 带自洽 registry fingerprint 的不可变快照
     */
    public ContractRegistryEntryResponse withCalculatedRegistryFingerprint() {
        return new ContractRegistryEntryResponse(
                schemaVersion,
                calculateRegistryFingerprint(),
                contractName,
                semanticVersion,
                chainType,
                chainId,
                groupId,
                contractAddress,
                abiFingerprintAlgorithm,
                abiSha256,
                artifactBytecodeSha256,
                onChainCodeSha256,
                deploymentTransactionHash,
                deploymentBlockNumber,
                status,
                effectiveAt,
                upgradeStrategy);
    }

    /**
     * 把可空字段转换为注册表 payload 约定的空串。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
