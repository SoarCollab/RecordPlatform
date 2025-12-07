package cn.flying.common.tenant;

/**
 * Thread-local tenant context for multi-tenant isolation.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> TENANT_HOLDER = new ThreadLocal<>();
    private static final long DEFAULT_TENANT_ID = 0L;

    private TenantContext() {}

    public static void setTenantId(Long tenantId) {
        if (tenantId != null) {
            TENANT_HOLDER.set(tenantId);
        }
    }

    public static Long getTenantId() {
        return TENANT_HOLDER.get();
    }

    public static Long getTenantIdOrDefault() {
        Long tenantId = TENANT_HOLDER.get();
        return tenantId != null ? tenantId : DEFAULT_TENANT_ID;
    }

    public static void clear() {
        TENANT_HOLDER.remove();
    }
}
