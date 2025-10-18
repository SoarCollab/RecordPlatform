<template>
  <div class="clients-view">
    <div class="clients-header">
      <h1 class="clients-title">Clients</h1>
      <div class="clients-actions">
        <button class="btn btn-secondary" @click="refreshClients">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23,4 23,10 17,10"></polyline>
            <polyline points="1,20 1,14 7,14"></polyline>
            <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15"></path>
          </svg>
          Refresh
        </button>
      </div>
    </div>

    <!-- Advanced Search and Filters -->
    <div class="search-section">
      <AdvancedSearch
        placeholder="Search clients by name, hostname, IP address..."
        :filter-config="filterConfig"
        :suggestions="searchSuggestions"
        @search="handleSearch"
        @filter-change="handleFilterChange"
      />
    </div>

    <!-- Quick Stats -->
    <div class="stats-section">
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-icon active">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"></circle>
              <polyline points="12,6 12,12 16,14"></polyline>
            </svg>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ clientsCount.active }}</div>
            <div class="stat-label">Active</div>
          </div>
        </div>
        
        <div class="stat-card">
          <div class="stat-icon inactive">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"></circle>
              <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"></line>
            </svg>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ clientsCount.inactive }}</div>
            <div class="stat-label">Inactive</div>
          </div>
        </div>
        
        <div class="stat-card">
          <div class="stat-icon maintenance">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"></path>
            </svg>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ clientsCount.maintenance }}</div>
            <div class="stat-label">Maintenance</div>
          </div>
        </div>
        
        <div class="stat-card">
          <div class="stat-icon total">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
              <line x1="8" y1="21" x2="16" y2="21"></line>
              <line x1="12" y1="17" x2="12" y2="21"></line>
            </svg>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ clientsCount.total }}</div>
            <div class="stat-label">Total</div>
          </div>
        </div>
      </div>
    </div>

    <!-- View Controls -->
    <div class="view-controls">
      <div class="view-options">
        <button
          class="view-toggle"
          :class="{ active: viewMode === 'grid' }"
          @click="viewMode = 'grid'"
          title="Grid view"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="3" width="7" height="7"></rect>
            <rect x="14" y="3" width="7" height="7"></rect>
            <rect x="14" y="14" width="7" height="7"></rect>
            <rect x="3" y="14" width="7" height="7"></rect>
          </svg>
        </button>
        <button
          class="view-toggle"
          :class="{ active: viewMode === 'list' }"
          @click="viewMode = 'list'"
          title="List view"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="8" y1="6" x2="21" y2="6"></line>
            <line x1="8" y1="12" x2="21" y2="12"></line>
            <line x1="8" y1="18" x2="21" y2="18"></line>
            <line x1="3" y1="6" x2="3.01" y2="6"></line>
            <line x1="3" y1="12" x2="3.01" y2="12"></line>
            <line x1="3" y1="18" x2="3.01" y2="18"></line>
          </svg>
        </button>
      </div>
      
      <div class="sort-controls">
        <select v-model="sortBy" class="sort-select">
          <option value="name">Sort by Name</option>
          <option value="status">Sort by Status</option>
          <option value="lastHeartbeat">Sort by Last Seen</option>
          <option value="environment">Sort by Environment</option>
          <option value="region">Sort by Region</option>
        </select>
        <button
          class="sort-direction"
          @click="sortDirection = sortDirection === 'asc' ? 'desc' : 'asc'"
          :title="`Sort ${sortDirection === 'asc' ? 'descending' : 'ascending'}`"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path v-if="sortDirection === 'asc'" d="M3 16l4 4 4-4M7 20V4"></path>
            <path v-else d="M3 8l4-4 4 4M7 4v16"></path>
          </svg>
        </button>
      </div>
      
      <div class="results-info">
        Showing {{ filteredAndSortedClients.length }} of {{ clientsCount.total }} clients
      </div>
    </div>

    <!-- Clients Grid -->
    <div v-if="isLoading" class="loading-state">
      <div class="loading-spinner">Loading clients...</div>
    </div>

    <div v-else-if="filteredClients.length === 0" class="empty-state">
      <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
        <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
        <line x1="8" y1="21" x2="16" y2="21"></line>
        <line x1="12" y1="17" x2="12" y2="21"></line>
      </svg>
      <h3>No clients found</h3>
      <p>No clients match your current filters.</p>
    </div>

    <!-- Grid View -->
    <div v-else-if="viewMode === 'grid'" class="clients-grid">
      <div
        v-for="client in filteredAndSortedClients"
        :key="client.id"
        class="client-card"
        @click="navigateToClient(client.id)"
      >
        <div class="client-header">
          <div class="client-info">
            <h3 class="client-name">{{ client.name }}</h3>
            <p class="client-hostname">{{ client.hostname }}</p>
            <p class="client-ip">{{ client.ipAddress }}</p>
          </div>
          <div class="client-status" :class="client.status">
            <span class="status-dot"></span>
            <span class="status-text">{{ client.status }}</span>
          </div>
        </div>

        <div class="client-metrics">
          <div class="metric-item">
            <span class="metric-label">CPU</span>
            <div class="metric-value-container">
              <span class="metric-value">{{ getClientMetric(client.id, 'cpuUsage') }}%</span>
              <div class="metric-bar">
                <div 
                  class="metric-fill" 
                  :style="{ width: `${getClientMetric(client.id, 'cpuUsage')}%` }"
                ></div>
              </div>
            </div>
          </div>
          <div class="metric-item">
            <span class="metric-label">Memory</span>
            <div class="metric-value-container">
              <span class="metric-value">{{ getClientMetric(client.id, 'memoryUsage') }}%</span>
              <div class="metric-bar">
                <div 
                  class="metric-fill" 
                  :style="{ width: `${getClientMetric(client.id, 'memoryUsage')}%` }"
                ></div>
              </div>
            </div>
          </div>
          <div class="metric-item">
            <span class="metric-label">Disk</span>
            <div class="metric-value-container">
              <span class="metric-value">{{ getClientMetric(client.id, 'diskUsage') }}%</span>
              <div class="metric-bar">
                <div 
                  class="metric-fill" 
                  :style="{ width: `${getClientMetric(client.id, 'diskUsage')}%` }"
                ></div>
              </div>
            </div>
          </div>
        </div>

        <div class="client-footer">
          <span class="client-region">{{ client.region }}</span>
          <span class="client-environment">{{ client.environment }}</span>
          <span class="client-heartbeat">{{ formatHeartbeat(client.lastHeartbeat) }}</span>
        </div>
      </div>
    </div>

    <!-- List View -->
    <div v-else class="clients-list">
      <div class="list-header">
        <div class="list-column">Client</div>
        <div class="list-column">Status</div>
        <div class="list-column">Environment</div>
        <div class="list-column">CPU</div>
        <div class="list-column">Memory</div>
        <div class="list-column">Disk</div>
        <div class="list-column">Last Seen</div>
        <div class="list-column">Actions</div>
      </div>
      
      <div
        v-for="client in filteredAndSortedClients"
        :key="client.id"
        class="list-row"
        @click="navigateToClient(client.id)"
      >
        <div class="list-column client-column">
          <div class="client-info">
            <div class="client-name">{{ client.name }}</div>
            <div class="client-details">
              <span class="client-hostname">{{ client.hostname }}</span>
              <span class="client-ip">{{ client.ipAddress }}</span>
            </div>
          </div>
        </div>
        
        <div class="list-column">
          <div class="client-status" :class="client.status">
            <span class="status-dot"></span>
            <span class="status-text">{{ client.status }}</span>
          </div>
        </div>
        
        <div class="list-column">
          <span class="client-environment">{{ client.environment }}</span>
          <span class="client-region">{{ client.region }}</span>
        </div>
        
        <div class="list-column">
          <div class="metric-display">
            <span class="metric-value">{{ getClientMetric(client.id, 'cpuUsage') }}%</span>
            <div class="metric-bar-small">
              <div 
                class="metric-fill" 
                :style="{ width: `${getClientMetric(client.id, 'cpuUsage')}%` }"
              ></div>
            </div>
          </div>
        </div>
        
        <div class="list-column">
          <div class="metric-display">
            <span class="metric-value">{{ getClientMetric(client.id, 'memoryUsage') }}%</span>
            <div class="metric-bar-small">
              <div 
                class="metric-fill" 
                :style="{ width: `${getClientMetric(client.id, 'memoryUsage')}%` }"
              ></div>
            </div>
          </div>
        </div>
        
        <div class="list-column">
          <div class="metric-display">
            <span class="metric-value">{{ getClientMetric(client.id, 'diskUsage') }}%</span>
            <div class="metric-bar-small">
              <div 
                class="metric-fill" 
                :style="{ width: `${getClientMetric(client.id, 'diskUsage')}%` }"
              ></div>
            </div>
          </div>
        </div>
        
        <div class="list-column">
          <span class="client-heartbeat">{{ formatHeartbeat(client.lastHeartbeat) }}</span>
        </div>
        
        <div class="list-column actions-column">
          <button 
            class="action-btn"
            @click.stop="navigateToClient(client.id)"
            title="View details"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
              <circle cx="12" cy="12" r="3"></circle>
            </svg>
          </button>
          <button 
            class="action-btn"
            @click.stop="toggleClientMaintenance(client)"
            :title="client.status === 'maintenance' ? 'Exit maintenance' : 'Enter maintenance'"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"></path>
            </svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useClientsStore } from '@/stores/clients'
