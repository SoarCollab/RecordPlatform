package cn.flying.service.platform;

import cn.flying.common.constant.PlatformPermissions;

/** Fixed operation taxonomy binds each command to its required platform capability. */
public enum PlatformOperationType {
    TENANT_CREATE(PlatformPermissions.TENANT_WRITE, "TENANT"),
    TENANT_UPDATE(PlatformPermissions.TENANT_WRITE, "TENANT"),
    TENANT_STATUS_CHANGE(PlatformPermissions.TENANT_WRITE, "TENANT"),
    USER_ROLE_CHANGE(PlatformPermissions.USER_WRITE, "USER"),
    USER_STATUS_CHANGE(PlatformPermissions.USER_WRITE, "USER"),
    USER_SESSIONS_REVOKE(PlatformPermissions.USER_WRITE, "USER"),
    INVITATION_CREATE(PlatformPermissions.USER_WRITE, "INVITATION"),
    INVITATION_REVOKE(PlatformPermissions.USER_WRITE, "INVITATION"),
    QUOTA_UPDATE(PlatformPermissions.QUOTA_WRITE, "QUOTA"),
    CONFIGURATION_UPDATE(PlatformPermissions.CONFIGURATION_WRITE, "CONFIGURATION");

    private final String permission;
    private final String resourceType;

    /** Binds a fixed command name to its code-owned authorization and resource type. */
    PlatformOperationType(String permission, String resourceType) {
        this.permission = permission;
        this.resourceType = resourceType;
    }

    /** Returns the authority that must be checked before accepting this command. */
    public String permission() {
        return permission;
    }

    /** Returns the fixed resource type used in dedicated platform audit. */
    public String resourceType() {
        return resourceType;
    }
}
