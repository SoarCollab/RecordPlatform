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

/**
 * 获取系统统计信息。
 *
 * @returns 系统统计
 */
export async function getSystemStats(): Promise<SystemStats> {
  return api.get<SystemStats>("/system/stats");
}

/**
 * 获取区块链状态。
 *
 * @returns 区块链状态
 */
export async function getChainStatus(): Promise<ChainStatus> {
  return api.get<ChainStatus>("/system/chain-status");
}

/**
 * 获取系统健康状态。
 *
 * @returns 健康状态
 */
export async function getSystemHealth(): Promise<SystemHealth> {
  return api.get<SystemHealth>("/system/health");
}

/**
 * 获取监控指标。
 *
 * @returns 监控指标
 */
export async function getMonitorMetrics(): Promise<MonitorMetrics> {
  return api.get<MonitorMetrics>("/system/monitor");
}

/**
 * 获取存储容量统计。
 *
 * @returns 容量统计
 */
export async function getStorageCapacity(): Promise<StorageCapacity> {
  return api.get<StorageCapacity>("/system/storage-capacity");
}

const PERM_BASE = "/system/permissions";
const ROLE_PERM_BASE = "/system/roles";
const AUDIT_BASE = "/system/audit";

/**
 * 获取权限树。
 *
 * @returns 权限列表
 */
export async function getPermissionTree(): Promise<SysPermission[]> {
  return api.get<SysPermission[]>(PERM_BASE);
}

/**
 * 获取权限模块列表。
 *
 * @returns 模块列表
 */
export async function listPermissionModules(): Promise<string[]> {
  return api.get<string[]>(`${PERM_BASE}/modules`);
}

/**
 * 获取权限分页。
 *
 * @param params 查询参数
 * @returns 分页结果
 */
export async function listPermissions(params?: {
  module?: string;
  pageNum?: number;
  pageSize?: number;
}): Promise<Page<SysPermission>> {
  return api.get<Page<SysPermission>>(`${PERM_BASE}/list`, { params });
}

/**
 * 创建权限定义。
 *
 * @param data 创建参数
 * @returns 权限实体
 */
export async function createPermission(
  data: PermissionCreateRequest,
): Promise<SysPermission> {
  return api.post<SysPermission>(PERM_BASE, data);
}

/**
 * 更新权限定义。
 *
 * @param id 权限 ID
 * @param data 更新参数
 * @returns 权限实体
 */
export async function updatePermission(
  id: string,
  data: PermissionUpdateRequest,
): Promise<SysPermission> {
  return api.put<SysPermission>(`${PERM_BASE}/${id}`, data);
}

/**
 * 删除权限定义。
 *
 * @param id 权限 ID
 */
export async function deletePermission(id: string): Promise<void> {
  await api.delete(`${PERM_BASE}/${id}`);
}

/**
 * 获取角色权限。
 *
 * @param role 角色名
 * @returns 权限码列表
 */
export async function getRolePermissions(role: string): Promise<string[]> {
  return api.get<string[]>(`${PERM_BASE}/roles/${role}`);
}

/**
 * 为角色授予权限。
 *
 * @param role 角色名
 * @param data 授权参数
 */
export async function grantRolePermission(
  role: string,
  data: GrantPermissionRequest,
): Promise<void> {
  await api.post(`${ROLE_PERM_BASE}/${role}/permissions`, data);
}

/**
 * 撤销角色权限。
 *
 * @param role 角色名
 * @param permissionCode 权限码
 */
export async function revokeRolePermission(
  role: string,
  permissionCode: string,
): Promise<void> {
  await api.delete(`${ROLE_PERM_BASE}/${role}/permissions/${permissionCode}`);
}

/**
 * 获取审计日志列表。
 *
 * @param params 查询参数
 * @returns 审计日志分页
 */
export async function getAuditLogs(
  params?: PageParams & AuditLogQueryParams,
): Promise<Page<AuditLogVO>> {
  return api.get<Page<AuditLogVO>>(`${AUDIT_BASE}/logs`, { params });
}

/**
 * 获取审计日志详情。
 *
 * @param id 日志 ID
 * @returns 日志详情
 */
export async function getAuditLog(id: string): Promise<SysOperationLog> {
  return api.get<SysOperationLog>(`${AUDIT_BASE}/logs/${id}`);
}

/**
 * 获取审计概览。
 *
 * @returns 审计概览
 */
export async function getAuditOverview(): Promise<AuditOverview> {
  return api.get<AuditOverview>(`${AUDIT_BASE}/overview`);
}

/**
 * 获取高频操作列表。
 *
 * @returns 高频列表
 */
export async function getHighFrequencyOperations(): Promise<
  HighFrequencyOperationVO[]
> {
  return api.get<HighFrequencyOperationVO[]>(`${AUDIT_BASE}/high-frequency`);
}

/**
 * 获取敏感操作。
 *
 * @param data 查询参数
 * @returns 分页结果
 */
export async function getSensitiveOperations(
  data: AuditLogQueryVO,
): Promise<Page<SysOperationLog>> {
  return api.post<Page<SysOperationLog>>(`${AUDIT_BASE}/sensitive/page`, data);
}

/**
 * 获取错误操作统计。
 *
 * @returns 统计列表
 */
export async function getErrorOperationStats(): Promise<
  ErrorOperationStatsVO[]
> {
  return api.get<ErrorOperationStatsVO[]>(`${AUDIT_BASE}/error-stats`);
}

/**
 * 获取用户操作时间分布。
 *
 * @returns 分布列表
 */
export async function getUserTimeDistribution(): Promise<
  UserTimeDistributionVO[]
> {
  return api.get<UserTimeDistributionVO[]>(`${AUDIT_BASE}/time-distribution`);
}

/**
 * 获取审计配置。
 *
 * @returns 配置列表
 */
export async function getAuditConfigs(): Promise<AuditConfigVO[]> {
  return api.get<AuditConfigVO[]>(`${AUDIT_BASE}/configs`);
}

/**
 * 更新审计配置。
 *
 * @param data 配置参数
 * @returns 更新结果
 */
export async function updateAuditConfig(data: AuditConfigVO): Promise<boolean> {
  return api.put<boolean>(`${AUDIT_BASE}/configs`, data);
}

/**
 * 手动检查审计异常。
 *
 * @returns 检查结果
 */
export async function checkAuditAnomalies(): Promise<Record<string, unknown>> {
  return api.post<Record<string, unknown>>(`${AUDIT_BASE}/anomalies/check`);
}

/**
 * 执行审计日志备份。
 *
 * @param params 备份参数
 * @returns 备份结果
 */
export async function backupAuditLogs(params?: {
  days?: number;
  deleteAfterBackup?: boolean;
}): Promise<string> {
  return api.post<string>(`${AUDIT_BASE}/logs/backups`, null, { params });
}

/**
 * 导出审计日志。
 *
 * @param params 查询参数
 * @returns 导出文件 Blob
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
