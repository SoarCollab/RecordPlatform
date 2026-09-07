import { ApiError } from "$api/client";
import {
  PLATFORM_CAPABILITIES,
  PLATFORM_CONFIGURATION_KEYS,
  PLATFORM_OPERATIONS,
  ResultCode,
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

const ROLES = ["user", "admin", "monitor"] as const;
const HEALTH_STATUSES = ["UP", "DOWN", "DEGRADED", "UNKNOWN"] as const;
const RESOURCE_TYPES = {
  TENANT_CREATE: "TENANT",
  TENANT_UPDATE: "TENANT",
  TENANT_STATUS_CHANGE: "TENANT",
  USER_ROLE_CHANGE: "USER",
  USER_STATUS_CHANGE: "USER",
  USER_SESSIONS_REVOKE: "USER",
  INVITATION_CREATE: "INVITATION",
  INVITATION_REVOKE: "INVITATION",
  QUOTA_UPDATE: "QUOTA",
  CONFIGURATION_UPDATE: "CONFIGURATION",
} as const;

/** Reports a bounded contract failure without reflecting response contents. */
function invalid(): never {
  throw new ApiError(ResultCode.PARSE_ERROR, "平台响应格式无效");
}

/** Narrows a JSON object before any typed field access. */
function record(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    return invalid();
  }
  // The object check above permits reading fields only as unknown values.
  return value as Record<string, unknown>;
}

/** Validates bounded text without coercion or changing the server's value. */
function text(value: unknown, maximum = 255, allowEmpty = false): string {
  if (
    typeof value !== "string" ||
    value.length > maximum ||
    (!allowEmpty && value.trim().length === 0)
  ) {
    return invalid();
  }
  return value;
}

/** Preserves opaque identifiers while rejecting empty or control-bearing IDs. */
function identifier(value: unknown): string {
  const id = text(value);
  if (/\s/.test(id)) return invalid();
  return id;
}

/** Requires numeric safe integers, including legitimate zero counters and versions. */
function integer(
  value: unknown,
  minimum = 0,
  maximum = Number.MAX_SAFE_INTEGER,
): number {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < minimum ||
    value > maximum
  ) {
    return invalid();
  }
  return value;
}

/** Retains only a declared enum member with its exact literal type. */
function member<T extends string | number>(
  value: unknown,
  choices: readonly T[],
): T {
  const match = choices.find((choice) => choice === value);
  return match === undefined ? invalid() : match;
}

/** Requires real booleans rather than truthy strings or numeric flags. */
function boolean(value: unknown): boolean {
  return typeof value === "boolean" ? value : invalid();
}

/** Preserves an explicit null and rejects omitted required-nullable fields. */
function nullable<T>(value: unknown, decode: (input: unknown) => T): T | null {
  return value === null ? null : decode(value);
}

/** Validates ISO local or offset timestamps without accepting normalized invalid dates. */
function timestamp(value: unknown): string {
  const result = text(value, 64);
  const match =
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|[+-]\d{2}:?\d{2})?$/.exec(
      result,
    );
  if (!match || !Number.isFinite(Date.parse(result))) return invalid();
  const [, year, month, day, hour, minute, second] = match;
  const days = new Date(Date.UTC(Number(year), Number(month), 0)).getUTCDate();
  if (
    Number(month) < 1 ||
    Number(month) > 12 ||
    Number(day) < 1 ||
    Number(day) > days ||
    Number(hour) > 23 ||
    Number(minute) > 59 ||
    Number(second) > 59
  ) {
    return invalid();
  }
  return result;
}

/** Decodes a required array instead of treating a malformed success as an empty list. */
export function decodePlatformList<T>(
  value: unknown,
  decode: (input: unknown) => T,
): T[] {
  if (!Array.isArray(value)) return invalid();
  return value.map(decode);
}

/** Validates page metadata and derives the count without trusting a raw pages field. */
export function decodePlatformPage<T>(
  value: unknown,
  decode: (input: unknown) => T,
): PlatformPage<T> {
  const data = record(value);
  const current = integer(data.current, 1);
  const size = integer(data.size, 1, 100);
  const total = integer(data.total);
  const records = decodePlatformList(data.records, decode);
  const pages = Math.ceil(total / size);
  if (
    records.length > size ||
    records.length > total ||
    (data.pages !== undefined && integer(data.pages) !== pages)
  ) {
    return invalid();
  }
  return { records, current, size, total, pages };
}

