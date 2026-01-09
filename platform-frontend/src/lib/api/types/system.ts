/**
 * 权限信息
 * @see SysPermission.java
 */
export interface SysPermission {
  id: string;
  name: string;
  code: string;
  type: PermissionType;
  module?: string;
  action?: string;
  description?: string;
  tenantId?: number;
  parentId?: string;
  path?: string;
  icon?: string;
  sort: number;
  status: number;
  createTime?: string;
  updateTime?: string;
  children?: SysPermission[];
}

/**
 * 权限类型
 */
export enum PermissionType {
  MENU = 0,
  BUTTON = 1,
  API = 2,
}

/**
 * 审计日志
 * @see AuditLogVO.java
 */
export interface AuditLogVO {
  id: string;
  userId: string;
  username: string;
  action: string;
  module: string;
  targetId?: string;
  targetType?: string;
  detail?: string;
  ip: string;
  userAgent?: string;
  status: number;
  errorMessage?: string;
  duration: number;
  createTime: string;
}

/**
 * 操作日志
 * @see OperationLogVO.java
 */
export interface OperationLogVO {
  id: string;
  userId: string;
  username: string;
  operation: string;
  method: string;
  params?: string;
  result?: string;
  ip: string;
  duration: number;
  createTime: string;
}

/**
 * 审计日志查询参数
 */
export interface AuditLogQueryParams {
  username?: string;
  action?: string;
  module?: string;
  startTime?: string;
  endTime?: string;
  status?: number;
}

/**
 * 系统统计信息
 * 注意：Long 类型字段从后端序列化为字符串
 */
export interface SystemStats {
  totalUsers: number | string;
  totalFiles: number | string;
  totalStorage: number | string;
  totalTransactions: number | string;
  todayUploads: number | string;
  todayDownloads: number | string;
}

/**
 * 区块链类型
 */
export enum ChainType {
  LOCAL_FISCO = "LOCAL_FISCO",
  BSN_FISCO = "BSN_FISCO",
  BSN_BESU = "BSN_BESU",
}

/**
 * 区块链状态信息
 * 注意：Long 类型字段从后端序列化为字符串
 */
export interface ChainStatus {
  blockNumber: number | string;
  transactionCount: number | string;
  failedTransactionCount: number | string;
  nodeCount: number;
  chainType: ChainType;
  healthy: boolean;
  lastUpdateTime: number | string;
}

/**
 * 系统健康状态
 */
export interface SystemHealth {
  status: "UP" | "DOWN" | "DEGRADED";
  components: {
    database: ComponentHealth;
    redis: ComponentHealth;
    blockchain: ComponentHealth;
    storage: ComponentHealth;
  };
  uptime: number;
  timestamp: string;
}

/**
 * 组件健康状态
 */
export interface ComponentHealth {
  status: "UP" | "DOWN" | "UNKNOWN";
  details?: Record<string, unknown>;
}

/**
 * 监控指标
 */
export interface MonitorMetrics {
  systemStats: SystemStats;
  chainStatus: ChainStatus;
  health: SystemHealth;
}

export type AuditOverview = Record<string, unknown>;

export interface AuditConfigVO {
  id: number;
  configKey: string;
  configValue: string;
  description?: string;
  createTime?: string;
  updateTime?: string;
}

export interface HighFrequencyOperationVO {
  userId: string;
  username: string;
  requestIp: string;
  operationCount: number;
  startTime: string;
  endTime: string;
  timeSpanSeconds: number;
}

export interface ErrorOperationStatsVO {
  module: string;
  operationType: string;
  errorMsg: string;
  errorCount: number;
  firstOccurrence: string;
  lastOccurrence: string;
}

export interface UserTimeDistributionVO {
  hourOfDay: number;
  dayOfWeek: number;
  operationCount: number;
}

export interface AuditLogQueryVO {
  pageNum?: number;
  pageSize?: number;
  userId?: string;
  username?: string;
  module?: string;
  operationType?: string;
  status?: number;
  requestIp?: string;
  startTime?: string;
  endTime?: string;
}

export interface SysOperationLog {
  id: number | string;
  module: string;
  operationType: string;
  description?: string;
  method?: string;
  requestUrl?: string;
  requestMethod?: string;
  requestIp?: string;
  requestParam?: string;
  responseResult?: string;
  status: number;
  errorMsg?: string;
  userId?: string;
  username?: string;
  operationTime?: string;
  executionTime?: number;
}

export interface PermissionCreateRequest {
  code: string;
  name: string;
  module: string;
  action: string;
  description?: string;
}

export interface PermissionUpdateRequest {
  name?: string;
  description?: string;
  status?: number;
}

export interface GrantPermissionRequest {
  permissionCode: string;
}
