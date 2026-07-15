package cn.flying.verifier.model;

import java.util.List;

/**
 * Stable report returned by the SDK and serialized unchanged by CLI and Web.
 */
public record VerificationReport(
        String schemaVersion,
        VerificationOutcome outcome,
        String verifiedAt,
        String verifierVersion,
        VerificationSummary summary,
        List<VerificationCheck> checks
) {
    public static final String SCHEMA_VERSION = "record-platform-verification-report.v1";
    public static final String VERIFIER_VERSION = "0.0.2";

    public VerificationReport {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }
}
