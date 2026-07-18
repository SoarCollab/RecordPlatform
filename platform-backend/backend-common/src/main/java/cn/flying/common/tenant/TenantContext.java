package cn.flying.common.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Thread-local tenant context for multi-tenant isolation.
 * <p>
 * Supports:
 * - Tenant ID management via ThreadLocal
 * - Ignore isolation flag for cross-tenant operations
 * - Context switching utilities for async tasks and scheduled jobs
 */
public final class TenantContext {

    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);
    private static final ThreadLocal<Long> TENANT_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IGNORE_ISOLATION = ThreadLocal.withInitial(() -> false);
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
        IGNORE_ISOLATION.remove();
    }

    // ========== Ignore Isolation Support ==========

    /**
     * Set ignore isolation flag. When true, TenantLineInterceptor will skip tenant filtering.
     * Use this for cross-tenant operations like scheduled cleanup tasks.
     */
    public static void setIgnoreIsolation(boolean ignore) {
        IGNORE_ISOLATION.set(ignore);
    }

    /**
     * Check if tenant isolation should be ignored.
     */
    public static boolean isIgnoreIsolation() {
        return Boolean.TRUE.equals(IGNORE_ISOLATION.get());
    }

    /**
     * Clear ignore isolation flag.
     */
    public static void clearIgnoreIsolation() {
        IGNORE_ISOLATION.remove();
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

    /**
     * 在指定租户内执行操作，并在执行期间强制启用租户隔离。
     * 适用于从窄全局元数据查询恢复 owner tenant 后的完整数据访问。
     */
    public static void runWithTenantIsolation(Long tenantId, Runnable action) {
        Long previousTenantId = getTenantId();
        boolean previousIgnoreIsolation = isIgnoreIsolation();
        try {
            TENANT_HOLDER.set(requireIsolationTenantId(tenantId));
            IGNORE_ISOLATION.set(false);
            action.run();
        } finally {
            restoreContext(previousTenantId, previousIgnoreIsolation);
        }
    }

    /**
     * 在指定租户内执行有返回值的操作，并在执行期间强制启用租户隔离。
     * 结束时完整恢复调用前的租户与跨租户标志。
     */
    public static <T> T callWithTenantIsolation(Long tenantId, Supplier<T> action) {
        Long previousTenantId = getTenantId();
        boolean previousIgnoreIsolation = isIgnoreIsolation();
        try {
            TENANT_HOLDER.set(requireIsolationTenantId(tenantId));
            IGNORE_ISOLATION.set(false);
            return action.get();
        } finally {
            restoreContext(previousTenantId, previousIgnoreIsolation);
        }
    }

    /**
     * 校验隔离执行必须具备明确租户，防止空值沿用调用方上下文。
     */
    private static Long requireIsolationTenantId(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null for isolated execution");
        }
        return tenantId;
    }

    /**
     * 恢复隔离执行前的完整线程上下文。
     */
    private static void restoreContext(Long previousTenantId, boolean previousIgnoreIsolation) {
        if (previousTenantId == null) {
            TENANT_HOLDER.remove();
        } else {
            TENANT_HOLDER.set(previousTenantId);
        }
        if (previousIgnoreIsolation) {
            IGNORE_ISOLATION.set(true);
        } else {
            IGNORE_ISOLATION.remove();
        }
    }

    // ========== Cross-Tenant Operation Support ==========

    /**
     * Execute action without tenant isolation (cross-tenant query).
     * Use this for system-level operations that need to access all tenants' data.
     * Restores original isolation state after execution.
     *
     * @param action the action to execute
     * @param <T> return type
     * @return the result of the action
     */
    public static <T> T runWithoutIsolation(Supplier<T> action) {
        boolean previousIgnore = isIgnoreIsolation();
        try {
            setIgnoreIsolation(true);
            return action.get();
        } finally {
            setIgnoreIsolation(previousIgnore);
        }
    }

    /**
     * Execute action without tenant isolation (cross-tenant query).
     * Use this for system-level operations that need to access all tenants' data.
     * Restores original isolation state after execution.
     *
     * @param action the action to execute
     */
    public static void runWithoutIsolation(Runnable action) {
        boolean previousIgnore = isIgnoreIsolation();
        try {
            setIgnoreIsolation(true);
            action.run();
        } finally {
            setIgnoreIsolation(previousIgnore);
        }
    }
}
