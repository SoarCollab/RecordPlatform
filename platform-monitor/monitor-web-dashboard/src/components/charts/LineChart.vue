<template>
  <BaseChart
    type="line"
    :data="chartData"
    :options="chartOptions"
    :height="height"
    :update-mode="updateMode"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import BaseChart from './BaseChart.vue'
import type { ChartOptions } from 'chart.js'

interface DataPoint {
  x: string | number | Date
  y: number
}

interface Dataset {
  label: string
  data: DataPoint[]
  borderColor?: string
  backgroundColor?: string
  fill?: boolean
  tension?: number
}

interface Props {
  datasets: Dataset[]
  height?: string
  updateMode?: 'resize' | 'reset' | 'none'
  showGrid?: boolean
  showLegend?: boolean
  animate?: boolean
  yAxisMin?: number
  yAxisMax?: number
  timeFormat?: string
}

const props = withDefaults(defineProps<Props>(), {
  height: '300px',
  updateMode: 'resize',
  showGrid: true,
  showLegend: true,
  animate: true
})

const defaultColors = [
  'rgb(59, 130, 246)',   // blue
  'rgb(16, 185, 129)',   // green
  'rgb(245, 158, 11)',   // yellow
  'rgb(239, 68, 68)',    // red
  'rgb(139, 92, 246)',   // purple
  'rgb(236, 72, 153)',   // pink
]

const chartData = computed(() => ({
  datasets: props.datasets.map((dataset, index) => ({
    ...dataset,
    borderColor: dataset.borderColor || defaultColors[index % defaultColors.length],
    backgroundColor: dataset.backgroundColor || 
      (dataset.borderColor || defaultColors[index % defaultColors.length]) + '20',
    fill: dataset.fill ?? false,
    tension: dataset.tension ?? 0.4,
    pointRadius: 2,
    pointHoverRadius: 4,
    borderWidth: 2
  }))
}))

const chartOptions = computed((): ChartOptions<'line'> => ({
  responsive: true,
  maintainAspectRatio: false,
  animation: {
    duration: props.animate ? 750 : 0
  },
  interaction: {
    intersect: false,
    mode: 'index'
  },
  plugins: {
    legend: {
      display: props.showLegend,
      position: 'top',
      labels: {
        usePointStyle: true,
        padding: 20,
        color: 'var(--text-primary)'
      }
    },
    tooltip: {
      backgroundColor: 'var(--bg-primary)',
      titleColor: 'var(--text-primary)',
      bodyColor: 'var(--text-secondary)',
      borderColor: 'var(--border-color)',
      borderWidth: 1,
      cornerRadius: 8,
      displayColors: true,
      callbacks: {
        title: (context) => {
          const point = context[0]
          if (point.parsed.x && typeof point.parsed.x === 'number') {
            return new Date(point.parsed.x).toLocaleString()
          }
          return point.label || ''
        },
        label: (context) => {
          const value = typeof context.parsed.y === 'number' 
            ? context.parsed.y.toFixed(2) 
            : context.parsed.y
          return `${context.dataset.label}: ${value}`
        }
      }
    }
  },
  scales: {
    x: {
      type: 'time',
      time: {
        displayFormats: {
          minute: 'HH:mm',
          hour: 'HH:mm',
          day: 'MMM dd'
        }
      },
      grid: {
        display: props.showGrid,
        color: 'var(--border-color-light)'
      },
      ticks: {
        color: 'var(--text-secondary)',
        maxTicksLimit: 10
      }
    },
    y: {
      min: props.yAxisMin,
      max: props.yAxisMax,
      grid: {
        display: props.showGrid,
        color: 'var(--border-color-light)'
      },
      ticks: {
        color: 'var(--text-secondary)',
        callback: function(value) {
          return typeof value === 'number' ? value.toFixed(1) : value
        }
      }
    }
  }
}))
</script>