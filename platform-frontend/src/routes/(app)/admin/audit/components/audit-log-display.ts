import type { AuditLogVO, SysOperationLog } from "$api/types";

/** Maps the sensitive-operation DTO into the shared audit table row contract. */
export function mapSensitiveOperation(log: SysOperationLog): AuditLogVO {
  return {
    id: String(log.id),
    userId: log.userId ?? "",
    username: log.username ?? "",
    action: log.operationType,
    module: log.module,
    detail: log.requestParam,
    ip: log.requestIp ?? "",
    status: log.status ?? 0,
    errorMessage: log.errorMsg,
    duration: log.executionTime ?? 0,
    createTime: log.operationTime ?? "",
  };
}

/** Formats structured audit payloads while preserving non-JSON text verbatim. */
export function formatAuditPayload(value?: string | null): string {
  if (!value || value.trim() === "") return "-";

  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}
