/**
 * 权限信息
 * @see SysPermission.java
 */
export interface SysPermission {
  id: string;
  name: string;
  code: string;
  type: PermissionType;
  parentId?: string;
  path?: string;
  icon?: string;
  sort: number;
  status: number;
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
 */
export interface SystemStats {
  totalUsers: number;
  totalFiles: number;
  totalStorage: number;
  totalTransactions: number;
  todayUploads: number;
  todayDownloads: number;
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
 */
export interface ChainStatus {
  blockNumber: number;
  transactionCount: number;
  failedTransactionCount: number;
  nodeCount: number;
  chainType: ChainType;
  healthy: boolean;
  lastUpdateTime: number;
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