/** Decodes platform identity from the server's fixed scope and capability vocabulary. */
export function decodePlatformSession(value: unknown): PlatformSession {
  const data = record(value);
  const capabilities = decodePlatformList(data.capabilities, (item) =>
    member(item, PLATFORM_CAPABILITIES),
  );
  if (new Set(capabilities).size !== capabilities.length) return invalid();
  return {
    actorId: identifier(data.actorId),
    username: text(data.username),
    scope: member(data.scope, ["platform"]),
    systemTenantId: member(data.systemTenantId, [0]),
    capabilities,
  };
}

/** Returns only successfully measured overview counters. */
export function decodePlatformOverview(value: unknown): PlatformOverview {
  const data = record(value);
  return {
    tenants: integer(data.tenants),
    activeTenants: integer(data.activeTenants),
    disabledTenants: integer(data.disabledTenants),
    users: integer(data.users),
    activeUsers: integer(data.activeUsers),
    state: member(data.state, ["AVAILABLE"]),
  };
}

/** Projects only the four code-owned health components and their closed status values. */
export function decodePlatformHealth(value: unknown): PlatformHealth {
  const data = record(value);
  const components = record(data.components);
  return {
    status: member(data.status, HEALTH_STATUSES),
    components: {
      database: member(components.database, HEALTH_STATUSES),
      redis: member(components.redis, HEALTH_STATUSES),
      storage: member(components.storage, HEALTH_STATUSES),
      blockchain: member(components.blockchain, HEALTH_STATUSES),
    },
  };
}

/** Preserves required-nullable lifecycle evidence alongside tenant metadata. */
export function decodePlatformTenant(value: unknown): PlatformTenant {
  const data = record(value);
  return {
    id: identifier(data.id),
    code: text(data.code, 64),
    name: text(data.name, 128),
    status: member(data.status, [0, 1]),
    version: integer(data.version),
    memberCount: integer(data.memberCount),
    disabledReason: nullable(data.disabledReason, (item) =>
      text(item, 255, true),
    ),
    disabledAt: nullable(data.disabledAt, timestamp),
    disabledBy: nullable(data.disabledBy, identifier),
    createTime: nullable(data.createTime, timestamp),
    updateTime: nullable(data.updateTime, timestamp),
  };
}

/** Decodes the bounded global-user projection, which deliberately has no email. */
export function decodePlatformUser(value: unknown): PlatformUser {
  const data = record(value);
  return {
    id: identifier(data.id),
    tenantId: identifier(data.tenantId),
    username: text(data.username),
    nickname: nullable(data.nickname, (item) => text(item, 255, true)),
    role: member(data.role, ROLES),
    status: member(data.status, [0, 1]),
    registerTime: nullable(data.registerTime, timestamp),
    lastLoginTime: nullable(data.lastLoginTime, timestamp),
  };
}

/** Retains target-member email and permits only the generated optional profile fields. */
export function decodePlatformMember(value: unknown): PlatformMember {
  const data = record(value);
  return {
    id: identifier(data.id),
    username: text(data.username),
    email: text(data.email, 100),
    role: member(data.role, ROLES),
    status: member(data.status, [0, 1]),
    registerTime: timestamp(data.registerTime),
    ...(data.nickname === undefined
      ? {}
      : { nickname: nullable(data.nickname, (item) => text(item, 255, true)) }),
    ...(data.lastLoginTime === undefined
      ? {}
      : { lastLoginTime: nullable(data.lastLoginTime, timestamp) }),
  };
}

/** Returns invitation metadata only, never token, digest, URL or provider fields. */
export function decodePlatformInvitation(value: unknown): PlatformInvitation {
  const data = record(value);
  return {
    id: identifier(data.id),
    email: text(data.email, 100),
    role: member(data.role, ROLES),
    status: member(data.status, ["PENDING", "ACCEPTED", "REVOKED", "EXPIRED"]),
    expiresAt: timestamp(data.expiresAt),
    createTime: timestamp(data.createTime),
  };
}

/** Keeps effective quota source, rollout mode and the writable zero version distinct. */
export function decodePlatformQuota(value: unknown): PlatformQuota {
  const data = record(value);
  return {
    tenantId: identifier(data.tenantId),
    maxStorageBytes: integer(data.maxStorageBytes),
    maxFileCount: integer(data.maxFileCount),
    usedStorageBytes: integer(data.usedStorageBytes),
    usedFileCount: integer(data.usedFileCount),
    version: integer(data.version),
    source: member(data.source, [
      "TENANT_OVERRIDE",
      "TENANT_DEFAULT",
      "APPLICATION_DEFAULT",
    ]),
    enforcementMode: member(data.enforcementMode, ["SHADOW", "ENFORCE"]),
  };
}

