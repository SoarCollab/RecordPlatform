package cn.flying.common.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Thread-local tenant context for multi-tenant isolation.
 */
public final class TenantContext {

    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);
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
        if (tenantId == null) {
            log.warn("TenantContext not set, using default tenant_id={}. " +
                    "This may indicate missing tenant context in async task, MQ consumer, or test.", DEFAULT_TENANT_ID);
            return DEFAULT_TENANT_ID;
        }
        return tenantId;
    }

    public static void clear() {
        TENANT_HOLDER.remove();
    }

    /**
     * Check if tenant context is set.
     */
    public static boolean isSet() {
        return TENANT_HOLDER.get() != null;
    }

    /**
     * Require tenant context to be set, throw exception if not.
     * Use this for critical operations that must have tenant isolation.
     */
    public static Long requireTenantId() {
        Long tenantId = TENANT_HOLDER.get();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext not set - tenant isolation required");
        }
        return tenantId;
    }

    /**
     * Execute action within specified tenant context (no return value).
     * Restores original context after execution.
     */
    public static void runWithTenant(Long tenantId, Runnable action) {
        Long previous = getTenantId();
        try {
            setTenantId(tenantId);
            action.run();
        } finally {
            if (previous == null) {
                clear();
            } else {
                setTenantId(previous);
            }
        }
    }

    /**
     * Execute action within specified tenant context (with return value).
     * Restores original context after execution.
     */
    public static <T> T callWithTenant(Long tenantId, Supplier<T> action) {
        Long previous = getTenantId();
        try {
            setTenantId(tenantId);
            return action.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                setTenantId(previous);
            }
        }
    }
}
