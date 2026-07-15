package cn.flying.verifier.internal;

import cn.flying.verifier.model.VerificationSummary;

/** Mutable request-local builder for the immutable public report summary. */
public final class VerificationSummaryBuilder {

    public String proofId;
    public String manifestSchema;
    public String fileId;
    public Integer fileVersion;
    public String leafId;
    public String batchNo;
    public String issuedAt;
    public String issuedStatus;
    public String currentStatus;
    public String keyId;
    public Integer keyVersion;
    public String keyFingerprint;
    public String keySource;
    public String contentHash;
    public String computedContentHash;
    public String merkleRoot;
    public String computedMerkleRoot;
    public String chainType;
    public String chainId;
    public String groupId;
    public String contractAddress;
    public String contractVersion;
    public String abiFingerprint;
    public String batchTransactionHash;
    public Long liveBlockNumber;
    public String liveChainSource;

    /** Builds the immutable safe summary. */
    public VerificationSummary build() {
        return new VerificationSummary(
                safeText(proofId),
                safeText(manifestSchema),
                safeText(fileId),
                fileVersion,
                safeText(leafId),
                safeText(batchNo),
                safeText(issuedAt),
                safeText(issuedStatus),
                safeText(currentStatus),
                safeText(keyId),
                keyVersion,
                safeText(keyFingerprint),
                safeText(keySource),
                safeText(contentHash),
                safeText(computedContentHash),
                safeText(merkleRoot),
                safeText(computedMerkleRoot),
                safeText(chainType),
                safeText(chainId),
                safeText(groupId),
                safeText(contractAddress),
                safeText(contractVersion),
                safeText(abiFingerprint),
                safeText(batchTransactionHash),
                liveBlockNumber,
                safeText(liveChainSource));
    }

    /** Removes terminal/browser control characters and bounds every untrusted summary value. */
    private String safeText(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), 512));
        for (int index = 0; index < value.length() && sanitized.length() < 512; index++) {
            char character = value.charAt(index);
            sanitized.append(Character.isISOControl(character) ? ' ' : character);
        }
        return sanitized.toString().trim();
    }
}
