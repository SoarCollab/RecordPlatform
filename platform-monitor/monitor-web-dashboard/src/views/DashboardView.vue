<template>
  <div class="dashboard">
    <div class="dashboard-header">
      <h1 class="dashboard-title">Dashboard</h1>
      <div class="dashboard-actions">
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

    <div class="dashboard-content">
      <!-- Summary Cards -->
      <div class="summary-grid">
        <div class="summary-card">
          <div class="summary-header">
            <h3 class="summary-title">Total Clients</h3>
            <div class="summary-icon clients-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
                <line x1="8" y1="21" x2="16" y2="21"></line>
                <line x1="12" y1="17" x2="12" y2="21"></line>
              </svg>
            </div>
          </div>
          <div class="summary-value">{{ clientsCount.total }}</div>
          <div class="summary-details">
            <span class="detail-item success">{{ clientsCount.active }} Active</span>
            <span class="detail-item error">{{ clientsCount.inactive }} Inactive</span>
          </div>
        </div>

        <div class="summary-card">
          <div class="summary-header">
            <h3 class="summary-title">Avg CPU Usage</h3>
            <div class="summary-icon cpu-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect>
                <rect x="9" y="9" width="6" height="6"></rect>
                <line x1="9" y1="1" x2="9" y2="4"></line>
                <line x1="15" y1="1" x2="15" y2="4"></line>
                <line x1="9" y1="20" x2="9" y2="23"></line>
                <line x1="15" y1="20" x2="15" y2="23"></line>
                <line x1="20" y1="9" x2="23" y2="9"></line>
                <line x1="20" y1="14" x2="23" y2="14"></line>
                <line x1="1" y1="9" x2="4" y2="9"></line>
                <line x1="1" y1="14" x2="4" y2="14"></line>
              </svg>
            </div>
          </div>
          <div class="summary-value">{{ avgCpuUsage }}%</div>
          <div class="summary-trend" :class="cpuTrendClass">
            {{ cpuTrendText }}
          </div>
        </div>

        <div class="summary-card">
          <div class="summary-header">
            <h3 class="summary-title">Avg Memory Usage</h3>
            <div class="summary-icon memory-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="2" y="4" width="20" height="16" rx="2" ry="2"></rect>
                <line x1="6" y1="8" x2="6" y2="16"></line>
                <line x1="10" y1="8" x2="10" y2="16"></line>
                <line x1="14" y1="8" x2="14" y2="16"></line>
                <line x1="18" y1="8" x2="18" y2="16"></line>
              </svg>
            </div>
          </div>
          <div class="summary-value">{{ avgMemoryUsage }}%</div>
          <div class="summary-trend" :class="memoryTrendClass">
            {{ memoryTrendText }}
          </div>
        </div>

        <div class="summary-card">
          <div class="summary-header">
            <h3 class="summary-title">Active Alerts</h3>
            <div class="summary-icon alerts-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
                <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
              </svg>
            </div>
          </div>
          <div class="summary-value">{{ activeAlerts }}</div>
          <div class="summary-details">
            <span class="detail-item error">{{ criticalAlerts }} Critical</span>
            <span class="detail-item warning">{{ warningAlerts }} Warning</span>
          </div>
        </div>
      </div>

      <!-- Real-time Metrics Grid -->
      <div class="metrics-grid">
        <RealtimeMetricsWidget
          title="Average CPU Usage"
          subtitle="Across all active clients"
          metric-key="cpuUsage"
          unit="%"
          :data="cpuMetricsData"
          :warning-threshold="70"
          :critical-threshold="85"
          :y-axis-min="0"
          :y-axis-max="100"
          @refresh="refreshMetrics"
        />

        <RealtimeMetricsWidget
          title="Average Memory Usage"
          subtitle="Across all active clients"
          metric-key="memoryUsage"
          unit="%"
          :data="memoryMetricsData"
          :warning-threshold="80"
          :critical-threshold="90"
          :y-axis-min="0"
          :y-axis-max="100"
          @refresh="refreshMetrics"
        />

        <RealtimeMetricsWidget
          title="Network Traffic"
          subtitle="Total I/O across clients"
          metric-key="networkTraffic"
          unit=" MB/s"
          :data="networkMetricsData"
          :show-thresholds="false"
          @refresh="refreshMetrics"
        />
      </div>

      <!-- Charts Section -->
      <div class="charts-grid">
        <div class="chart-card">
          <div class="chart-header">
            <h3 class="chart-title">System Performance Overview</h3>
            <div class="chart-controls">
              <select v-model="selectedTimeRange" class="time-range-select">
                <option value="1h">Last Hour</option>
                <option value="6h">Last 6 Hours</option>
                <option value="24h">Last 24 Hours</option>
                <option value="7d">Last 7 Days</option>
              </select>
            </div>
          </div>
          <div class="chart-container">
            <LineChart
              :datasets="performanceChartData"
              height="300px"
              :show-legend="true"
              :animate="true"
            />
          </div>
        </div>

        <ClientStatusWidget
          :data="clientStatusData"
          :recent-changes="recentStatusChanges"
          @refresh="refreshData"
        />
      </div>

      <!-- Recent Activity -->
      <div class="activity-section">
        <div class="activity-header">
          <h3 class="activity-title">Recent Activity</h3>
          <router-link to="/clients" class="btn btn-secondary btn-sm">
            View All Clients
          </router-link>
        </div>
        
        <div class="activity-list">
          <div v-for="activity in recentActivity" :key="activity.id" class="activity-item">
            <div class="activity-icon" :class="activity.type">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"></circle>
                <polyline points="12,6 12,12 16,14"></polyline>
              </svg>
            </div>
            <div class="activity-content">
              <div class="activity-message">{{ activity.message }}</div>
              <div class="activity-time">{{ formatTime(activity.timestamp) }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useClientsStore } from '@/stores/clients'
