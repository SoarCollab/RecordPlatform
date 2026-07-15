package cn.flying.verifier;

import cn.flying.verifier.contract.SignedProofBundleContract;

/**
 * Resource limits applied before or during proof verification.
 */
public record VerificationLimits(
        long maxOriginalFileBytes,
        long maxArchiveBytes,
        int maxEntryBytes,
        int maxTotalEntryBytes,
        int maxChunks,
        int maxProofNodes
) {

    /**
     * Returns conservative SDK defaults suitable for the CLI.
     *
     * @return default limits
     */
    public static VerificationLimits defaults() {
        return new VerificationLimits(
                4L * 1024 * 1024 * 1024,
                SignedProofBundleContract.MAX_ARCHIVE_BYTES,
                SignedProofBundleContract.MAX_ENTRY_BYTES,
                SignedProofBundleContract.MAX_TOTAL_ENTRY_BYTES,
                SignedProofBundleContract.MAX_CHUNKS,
                SignedProofBundleContract.MAX_PROOF_NODES);
    }

    /**
     * Validates that every configured limit is positive and within the frozen ZIP contract.
     */
    public VerificationLimits {
        if (maxOriginalFileBytes <= 0
                || maxArchiveBytes <= 0
                || maxEntryBytes <= 0
                || maxTotalEntryBytes <= 0
                || maxChunks <= 0
                || maxProofNodes <= 0
                || maxArchiveBytes > SignedProofBundleContract.MAX_ARCHIVE_BYTES
                || maxEntryBytes > SignedProofBundleContract.MAX_ENTRY_BYTES
                || maxTotalEntryBytes > SignedProofBundleContract.MAX_TOTAL_ENTRY_BYTES
                || maxChunks > SignedProofBundleContract.MAX_CHUNKS
                || maxProofNodes > SignedProofBundleContract.MAX_PROOF_NODES) {
            throw new IllegalArgumentException("Verification limits exceed the signed proof contract");
        }
    }
}
