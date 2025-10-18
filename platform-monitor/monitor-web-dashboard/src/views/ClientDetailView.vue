<template>
  <div class="client-detail">
    <div v-if="isLoading" class="loading-state">
      <div class="loading-spinner">Loading client details...</div>
    </div>

    <div v-else-if="!client" class="error-state">
      <h2>Client not found</h2>
      <p>The requested client could not be found.</p>
      <router-link to="/clients" class="btn btn-primary">Back to Clients</router-link>
    </div>

    <div v-else class="client-content">
      <!-- Client Header -->
      <div class="client-header">
        <div class="header-left">
          <router-link to="/clients" class="back-button">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="15,18 9,12 15,6"></polyline>
            </svg>
            Back to Clients
          </router-link>
          <div class="client-title-section">
            <h1 class="client-title">{{ client.name }}</h1>
            <div class="client-status" :class="client.status">
              <span class="status-dot"></span>
              <span class="status-text">{{ client.status }}</span>
            </div>
          </div>
        </div>
        <div class="header-actions">
          <button class="btn btn-secondary" @click="refreshData">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="23,4 23,10 17,10"></polyline>
              <polyline points="1,20 1,14 7,14"></polyline>
              <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15"></path>
            </svg>
            Refresh
          </button>
        </div>
      </div>

      <!-- Client Info Cards -->
      <div class="info-grid">
        <div class="info-card">
          <h3 class="info-title">System Information</h3>
          <div class="info-content">
            <div class="info-item">
              <span class="info-label">Hostname</span>
              <span class="info-value">{{ client.hostname }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">IP Address</span>
              <span class="info-value">{{ client.ipAddress }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Region</span>
              <span class="info-value">{{ client.region }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Environment</span>
              <span class="info-value">{{ client.environment }}</span>
            </div>
          </div>
        </div>

        <div class="info-card">
          <h3 class="info-title">Connection Status</h3>
          <div class="info-content">
            <div class="info-item">
              <span class="info-label">Last Heartbeat</span>
              <span class="info-value">{{ formatHeartbeat(client.lastHeartbeat) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Connection Failures</span>
              <span class="info-value">{{ client.connectionFailures }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Data Compression</span>
              <span class="info-value">{{ client.dataCompressionEnabled ? 'Enabled' : 'Disabled' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Certificate Expiry</span>
              <span class="info-value">{{ formatCertificateExpiry(client.certificateExpiry) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Real-time Metrics -->
      <div class="metrics-section">
        <div class="metrics-header">
          <h2 class="metrics-title">Real-time Metrics</h2>
          <div class="metrics-controls">
            <select v-model="selectedTimeRange" class="time-range-select">
              <option value="1h">Last Hour</option>
              <option value="6h">Last 6 Hours</option>
              <option value="24h">Last 24 Hours</option>
              <option value="7d">Last 7 Days</option>
            </select>
          </div>
        </div>

        <div class="current-metrics">
          <div class="metric-card">
            <div class="metric-header">
              <h4 class="metric-name">CPU Usage</h4>
              <span class="metric-value" :class="getCpuStatusClass(currentMetrics?.cpuUsage)">
                {{ currentMetrics?.cpuUsage || '--' }}%
              </span>
            </div>
            <div class="metric-chart">
              <canvas ref="cpuChartRef"></canvas>
            </div>
          </div>

          <div class="metric-card">
            <div class="metric-header">
              <h4 class="metric-name">Memory Usage</h4>
              <span class="metric-value" :class="getMemoryStatusClass(currentMetrics?.memoryUsage)">
                {{ currentMetrics?.memoryUsage || '--' }}%
              </span>
            </div>
            <div class="metric-chart">
              <canvas ref="memoryChartRef"></canvas>
            </div>
          </div>

          <div class="metric-card">
            <div class="metric-header">
              <h4 class="metric-name">Disk Usage</h4>
              <span class="metric-value" :class="getDiskStatusClass(currentMetrics?.diskUsage)">
                {{ currentMetrics?.diskUsage || '--' }}%
              </span>
            </div>
            <div class="metric-chart">
              <canvas ref="diskChartRef"></canvas>
            </div>
          </div>

          <div class="metric-card">
            <div class="metric-header">
              <h4 class="metric-name">Network I/O</h4>
              <span class="metric-value">
                ↓{{ formatBytes(currentMetrics?.networkIn || 0) }}/s
                ↑{{ formatBytes(currentMetrics?.networkOut || 0) }}/s
              </span>
            </div>
            <div class="metric-chart">
              <canvas ref="networkChartRef"></canvas>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useClientsStore } from '@/stores/clients'
import { useWebSocketStore } from '@/stores/websocket'
import { formatDistanceToNow, format } from 'date-fns'

const route = useRoute()
const clientsStore = useClientsStore()
const webSocketStore = useWebSocketStore()

const selectedTimeRange = ref('24h')
const cpuChartRef = ref<HTMLCanvasElement>()
const memoryChartRef = ref<HTMLCanvasElement>()
const diskChartRef = ref<HTMLCanvasElement>()
const networkChartRef = ref<HTMLCanvasElement>()

const clientId = computed(() => route.params.id as string)
const client = computed(() => clientsStore.selectedClient)
const isLoading = computed(() => clientsStore.isLoading)
const currentMetrics = computed(() => webSocketStore.getClientMetrics(clientId.value))

const formatHeartbeat = (timestamp: string): string => {
  return formatDistanceToNow(new Date(timestamp), { addSuffix: true })
}

const formatCertificateExpiry = (expiry?: string): string => {
  if (!expiry) return 'Not available'
  return format(new Date(expiry), 'MMM dd, yyyy')
}

const formatBytes = (bytes: number): string => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
}

const getCpuStatusClass = (value?: number): string => {
  if (!value) return ''
  if (value > 80) return 'status-critical'
  if (value > 60) return 'status-warning'
  return 'status-normal'
}

const getMemoryStatusClass = (value?: number): string => {
  if (!value) return ''
  if (value > 85) return 'status-critical'
  if (value > 70) return 'status-warning'
  return 'status-normal'
}

const getDiskStatusClass = (value?: number): string => {
  if (!value) return ''
  if (value > 90) return 'status-critical'
  if (value > 75) return 'status-warning'
  return 'status-normal'
}

const refreshData = async () => {
  try {
    await clientsStore.fetchClient(clientId.value)
    await clientsStore.fetchClientMetrics(clientId.value, selectedTimeRange.value)
  } catch (error) {
    console.error('Failed to refresh client data:', error)
  }
}

const initializeCharts = () => {
  // Chart initialization would go here
  console.log('Charts would be initialized here')
}

watch(selectedTimeRange, async (newRange) => {
  await clientsStore.fetchClientMetrics(clientId.value, newRange)
})

onMounted(async () => {
  await refreshData()
  initializeCharts()
  
  // Subscribe to real-time updates for this client
  if (client.value?.status === 'active') {
    webSocketStore.subscribeToClient(client.value.clientId)
  }
})

onUnmounted(() => {
  // Unsubscribe from real-time updates
  if (client.value) {
    webSocketStore.unsubscribeFromClient(client.value.clientId)
  }
})
</script>

<style scoped>
.client-detail {
  padding: 0;
}

.loading-state,
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 2rem;
  text-align: center;
  color: var(--text-secondary);
}

.client-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 2rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--border-color-light);
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.back-button {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 0.875rem;
  transition: color 0.2s ease;
}

.back-button:hover {
  color: var(--color-primary);
}

.client-title-section {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.client-title {
  font-size: 2rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.client-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  border-radius: var(--radius-lg);
  font-size: 0.875rem;
  font-weight: 500;
}

.client-status.active {
  background-color: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

.client-status.inactive {
  background-color: rgba(107, 114, 128, 0.1);
  color: var(--text-secondary);
}

.client-status.maintenance {
  background-color: rgba(245, 158, 11, 0.1);
  color: var(--color-warning);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: currentColor;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.info-card {
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 1.5rem;
  box-shadow: var(--shadow-sm);
}

.info-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 1rem 0;
}

.info-content {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.info-label {
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.info-value {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-primary);
}

.metrics-section {
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 1.5rem;
  box-shadow: var(--shadow-sm);
}

.metrics-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1.5rem;
}

.metrics-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.time-range-select {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background-color: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.875rem;
}

.current-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 1.5rem;
}

.metric-card {
  background-color: var(--bg-secondary);
  border-radius: var(--radius-lg);
  padding: 1rem;
}

.metric-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.metric-name {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-secondary);
  margin: 0;
}

.metric-value {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
}

.metric-value.status-normal {
  color: var(--color-success);
}

.metric-value.status-warning {
  color: var(--color-warning);
}

.metric-value.status-critical {
  color: var(--color-error);
}

.metric-chart {
  height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--bg-tertiary);
  border-radius: var(--radius-md);
  color: var(--text-tertiary);
  font-size: 0.875rem;
}

@media (max-width: 768px) {
  .client-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 1rem;
  }

  .client-title-section {
    flex-direction: column;
    align-items: flex-start;
    gap: 0.5rem;
  }

  .metrics-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 1rem;
  }

  .current-metrics {
    grid-template-columns: 1fr;
  }
}
</style>