<template>
  <teleport to="body">
    <div class="notifications-container">
      <transition-group name="notification" tag="div">
        <div
          v-for="notification in notifications"
          :key="notification.id"
          class="notification"
          :class="[`notification-${notification.type}`, { 'notification-dismissible': notification.dismissible }]"
        >
          <div class="notification-icon">
            <svg v-if="notification.type === 'success'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="20,6 9,17 4,12"></polyline>
            </svg>
            <svg v-else-if="notification.type === 'error'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"></circle>
              <line x1="15" y1="9" x2="9" y2="15"></line>
              <line x1="9" y1="9" x2="15" y2="15"></line>
            </svg>
            <svg v-else-if="notification.type === 'warning'" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
              <line x1="12" y1="9" x2="12" y2="13"></line>
              <line x1="12" y1="17" x2="12.01" y2="17"></line>
            </svg>
            <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"></circle>
              <line x1="12" y1="16" x2="12" y2="12"></line>
              <line x1="12" y1="8" x2="12.01" y2="8"></line>
            </svg>
          </div>

          <div class="notification-content">
            <div class="notification-title">{{ notification.title }}</div>
            <div v-if="notification.message" class="notification-message">{{ notification.message }}</div>
            
            <div v-if="notification.actions && notification.actions.length > 0" class="notification-actions">
              <button
                v-for="action in notification.actions"
                :key="action.label"
                :class="['notification-action-btn', `btn-${action.style || 'secondary'}`]"
                @click="action.action(); dismissNotification(notification.id)"
              >
                {{ action.label }}
              </button>
            </div>
          </div>

          <button
            v-if="notification.dismissible"
            class="notification-close"
            @click="dismissNotification(notification.id)"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
      </transition-group>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useWebSocketStore } from '@/stores/websocket'
import { useSettingsStore } from '@/stores/settings'

interface Notification {
  id: string
  type: 'success' | 'error' | 'warning' | 'info'
  title: string
  message?: string
  duration?: number
  dismissible?: boolean
  persistent?: boolean
  actions?: Array<{
    label: string
    action: () => void
    style?: 'primary' | 'secondary'
  }>
}

const webSocketStore = useWebSocketStore()
const settingsStore = useSettingsStore()

const notifications = ref<Notification[]>([])
const maxNotifications = 5

const addNotification = (notification: Omit<Notification, 'id'>) => {
  const id = Date.now().toString() + Math.random().toString(36).substr(2, 9)
  const newNotification: Notification = {
    id,
    dismissible: true,
    duration: notification.persistent ? 0 : (notification.duration || 5000),
    ...notification
  }

  // Remove oldest notification if we exceed max
  if (notifications.value.length >= maxNotifications) {
    notifications.value.shift()
  }

  notifications.value.push(newNotification)

  // Play notification sound if enabled
  if (settingsStore.preferences.notificationSound && settingsStore.preferences.enableNotifications) {
    playNotificationSound(newNotification.type)
  }

  // Auto-dismiss if duration is set
  if (newNotification.duration && newNotification.duration > 0) {
    setTimeout(() => {
      dismissNotification(id)
    }, newNotification.duration)
  }

  // Show browser notification if permission granted
  if (settingsStore.preferences.pushNotifications && 'Notification' in window && Notification.permission === 'granted') {
    showBrowserNotification(newNotification)
  }
}

const dismissNotification = (id: string) => {
  const index = notifications.value.findIndex(n => n.id === id)
  if (index > -1) {
    notifications.value.splice(index, 1)
  }
}

const clearAllNotifications = () => {
  notifications.value = []
}

