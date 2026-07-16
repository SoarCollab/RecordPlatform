package cn.flying.fisco_bcos.registry;

import java.util.List;
import java.util.Map;

/**
 * 版本控制中的合约构建产物目录。
 *
 * @param schemaVersion catalog schema
 * @param abiFingerprintAlgorithm ABI 指纹算法
 * @param bytecodeFingerprintAlgorithm bytecode 指纹算法
 * @param sourceFingerprintAlgorithm 源码指纹算法
 * @param contracts 合约构建产物条目
 */
public record ContractArtifactCatalog(
        String schemaVersion,
        String abiFingerprintAlgorithm,
        String bytecodeFingerprintAlgorithm,
        String sourceFingerprintAlgorithm,
        List<ContractArtifact> contracts
) {

    /**
     * 单个合约版本对应的签入构建产物身份。
     *
     * @param contractName 合约名称
     * @param semanticVersion 语义版本
     * @param status 生命周期状态
     * @param effectiveAt 生效时间
     * @param upgradeStrategy 升级策略
     * @param sourcePaths 双份 Solidity 源路径
     * @param sourceSha256 规范化源码指纹
     * @param abiPath ABI 路径
     * @param abiSha256 canonical ABI 指纹
     * @param creationBytecodePaths ECC/SM creation bytecode 路径
     * @param creationBytecodeSha256 ECC/SM creation bytecode 指纹
     * @param runtimeBytecodePaths ECC/SM deployed runtime bytecode 路径
     * @param runtimeBytecodeSha256 ECC/SM deployed runtime bytecode 指纹
     */
    public record ContractArtifact(
            String contractName,
            String semanticVersion,
            String status,
            String effectiveAt,
            String upgradeStrategy,
            List<String> sourcePaths,
            String sourceSha256,
            String abiPath,
            String abiSha256,
            Map<String, String> creationBytecodePaths,
            Map<String, String> creationBytecodeSha256,
            Map<String, String> runtimeBytecodePaths,
            Map<String, String> runtimeBytecodeSha256
    ) {
    }
}