import { useWebSocketStore } from '@/stores/websocket'
import { formatDistanceToNow } from 'date-fns'
import RealtimeMetricsWidget from '@/components/dashboard/RealtimeMetricsWidget.vue'
import ClientStatusWidget from '@/components/dashboard/ClientStatusWidget.vue'
import LineChart from '@/components/charts/LineChart.vue'

const clientsStore = useClientsStore()
const webSocketStore = useWebSocketStore()

const selectedTimeRange = ref('24h')

// Mock data - in real app this would come from stores/API
const avgCpuUsage = ref(45.2)
const avgMemoryUsage = ref(67.8)
const activeAlerts = ref(3)
const criticalAlerts = ref(1)
const warningAlerts = ref(2)

// Real-time metrics data
const cpuMetricsData = ref<Array<{ timestamp: Date; value: number }>>([])
const memoryMetricsData = ref<Array<{ timestamp: Date; value: number }>>([])
const networkMetricsData = ref<Array<{ timestamp: Date; value: number }>>([])

// Client status data
const clientStatusData = ref({
  active: 0,
  inactive: 0,
  maintenance: 0,
  total: 0
})

const recentStatusChanges = ref<Array<{
  id: string
  clientId: string
  clientName: string
  previousStatus: string
  newStatus: string
  timestamp: Date
}>>([])

const recentActivity = ref([
  {
    id: '1',
    type: 'info',
    message: 'Client server-01 connected',
    timestamp: new Date(Date.now() - 5 * 60 * 1000).toISOString()
  },
  {
    id: '2',
    type: 'warning',
    message: 'High CPU usage detected on server-03',
    timestamp: new Date(Date.now() - 15 * 60 * 1000).toISOString()
  },
  {
    id: '3',
    type: 'error',
    message: 'Client server-05 disconnected',
    timestamp: new Date(Date.now() - 30 * 60 * 1000).toISOString()
  }
])

