import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useWebSocketStore } from '../websocket'
import { useAuthStore } from '../auth'

describe('WebSocket Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('should initialize with default state', () => {
    const wsStore = useWebSocketStore()
    
    expect(wsStore.socket).toBeNull()
    expect(wsStore.isConnected).toBe(false)
    expect(wsStore.realtimeMetrics.size).toBe(0)
    expect(wsStore.subscriptions.size).toBe(0)
    expect(wsStore.connectionError).toBeNull()
    expect(wsStore.hasActiveSubscriptions).toBe(false)
  })

  it('should connect with authentication token', () => {
    const authStore = useAuthStore()
    authStore.token = 'mock-token'
    
    const wsStore = useWebSocketStore()
    wsStore.connect()
    
    expect(wsStore.socket).toBeDefined()
  })

  it('should not connect without authentication token', () => {
    const wsStore = useWebSocketStore()
    wsStore.connect()
    
    expect(wsStore.connectionError).toBe('No authentication token available')
  })

  it('should handle connection events', () => {
    const authStore = useAuthStore()
    authStore.token = 'mock-token'
    
    const wsStore = useWebSocketStore()
    wsStore.connect()
    
    // Simulate connection
    const mockSocket = wsStore.socket as any
    mockSocket.on.mock.calls.find(([event]: [string]) => event === 'connect')?.[1]()
    
    expect(wsStore.isConnected).toBe(true)
    expect(wsStore.connectionError).toBeNull()
    expect(wsStore.reconnectAttempts).toBe(0)
  })

  it('should handle disconnection events', () => {
    const authStore = useAuthStore()
    authStore.token = 'mock-token'
    
    const wsStore = useWebSocketStore()
    wsStore.connect()
    
    // Simulate connection then disconnection
    const mockSocket = wsStore.socket as any
    mockSocket.on.mock.calls.find(([event]: [string]) => event === 'connect')?.[1]()
    mockSocket.on.mock.calls.find(([event]: [string]) => event === 'disconnect')?.[1]('transport close')
    
    expect(wsStore.isConnected).toBe(false)
  })

  it('should handle metrics updates', () => {
    const authStore = useAuthStore()
    authStore.token = 'mock-token'
    
    const wsStore = useWebSocketStore()
    wsStore.connect()
    
    const mockMetrics = {
      clientId: 'client-1',
      timestamp: '2024-01-01T00:00:00Z',
      cpuUsage: 50,
      memoryUsage: 60,
      diskUsage: 70,
      networkIn: 1000,
      networkOut: 500,
      loadAverage: 1.5,
      status: 'active' as const
    }
    
    // Simulate metrics update
    const mockSocket = wsStore.socket as any
    mockSocket.on.mock.calls.find(([event]: [string]) => event === 'metrics:update')?.[1](mockMetrics)
    
    expect(wsStore.realtimeMetrics.get('client-1')).toEqual(mockMetrics)
  })

  it('should manage client subscriptions', () => {
    const authStore = useAuthStore()
    authStore.token = 'mock-token'
    
    const wsStore = useWebSocketStore()
    wsStore.connect()
    wsStore.isConnected = true // Mock connected state
    
    wsStore.subscribeToClient('client-1')
    
    expect(wsStore.subscriptions.has('client-1')).toBe(true)
    expect(wsStore.hasActiveSubscriptions).toBe(true)
    
    const mockSocket = wsStore.socket as any
    expect(mockSocket.emit).toHaveBeenCalledWith('subscribe:client', { clientId: 'client-1' })
  })

  it('should unsubscribe from clients', () => {
    const authStore = useAuthStore()
    authStore.token = 'mock-token'
    
    const wsStore = useWebSocketStore()
    wsStore.connect()
    wsStore.isConnected = true
    
    wsStore.subscribeToClient('client-1')
    wsStore.unsubscribeFromClient('client-1')
    
    expect(wsStore.subscriptions.has('client-1')).toBe(false)
    expect(wsStore.realtimeMetrics.has('client-1')).toBe(false)
    
    const mockSocket = wsStore.socket as any
    expect(mockSocket.emit).toHaveBeenCalledWith('unsubscribe:client', { clientId: 'client-1' })
  })

  it('should handle alert subscriptions', () => {
    const authStore = useAuthStore()
    authStore.token = 'mock-token'
    
    const wsStore = useWebSocketStore()
    wsStore.connect()
    wsStore.isConnected = true
    
    wsStore.subscribeToAlerts()
    
    expect(wsStore.subscriptions.has('alerts')).toBe(true)
    
    const mockSocket = wsStore.socket as any
    expect(mockSocket.emit).toHaveBeenCalledWith('subscribe:alerts')
  })

  it('should disconnect and cleanup', () => {
    const authStore = useAuthStore()
    authStore.token = 'mock-token'
    
    const wsStore = useWebSocketStore()
    wsStore.connect()
    wsStore.subscribeToClient('client-1')
    
    wsStore.disconnect()
    
    expect(wsStore.isConnected).toBe(false)
    expect(wsStore.subscriptions.size).toBe(0)
    expect(wsStore.realtimeMetrics.size).toBe(0)
    expect(wsStore.socket).toBeNull()
  })

  it('should get client metrics', () => {
    const wsStore = useWebSocketStore()
    
    const mockMetrics = {
      clientId: 'client-1',
      timestamp: '2024-01-01T00:00:00Z',
      cpuUsage: 50,
      memoryUsage: 60,
      diskUsage: 70,
      networkIn: 1000,
      networkOut: 500,
      loadAverage: 1.5,
      status: 'active' as const
    }
    
    wsStore.realtimeMetrics.set('client-1', mockMetrics)
    
    expect(wsStore.getClientMetrics('client-1')).toEqual(mockMetrics)
    expect(wsStore.getClientMetrics('non-existent')).toBeNull()
  })
})