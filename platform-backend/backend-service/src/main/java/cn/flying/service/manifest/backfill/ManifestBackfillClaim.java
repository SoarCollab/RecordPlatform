package cn.flying.service.manifest.backfill;

import cn.flying.dao.entity.ManifestBackfillItem;

import java.util.List;

/**
 * One lease-fenced apply claim.
 *
 * @param tenantId tenant ID
 * @param runId run ID
 * @param claimToken ownership token
 * @param items claimed items
 */
public record ManifestBackfillClaim(
        Long tenantId,
        Long runId,
        String claimToken,
        List<ManifestBackfillItem> items
) {
}
