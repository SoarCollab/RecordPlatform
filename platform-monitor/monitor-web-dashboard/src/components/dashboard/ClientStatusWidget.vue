<template>
  <MetricWidget
    title="Client Status Distribution"
    :is-loading="isLoading"
    :error="error"
    :last-updated="lastUpdated"
    @refresh="handleRefresh"
  >
    <div class="client-status-widget">
      <div class="status-summary">
        <div class="summary-item" v-for="status in statusSummary" :key="status.label">
          <div class="summary-icon" :class="status.class">
            <div class="status-dot"></div>
          </div>
          <div class="summary-content">
            <span class="summary-value">{{ status.count }}</span>
            <span class="summary-label">{{ status.label }}</span>
          </div>
        </div>
      </div>

      <div class="chart-section">
        <DoughnutChart
          :data="chartData"
          height="200px"
          :show-legend="true"
          :animate="true"
        />
      </div>

      <div class="recent-changes" v-if="recentChanges.length > 0">
        <h4 class="changes-title">Recent Status Changes</h4>
        <div class="changes-list">
          <div 
            v-for="change in recentChanges.slice(0, 3)" 
            :key="change.id"
            class="change-item"
          >
            <div class="change-icon" :class="getChangeIconClass(change.newStatus)">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"></circle>
                <polyline points="12,6 12,12 16,14"></polyline>
              </svg>
            </div>
            <div class="change-content">
              <span class="change-client">{{ change.clientName }}</span>
              <span class="change-status">{{ change.previousStatus }} → {{ change.newStatus }}</span>
              <span class="change-time">{{ formatTime(change.timestamp) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </MetricWidget>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { formatDistanceToNow } from 'date-fns'
import MetricWidget from './MetricWidget.vue'
import DoughnutChart from '@/components/charts/DoughnutChart.vue'

interface ClientStatusData {
  active: number
  inactive: number
  maintenance: number
  total: number
}

interface StatusChange {
  id: string
  clientId: string
  clientName: string
  previousStatus: string
  newStatus: string
  timestamp: Date
}

interface Props {
  data: ClientStatusData
  recentChanges?: StatusChange[]
  isLoading?: boolean
  error?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  recentChanges: () => [],
  isLoading: false,
  error: null
})

const emit = defineEmits<{
  refresh: []
}>()

const lastUpdated = ref<Date>(new Date())

const statusSummary = computed(() => [
  {
    label: 'Active',
    count: props.data.active,
    class: 'status-active'
  },
  {
    label: 'Inactive',
    count: props.data.inactive,
    class: 'status-inactive'
  },
  {
    label: 'Maintenance',
    count: props.data.maintenance,
    class: 'status-maintenance'
  }
])

const chartData = computed(() => [
  {
    label: 'Active',
    value: props.data.active,
    color: 'rgb(16, 185, 129)' // green
  },
  {
    label: 'Inactive',
    value: props.data.inactive,
    color: 'rgb(107, 114, 128)' // gray
  },
  {
    label: 'Maintenance',
    value: props.data.maintenance,
    color: 'rgb(245, 158, 11)' // yellow
  }
])

const getChangeIconClass = (status: string): string => {
  switch (status) {
    case 'active':
      return 'change-active'
    case 'inactive':
      return 'change-inactive'
    case 'maintenance':
      return 'change-maintenance'
    default:
      return 'change-unknown'
  }
}

const formatTime = (timestamp: Date): string => {
  return formatDistanceToNow(timestamp, { addSuffix: true })
}

const handleRefresh = () => {
  emit('refresh')
  lastUpdated.value = new Date()
}
</script>

<style scoped>
.client-status-widget {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.status-summary {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
}

.summary-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  background-color: var(--bg-secondary);
  border-radius: var(--radius-md);
}

.summary-icon {
  width: 2rem;
  height: 2rem;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.summary-icon.status-active {
  background-color: rgba(16, 185, 129, 0.1);
}

.summary-icon.status-inactive {
  background-color: rgba(107, 114, 128, 0.1);
}

.summary-icon.status-maintenance {
  background-color: rgba(245, 158, 11, 0.1);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-active .status-dot {
  background-color: var(--color-success);
}

.status-inactive .status-dot {
  background-color: var(--text-secondary);
}

.status-maintenance .status-dot {
  background-color: var(--color-warning);
}

.summary-content {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.summary-value {
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1;
}

.summary-label {
  font-size: 0.75rem;
  color: var(--text-secondary);
  font-weight: 500;
}

.chart-section {
  display: flex;
  justify-content: center;
}

.recent-changes {
  border-top: 1px solid var(--border-color-light);
  padding-top: 1rem;
}

.changes-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 0.75rem 0;
}

.changes-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.change-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem;
  background-color: var(--bg-secondary);
  border-radius: var(--radius-sm);
}

.change-icon {
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.change-icon.change-active {
  background-color: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

.change-icon.change-inactive {
  background-color: rgba(107, 114, 128, 0.1);
  color: var(--text-secondary);
}

.change-icon.change-maintenance {
  background-color: rgba(245, 158, 11, 0.1);
  color: var(--color-warning);
}

.change-content {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}

.change-client {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.change-status {
  font-size: 0.6875rem;
  color: var(--text-secondary);
}

.change-time {
  font-size: 0.6875rem;
  color: var(--text-tertiary);
}

@media (max-width: 768px) {
  .status-summary {
    grid-template-columns: 1fr;
  }
  
  .summary-item {
    justify-content: center;
    text-align: center;
  }
}
</style>