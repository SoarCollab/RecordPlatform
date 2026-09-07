import {
  ApiError,
  getCredentialSnapshot,
  isCurrentCredential,
} from "$api/client";
import * as platformApi from "$api/endpoints/platform";
import { ResultCode } from "$api/types/common";
import { requirePlatformCapability } from "$utils/platformAccess";
import type {
  ChangePlatformMemberRoleRequest,
  ChangePlatformMemberStatusRequest,
  ChangePlatformTenantStatusRequest,
  CreatePlatformInvitationRequest,
  CreatePlatformTenantRequest,
  PlatformConfigurationKey,
  PlatformMutationVO,
  PlatformOperation,
  PlatformReasonRequest,
  UpdatePlatformConfigurationRequest,
  UpdatePlatformQuotaRequest,
  UpdatePlatformTenantRequest,
} from "$api/types/platform";

type Command<
  Operation extends PlatformOperation,
  Request,
  Target extends object = Record<never, never>,
> = Readonly<
  Target & {
    operation: Operation;
    payload: Readonly<Omit<Request, "reason">>;
  }
>;

type TenantTarget = { tenantId: string };
type MemberTarget = TenantTarget & { userId: string };

export type PlatformCommand =
  | Command<"TENANT_CREATE", CreatePlatformTenantRequest>
  | Command<"TENANT_UPDATE", UpdatePlatformTenantRequest, TenantTarget>
  | Command<
      "TENANT_STATUS_CHANGE",
      ChangePlatformTenantStatusRequest,
      TenantTarget
    >
  | Command<"USER_ROLE_CHANGE", ChangePlatformMemberRoleRequest, MemberTarget>
  | Command<
      "USER_STATUS_CHANGE",
      ChangePlatformMemberStatusRequest,
      MemberTarget
    >
  | Command<"USER_SESSIONS_REVOKE", PlatformReasonRequest, MemberTarget>
  | Command<"INVITATION_CREATE", CreatePlatformInvitationRequest, TenantTarget>
  | Command<
      "INVITATION_REVOKE",
      PlatformReasonRequest,
      TenantTarget & { invitationId: string }
    >
  | Command<"QUOTA_UPDATE", UpdatePlatformQuotaRequest, TenantTarget>
  | Command<
      "CONFIGURATION_UPDATE",
      UpdatePlatformConfigurationRequest,
      { key: PlatformConfigurationKey }
    >;

export type PlatformMutationAttempt = Readonly<{
  command: PlatformCommand;
  reason: string;
  idempotencyKey: string;
}>;

export type PlatformMutationFailure =
  | "version-conflict"
  | "idempotency-conflict"
  | "processing"
  | "unauthorized"
  | "denied"
  | "rejected"
  | "uncertain";

/** Rejects unsupported operations without including arbitrary input in diagnostics. */
function unsupportedCommand(command: never): never {
  void command;
  throw new ApiError(ResultCode.PARAM_IS_INVALID, "不支持此平台操作");
}

/** Copies only the documented scalar payload and target fields from the editable draft. */
function copyCommand(command: PlatformCommand): PlatformCommand {
  switch (command.operation) {
    case "TENANT_CREATE":
      return {
        operation: command.operation,
        payload: {
          code: command.payload.code,
          name: command.payload.name,
          ...(command.payload.maxStorageBytes !== undefined
            ? { maxStorageBytes: command.payload.maxStorageBytes }
            : {}),
          ...(command.payload.maxFileCount !== undefined
            ? { maxFileCount: command.payload.maxFileCount }
            : {}),
        },
      };
    case "TENANT_UPDATE":
      return {
        operation: command.operation,
        tenantId: command.tenantId,
        payload: {
          name: command.payload.name,
          expectedVersion: command.payload.expectedVersion,
        },
      };
    case "TENANT_STATUS_CHANGE":
      return {
        operation: command.operation,
        tenantId: command.tenantId,
        payload: {
          status: command.payload.status,
          expectedVersion: command.payload.expectedVersion,
        },
      };
    case "USER_ROLE_CHANGE":
      return {
        operation: command.operation,
        tenantId: command.tenantId,
        userId: command.userId,
        payload: { role: command.payload.role },
      };
    case "USER_STATUS_CHANGE":
      return {
        operation: command.operation,
        tenantId: command.tenantId,
        userId: command.userId,
        payload: { status: command.payload.status },
      };
    case "USER_SESSIONS_REVOKE":
      return {
        operation: command.operation,
        tenantId: command.tenantId,
        userId: command.userId,
        payload: {},
      };
    case "INVITATION_CREATE":
      return {
        operation: command.operation,
        tenantId: command.tenantId,
        payload: {
          email: command.payload.email,
          role: command.payload.role,
          expiresInHours: command.payload.expiresInHours,
        },
      };
    case "INVITATION_REVOKE":
      return {
        operation: command.operation,
        tenantId: command.tenantId,
        invitationId: command.invitationId,
        payload: {},
      };
    case "QUOTA_UPDATE":
      return {
        operation: command.operation,
        tenantId: command.tenantId,
        payload: {
          maxStorageBytes: command.payload.maxStorageBytes,
          maxFileCount: command.payload.maxFileCount,
          expectedVersion: command.payload.expectedVersion,
        },
      };
    case "CONFIGURATION_UPDATE":
      return {
        operation: command.operation,
        key: command.key,
        payload: {
          value: command.payload.value,
          expectedVersion: command.payload.expectedVersion,
        },
      };
    default:
      return unsupportedCommand(command);
  }
}

