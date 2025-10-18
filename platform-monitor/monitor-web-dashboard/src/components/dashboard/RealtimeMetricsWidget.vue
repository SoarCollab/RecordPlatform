<template>
  <MetricWidget
    :title="title"
    :subtitle="subtitle"
    :is-loading="isLoading"
    :error="error"
    :last-updated="lastUpdated"
    @refresh="handleRefresh"
  >
    <div class="realtime-metrics">
      <div class="current-value">
        <span class="value" :class="getValueClass(currentValue)">
          {{ formatValue(currentValue) }}{{ unit }}
        </span>
        <span v-if="trend" class="trend" :class="getTrendClass(trend)">
          {{ getTrendIcon(trend) }} {{ Math.abs(trend).toFixed(1) }}%
        </span>
      </div>
      
      <div class="chart-container">
        <LineChart
          :datasets="chartDatasets"
          :height="chartHeight"
          :show-legend="false"
          :show-grid="showGrid"
          :animate="false"
          :y-axis-min="yAxisMin"
          :y-axis-max="yAxisMax"
          :update-mode="'none'"
        />
      </div>
      
      <div v-if="showThresholds" class="thresholds">
        <div class="threshold-item warning" v-if="warningThreshold">
          <span class="threshold-label">Warning</span>
          <span class="threshold-value">{{ warningThreshold }}{{ unit }}</span>
        </div>
        <div class="threshold-item critical" v-if="criticalThreshold">
          <span class="threshold-label">Critical</span>
          <span class="threshold-value">{{ criticalThreshold }}{{ unit }}</span>
        </div>
      </div>
    </div>
  </MetricWidget>
</template>

<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import MetricWidget from './MetricWidget.vue'
import LineChart from '@/components/charts/LineChart.vue'

interface DataPoint {
  timestamp: Date
  value: number
}

interface Props {
  title: string
  subtitle?: string
  clientId?: string
  metricKey: string
  unit?: string
  data: DataPoint[]
  isLoading?: boolean
  error?: string | null
  maxDataPoints?: number
  chartHeight?: string
  showGrid?: boolean
  showThresholds?: boolean
  warningThreshold?: number
  criticalThreshold?: number
  yAxisMin?: number
  yAxisMax?: number
  refreshInterval?: number
}

const props = withDefaults(defineProps<Props>(), {
  unit: '',
  isLoading: false,
  error: null,
  maxDataPoints: 50,
  chartHeight: '120px',
  showGrid: true,
  showThresholds: true,
  refreshInterval: 5000
})

const emit = defineEmits<{
  refresh: []
}>()

const lastUpdated = ref<Date | null>(null)
const trend = ref<number | null>(null)

const currentValue = computed(() => {
  if (props.data.length === 0) return 0
  return props.data[props.data.length - 1].value
})

const chartDatasets = computed(() => {
  const limitedData = props.data.slice(-props.maxDataPoints)
  
  return [{
    label: props.title,
    data: limitedData.map(point => ({
      x: point.timestamp.getTime(),
      y: point.value
    })),
    borderColor: getLineColor(currentValue.value),
    backgroundColor: getLineColor(currentValue.value) + '20',
    fill: true,
    tension: 0.4
  }]
})

const getValueClass = (value: number): string => {
  if (props.criticalThreshold && value >= props.criticalThreshold) {
    return 'value-critical'
  }
  if (props.warningThreshold && value >= props.warningThreshold) {
    return 'value-warning'
  }
  return 'value-normal'
}

const getLineColor = (value: number): string => {
  if (props.criticalThreshold && value >= props.criticalThreshold) {
    return 'rgb(239, 68, 68)' // red
  }
  if (props.warningThreshold && value >= props.warningThreshold) {
    return 'rgb(245, 158, 11)' // yellow
  }
  return 'rgb(16, 185, 129)' // green
}

const getTrendClass = (trendValue: number): string => {
  if (trendValue > 0) return 'trend-up'
  if (trendValue < 0) return 'trend-down'
  return 'trend-stable'
}

const getTrendIcon = (trendValue: number): string => {
  if (trendValue > 0) return '↗'
  if (trendValue < 0) return '↘'
  return '→'
}

const formatValue = (value: number): string => {
  if (value >= 1000000) {
    return (value / 1000000).toFixed(1) + 'M'
  }
  if (value >= 1000) {
    return (value / 1000).toFixed(1) + 'K'
  }
  return value.toFixed(1)
}

const calculateTrend = () => {
  if (props.data.length < 2) {
    trend.value = null
    return
  }

  const recent = props.data.slice(-10) // Last 10 data points
  if (recent.length < 2) {
    trend.value = null
    return
  }

  const firstValue = recent[0].value
  const lastValue = recent[recent.length - 1].value
  
  if (firstValue === 0) {
    trend.value = null
    return
  }

  trend.value = ((lastValue - firstValue) / firstValue) * 100
}

const handleRefresh = () => {
  emit('refresh')
  lastUpdated.value = new Date()
}

// Auto-refresh functionality
let refreshTimer: number | null = null

const startAutoRefresh = () => {
  if (props.refreshInterval > 0) {
    refreshTimer = window.setInterval(() => {
      handleRefresh()
    }, props.refreshInterval)
  }
}

const stopAutoRefresh = () => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

watch(() => props.data, () => {
  calculateTrend()
  lastUpdated.value = new Date()
}, { deep: true })

watch(() => props.refreshInterval, () => {
  stopAutoRefresh()
  startAutoRefresh()
})

onMounted(() => {
  calculateTrend()
  startAutoRefresh()
})

onUnmounted(() => {
  stopAutoRefresh()
})
</script>

<style scoped>
.realtime-metrics {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.current-value {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 0.5rem;
}

.value {
  font-size: 1.5rem;
  font-weight: 700;
  line-height: 1;
}

.value-normal {
  color: var(--color-success);
}

.value-warning {
  color: var(--color-warning);
}

.value-critical {
  color: var(--color-error);
}

.trend {
  font-size: 0.875rem;
  font-weight: 500;
  padding: 0.125rem 0.5rem;
  border-radius: var(--radius-sm);
}

.trend-up {
  color: var(--color-error);
  background-color: rgba(239, 68, 68, 0.1);
}

.trend-down {
  color: var(--color-success);
  background-color: rgba(16, 185, 129, 0.1);
}

.trend-stable {
  color: var(--text-secondary);
  background-color: var(--bg-tertiary);
}

.chart-container {
  position: relative;
  width: 100%;
}

.thresholds {
  display: flex;
  gap: 1rem;
  font-size: 0.75rem;
}

.threshold-item {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.5rem;
  border-radius: var(--radius-sm);
}

.threshold-item.warning {
  background-color: rgba(245, 158, 11, 0.1);
  color: var(--color-warning);
}

.threshold-item.critical {
  background-color: rgba(239, 68, 68, 0.1);
  color: var(--color-error);
}

.threshold-label {
  font-weight: 500;
}

.threshold-value {
  font-weight: 600;
}
</style>