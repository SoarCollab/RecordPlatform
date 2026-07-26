package cn.flying.service.manifest.backfill;

/**
 * Complete scan outcome for one file candidate.
 *
 * @param status item lifecycle state
 * @param classification evidence classification
 * @param reason stable reason code
 * @param retryable whether retry is safe
 * @param legacyDownloadAllowed whether explicit bounded legacy recovery is permitted
 * @param evidenceDigest digest of normalized evidence
 * @param evidencePayload serialized normalized evidence for apply, or null
 * @param manifestId existing active manifest when applicable
 */
public record ManifestEvidenceResolution(
        ManifestBackfillItemStatus status,
        ManifestBackfillClassification classification,
        ManifestBackfillReason reason,
        boolean retryable,
        boolean legacyDownloadAllowed,
        String evidenceDigest,
        String evidencePayload,
        Long manifestId
) {
}
