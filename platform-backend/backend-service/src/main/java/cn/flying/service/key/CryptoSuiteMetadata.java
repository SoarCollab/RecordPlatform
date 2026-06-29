package cn.flying.service.key;

import java.time.Instant;
import java.util.Date;

/**
 * Current crypto agility metadata emitted at file, envelope, and proof boundaries.
 *
 * @param algorithmSuite content encryption suite identifier
 * @param signatureSuite proof or issuer signature suite identifier
 * @param kemSuite recipient key-establishment suite identifier
 * @param proofSuite proof construction suite identifier
 * @param keyVersion wrapping key version
 * @param deprecatedAfter optional deprecation instant for the active suite set
 */
public record CryptoSuiteMetadata(
        String algorithmSuite,
        String signatureSuite,
        String kemSuite,
        String proofSuite,
        Integer keyVersion,
        Instant deprecatedAfter
) {

    /**
     * Converts deprecation metadata to a defensive Date copy for persistence or API output.
     */
    public Date deprecatedAfterDate() {
        return deprecatedAfter == null ? null : Date.from(deprecatedAfter);
    }

    /**
     * Converts deprecation metadata to a stable ISO-8601 string for JSON file metadata.
     */
    public String deprecatedAfterIso() {
        return deprecatedAfter == null ? null : deprecatedAfter.toString();
    }
}