import { useWebSocketStore } from '@/stores/websocket'
import { formatDistanceToNow } from 'date-fns'
import AdvancedSearch from '@/components/common/AdvancedSearch.vue'
import type { Client } from '@/types/client'

const router = useRouter()
const route = useRoute()
const clientsStore = useClientsStore()
const webSocketStore = useWebSocketStore()

// View state
const viewMode = ref<'grid' | 'list'>('grid')
const sortBy = ref('name')
const sortDirection = ref<'asc' | 'desc'>('asc')
const searchQuery = ref('')
const advancedFilters = ref<Record<string, any>>({})

// Computed properties
const filter = computed(() => clientsStore.filter)
const filteredClients = computed(() => clientsStore.filteredClients)
const isLoading = computed(() => clientsStore.isLoading)
const clientsCount = computed(() => clientsStore.clientsCount)

// Filter configuration for advanced search
const filterConfig = [
  {
    key: 'status',
    label: 'Status',
    type: 'multiselect' as const,
    options: [
      { label: 'Active', value: 'active' },
      { label: 'Inactive', value: 'inactive' },
      { label: 'Maintenance', value: 'maintenance' }
    ],
    placeholder: 'Select status...'
  },
  {
    key: 'environment',
    label: 'Environment',
    type: 'multiselect' as const,
    options: [
      { label: 'Production', value: 'prod' },
      { label: 'Staging', value: 'staging' },
      { label: 'Development', value: 'dev' }
    ],
    placeholder: 'Select environment...'
  },
  {
    key: 'region',
    label: 'Region',
    type: 'select' as const,
    options: [
      { label: 'US East 1', value: 'us-east-1' },
      { label: 'US West 2', value: 'us-west-2' },
      { label: 'EU West 1', value: 'eu-west-1' },
      { label: 'AP Southeast 1', value: 'ap-southeast-1' }
    ],
    placeholder: 'All regions'
  },
  {
    key: 'cpuUsage',
    label: 'CPU Usage (%)',
    type: 'range' as const,
    minPlaceholder: 'Min %',
    maxPlaceholder: 'Max %'
  },
  {
    key: 'memoryUsage',
    label: 'Memory Usage (%)',
    type: 'range' as const,
    minPlaceholder: 'Min %',
    maxPlaceholder: 'Max %'
  },
  {
    key: 'lastSeen',
    label: 'Last Seen',
    type: 'daterange' as const
  }
]