// Performance chart data
const performanceChartData = computed(() => {
  const now = new Date()
  const dataPoints = 20
  
  return [
    {
      label: 'CPU Usage (%)',
      data: Array.from({ length: dataPoints }, (_, i) => ({
        x: new Date(now.getTime() - (dataPoints - i) * 5 * 60 * 1000),
        y: Math.random() * 30 + 40 // Random data between 40-70%
      })),
      borderColor: 'rgb(59, 130, 246)',
      backgroundColor: 'rgba(59, 130, 246, 0.1)'
    },
    {
      label: 'Memory Usage (%)',
      data: Array.from({ length: dataPoints }, (_, i) => ({
        x: new Date(now.getTime() - (dataPoints - i) * 5 * 60 * 1000),
        y: Math.random() * 25 + 60 // Random data between 60-85%
      })),
      borderColor: 'rgb(16, 185, 129)',
      backgroundColor: 'rgba(16, 185, 129, 0.1)'
    }
  ]
})

const clientsCount = computed(() => clientsStore.clientsCount)

const cpuTrendClass = computed(() => {
  if (avgCpuUsage.value > 80) return 'trend-up error'
  if (avgCpuUsage.value > 60) return 'trend-up warning'
  return 'trend-stable success'
})

const cpuTrendText = computed(() => {
  if (avgCpuUsage.value > 80) return '↑ High'
  if (avgCpuUsage.value > 60) return '↑ Moderate'
  return '→ Normal'
})

const memoryTrendClass = computed(() => {
  if (avgMemoryUsage.value > 85) return 'trend-up error'
  if (avgMemoryUsage.value > 70) return 'trend-up warning'
  return 'trend-stable success'
})

const memoryTrendText = computed(() => {
  if (avgMemoryUsage.value > 85) return '↑ High'
  if (avgMemoryUsage.value > 70) return '↑ Moderate'
  return '→ Normal'
})

const formatTime = (timestamp: string): string => {
  return formatDistanceToNow(new Date(timestamp), { addSuffix: true })
}

const refreshData = async () => {
  try {
    await clientsStore.fetchClients()
    updateClientStatusData()
    // Refresh other data sources
  } catch (error) {
    console.error('Failed to refresh data:', error)
  }
}

const refreshMetrics = () => {
  generateMockMetricsData()
}

const updateClientStatusData = () => {
  const counts = clientsStore.clientsCount
  clientStatusData.value = {
    active: counts.active,
    inactive: counts.inactive,
    maintenance: counts.maintenance,
    total: counts.total
  }
}

const generateMockMetricsData = () => {
  const now = new Date()
  const dataPoints = 50
  
  // Generate CPU metrics
  cpuMetricsData.value = Array.from({ length: dataPoints }, (_, i) => ({
    timestamp: new Date(now.getTime() - (dataPoints - i) * 30 * 1000), // 30 second intervals
    value: Math.random() * 30 + 40 + Math.sin(i * 0.1) * 10 // Oscillating between 30-80%
  }))
  
  // Generate Memory metrics
  memoryMetricsData.value = Array.from({ length: dataPoints }, (_, i) => ({
    timestamp: new Date(now.getTime() - (dataPoints - i) * 30 * 1000),
    value: Math.random() * 20 + 60 + Math.cos(i * 0.15) * 8 // Oscillating between 52-88%
  }))
  
  // Generate Network metrics
  networkMetricsData.value = Array.from({ length: dataPoints }, (_, i) => ({
    timestamp: new Date(now.getTime() - (dataPoints - i) * 30 * 1000),
    value: Math.random() * 50 + 10 + Math.sin(i * 0.2) * 15 // Oscillating network traffic
  }))
  
  // Update average values for summary cards
  avgCpuUsage.value = cpuMetricsData.value[cpuMetricsData.value.length - 1]?.value || 0
  avgMemoryUsage.value = memoryMetricsData.value[memoryMetricsData.value.length - 1]?.value || 0
}

// Simulate real-time updates
let metricsUpdateInterval: number | null = null

