import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { io, Socket } from 'socket.io-client'
import { useAuthStore } from './auth'
import type { RealtimeMetrics, WebSocketEvent } from '@/types/websocket'

export const useWebSocketStore = defineStore('websocket', () => {
  const socket = ref<Socket | null>(null)
  const isConnected = ref(false)
  const realtimeMetrics = ref<Map<string, RealtimeMetrics>>(new Map())
  const subscriptions = ref<Set<string>>(new Set())
  const connectionError = ref<string | null>(null)
  const reconnectAttempts = ref(0)
  const maxReconnectAttempts = 5

  const hasActiveSubscriptions = computed(() => subscriptions.value.size > 0)

  const connect = (): void => {
    const authStore = useAuthStore()
    
    if (!authStore.token) {
      connectionError.value = 'No authentication token available'
      return
    }

    const wsUrl = import.meta.env.VITE_WS_BASE_URL || 'ws://localhost:8080'
    
    socket.value = io(wsUrl, {
      auth: {
        token: authStore.token
      },
      transports: ['websocket'],
      timeout: 5000,
      forceNew: true,
      reconnection: true,
      reconnectionAttempts: maxReconnectAttempts,
      reconnectionDelay: 1000,
      reconnectionDelayMax: 5000
    })

    socket.value.on('connect', () => {
      isConnected.value = true
      connectionError.value = null
      reconnectAttempts.value = 0
      console.log('WebSocket connected')
      
      // Resubscribe to previous subscriptions
      subscriptions.value.forEach(subscription => {
        if (subscription === 'alerts') {
          socket.value?.emit('subscribe:alerts')
        } else {
          socket.value?.emit('subscribe:client', { clientId: subscription })
        }
      })
    })

    socket.value.on('disconnect', (reason) => {
      isConnected.value = false
      console.log('WebSocket disconnected:', reason)
      
      if (reason === 'io server disconnect') {
        connectionError.value = 'Server disconnected the connection'
      } else if (reason === 'transport close') {
        connectionError.value = 'Connection lost, attempting to reconnect...'
      }
    })

    socket.value.on('connect_error', (error) => {
      connectionError.value = error.message
      reconnectAttempts.value++
      
      if (reconnectAttempts.value >= maxReconnectAttempts) {
        connectionError.value = 'Failed to connect after multiple attempts'
      }
    })

    socket.value.on('reconnect', (attemptNumber) => {
      console.log('WebSocket reconnected after', attemptNumber, 'attempts')
      connectionError.value = null
    })

    socket.value.on('reconnect_attempt', (attemptNumber) => {
      console.log('WebSocket reconnection attempt', attemptNumber)
      connectionError.value = `Reconnecting... (attempt ${attemptNumber}/${maxReconnectAttempts})`
    })

    socket.value.on('reconnect_failed', () => {
      connectionError.value = 'Failed to reconnect to server'
    })

    socket.value.on('metrics:update', (data: RealtimeMetrics) => {
      realtimeMetrics.value.set(data.clientId, data)
    })

    socket.value.on('client:status', (data: WebSocketEvent) => {
      console.log('Client status update:', data)
      // Update client status in metrics if available
      if (data.clientId && realtimeMetrics.value.has(data.clientId)) {
        const metrics = realtimeMetrics.value.get(data.clientId)!
        metrics.status = (data as any).newStatus || metrics.status
        realtimeMetrics.value.set(data.clientId, metrics)
      }
    })

    socket.value.on('alert:triggered', (data: WebSocketEvent) => {
      console.log('Alert triggered:', data)
      // Could emit to a notification system here
    })

    socket.value.on('error', (error) => {
      console.error('WebSocket error:', error)
      connectionError.value = 'WebSocket error: ' + error.message
    })
  }

  const disconnect = (): void => {
    if (socket.value) {
      socket.value.disconnect()
      socket.value = null
    }
    isConnected.value = false
    subscriptions.value.clear()
    realtimeMetrics.value.clear()
  }

  const subscribeToClient = (clientId: string): void => {
    if (!socket.value || !isConnected.value) {
      console.warn('WebSocket not connected, cannot subscribe to client:', clientId)
      return
    }

    socket.value.emit('subscribe:client', { clientId })
    subscriptions.value.add(clientId)
  }

  const unsubscribeFromClient = (clientId: string): void => {
    if (!socket.value || !isConnected.value) {
      return
    }

    socket.value.emit('unsubscribe:client', { clientId })
    subscriptions.value.delete(clientId)
    realtimeMetrics.value.delete(clientId)
  }

  const subscribeToAlerts = (): void => {
    if (!socket.value || !isConnected.value) {
      console.warn('WebSocket not connected, cannot subscribe to alerts')
      return
    }

    socket.value.emit('subscribe:alerts')
    subscriptions.value.add('alerts')
  }

  const unsubscribeFromAlerts = (): void => {
    if (!socket.value || !isConnected.value) {
      return
    }

    socket.value.emit('unsubscribe:alerts')
    subscriptions.value.delete('alerts')
  }

  const getClientMetrics = (clientId: string): RealtimeMetrics | null => {
    return realtimeMetrics.value.get(clientId) || null
  }

  return {
    socket,
    isConnected,
    realtimeMetrics,
    subscriptions,
    connectionError,
    reconnectAttempts,
    hasActiveSubscriptions,
    connect,
    disconnect,
    subscribeToClient,
    unsubscribeFromClient,
    subscribeToAlerts,
    unsubscribeFromAlerts,
    getClientMetrics
  }
})