const playNotificationSound = (type: string) => {
  try {
    const audio = new Audio()
    // Different sounds for different notification types
    switch (type) {
      case 'error':
        audio.src = 'data:audio/wav;base64,UklGRnoGAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQoGAACBhYqFbF1fdJivrJBhNjVgodDbq2EcBj+a2/LDciUFLIHO8tiJNwgZaLvt559NEAxQp+PwtmMcBjiR1/LMeSwFJHfH8N2QQAoUXrTp66hVFApGn+DyvmwhBSuBzvLZiTYIG2m98OScTgwOUarm7blmGgU7k9n1unEiBC13yO/eizEIHWq+8+OWT'
        break
      case 'warning':
        audio.src = 'data:audio/wav;base64,UklGRnoGAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQoGAACBhYqFbF1fdJivrJBhNjVgodDbq2EcBj+a2/LDciUFLIHO8tiJNwgZaLvt559NEAxQp+PwtmMcBjiR1/LMeSwFJHfH8N2QQAoUXrTp66hVFApGn+DyvmwhBSuBzvLZiTYIG2m98OScTgwOUarm7blmGgU7k9n1unEiBC13yO/eizEIHWq+8+OWT'
        break
      case 'success':
        audio.src = 'data:audio/wav;base64,UklGRnoGAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQoGAACBhYqFbF1fdJivrJBhNjVgodDbq2EcBj+a2/LDciUFLIHO8tiJNwgZaLvt559NEAxQp+PwtmMcBjiR1/LMeSwFJHfH8N2QQAoUXrTp66hVFApGn+DyvmwhBSuBzvLZiTYIG2m98OScTgwOUarm7blmGgU7k9n1unEiBC13yO/eizEIHWq+8+OWT'
        break
      default:
        audio.src = 'data:audio/wav;base64,UklGRnoGAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQoGAACBhYqFbF1fdJivrJBhNjVgodDbq2EcBj+a2/LDciUFLIHO8tiJNwgZaLvt559NEAxQp+PwtmMcBjiR1/LMeSwFJHfH8N2QQAoUXrTp66hVFApGn+DyvmwhBSuBzvLZiTYIG2m98OScTgwOUarm7blmGgU7k9n1unEiBC13yO/eizEIHWq+8+OWT'
    }
    audio.volume = 0.3
    audio.play().catch(() => {
      // Ignore audio play errors (user interaction required)
    })
  } catch (error) {
    // Ignore audio errors
  }
}

const showBrowserNotification = (notification: Notification) => {
  try {
    const browserNotification = new Notification(notification.title, {
      body: notification.message,
      icon: '/favicon.ico',
      tag: notification.id,
      requireInteraction: notification.persistent
    })

    browserNotification.onclick = () => {
      window.focus()
      browserNotification.close()
    }

    // Auto-close browser notification
    if (!notification.persistent) {
      setTimeout(() => {
        browserNotification.close()
      }, notification.duration || 5000)
    }
  } catch (error) {
    console.warn('Failed to show browser notification:', error)
  }
}

// Handle WebSocket connection status
watch(() => webSocketStore.isConnected, (isConnected, wasConnected) => {
  if (wasConnected === false && isConnected) {
    addNotification({
      type: 'success',
      title: 'Connected',
      message: 'Real-time monitoring is now active',
      duration: 3000
    })
  } else if (wasConnected === true && !isConnected) {
    addNotification({
      type: 'warning',
      title: 'Connection Lost',
      message: 'Attempting to reconnect to monitoring server...',
      persistent: true
    })
  }
})

// Handle WebSocket errors
watch(() => webSocketStore.connectionError, (error) => {
  if (error) {
    addNotification({
      type: 'error',
      title: 'Connection Error',
      message: error,
      duration: 8000,
      actions: [
        {
          label: 'Retry',
          action: () => {
            webSocketStore.connect()
          },
          style: 'primary'
        }
      ]
    })
  }
})

// Global notification system
const handleGlobalNotification = (event: CustomEvent<Notification>) => {
  addNotification(event.detail)
}

// Handle WebSocket alert events
const handleWebSocketAlert = () => {
  if (webSocketStore.socket) {
    webSocketStore.socket.on('alert:triggered', (data: any) => {
      addNotification({
        type: data.severity === 'critical' ? 'error' : 'warning',
        title: 'Alert Triggered',
        message: data.message,
        duration: data.severity === 'critical' ? 0 : 10000,
        persistent: data.severity === 'critical'
      })
    })

    webSocketStore.socket.on('client:disconnected', (data: any) => {
      addNotification({
        type: 'warning',
        title: 'Client Disconnected',
        message: `Client ${data.clientId} has disconnected`,
        duration: 5000
      })
    })

    webSocketStore.socket.on('client:connected', (data: any) => {
      addNotification({
        type: 'success',
        title: 'Client Connected',
        message: `Client ${data.clientId} has connected`,
        duration: 3000
      })
    })
  }
}