// Search suggestions
const searchSuggestions = computed(() => {
  const suggestions = []
  
  // Add client names and hostnames as suggestions
  filteredClients.value.forEach(client => {
    suggestions.push(
      { text: client.name, category: 'Client Name' },
      { text: client.hostname, category: 'Hostname' },
      { text: client.ipAddress, category: 'IP Address' }
    )
  })
  
  // Add common search terms
  suggestions.push(
    { text: 'status:active', category: 'Filter' },
    { text: 'environment:prod', category: 'Filter' },
    { text: 'region:us-east-1', category: 'Filter' }
  )
  
  return suggestions.slice(0, 10) // Limit to 10 suggestions
})

// Filtered and sorted clients
const filteredAndSortedClients = computed(() => {
  let clients = [...filteredClients.value]
  
  // Apply advanced search query
  if (searchQuery.value.trim()) {
    const query = searchQuery.value.toLowerCase()
    clients = clients.filter(client =>
      client.name.toLowerCase().includes(query) ||
      client.hostname.toLowerCase().includes(query) ||
      client.ipAddress.toLowerCase().includes(query) ||
      client.region.toLowerCase().includes(query)
    )
  }
  
  // Apply advanced filters
  if (advancedFilters.value.status && advancedFilters.value.status.length > 0) {
    clients = clients.filter(client => advancedFilters.value.status.includes(client.status))
  }
  
  if (advancedFilters.value.environment && advancedFilters.value.environment.length > 0) {
    clients = clients.filter(client => advancedFilters.value.environment.includes(client.environment))
  }
  
  if (advancedFilters.value.region) {
    clients = clients.filter(client => client.region === advancedFilters.value.region)
  }
  
  // Apply CPU usage filter
  if (advancedFilters.value.cpuUsage?.min || advancedFilters.value.cpuUsage?.max) {
    clients = clients.filter(client => {
      const cpuUsage = parseFloat(getClientMetric(client.id, 'cpuUsage'))
      const min = advancedFilters.value.cpuUsage?.min || 0
      const max = advancedFilters.value.cpuUsage?.max || 100
      return cpuUsage >= min && cpuUsage <= max
    })
  }
  
  // Apply memory usage filter
  if (advancedFilters.value.memoryUsage?.min || advancedFilters.value.memoryUsage?.max) {
    clients = clients.filter(client => {
      const memoryUsage = parseFloat(getClientMetric(client.id, 'memoryUsage'))
      const min = advancedFilters.value.memoryUsage?.min || 0
      const max = advancedFilters.value.memoryUsage?.max || 100
      return memoryUsage >= min && memoryUsage <= max
    })
  }
  
  // Apply date range filter
  if (advancedFilters.value.lastSeen?.start || advancedFilters.value.lastSeen?.end) {
    clients = clients.filter(client => {
      const lastSeen = new Date(client.lastHeartbeat)
      const start = advancedFilters.value.lastSeen?.start ? new Date(advancedFilters.value.lastSeen.start) : null
      const end = advancedFilters.value.lastSeen?.end ? new Date(advancedFilters.value.lastSeen.end) : null
      
      if (start && lastSeen < start) return false
      if (end && lastSeen > end) return false
      return true
    })
  }
  
  // Sort clients
  clients.sort((a, b) => {
    let aValue: any, bValue: any
    
    switch (sortBy.value) {
      case 'name':
        aValue = a.name.toLowerCase()
        bValue = b.name.toLowerCase()
        break
      case 'status':
        aValue = a.status
        bValue = b.status
        break
      case 'lastHeartbeat':
        aValue = new Date(a.lastHeartbeat)
        bValue = new Date(b.lastHeartbeat)
        break
      case 'environment':
        aValue = a.environment
        bValue = b.environment
        break
      case 'region':
        aValue = a.region
        bValue = b.region
        break
      default:
        aValue = a.name.toLowerCase()
        bValue = b.name.toLowerCase()
    }
    
    if (aValue < bValue) return sortDirection.value === 'asc' ? -1 : 1
    if (aValue > bValue) return sortDirection.value === 'asc' ? 1 : -1
    return 0
  })
  
  return clients
})