/** Validates real tenant measurements and rejects shared-chain scope substitutions. */
export function decodePlatformUsage(value: unknown): PlatformUsage {
  const data = record(value);
  return {
    tenantId: identifier(data.tenantId),
    users: integer(data.users),
    files: integer(data.files),
    logicalStorageBytes: integer(data.logicalStorageBytes),
    auditRecords: integer(data.auditRecords),
    completedAttestations: integer(data.completedAttestations),
    auditScope: member(data.auditScope, ["TENANT_BUSINESS_OPERATION_LOG"]),
    attestationScope: member(data.attestationScope, [
      "TENANT_COMPLETED_ATTESTATION_BATCH",
    ]),
    state: member(data.state, ["AVAILABLE"]),
  };
}

/** Preserves unavailable registry values and versions without manufacturing writable defaults. */
export function decodePlatformConfiguration(
  value: unknown,
): PlatformConfiguration {
  const data = record(value);
  const minimum = integer(data.minimum);
  const maximum = integer(data.maximum, minimum);
  const configuration: PlatformConfiguration = {
    key: member(data.key, PLATFORM_CONFIGURATION_KEYS),
    description: text(data.description),
    scope: member(data.scope, ["GLOBAL"]),
    source: member(data.source, ["DATABASE"]),
    type: member(data.type, ["INTEGER"]),
    minimum,
    maximum,
    mutable: boolean(data.mutable),
    restartRequired: boolean(data.restartRequired),
    state: member(data.state, ["AVAILABLE", "UNAVAILABLE"]),
    value: nullable(data.value, (item) => integer(item, minimum, maximum)),
    version: nullable(data.version, integer),
  };
  if (
    (configuration.state === "AVAILABLE" &&
      (configuration.value === null || configuration.version === null)) ||
    (configuration.state === "UNAVAILABLE" && configuration.value !== null)
  ) {
    return invalid();
  }
  return configuration;
}

/** Decodes the durable command reference, including explicit unversioned success. */
export function decodePlatformMutation(value: unknown): PlatformMutationVO {
  const data = record(value);
  return {
    operationId: identifier(data.operationId),
    resourceId: identifier(data.resourceId),
    version: nullable(data.version, integer),
  };
}

/** Requires trace-shaped evidence instead of rendering arbitrary provider diagnostics. */
function traceId(value: unknown): string {
  const result = text(value, 64);
  return /^[0-9a-fA-F]{16,64}$/.test(result) ? result : invalid();
}

/** Validates sanitized operation evidence and its durable lifecycle/result consistency. */
export function decodePlatformAudit(value: unknown): PlatformAudit {
  const data = record(value);
  const operation = member(data.operation, PLATFORM_OPERATIONS);
  const audit: PlatformAudit = {
    id: identifier(data.id),
    actorId: identifier(data.actorId),
    operation,
    resourceType: member(data.resourceType, [RESOURCE_TYPES[operation]]),
    targetTenantId: nullable(data.targetTenantId, identifier),
    resourceId: nullable(data.resourceId, identifier),
    reason: text(data.reason),
    beforeSummary: nullable(data.beforeSummary, (item) =>
      text(item, 255, true),
    ),
    afterSummary: nullable(data.afterSummary, (item) => text(item, 255, true)),
    status: member(data.status, ["PROCESSING", "SUCCESS", "FAILURE"]),
    result: nullable(data.result, decodePlatformMutation),
    errorCode: nullable(data.errorCode, integer),
    traceId: nullable(data.traceId, traceId),
    startedAt: timestamp(data.startedAt),
    completedAt: nullable(data.completedAt, timestamp),
    durationMs: nullable(data.durationMs, integer),
  };
  if (audit.status === "PROCESSING") {
    if (
      audit.result !== null ||
      audit.errorCode !== null ||
      audit.completedAt !== null ||
      audit.durationMs !== null
    ) {
      return invalid();
    }
  } else {
    if (audit.completedAt === null || audit.durationMs === null)
      return invalid();
    if (audit.status === "SUCCESS") {
      if (
        audit.result === null ||
        audit.errorCode !== null ||
        audit.result.operationId !== audit.id ||
        audit.result.resourceId !== audit.resourceId
      ) {
        return invalid();
      }
    } else if (audit.result !== null || audit.errorCode === null) {
      return invalid();
    }
  }
  if (audit.resourceType === "CONFIGURATION" && audit.resourceId !== null) {
    member(audit.resourceId, PLATFORM_CONFIGURATION_KEYS);
  }
  return audit;
}
