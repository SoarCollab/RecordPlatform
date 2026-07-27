package cn.flying.service.key.rotation;

import cn.flying.dao.entity.KeyRotationItem;

import java.util.List;

/**
 * Immutable worker ownership token and its bounded claimed items.
 */
public record KeyRotationClaim(
        Long tenantId,
        Long runId,
        String claimToken,
        List<KeyRotationItem> items
) {
}
