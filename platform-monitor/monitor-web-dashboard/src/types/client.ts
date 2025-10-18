export interface Client {
  id: string
  clientId: string
  name: string
  hostname: string
  ipAddress: string
  region: string
  environment: 'dev' | 'staging' | 'prod'
  status: 'active' | 'inactive' | 'maintenance'
  lastHeartbeat: string
  connectionFailures: number
  dataCompressionEnabled: boolean
  certificateExpiry?: string
  createdAt: string
  updatedAt: string
}

export interface ClientMetrics {
  clientId: string
  timestamp: string
  cpuUsage: number
  memoryUsage: number
  diskUsage: number
  networkIn: number
  networkOut: number
  loadAverage: number
  processCount?: number
  uptime?: number
}

export interface ClientFilter {
  search: string
  status: 'all' | 'active' | 'inactive' | 'maintenance'
  region: 'all' | string
  environment: 'all' | 'dev' | 'staging' | 'prod'
}

export interface ClientHistoryQuery {
  clientId: string
  startTime: string
  endTime: string
  metrics: string[]
  aggregation?: 'avg' | 'max' | 'min' | 'sum'
  interval?: string
}

export interface ClientHistoryData {
  timestamp: string
  values: Record<string, number>
}

export interface ClientSummary {
  totalClients: number
  activeClients: number
  inactiveClients: number
  maintenanceClients: number
  avgCpuUsage: number
  avgMemoryUsage: number
  totalNetworkTraffic: number
}