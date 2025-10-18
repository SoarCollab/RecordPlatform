export interface RealtimeMetrics {
  clientId: string
  timestamp: string
  cpuUsage: number
  memoryUsage: number
  diskUsage: number
  networkIn: number
  networkOut: number
  loadAverage: number
  status: 'active' | 'inactive' | 'maintenance'
}

export interface WebSocketEvent {
  type: string
  clientId?: string
  data: any
  timestamp: string
}

export interface AlertEvent extends WebSocketEvent {
  type: 'alert:triggered' | 'alert:resolved' | 'alert:acknowledged'
  alertId: string
  severity: 'low' | 'medium' | 'high' | 'critical'
  message: string
}

export interface ClientStatusEvent extends WebSocketEvent {
  type: 'client:connected' | 'client:disconnected' | 'client:maintenance'
  clientId: string
  previousStatus?: string
  newStatus: string
}

export interface SubscriptionRequest {
  clientId?: string
  metrics?: string[]
  alerts?: boolean
}