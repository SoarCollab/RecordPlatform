package cn.flying.verifier.model;

/** Current public proof lifecycle state resolved outside the signed archive. */
public record PublicProofStatus(
        String proofId,
        String status,
        String statusVersion,
        String issuedStatus,
        String keyId,
        Integer keyVersion,
        String reason,
        String issuedAt,
        String updatedAt,
        String source
) {
}