const startRealtimeUpdates = () => {
  metricsUpdateInterval = window.setInterval(() => {
    const now = new Date()
    
    // Add new data point to each metric
    const newCpuPoint = {
      timestamp: now,
      value: Math.random() * 30 + 40 + Math.sin(Date.now() * 0.001) * 10
    }
    
    const newMemoryPoint = {
      timestamp: now,
      value: Math.random() * 20 + 60 + Math.cos(Date.now() * 0.0015) * 8
    }
    
    const newNetworkPoint = {
      timestamp: now,
      value: Math.random() * 50 + 10 + Math.sin(Date.now() * 0.002) * 15
    }
    
    // Add new points and remove old ones to maintain data size
    cpuMetricsData.value.push(newCpuPoint)
    memoryMetricsData.value.push(newMemoryPoint)
    networkMetricsData.value.push(newNetworkPoint)
    
    if (cpuMetricsData.value.length > 50) {
      cpuMetricsData.value.shift()
      memoryMetricsData.value.shift()
      networkMetricsData.value.shift()
    }
    
    // Update summary values
    avgCpuUsage.value = newCpuPoint.value
    avgMemoryUsage.value = newMemoryPoint.value
  }, 5000) // Update every 5 seconds for <5 second latency requirement
}

const stopRealtimeUpdates = () => {
  if (metricsUpdateInterval) {
    clearInterval(metricsUpdateInterval)
    metricsUpdateInterval = null
  }
}

onMounted(async () => {
  await refreshData()
  generateMockMetricsData()
  startRealtimeUpdates()
  
  // Connect to WebSocket for real-time updates
  if (!webSocketStore.isConnected) {
    webSocketStore.connect()
  }
})

onUnmounted(() => {
  stopRealtimeUpdates()
})
</script>

<style scoped>
.dashboard {
  padding: 0;
}

.dashboard-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--border-color-light);
}

.dashboard-title {
  font-size: 2rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.dashboard-actions {
  display: flex;
  gap: 1rem;
}

.dashboard-content {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 1.5rem;
}

.summary-card {
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 1.5rem;
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease;
}

.summary-card:hover {
  box-shadow: var(--shadow-md);
}

.summary-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.summary-title {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-secondary);
  margin: 0;
}

.summary-icon {
  padding: 0.5rem;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
}

.clients-icon {
  background-color: rgba(59, 130, 246, 0.1);
  color: var(--color-primary);
}

.cpu-icon {
  background-color: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

.memory-icon {
  background-color: rgba(245, 158, 11, 0.1);
  color: var(--color-warning);
}

.alerts-icon {
  background-color: rgba(239, 68, 68, 0.1);
  color: var(--color-error);
}

.summary-value {
  font-size: 2rem;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.summary-details {
  display: flex;
  gap: 1rem;
  font-size: 0.75rem;
}

.detail-item {
  font-weight: 500;
}

.detail-item.success {
  color: var(--color-success);
}

.detail-item.warning {
  color: var(--color-warning);
}

.detail-item.error {
  color: var(--color-error);
}

.summary-trend {
  font-size: 0.75rem;
  font-weight: 500;
}

.trend-up.error {
  color: var(--color-error);
}

.trend-up.warning {
  color: var(--color-warning);
}

.trend-stable.success {
  color: var(--color-success);
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.charts-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 1.5rem;
}

.chart-card {
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 1.5rem;
  box-shadow: var(--shadow-sm);
}

.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.chart-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.time-range-select {
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background-color: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.75rem;
}

.chart-container {
  height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--bg-secondary);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
}

.activity-section {
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 1.5rem;
  box-shadow: var(--shadow-sm);
}

.activity-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.activity-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.activity-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.activity-item {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 0.75rem;
  border-radius: var(--radius-md);
  background-color: var(--bg-secondary);
}

.activity-icon {
  flex-shrink: 0;
  width: 2rem;
  height: 2rem;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.activity-icon.info {
  background-color: rgba(6, 182, 212, 0.1);
  color: var(--color-info);
}

.activity-icon.warning {
  background-color: rgba(245, 158, 11, 0.1);
  color: var(--color-warning);
}

.activity-icon.error {
  background-color: rgba(239, 68, 68, 0.1);
  color: var(--color-error);
}

.activity-content {
  flex: 1;
  min-width: 0;
}

.activity-message {
  font-size: 0.875rem;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
}

.activity-time {
  font-size: 0.75rem;
  color: var(--text-tertiary);
}

@media (max-width: 1024px) {
  .metrics-grid {
    grid-template-columns: 1fr;
  }
  
  .charts-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .dashboard-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 1rem;
  }

  .summary-grid {
    grid-template-columns: 1fr;
  }

  .activity-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 1rem;
  }
}
</style>