import { ApiError } from "$api/client";
import { platformGet, platformWrite } from "$api/platformClient";
import { ResultCode, PLATFORM_CONFIGURATION_KEYS } from "$api/types";
import type {
  PlatformTenantQuery,
  PlatformUserQuery,
  PlatformMemberQuery,
  PlatformAuditQuery,
  PlatformConfigurationKey,
  CreatePlatformTenantRequest,
  UpdatePlatformTenantRequest,
  ChangePlatformTenantStatusRequest,
  ChangePlatformMemberRoleRequest,
  ChangePlatformMemberStatusRequest,
  PlatformReasonRequest,
  CreatePlatformInvitationRequest,
  UpdatePlatformQuotaRequest,
  UpdatePlatformConfigurationRequest,
} from "$api/types";
import {
  decodePlatformSession,
  decodePlatformOverview,
  decodePlatformHealth,
  decodePlatformTenant,
  decodePlatformUser,
  decodePlatformMember,
  decodePlatformInvitation,
  decodePlatformQuota,
  decodePlatformUsage,
  decodePlatformConfiguration,
  decodePlatformAudit,
  decodePlatformMutation,
  decodePlatformPage,
  decodePlatformList,
} from "./platformDecoders";

/** Encode an opaque resource segment without allowing path traversal. */
function segment(value: string): string {
  if (
    typeof value !== "string" ||
    !value.trim() ||
    value.length > 255 ||
    /[\s\p{Cc}]/u.test(value) ||
    value === "." ||
    value === ".."
  ) {
    throw new ApiError(ResultCode.PARAM_IS_INVALID, "平台资源标识无效");
  }
  return encodeURIComponent(value);
}

/** Restrict query values to the documented platform pagination and filters. */
function query(
  input:
    | PlatformTenantQuery
    | PlatformUserQuery
    | PlatformMemberQuery
    | PlatformAuditQuery = {},
) {
  const result: Record<string, string | number> = {};
  for (const [key, value] of Object.entries(input)) {
    if (value === undefined || value === "") continue;
    if (key === "pageNum" || key === "pageSize") {
      if (
        typeof value !== "number" ||
        !Number.isSafeInteger(value) ||
        value < 1 ||
        (key === "pageSize" && value > 100)
      ) {
        throw new ApiError(ResultCode.PARAM_IS_INVALID, "分页参数无效");
      }
    } else if (key === "keyword") {
      if (typeof value !== "string" || value.length > 100)
        throw new ApiError(
          ResultCode.PARAM_IS_INVALID,
          "搜索内容不能超过 100 字符",
        );
    } else if (key === "tenantId") {
      if (typeof value !== "string")
        throw new ApiError(ResultCode.PARAM_IS_INVALID, "租户标识无效");
      segment(value);
    } else if (key === "role") {
      if (!["user", "admin", "monitor"].includes(String(value)))
        throw new ApiError(ResultCode.PARAM_IS_INVALID, "角色无效");
    } else if (key === "status") {
      if (![0, 1, "PROCESSING", "SUCCESS", "FAILURE"].includes(value))
        throw new ApiError(ResultCode.PARAM_IS_INVALID, "状态无效");
    } else {
      throw new ApiError(ResultCode.PARAM_IS_INVALID, "平台查询参数无效");
    }
    result[key] = value;
  }
  return result;
}

/** Reject unsupported configuration keys before issuing a request. */
function configurationKey(key: PlatformConfigurationKey): string {
  if (!PLATFORM_CONFIGURATION_KEYS.includes(key))
    throw new ApiError(
      ResultCode.PLATFORM_CONFIGURATION_UNSUPPORTED,
      "不支持此配置项",
    );
  return key;
}