// Methods
const refreshClients = async () => {
  try {
    await clientsStore.fetchClients()
  } catch (error) {
    console.error('Failed to refresh clients:', error)
  }
}

const navigateToClient = (clientId: string) => {
  router.push({ name: 'client-detail', params: { id: clientId } })
}

const getClientMetric = (clientId: string, metric: string): string => {
  const metrics = webSocketStore.getClientMetrics(clientId)
  if (metrics && metrics[metric as keyof typeof metrics] !== undefined) {
    const value = metrics[metric as keyof typeof metrics]
    return typeof value === 'number' ? value.toFixed(1) : value.toString()
  }
  return '0'
}

const formatHeartbeat = (timestamp: string): string => {
  return formatDistanceToNow(new Date(timestamp), { addSuffix: true })
}

const handleSearch = (query: string, filters: Record<string, any>) => {
  searchQuery.value = query
  advancedFilters.value = filters
}

const handleFilterChange = (filters: Record<string, any>) => {
  advancedFilters.value = filters
}

const toggleClientMaintenance = async (client: Client) => {
  try {
    const newStatus = client.status === 'maintenance' ? 'active' : 'maintenance'
    await clientsStore.updateClient(client.id, { status: newStatus })
  } catch (error) {
    console.error('Failed to update client status:', error)
  }
}