/** Validate scalar form fields before freezing a command for confirmation. */
function validateCommand(command: PlatformCommand): void {
  const invalid = () => {
    throw new ApiError(
      ResultCode.PARAM_IS_INVALID,
      "请检查变更内容、数值范围和资源版本",
    );
  };
  const integer = (value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER) => {
    if (
      typeof value !== "number" ||
      !Number.isSafeInteger(value) ||
      value < min ||
      value > max
    )
      invalid();
  };
  if (
    "tenantId" in command &&
    (!command.tenantId || /\s/.test(command.tenantId))
  )
    invalid();
  if ("userId" in command && !command.userId) invalid();
  if ("invitationId" in command && !command.invitationId) invalid();
  if ("expectedVersion" in command.payload)
    integer(command.payload.expectedVersion);
  switch (command.operation) {
    case "TENANT_CREATE":
      if (!/^[a-z][a-z0-9-]{1,63}$/.test(command.payload.code)) invalid();
      if (command.payload.maxStorageBytes != null)
        integer(command.payload.maxStorageBytes);
      if (command.payload.maxFileCount != null)
        integer(command.payload.maxFileCount);
      if (!command.payload.name?.trim() || command.payload.name.length > 128)
        invalid();
      break;
    case "TENANT_UPDATE":
      if (!command.payload.name?.trim() || command.payload.name.length > 128)
        invalid();
      break;
    case "TENANT_STATUS_CHANGE":
    case "USER_STATUS_CHANGE":
      if (command.payload.status !== 0 && command.payload.status !== 1)
        invalid();
      break;
    case "USER_ROLE_CHANGE":
      if (!["user", "admin", "monitor"].includes(command.payload.role))
        invalid();
      break;
    case "INVITATION_CREATE":
      if (
        !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(command.payload.email) ||
        command.payload.email.length > 100 ||
        !["user", "admin", "monitor"].includes(command.payload.role)
      )
        invalid();
      integer(command.payload.expiresInHours, 1, 168);
      break;
    case "QUOTA_UPDATE":
      integer(command.payload.maxStorageBytes);
      integer(command.payload.maxFileCount);
      break;
    case "CONFIGURATION_UPDATE": {
      const maximums = {
        HIGH_FREQ_THRESHOLD: 1_000_000,
        FAILED_LOGIN_THRESHOLD: 10_000,
        ERROR_RATE_THRESHOLD: 100,
        LOG_RETENTION_DAYS: 3650,
      };
      const maximum = maximums[command.key];
      if (maximum === undefined) invalid();
      integer(command.payload.value, 1, maximum);
      break;
    }
    case "USER_SESSIONS_REVOKE":
    case "INVITATION_REVOKE":
      break;
    default:
      unsupportedCommand(command);
  }
}

/** Creates one independent, deeply frozen operation snapshot and one retry-stable UUID. */
export function createPlatformMutationAttempt(
  command: PlatformCommand,
  reason: string,
): PlatformMutationAttempt {
  const normalizedReason = typeof reason === "string" ? reason.trim() : "";
  if (!normalizedReason || normalizedReason.length > 255) {
    throw new ApiError(
      ResultCode.PARAM_IS_INVALID,
      "操作原因须为 1 至 255 个字符",
    );
  }
  const snapshot = copyCommand(command);
  validateCommand(snapshot);
  // Every allowlisted field is scalar; freezing both nested records freezes the entire snapshot.
  Object.freeze(snapshot.payload);
  Object.freeze(snapshot);
  return Object.freeze({
    command: snapshot,
    reason: normalizedReason,
    idempotencyKey: crypto.randomUUID(),
  });
}

