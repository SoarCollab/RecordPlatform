package cn.flying.verifier.model;

import java.util.Map;

/**
 * One stable, human-readable and machine-readable verifier check.
 *
 * @param id stable dimension identifier
 * @param category report category
 * @param status result status
 * @param code stable code
 * @param message safe human-readable message
 * @param evidence bounded non-secret evidence summary
 */
public record VerificationCheck(
        String id,
        String category,
        VerificationCheckStatus status,
        VerificationCode code,
        String message,
        Map<String, String> evidence
) {
    public VerificationCheck {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
