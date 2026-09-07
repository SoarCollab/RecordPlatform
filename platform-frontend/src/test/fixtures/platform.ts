import {
  PLATFORM_CAPABILITIES,
  type PlatformAudit,
  type PlatformConfiguration,
  type PlatformHealth,
  type PlatformInvitation,
  type PlatformMember,
  type PlatformMutationVO,
  type PlatformOverview,
  type PlatformPage,
  type PlatformQuota,
  type PlatformSession,
  type PlatformTenant,
  type PlatformUsage,
  type PlatformUser,
} from "$api/types";

export const PLATFORM_TEST_IDS = {
  tenant: "tenant-alpha",
  user: "user-member",
  actor: "user-platform-admin",
  invitation: "invitation-alpha",
  operation: "operation-alpha",
} as const;

export const PLATFORM_TEST_KEY = "8914a4ab-c9c2-4e9f-8e48-8cffac10b17c";
export const PLATFORM_TEST_TIME = "2026-09-07T09:00:00";

/** Builds the generated session projection with only code-owned capabilities. */
export function platformSessionFixture(
  overrides: Partial<PlatformSession> = {},
): PlatformSession {
  return {
    actorId: PLATFORM_TEST_IDS.actor,
    username: "platform-admin",
    scope: "platform",
    systemTenantId: 0,
    capabilities: [...PLATFORM_CAPABILITIES],
    ...overrides,
  };
}

/** Supplies measured metadata counts without inventing metrics for failed requests. */
export function platformOverviewFixture(
  overrides: Partial<PlatformOverview> = {},
): PlatformOverview {
  return {
    tenants: 2,
    activeTenants: 1,
    disabledTenants: 1,
    users: 3,
    activeUsers: 2,
    state: "AVAILABLE",
    ...overrides,
  };
}

/** Includes every required-nullable tenant lifecycle field and a valid zero version. */
export function platformTenantFixture(
  overrides: Partial<PlatformTenant> = {},
): PlatformTenant {
  return {
    id: PLATFORM_TEST_IDS.tenant,
    code: "tenant-alpha",
    name: "Alpha tenant",
    status: 1,
    version: 0,
    memberCount: 1,
    disabledReason: null,
    disabledAt: null,
    disabledBy: null,
    createTime: PLATFORM_TEST_TIME,
    updateTime: null,
    ...overrides,
  };
}

/** Creates the global metadata projection without email or other private member fields. */
export function platformUserFixture(
  overrides: Partial<PlatformUser> = {},
): PlatformUser {
  return {
    id: PLATFORM_TEST_IDS.user,
    tenantId: PLATFORM_TEST_IDS.tenant,
    username: "member",
    nickname: null,
    role: "admin",
    status: 1,
    registerTime: null,
    lastLoginTime: null,
    ...overrides,
  };
}

/** Mirrors the target-member DTO, including Jackson Date text and nullable profile values. */
export function platformMemberFixture(
  overrides: Partial<PlatformMember> = {},
): PlatformMember {
  return {
    id: PLATFORM_TEST_IDS.user,
    username: "member",
    email: "member@example.test",
    role: "admin",
    status: 1,
    registerTime: "2026-09-07T09:00:00.000+08:00",
    nickname: null,
    lastLoginTime: null,
    ...overrides,
  };
}

/** Supplies invitation metadata whose capability is delivered only by mail. */
export function platformInvitationFixture(
  overrides: Partial<PlatformInvitation> = {},
): PlatformInvitation {
  return {
    id: PLATFORM_TEST_IDS.invitation,
    email: "new-admin@example.test",
    role: "admin",
    status: "PENDING",
    createTime: PLATFORM_TEST_TIME,
    expiresAt: "2026-09-08T09:00:00",
    ...overrides,
  };
}

/** Represents effective application defaults and a writable absent-override version. */
export function platformQuotaFixture(
  overrides: Partial<PlatformQuota> = {},
): PlatformQuota {
  return {
    tenantId: PLATFORM_TEST_IDS.tenant,
    maxStorageBytes: 1_048_576,
    maxFileCount: 100,
    usedStorageBytes: 128,
    usedFileCount: 1,
    version: 0,
    source: "APPLICATION_DEFAULT",
    enforcementMode: "SHADOW",
    ...overrides,
  };
}

