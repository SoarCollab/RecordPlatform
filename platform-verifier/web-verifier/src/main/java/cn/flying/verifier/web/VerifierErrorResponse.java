package cn.flying.verifier.web;

/**
 * Stable request-layer error returned when a verification report cannot be created.
 */
public record VerifierErrorResponse(
        String schemaVersion,
        String code,
        String message
) {
    public static final String SCHEMA_VERSION = "record-platform-verifier-error.v1";

    /** Creates one response with the frozen error schema version. */
    public static VerifierErrorResponse of(String code, String message) {
        return new VerifierErrorResponse(SCHEMA_VERSION, code, message);
    }
}