/** Execute the frozen command using the exact UUID and the current required capability. */
export async function executePlatformMutation(
  attempt: PlatformMutationAttempt,
): Promise<PlatformMutationVO> {
  const credential = getCredentialSnapshot();
  const command = attempt.command;
  validateCommand(command);
  const capability = command.operation.startsWith("TENANT_")
    ? "platform:tenant:write"
    : command.operation === "QUOTA_UPDATE"
      ? "platform:quota:write"
      : command.operation === "CONFIGURATION_UPDATE"
        ? "platform:configuration:write"
        : "platform:user:write";
  await requirePlatformCapability(capability);
  if (!isCurrentCredential(credential)) {
    throw new ApiError(ResultCode.PERMISSION_UNAUTHENTICATED, "操作会话已改变");
  }
  const key = attempt.idempotencyKey;
  const reason = attempt.reason;
  switch (command.operation) {
    case "TENANT_CREATE":
      return platformApi.createPlatformTenant(
        { ...command.payload, reason },
        key,
      );
    case "TENANT_UPDATE":
      return platformApi.updatePlatformTenant(
        command.tenantId,
        { ...command.payload, reason },
        key,
      );
    case "TENANT_STATUS_CHANGE":
      return platformApi.changePlatformTenantStatus(
        command.tenantId,
        { ...command.payload, reason },
        key,
      );
    case "USER_ROLE_CHANGE":
      return platformApi.changePlatformMemberRole(
        command.tenantId,
        command.userId,
        { ...command.payload, reason },
        key,
      );
    case "USER_STATUS_CHANGE":
      return platformApi.changePlatformMemberStatus(
        command.tenantId,
        command.userId,
        { ...command.payload, reason },
        key,
      );
    case "USER_SESSIONS_REVOKE":
      return platformApi.revokePlatformMemberSessions(
        command.tenantId,
        command.userId,
        { reason },
        key,
      );
    case "INVITATION_CREATE":
      return platformApi.invitePlatformTenantMember(
        command.tenantId,
        { ...command.payload, reason },
        key,
      );
    case "INVITATION_REVOKE":
      return platformApi.revokePlatformTenantInvitation(
        command.tenantId,
        command.invitationId,
        { reason },
        key,
      );
    case "QUOTA_UPDATE":
      return platformApi.updatePlatformTenantQuota(
        command.tenantId,
        { ...command.payload, reason },
        key,
      );
    case "CONFIGURATION_UPDATE":
      return platformApi.updatePlatformConfiguration(
        command.key,
        { ...command.payload, reason },
        key,
      );
    default:
      return unsupportedCommand(command);
  }
}

/** Distinguish known terminal business errors from an uncertain transport outcome. */
export function classifyPlatformMutationFailure(
  error: unknown,
): PlatformMutationFailure {
  if (!(error instanceof ApiError)) return "uncertain";
  if (error.code === ResultCode.PLATFORM_VERSION_CONFLICT)
    return "version-conflict";
  if (error.code === ResultCode.PLATFORM_IDEMPOTENCY_CONFLICT)
    return "idempotency-conflict";
  if (error.code === ResultCode.PLATFORM_OPERATION_IN_PROGRESS)
    return "processing";
  if (error.isUnauthorized) return "unauthorized";
  if (error.code === ResultCode.PERMISSION_UNAUTHORIZED) return "denied";
  return error.code >= 10000 && error.code < 90000 ? "rejected" : "uncertain";
}

/** Return bounded local messages without reflecting untrusted provider error details. */
export function platformErrorMessage(error: unknown): string {
  const kind = classifyPlatformMutationFailure(error);
  const messages: Record<PlatformMutationFailure, string> = {
    "version-conflict": "资源已被其他操作修改，请重新读取并核对后确认。",
    "idempotency-conflict":
      "此操作标识已对应另一份请求，请核查原操作，不要重复提交。",
    processing: "原操作仍在处理中，请保留此操作并稍后查询或重试。",
    unauthorized: "会话已失效，请重新登录。",
    denied: "当前账号没有执行此操作的权限。",
    rejected: "请求被拒绝，请检查当前资源状态和变更内容。",
    uncertain: "未能确认请求结果。请保留当前操作，使用同一操作重试。",
  };
  const known: Record<number, string> = {
    10001: "请求参数无效，请检查输入内容。",
    50020: "目标资源不可用，请刷新列表。",
    50021: "系统租户受保护，不能执行此操作。",
    50025: "租户编码已存在，请使用其他编码。",
    50026: "此配置项不支持在线修改。",
    50027: "目标租户已停用，当前操作不可用。",
  };
  let message =
    error instanceof ApiError
      ? (known[error.code] ?? messages[kind])
      : messages[kind];
  if (
    error instanceof ApiError &&
    Number.isSafeInteger(error.code) &&
    error.code >= 10000 &&
    error.code < 90000
  )
    message += `（${error.code}）`;
  if (
    error instanceof ApiError &&
    error.traceId &&
    /^[0-9a-fA-F]{16,64}$/.test(error.traceId)
  )
    message += ` 追踪号：${error.traceId}`;
  return message;
}
