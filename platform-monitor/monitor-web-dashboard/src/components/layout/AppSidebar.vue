<template>
  <aside class="app-sidebar" :class="{ 'collapsed': isCollapsed }">
    <nav class="sidebar-nav">
      <ul class="nav-list">
        <li class="nav-item">
          <router-link to="/" class="nav-link" :class="{ 'active': $route.name === 'dashboard' }">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="3" width="7" height="7"></rect>
              <rect x="14" y="3" width="7" height="7"></rect>
              <rect x="14" y="14" width="7" height="7"></rect>
              <rect x="3" y="14" width="7" height="7"></rect>
            </svg>
            <span class="nav-text">Dashboard</span>
          </router-link>
        </li>

        <li class="nav-item">
          <router-link to="/clients" class="nav-link" :class="{ 'active': $route.name?.toString().startsWith('client') }">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
              <line x1="8" y1="21" x2="16" y2="21"></line>
              <line x1="12" y1="17" x2="12" y2="21"></line>
            </svg>
            <span class="nav-text">Clients</span>
            <span v-if="clientsCount.active > 0" class="nav-badge">{{ clientsCount.active }}</span>
          </router-link>
        </li>

        <li class="nav-item">
          <router-link to="/alerts" class="nav-link" :class="{ 'active': $route.name === 'alerts' }">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
              <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
            </svg>
            <span class="nav-text">Alerts</span>
            <span v-if="activeAlertsCount > 0" class="nav-badge alert-badge">{{ activeAlertsCount }}</span>
          </router-link>
        </li>

        <li class="nav-item">
          <router-link to="/settings" class="nav-link" :class="{ 'active': $route.name === 'settings' }">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="3"></circle>
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1 1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
            </svg>
            <span class="nav-text">Settings</span>
          </router-link>
        </li>
      </ul>

      <div class="sidebar-footer">
        <div class="system-status">
          <div class="status-item">
            <span class="status-label">System Status</span>
            <div class="status-indicators">
              <div class="status-dot" :class="systemStatus.database" title="Database"></div>
              <div class="status-dot" :class="systemStatus.redis" title="Redis"></div>
              <div class="status-dot" :class="systemStatus.influxdb" title="InfluxDB"></div>
              <div class="status-dot" :class="systemStatus.rabbitmq" title="RabbitMQ"></div>
            </div>
          </div>
        </div>

        <button class="collapse-button" @click="toggleCollapse" :title="collapseButtonTitle">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline :points="isCollapsed ? '9,18 15,12 9,6' : '15,18 9,12 15,6'"></polyline>
          </svg>
        </button>
      </div>
    </nav>
  </aside>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useClientsStore } from '@/stores/clients'

const clientsStore = useClientsStore()

const isCollapsed = ref(false)
const activeAlertsCount = ref(0) // This would come from an alerts store
const systemStatus = ref({
  database: 'healthy',
  redis: 'healthy',
  influxdb: 'healthy',
  rabbitmq: 'healthy'
})

const clientsCount = computed(() => clientsStore.clientsCount)

const collapseButtonTitle = computed(() => 
  isCollapsed.value ? 'Expand sidebar' : 'Collapse sidebar'
)

const toggleCollapse = () => {
  isCollapsed.value = !isCollapsed.value
  document.body.classList.toggle('sidebar-collapsed', isCollapsed.value)
}

// Simulate system status updates
const updateSystemStatus = () => {
  // In a real app, this would fetch actual system health data
  const statuses = ['healthy', 'warning', 'error']
  
  // Randomly update status for demo purposes
  if (Math.random() > 0.95) {
    const services = Object.keys(systemStatus.value)
    const randomService = services[Math.floor(Math.random() * services.length)]
    const randomStatus = statuses[Math.floor(Math.random() * statuses.length)]
    systemStatus.value[randomService as keyof typeof systemStatus.value] = randomStatus
  }
}

let statusInterval: number

onMounted(() => {
  // Fetch initial clients data
  clientsStore.fetchClients().catch(console.error)
  
  // Set up periodic system status updates
  statusInterval = window.setInterval(updateSystemStatus, 30000)
})

onUnmounted(() => {
  if (statusInterval) {
    clearInterval(statusInterval)
  }
})
</script>

<style scoped>
.app-sidebar {
  position: fixed;
  top: 60px;
  left: 0;
  width: 250px;
  height: calc(100vh - 60px);
  background-color: var(--bg-primary);
  border-right: 1px solid var(--border-color);
  transition: width 0.3s ease, transform 0.3s ease;
  z-index: 999;
  overflow: hidden;
}

.app-sidebar.collapsed {
  width: 60px;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 1rem 0;
}

.nav-list {
  list-style: none;
  padding: 0;
  margin: 0;
  flex: 1;
}

.nav-item {
  margin-bottom: 0.25rem;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 0.875rem;
  font-weight: 500;
  transition: all 0.2s ease;
  border-radius: 0;
  position: relative;
}

.nav-link:hover {
  background-color: var(--bg-secondary);
  color: var(--text-primary);
}

.nav-link.active {
  background-color: var(--color-primary);
  color: white;
}

.nav-link.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background-color: var(--color-primary-dark);
}

.nav-text {
  white-space: nowrap;
  overflow: hidden;
  transition: opacity 0.3s ease;
}

.collapsed .nav-text {
  opacity: 0;
}

.nav-badge {
  margin-left: auto;
  background-color: var(--color-info);
  color: white;
  font-size: 0.75rem;
  font-weight: 600;
  padding: 0.125rem 0.5rem;
  border-radius: 1rem;
  min-width: 1.25rem;
  text-align: center;
  transition: opacity 0.3s ease;
}

.alert-badge {
  background-color: var(--color-error);
}

.collapsed .nav-badge {
  opacity: 0;
}

.sidebar-footer {
  padding: 1rem;
  border-top: 1px solid var(--border-color-light);
}

.system-status {
  margin-bottom: 1rem;
}

.status-item {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.status-label {
  font-size: 0.75rem;
  color: var(--text-tertiary);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  transition: opacity 0.3s ease;
}

.collapsed .status-label {
  opacity: 0;
}

.status-indicators {
  display: flex;
  gap: 0.5rem;
  justify-content: center;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  transition: all 0.2s ease;
}

.status-dot.healthy {
  background-color: var(--color-success);
}

.status-dot.warning {
  background-color: var(--color-warning);
}

.status-dot.error {
  background-color: var(--color-error);
}

.collapse-button {
  width: 100%;
  padding: 0.5rem;
  background: none;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.collapse-button:hover {
  background-color: var(--bg-secondary);
  color: var(--text-primary);
  border-color: var(--color-primary);
}

@media (max-width: 768px) {
  .app-sidebar {
    transform: translateX(-100%);
    width: 250px;
  }

  .app-sidebar.collapsed {
    width: 250px;
  }

  :global(body.sidebar-open) .app-sidebar {
    transform: translateX(0);
  }

  .collapsed .nav-text,
  .collapsed .nav-badge,
  .collapsed .status-label {
    opacity: 1;
  }
}
</style>