// Request notification permission
const requestNotificationPermission = async () => {
  if ('Notification' in window && Notification.permission === 'default') {
    try {
      const permission = await Notification.requestPermission()
      if (permission === 'granted') {
        addNotification({
          type: 'success',
          title: 'Notifications Enabled',
          message: 'You will now receive browser notifications for important alerts',
          duration: 3000
        })
      }
    } catch (error) {
      console.warn('Failed to request notification permission:', error)
    }
  }
}

onMounted(() => {
  window.addEventListener('app:notification', handleGlobalNotification as EventListener)
  
  // Set up WebSocket alert handling
  handleWebSocketAlert()
  
  // Request notification permission if push notifications are enabled
  if (settingsStore.preferences.pushNotifications) {
    requestNotificationPermission()
  }
})

onUnmounted(() => {
  window.removeEventListener('app:notification', handleGlobalNotification as EventListener)
})

// Expose methods for external use
defineExpose({
  addNotification,
  dismissNotification,
  clearAllNotifications,
  requestNotificationPermission
})
</script>

<style scoped>
.notifications-container {
  position: fixed;
  top: 80px;
  right: 1rem;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-width: 400px;
  width: 100%;
}

.notification {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 1rem;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  border-left: 4px solid;
  background-color: var(--bg-primary);
  border-color: var(--border-color);
}

.notification-success {
  border-left-color: var(--color-success);
  background-color: var(--bg-primary);
}

.notification-error {
  border-left-color: var(--color-error);
  background-color: var(--bg-primary);
}

.notification-warning {
  border-left-color: var(--color-warning);
  background-color: var(--bg-primary);
}

.notification-info {
  border-left-color: var(--color-info);
  background-color: var(--bg-primary);
}

.notification-icon {
  flex-shrink: 0;
  margin-top: 0.125rem;
}

.notification-success .notification-icon {
  color: var(--color-success);
}

.notification-error .notification-icon {
  color: var(--color-error);
}

.notification-warning .notification-icon {
  color: var(--color-warning);
}

.notification-info .notification-icon {
  color: var(--color-info);
}

.notification-content {
  flex: 1;
  min-width: 0;
}

.notification-title {
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
}

.notification-message {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  line-height: 1.4;
  margin-bottom: 0.5rem;
}

.notification-actions {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.75rem;
}

.notification-action-btn {
  padding: 0.25rem 0.75rem;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 0.75rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.notification-action-btn.btn-primary {
  background-color: var(--color-primary);
  color: white;
}

.notification-action-btn.btn-primary:hover {
  background-color: var(--color-primary-dark);
}

.notification-action-btn.btn-secondary {
  background-color: var(--bg-tertiary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
}

.notification-action-btn.btn-secondary:hover {
  background-color: var(--border-color);
}

.notification-close {
  flex-shrink: 0;
  background: none;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: 0.25rem;
  border-radius: var(--radius-sm);
  transition: all 0.2s ease;
  margin-top: -0.125rem;
}

.notification-close:hover {
  color: var(--text-primary);
  background-color: var(--bg-tertiary);
}

/* Transition animations */
.notification-enter-active {
  transition: all 0.3s ease;
}

.notification-leave-active {
  transition: all 0.3s ease;
}

.notification-enter-from {
  opacity: 0;
  transform: translateX(100%);
}

.notification-leave-to {
  opacity: 0;
  transform: translateX(100%);
}

.notification-move {
  transition: transform 0.3s ease;
}

@media (max-width: 768px) {
  .notifications-container {
    left: 1rem;
    right: 1rem;
    max-width: none;
  }
}
</style>