/** Load and validate the current fixed-system platform identity. */
export async function getPlatformSession() {
  return decodePlatformSession(await platformGet("/platform/session"));
}
/** Load measured platform metadata counts. */
export async function getPlatformOverview() {
  return decodePlatformOverview(await platformGet("/platform/overview"));
}
/** Load the allowlisted shared-service health projection. */
export async function getPlatformResourceHealth() {
  return decodePlatformHealth(await platformGet("/platform/resources/health"));
}
/** Search tenant metadata with validated page boundaries. */
export async function listPlatformTenants(params?: PlatformTenantQuery) {
  return decodePlatformPage(
    await platformGet("/platform/tenants", query(params)),
    decodePlatformTenant,
  );
}
/** Read one explicit tenant resource. */
export async function getPlatformTenant(tenantId: string) {
  return decodePlatformTenant(
    await platformGet(`/platform/tenants/${segment(tenantId)}`),
  );
}
/** Search the restricted cross-tenant user metadata projection. */
export async function listPlatformUsers(params?: PlatformUserQuery) {
  return decodePlatformPage(
    await platformGet("/platform/users", query(params)),
    decodePlatformUser,
  );
}
/** Search members belonging to one explicit tenant. */
export async function listPlatformTenantMembers(
  tenantId: string,
  params?: PlatformMemberQuery,
) {
  return decodePlatformPage(
    await platformGet(
      `/platform/tenants/${segment(tenantId)}/users`,
      query(params),
    ),
    decodePlatformMember,
  );
}
/** List invitation metadata without capability tokens. */
export async function listPlatformTenantInvitations(tenantId: string) {
  return decodePlatformList(
    await platformGet(`/platform/tenants/${segment(tenantId)}/invitations`),
    decodePlatformInvitation,
  );
}
/** Read measured usage restricted to the target tenant. */
export async function getPlatformTenantUsage(tenantId: string) {
  return decodePlatformUsage(
    await platformGet(`/platform/tenants/${segment(tenantId)}/usage`),
  );
}
/** Read effective tenant limits, usage and optimistic version. */
export async function getPlatformTenantQuota(tenantId: string) {
  return decodePlatformQuota(
    await platformGet(`/platform/tenants/${segment(tenantId)}/quota`),
  );
}
/** List the code-owned global configuration registry. */
export async function listPlatformConfiguration() {
  return decodePlatformList(
    await platformGet("/platform/configuration"),
    decodePlatformConfiguration,
  );
}
/** Read one allowlisted configuration and its nullable value/version. */
export async function getPlatformConfiguration(key: PlatformConfigurationKey) {
  return decodePlatformConfiguration(
    await platformGet(`/platform/configuration/${configurationKey(key)}`),
  );
}
/** Search system-owned platform operation evidence. */
export async function listPlatformAudit(params?: PlatformAuditQuery) {
  return decodePlatformPage(
    await platformGet("/platform/audit", query(params)),
    decodePlatformAudit,
  );
}
/** Read one durable platform operation and its sanitized outcome. */
export async function getPlatformAudit(operationId: string) {
  return decodePlatformAudit(
    await platformGet(`/platform/audit/${segment(operationId)}`),
  );
}
/** Create a tenant and initial quota without provisioning credentials. */
export async function createPlatformTenant(
  request: Readonly<CreatePlatformTenantRequest>,
  idempotencyKey: string,
) {
  return decodePlatformMutation(
    await platformWrite("POST", "/platform/tenants", request, idempotencyKey),
  );
}
/** Change tenant metadata at the reviewed optimistic version. */
export async function updatePlatformTenant(
  tenantId: string,
  request: Readonly<UpdatePlatformTenantRequest>,
  idempotencyKey: string,
) {
  return decodePlatformMutation(
    await platformWrite(
      "PUT",
      `/platform/tenants/${segment(tenantId)}`,
      request,
      idempotencyKey,
    ),
  );
}
/** Disable or restore the explicit tenant under a reasoned command. */
export async function changePlatformTenantStatus(
  tenantId: string,
  request: Readonly<ChangePlatformTenantStatusRequest>,
  idempotencyKey: string,
) {
  return decodePlatformMutation(
    await platformWrite(
      "PUT",
      `/platform/tenants/${segment(tenantId)}/status`,
      request,
      idempotencyKey,
    ),
  );
}
/** Change a target member's tenant role without granting platform authority. */
export async function changePlatformMemberRole(
  tenantId: string,
  userId: string,
  request: Readonly<ChangePlatformMemberRoleRequest>,
  idempotencyKey: string,
) {
  return decodePlatformMutation(
    await platformWrite(
      "PUT",
      `/platform/tenants/${segment(tenantId)}/users/${segment(userId)}/role`,
      request,
      idempotencyKey,
    ),
  );
}
/** Change a target member's authorization status. */
export async function changePlatformMemberStatus(
  tenantId: string,
  userId: string,
  request: Readonly<ChangePlatformMemberStatusRequest>,
  idempotencyKey: string,
) {
  return decodePlatformMutation(
    await platformWrite(
      "PUT",
      `/platform/tenants/${segment(tenantId)}/users/${segment(userId)}/status`,
      request,
      idempotencyKey,
    ),
  );
}
/** Revoke sessions belonging to the selected tenant member. */
export async function revokePlatformMemberSessions(
  tenantId: string,
  userId: string,
  request: Readonly<PlatformReasonRequest>,
  idempotencyKey: string,
) {
  return decodePlatformMutation(
    await platformWrite(
      "POST",
      `/platform/tenants/${segment(tenantId)}/users/${segment(userId)}/sessions/revoke`,
      request,
      idempotencyKey,
    ),
  );
}
/** Deliver a one-time invitation through the existing mail boundary. */
export async function invitePlatformTenantMember(
  tenantId: string,
  request: Readonly<CreatePlatformInvitationRequest>,
  idempotencyKey: string,
) {
  return decodePlatformMutation(
    await platformWrite(
      "POST",
      `/platform/tenants/${segment(tenantId)}/invitations`,
      request,
      idempotencyKey,
    ),
  );
}
/** Revoke an invitation while preserving the mandatory JSON reason body. */
export async function revokePlatformTenantInvitation(
  tenantId: string,
  invitationId: string,
  request: Readonly<PlatformReasonRequest>,
  idempotencyKey: string,
) {
  return decodePlatformMutation(
    await platformWrite(
      "DELETE",
      `/platform/tenants/${segment(tenantId)}/invitations/${segment(invitationId)}`,
      request,
      idempotencyKey,
    ),
  );
}
/** Update effective quota overrides at the reviewed version. */
export async function updatePlatformTenantQuota(
  tenantId: string,
  request: Readonly<UpdatePlatformQuotaRequest>,
  idempotencyKey: string,
) {
  return decodePlatformMutation(
    await platformWrite(
      "PUT",
      `/platform/tenants/${segment(tenantId)}/quota`,
      request,
      idempotencyKey,
    ),
  );
}
/** Change one allowlisted integer setting with optimistic concurrency. */
export async function updatePlatformConfiguration(
  key: PlatformConfigurationKey,
  request: Readonly<UpdatePlatformConfigurationRequest>,
  idempotencyKey: string,
) {
  return decodePlatformMutation(
    await platformWrite(
      "PUT",
      `/platform/configuration/${configurationKey(key)}`,
      request,
      idempotencyKey,
    ),
  );
}
