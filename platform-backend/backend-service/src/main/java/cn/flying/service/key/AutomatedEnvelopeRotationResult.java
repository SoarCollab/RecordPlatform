package cn.flying.service.key;

/**
 * Sanitized terminal or retryable outcome for one automated envelope item.
 */
public record AutomatedEnvelopeRotationResult(
        String outcome,
        Long candidateEnvelopeId,
        KeyWrappingFailureCategory failureCategory,
        boolean retryable
) {

    /**
     * Creates a successful or idempotently skipped outcome.
     */
    public static AutomatedEnvelopeRotationResult completed(String outcome, Long candidateEnvelopeId) {
        return new AutomatedEnvelopeRotationResult(
                outcome, candidateEnvelopeId, KeyWrappingFailureCategory.NONE, false);
    }

    /**
     * Creates a stable failure outcome without provider error text.
     */
    public static AutomatedEnvelopeRotationResult failed(KeyWrappingFailure failure) {
        return new AutomatedEnvelopeRotationResult(
                "FAILED", null, failure.category(), failure.retryable());
    }
}
