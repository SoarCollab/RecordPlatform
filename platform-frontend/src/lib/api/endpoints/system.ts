import { api } from "../client";
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

// ===== Permissions =====

const PERM_BASE = "/system/permissions";

/**
 * 获取权限树
 */
export async function getPermissionTree(): Promise<SysPermission[]> {
  return api.get<SysPermission[]>(PERM_BASE);
}

/**
 * 获取用户权限
 */
export async function getUserPermissions(
  userId: string,
): Promise<SysPermission[]> {
  return api.get<SysPermission[]>(`${PERM_BASE}/user/${userId}`);
}

// ===== Audit Logs =====

const AUDIT_BASE = "/system/audit";

/**
 * 获取审计日志列表
 */
export async function getAuditLogs(
  params?: PageParams & AuditLogQueryParams,
): Promise<Page<AuditLogVO>> {
  return api.get<Page<AuditLogVO>>(AUDIT_BASE, { params });
}

/**
 * 获取审计日志详情
 */
export async function getAuditLog(id: string): Promise<AuditLogVO> {
  return api.get<AuditLogVO>(`${AUDIT_BASE}/${id}`);
}

/**
 * 导出审计日志
 */
export async function exportAuditLogs(
  params?: AuditLogQueryParams,
): Promise<Blob> {
  const response = await fetch(
    `/record-platform/api/v1${AUDIT_BASE}/export?${new URLSearchParams(params as Record<string, string>)}`,
    {
      headers: {
        Authorization: `Bearer ${localStorage.getItem("auth_token")}`,
      },
    },
  );

  if (!response.ok) {
    throw new Error("导出失败");
  }

  return response.blob();
}
