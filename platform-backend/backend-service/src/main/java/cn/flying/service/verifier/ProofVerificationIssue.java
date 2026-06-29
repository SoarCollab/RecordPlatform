package cn.flying.service.verifier;

/**
 * One structured verifier issue suitable for CLI or web presentation.
 */
public record ProofVerificationIssue(
        ProofVerificationCode code,
        ProofVerificationSeverity severity,
        String field,
        String message
) {
}
