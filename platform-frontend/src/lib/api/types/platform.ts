import type { Page } from "./common";
import type { components, operations } from "./generated";

type Schema<Name extends keyof components["schemas"]> =
  components["schemas"][Name];

/** Code-owned capabilities from PlatformPermissions, never tenant grants. */
export const PLATFORM_CAPABILITIES = [
  "platform:tenant:read",
  "platform:tenant:write",
  "platform:user:read",
  "platform:user:write",
  "platform:quota:read",
  "platform:quota:write",
  "platform:configuration:read",
  "platform:configuration:write",
  "platform:resource:read",
  "platform:audit:read",
  "platform:overview:read",
] as const;

export type PlatformCapability = (typeof PLATFORM_CAPABILITIES)[number];
export type PlatformSession = Omit<
  Schema<"PlatformSessionVO">,
  "capabilities"
> & {
  capabilities: PlatformCapability[];
};
export type PlatformSessionVO = PlatformSession;
export type PlatformOverview = Schema<"PlatformOverviewVO">;
export type PlatformTenant = Schema<"PlatformTenantVO"> & { status: 0 | 1 };
export type PlatformUser = Schema<"PlatformUserVO"> & { status: 0 | 1 };
export type PlatformRole = PlatformUser["role"];

type Member = Schema<"TenantMemberVO">;
export type PlatformMember = Required<
  Pick<Member, "id" | "username" | "email" | "role" | "registerTime">
> & {
  // Jackson may retain null optional profile fields or omit them under NON_NULL.
  nickname?: Member["nickname"] | null;
  lastLoginTime?: Member["lastLoginTime"] | null;
  status: 0 | 1;
};
export type PlatformInvitation = Required<
  Omit<Schema<"TenantInvitationVO">, "role" | "status">
> & {
  role: PlatformRole;
  status: "PENDING" | "ACCEPTED" | "REVOKED" | "EXPIRED";
};

export type PlatformQuota = Schema<"PlatformQuotaVO">;
export type PlatformUsage = Schema<"PlatformUsageVO"> & {
  auditScope: "TENANT_BUSINESS_OPERATION_LOG";
  attestationScope: "TENANT_COMPLETED_ATTESTATION_BATCH";
};
export type PlatformHealthStatus = Schema<"PlatformHealthVO">["status"];
export type PlatformHealthComponent =
  | "database"
  | "redis"
  | "storage"
  | "blockchain";
export type PlatformHealth = Omit<Schema<"PlatformHealthVO">, "components"> & {
  components: Record<PlatformHealthComponent, PlatformHealthStatus>;
};
export const PLATFORM_CONFIGURATION_KEYS = [
  "HIGH_FREQ_THRESHOLD",
  "FAILED_LOGIN_THRESHOLD",
  "ERROR_RATE_THRESHOLD",
  "LOG_RETENTION_DAYS",
] as const;
export type PlatformConfigurationKey =
  (typeof PLATFORM_CONFIGURATION_KEYS)[number];
export type PlatformConfiguration = Schema<"PlatformConfigurationVO"> & {
  key: PlatformConfigurationKey;
  scope: "GLOBAL";
  source: "DATABASE";
};

export const PLATFORM_OPERATIONS = [
  "TENANT_CREATE",
  "TENANT_UPDATE",
  "TENANT_STATUS_CHANGE",
  "USER_ROLE_CHANGE",
  "USER_STATUS_CHANGE",
  "USER_SESSIONS_REVOKE",
  "INVITATION_CREATE",
  "INVITATION_REVOKE",
  "QUOTA_UPDATE",
  "CONFIGURATION_UPDATE",
] as const;
export type PlatformOperation = (typeof PLATFORM_OPERATIONS)[number];
export type PlatformMutationVO = Schema<"PlatformMutationVO">;
export type PlatformAudit = Schema<"PlatformAuditVO"> & {
  operation: PlatformOperation;
  resourceType: "TENANT" | "USER" | "INVITATION" | "QUOTA" | "CONFIGURATION";
};
export type PlatformPage<T> = Page<T>;

export type PlatformTenantQuery = NonNullable<
  operations["platformListTenants"]["parameters"]["query"]
> & { status?: 0 | 1 };
export type PlatformUserQuery = NonNullable<
  operations["platformListUsers"]["parameters"]["query"]
> & { status?: 0 | 1; role?: PlatformRole };
export type PlatformMemberQuery = NonNullable<
  operations["platformListTenantMembers"]["parameters"]["query"]
> & { status?: 0 | 1; role?: PlatformRole };
export type PlatformAuditQuery = NonNullable<
  operations["platformListAudit"]["parameters"]["query"]
> & { status?: PlatformAudit["status"] };

export type CreatePlatformTenantRequest =
  operations["platformCreateTenant"]["requestBody"]["content"]["application/json"];
export type UpdatePlatformTenantRequest =
  operations["platformUpdateTenant"]["requestBody"]["content"]["application/json"];
export type ChangePlatformTenantStatusRequest =
  operations["platformChangeTenantStatus"]["requestBody"]["content"]["application/json"] & {
    status: 0 | 1;
  };
export type ChangePlatformMemberRoleRequest =
  operations["platformChangeTenantMemberRole"]["requestBody"]["content"]["application/json"] & {
    role: PlatformRole;
  };
export type ChangePlatformMemberStatusRequest =
  operations["platformChangeTenantMemberStatus"]["requestBody"]["content"]["application/json"] & {
    status: 0 | 1;
  };
export type PlatformReasonRequest =
  operations["platformRevokeTenantMemberSessions"]["requestBody"]["content"]["application/json"];
export type CreatePlatformInvitationRequest =
  operations["platformInviteTenantMember"]["requestBody"]["content"]["application/json"] & {
    role: PlatformRole;
  };
export type UpdatePlatformQuotaRequest =
  operations["platformUpdateTenantQuota"]["requestBody"]["content"]["application/json"];
export type UpdatePlatformConfigurationRequest =
  operations["platformUpdateConfiguration"]["requestBody"]["content"]["application/json"];
