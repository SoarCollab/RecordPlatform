import { api, getToken } from "../client";
import { env } from "$env/dynamic/public";
import type {
  Page,
  PageParams,
  SysPermission,
  AuditLogVO,
  AuditLogQueryParams,
  SystemStats,
  ChainStatus,
  SystemHealth,
  MonitorMetrics,
  StorageCapacity,
  AuditOverview,
  AuditConfigVO,
  HighFrequencyOperationVO,
  ErrorOperationStatsVO,
  UserTimeDistributionVO,
  AuditLogQueryVO,
  SysOperationLog,
  PermissionCreateRequest,
  PermissionUpdateRequest,
  GrantPermissionRequest,
} from "../types";

// ===== System Stats =====

/**
 * 获取系统统计信息
 */
export async function getSystemStats(): Promise<SystemStats> {
  return api.get<SystemStats>("/system/stats");
}

/**
 * 获取区块链状态
 */
export async function getChainStatus(): Promise<ChainStatus> {
  return api.get<ChainStatus>("/system/chain-status");
}

/**
 * 获取系统健康状态
 */
export async function getSystemHealth(): Promise<SystemHealth> {
  return api.get<SystemHealth>("/system/health");
}

/**
 * 获取监控指标（聚合接口）
 */
export async function getMonitorMetrics(): Promise<MonitorMetrics> {
  return api.get<MonitorMetrics>("/system/monitor");
}

/**
 * 获取存储容量统计
 */
export async function getStorageCapacity(): Promise<StorageCapacity> {
  return api.get<StorageCapacity>("/system/storage-capacity");
}

// ===== Permissions =====

const PERM_BASE = "/system/permissions";

/**
 * 获取权限树
 */
export async function getPermissionTree(): Promise<SysPermission[]> {
  return api.get<SysPermission[]>(PERM_BASE);
}

export async function listPermissionModules(): Promise<string[]> {
  return api.get<string[]>(`${PERM_BASE}/modules`);
}

export async function listPermissions(params?: {
  module?: string;
  pageNum?: number;
  pageSize?: number;
}): Promise<Page<SysPermission>> {
  return api.get<Page<SysPermission>>(`${PERM_BASE}/list`, { params });
}

export async function createPermission(
  data: PermissionCreateRequest,
): Promise<SysPermission> {
  return api.post<SysPermission>(PERM_BASE, data);
}

export async function updatePermission(
  id: string,
  data: PermissionUpdateRequest,
): Promise<SysPermission> {
  return api.put<SysPermission>(`${PERM_BASE}/${id}`, data);
}

export async function deletePermission(id: string): Promise<void> {
  await api.delete(`${PERM_BASE}/${id}`);
}

export async function getRolePermissions(role: string): Promise<string[]> {
  return api.get<string[]>(`${PERM_BASE}/roles/${role}`);
}

export async function grantRolePermission(
  role: string,
  data: GrantPermissionRequest,
): Promise<void> {
  await api.post(`${PERM_BASE}/roles/${role}/grant`, data);
}

export async function revokeRolePermission(
  role: string,
  permissionCode: string,
): Promise<void> {
  await api.delete(`${PERM_BASE}/roles/${role}/revoke`, {
    params: { permissionCode },
  });
}

// ===== Audit Logs =====

const AUDIT_BASE = "/system/audit";

/**
 * 获取审计日志列表
 */
export async function getAuditLogs(
  params?: PageParams & AuditLogQueryParams,
): Promise<Page<AuditLogVO>> {
  return api.get<Page<AuditLogVO>>(`${AUDIT_BASE}/logs/page`, { params });
}

export async function getAuditLog(id: string): Promise<SysOperationLog> {
  return api.get<SysOperationLog>(`${AUDIT_BASE}/logs/${id}`);
}

export async function getAuditOverview(): Promise<AuditOverview> {
  return api.get<AuditOverview>(`${AUDIT_BASE}/overview`);
}

export async function getHighFrequencyOperations(): Promise<
  HighFrequencyOperationVO[]
> {
  return api.get<HighFrequencyOperationVO[]>(`${AUDIT_BASE}/high-frequency`);
}

export async function getSensitiveOperations(
  data: AuditLogQueryVO,
): Promise<Page<SysOperationLog>> {
  return api.post<Page<SysOperationLog>>(`${AUDIT_BASE}/sensitive/page`, data);
}

export async function getErrorOperationStats(): Promise<
  ErrorOperationStatsVO[]
> {
  return api.get<ErrorOperationStatsVO[]>(`${AUDIT_BASE}/error-stats`);
}

export async function getUserTimeDistribution(): Promise<
  UserTimeDistributionVO[]
> {
  return api.get<UserTimeDistributionVO[]>(`${AUDIT_BASE}/time-distribution`);
}

export async function getAuditConfigs(): Promise<AuditConfigVO[]> {
  return api.get<AuditConfigVO[]>(`${AUDIT_BASE}/configs`);
}

export async function updateAuditConfig(data: AuditConfigVO): Promise<boolean> {
  return api.put<boolean>(`${AUDIT_BASE}/configs`, data);
}

export async function checkAuditAnomalies(): Promise<Record<string, unknown>> {
  return api.get<Record<string, unknown>>(`${AUDIT_BASE}/check-anomalies`);
}

export async function backupAuditLogs(params?: {
  days?: number;
  deleteAfterBackup?: boolean;
}): Promise<string> {
  return api.post<string>(`${AUDIT_BASE}/backup-logs`, null, { params });
}

/**
 * 导出审计日志
 */
export async function exportAuditLogs(
  params?: AuditLogQueryParams,
): Promise<Blob> {
  const token = getToken();
  if (!token) {
    throw new Error("未登录");
  }

  const apiBase = import.meta.env.DEV
    ? "/record-platform/api/v1"
    : `${env.PUBLIC_API_BASE_URL || "/record-platform"}/api/v1`;

  const response = await fetch(`${apiBase}${AUDIT_BASE}/logs/export`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      "X-Tenant-ID": env.PUBLIC_TENANT_ID || "",
    },
    body: JSON.stringify(params ?? {}),
  });

  if (!response.ok) {
    throw new Error("导出失败");
  }

  return response.blob();
}
