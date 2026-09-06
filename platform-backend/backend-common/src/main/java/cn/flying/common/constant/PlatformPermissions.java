package cn.flying.common.constant;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Defines platform capabilities that cannot be granted through tenant permission data. */
public final class PlatformPermissions {

    public static final String TENANT_READ = "platform:tenant:read";
    public static final String TENANT_WRITE = "platform:tenant:write";
    public static final String USER_READ = "platform:user:read";
    public static final String USER_WRITE = "platform:user:write";
    public static final String QUOTA_READ = "platform:quota:read";
    public static final String QUOTA_WRITE = "platform:quota:write";
    public static final String CONFIGURATION_READ = "platform:configuration:read";
    public static final String CONFIGURATION_WRITE = "platform:configuration:write";
    public static final String RESOURCE_READ = "platform:resource:read";
    public static final String AUDIT_READ = "platform:audit:read";
    public static final String OVERVIEW_READ = "platform:overview:read";

    private static final Set<String> CODES = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            TENANT_READ, TENANT_WRITE, USER_READ, USER_WRITE, QUOTA_READ, QUOTA_WRITE,
            CONFIGURATION_READ, CONFIGURATION_WRITE, RESOURCE_READ, AUDIT_READ, OVERVIEW_READ)));

    /** Prevents instances of the fixed capability registry. */
    private PlatformPermissions() {
    }

    /** Returns the immutable capability set in a stable presentation order. */
    public static Set<String> allCodes() {
        return CODES;
    }

    /** Reserves the entire platform namespace, including case and whitespace variants. */
    public static boolean isReserved(String permissionCode) {
        return permissionCode != null
                && permissionCode.strip().toLowerCase(Locale.ROOT).startsWith("platform:");
    }
}
