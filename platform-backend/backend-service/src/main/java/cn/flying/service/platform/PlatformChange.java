package cn.flying.service.platform;

/** Internal allowlisted command outcome used to complete the system-owned operation record. */
public record PlatformChange(
        Long targetTenantId, Long resourceId, String resourceKey, Long version,
        String beforeSummary, String afterSummary) {
}