// Watch for route query changes to update filters
watch(
  () => route.query,
  (newQuery) => {
    if (newQuery.search) {
      searchQuery.value = newQuery.search as string
    }
  },
  { immediate: true }
)

// Save view preferences
watch(viewMode, (newMode) => {
  localStorage.setItem('clients_view_mode', newMode)
})

onMounted(async () => {
  // Restore view preferences
  const savedViewMode = localStorage.getItem('clients_view_mode')
  if (savedViewMode === 'list' || savedViewMode === 'grid') {
    viewMode.value = savedViewMode
  }
  
  await refreshClients()
  
  // Subscribe to real-time updates for all active clients
  filteredClients.value.forEach(client => {
    if (client.status === 'active') {
      webSocketStore.subscribeToClient(client.clientId)
    }
  })
})
</script>

<style scoped>
.clients-view {
  padding: 0;
}

.clients-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--border-color-light);
}

.clients-title {
  font-size: 2rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.search-section {
  margin-bottom: 2rem;
}

.stats-section {
  margin-bottom: 2rem;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease;
}

.stat-card:hover {
  box-shadow: var(--shadow-md);
}

.stat-icon {
  width: 3rem;
  height: 3rem;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-icon.active {
  background-color: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

.stat-icon.inactive {
  background-color: rgba(107, 114, 128, 0.1);
  color: var(--text-secondary);
}

.stat-icon.maintenance {
  background-color: rgba(245, 158, 11, 0.1);
  color: var(--color-warning);
}

.stat-icon.total {
  background-color: rgba(59, 130, 246, 0.1);
  color: var(--color-primary);
}

.stat-content {
  flex: 1;
  min-width: 0;
}

.stat-value {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1;
}

.stat-label {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin-top: 0.25rem;
}

.view-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1.5rem;
  padding: 1rem;
  background-color: var(--bg-secondary);
  border-radius: var(--radius-lg);
  gap: 1rem;
}

.view-options {
  display: flex;
  gap: 0.25rem;
  background-color: var(--bg-primary);
  border-radius: var(--radius-md);
  padding: 0.25rem;
}

.view-toggle {
  background: none;
  border: none;
  padding: 0.5rem;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.view-toggle:hover {
  color: var(--text-primary);
  background-color: var(--bg-secondary);
}

.view-toggle.active {
  color: var(--color-primary);
  background-color: var(--bg-secondary);
}

.sort-controls {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.sort-select {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background-color: var(--bg-primary);
  color: var(--text-primary);
  font-size: 0.875rem;
}

.sort-direction {
  background: none;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  padding: 0.5rem;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.sort-direction:hover {
  color: var(--text-primary);
  border-color: var(--color-primary);
}

.results-info {
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.loading-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 2rem;
  text-align: center;
  color: var(--text-secondary);
}

.loading-spinner {
  font-size: 1.125rem;
}

.empty-state svg {
  margin-bottom: 1rem;
  color: var(--text-tertiary);
}

.empty-state h3 {
  margin-bottom: 0.5rem;
  color: var(--text-primary);
}

.clients-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
  gap: 1.5rem;
}

.client-card {
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 1.5rem;
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  transition: all 0.2s ease;
}

.client-card:hover {
  box-shadow: var(--shadow-md);
  border-color: var(--color-primary);
}

.client-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.client-info {
  flex: 1;
  min-width: 0;
}

.client-name {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 0.25rem 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.client-hostname {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.client-ip {
  font-size: 0.75rem;
  color: var(--text-tertiary);
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.client-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0.75rem;
  border-radius: var(--radius-lg);
  font-size: 0.75rem;
  font-weight: 500;
  white-space: nowrap;
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
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: currentColor;
}

.client-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
  margin-bottom: 1rem;
  padding: 1rem;
  background-color: var(--bg-secondary);
  border-radius: var(--radius-md);
}

.metric-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.metric-label {
  font-size: 0.75rem;
  color: var(--text-tertiary);
  margin-bottom: 0.25rem;
}

.metric-value {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text-primary);
}

.metric-value-container {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  width: 100%;
}

.metric-bar {
  width: 100%;
  height: 4px;
  background-color: var(--bg-tertiary);
  border-radius: 2px;
  overflow: hidden;
}

.metric-fill {
  height: 100%;
  background-color: var(--color-primary);
  border-radius: 2px;
  transition: width 0.3s ease;
}

.metric-bar-small {
  width: 60px;
  height: 3px;
  background-color: var(--bg-tertiary);
  border-radius: 2px;
  overflow: hidden;
}

.metric-display {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

/* List View Styles */
.clients-list {
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.list-header {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr 1fr 1fr 1.5fr 100px;
  gap: 1rem;
  padding: 1rem;
  background-color: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.list-row {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr 1fr 1fr 1.5fr 100px;
  gap: 1rem;
  padding: 1rem;
  border-bottom: 1px solid var(--border-color-light);
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.list-row:hover {
  background-color: var(--bg-secondary);
}

.list-row:last-child {
  border-bottom: none;
}

.list-column {
  display: flex;
  align-items: center;
  font-size: 0.875rem;
  min-width: 0;
}

.client-column .client-info {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  min-width: 0;
}

.client-column .client-name {
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.client-column .client-details {
  display: flex;
  gap: 0.5rem;
  font-size: 0.75rem;
  color: var(--text-tertiary);
}

.client-column .client-hostname,
.client-column .client-ip {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.actions-column {
  display: flex;
  gap: 0.25rem;
  justify-content: flex-end;
}

.action-btn {
  background: none;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  padding: 0.25rem;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.action-btn:hover {
  color: var(--text-primary);
  border-color: var(--color-primary);
  background-color: var(--bg-secondary);
}

.client-footer {
  display: flex;
  align-items: center;
  gap: 1rem;
  font-size: 0.75rem;
  color: var(--text-tertiary);
}

.client-region,
.client-environment {
  padding: 0.125rem 0.5rem;
  background-color: var(--bg-tertiary);
  border-radius: var(--radius-sm);
}

.client-heartbeat {
  margin-left: auto;
}

@media (max-width: 1024px) {
  .list-header,
  .list-row {
    grid-template-columns: 2fr 1fr 1fr 80px;
  }
  
  .list-column:nth-child(4),
  .list-column:nth-child(5),
  .list-column:nth-child(6),
  .list-column:nth-child(7) {
    display: none;
  }
}

@media (max-width: 768px) {
  .clients-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 1rem;
  }

  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .view-controls {
    flex-direction: column;
    align-items: stretch;
    gap: 1rem;
  }

  .view-controls > * {
    justify-content: center;
  }

  .clients-grid {
    grid-template-columns: 1fr;
  }

  .client-metrics {
    grid-template-columns: repeat(3, 1fr);
    gap: 0.5rem;
  }

  .client-footer {
    flex-wrap: wrap;
    gap: 0.5rem;
  }
  
  .list-header,
  .list-row {
    grid-template-columns: 1fr 80px 60px;
    gap: 0.5rem;
  }
  
  .list-column:nth-child(2),
  .list-column:nth-child(3),
  .list-column:nth-child(4),
  .list-column:nth-child(5),
  .list-column:nth-child(6),
  .list-column:nth-child(7) {
    display: none;
  }
  
  .actions-column {
    grid-column: 3;
  }
}

@media (max-width: 480px) {
  .stats-grid {
    grid-template-columns: 1fr;
  }
  
  .stat-card {
    padding: 0.75rem;
  }
  
  .stat-icon {
    width: 2.5rem;
    height: 2.5rem;
  }
}
</style>