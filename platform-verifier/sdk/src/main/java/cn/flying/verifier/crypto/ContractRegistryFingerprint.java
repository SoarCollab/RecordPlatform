package cn.flying.verifier.crypto;

import cn.flying.verifier.contract.SignedProofBundleModel;

import java.util.Objects;

/**
 * Reproduces the immutable contract-registry entry.v1 fingerprint contract.
 */
public final class ContractRegistryFingerprint {

    private ContractRegistryFingerprint() {
    }

    /**
     * Calculates the registry fingerprint using the frozen ordered field list.
     *
     * @param entry registry evidence
     * @return prefixed lowercase SHA-256
     */
    public static String calculate(SignedProofBundleModel.ContractRegistryEvidence entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Contract registry evidence is required");
        }
        String payload = String.join("\n",
                "schemaVersion=" + entry.schemaVersion(),
                "contractName=" + entry.contractName(),
                "semanticVersion=" + entry.semanticVersion(),
                "chainType=" + entry.chainType(),
                "chainId=" + entry.chainId(),
                "groupId=" + nullToEmpty(entry.groupId()),
                "contractAddress=" + entry.contractAddress(),
                "abiFingerprintAlgorithm=" + entry.abiFingerprintAlgorithm(),
                "abiSha256=" + entry.abiFingerprint(),
                "artifactBytecodeSha256=" + entry.artifactBytecodeSha256(),
                "onChainCodeSha256=" + entry.onChainCodeSha256(),
                "deploymentTransactionHash=" + nullToEmpty(entry.deploymentTransactionHash()),
                "deploymentBlockNumber=" + Objects.toString(entry.deploymentBlockNumber(), ""),
                "status=" + entry.status(),
                "effectiveAt=" + entry.effectiveAt(),
                "upgradeStrategy=" + entry.upgradeStrategy());
        return ProofHashes.sha256(payload);
    }

    /**
     * Converts an optional registry field to the canonical empty-string representation.
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