/** Uses the exact tenant measurement scope names emitted by PlatformTenantQueryService. */
export function platformUsageFixture(
  overrides: Partial<PlatformUsage> = {},
): PlatformUsage {
  return {
    tenantId: PLATFORM_TEST_IDS.tenant,
    users: 1,
    files: 1,
    logicalStorageBytes: 128,
    auditRecords: 2,
    completedAttestations: 1,
    auditScope: "TENANT_BUSINESS_OPERATION_LOG",
    attestationScope: "TENANT_COMPLETED_ATTESTATION_BATCH",
    state: "AVAILABLE",
    ...overrides,
  };
}

/** Keeps provider details outside the four-component public health projection. */
export function platformHealthFixture(
  overrides: Partial<PlatformHealth> = {},
): PlatformHealth {
  return {
    status: "UP",
    components: {
      database: "UP",
      redis: "UP",
      storage: "UP",
      blockchain: "UP",
    },
    ...overrides,
  };
}

/** Provides the code-owned high-frequency configuration and its versioned integer value. */
export function platformConfigurationFixture(
  overrides: Partial<PlatformConfiguration> = {},
): PlatformConfiguration {
  return {
    key: "HIGH_FREQ_THRESHOLD",
    description: "Operations per five-minute audit window",
    type: "INTEGER",
    scope: "GLOBAL",
    source: "DATABASE",
    minimum: 1,
    maximum: 1_000_000,
    value: 100,
    version: 0,
    mutable: true,
    restartRequired: false,
    state: "AVAILABLE",
    ...overrides,
  };
}

/** Supplies every registry row with its actual code-owned bounds and description. */
export function platformConfigurationsFixture(): PlatformConfiguration[] {
  return [
    platformConfigurationFixture(),
    platformConfigurationFixture({
      key: "FAILED_LOGIN_THRESHOLD",
      maximum: 10_000,
      value: 5,
      description: "Failed logins per hour",
    }),
    platformConfigurationFixture({
      key: "ERROR_RATE_THRESHOLD",
      maximum: 100,
      value: 10,
      description: "Error-rate alert percentage",
    }),
    platformConfigurationFixture({
      key: "LOG_RETENTION_DAYS",
      maximum: 3_650,
      value: 30,
      description: "Audit retention in days",
    }),
  ];
}

/** Builds a durable command result with an explicitly present version. */
export function platformMutationFixture(
  overrides: Partial<PlatformMutationVO> = {},
): PlatformMutationVO {
  return {
    operationId: PLATFORM_TEST_IDS.operation,
    resourceId: PLATFORM_TEST_IDS.tenant,
    version: 0,
    ...overrides,
  };
}

/** Supplies consistent successful operation evidence without raw payloads or diagnostics. */
export function platformAuditFixture(
  overrides: Partial<PlatformAudit> = {},
): PlatformAudit {
  return {
    id: PLATFORM_TEST_IDS.operation,
    actorId: PLATFORM_TEST_IDS.actor,
    operation: "TENANT_UPDATE",
    targetTenantId: PLATFORM_TEST_IDS.tenant,
    resourceType: "TENANT",
    resourceId: PLATFORM_TEST_IDS.tenant,
    reason: "Approved tenant metadata correction",
    beforeSummary: null,
    afterSummary: "name=Alpha tenant; version=0",
    status: "SUCCESS",
    result: platformMutationFixture(),
    errorCode: null,
    traceId: "0123456789abcdef0123456789abcdef",
    startedAt: PLATFORM_TEST_TIME,
    completedAt: "2026-09-07T09:00:01",
    durationMs: 1_000,
    ...overrides,
  };
}

/** Represents an unresolved operation with all nullable terminal fields explicitly present. */
export function platformProcessingAuditFixture(
  overrides: Partial<PlatformAudit> = {},
): PlatformAudit {
  return platformAuditFixture({
    operation: "TENANT_CREATE",
    targetTenantId: null,
    resourceId: null,
    beforeSummary: null,
    afterSummary: null,
    status: "PROCESSING",
    result: null,
    errorCode: null,
    traceId: null,
    completedAt: null,
    durationMs: null,
    ...overrides,
  });
}

/** Builds valid page metadata while preserving explicit empty and later-page fixtures. */
export function platformPageFixture<T>(
  records: T[],
  metadata: Partial<Omit<PlatformPage<T>, "records">> = {},
): PlatformPage<T> {
  const { current = 1, size = 20, total = records.length } = metadata;
  return {
    records,
    current,
    size,
    total,
    pages: Math.ceil(total / size),
    ...metadata,
  };
}
