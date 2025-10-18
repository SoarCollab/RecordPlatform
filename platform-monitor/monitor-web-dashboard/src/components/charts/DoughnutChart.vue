<template>
  <BaseChart
    type="doughnut"
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

interface DataItem {
  label: string
  value: number
  color?: string
}

interface Props {
  data: DataItem[]
  height?: string
  updateMode?: 'resize' | 'reset' | 'none'
  showLegend?: boolean
  showLabels?: boolean
  animate?: boolean
  cutout?: string
}

const props = withDefaults(defineProps<Props>(), {
  height: '300px',
  updateMode: 'resize',
  showLegend: true,
  showLabels: true,
  animate: true,
  cutout: '60%'
})

const defaultColors = [
  'rgb(59, 130, 246)',   // blue
  'rgb(16, 185, 129)',   // green
  'rgb(245, 158, 11)',   // yellow
  'rgb(239, 68, 68)',    // red
  'rgb(139, 92, 246)',   // purple
  'rgb(236, 72, 153)',   // pink
  'rgb(6, 182, 212)',    // cyan
  'rgb(107, 114, 128)',  // gray
]

const chartData = computed(() => ({
  labels: props.data.map(item => item.label),
  datasets: [{
    data: props.data.map(item => item.value),
    backgroundColor: props.data.map((item, index) => 
      item.color || defaultColors[index % defaultColors.length]
    ),
    borderColor: 'var(--bg-primary)',
    borderWidth: 2,
    hoverBorderWidth: 3
  }]
}))

const chartOptions = computed((): ChartOptions<'doughnut'> => ({
  responsive: true,
  maintainAspectRatio: false,
  cutout: props.cutout,
  animation: {
    duration: props.animate ? 750 : 0
  },
  plugins: {
    legend: {
      display: props.showLegend,
      position: 'right',
      labels: {
        usePointStyle: true,
        padding: 20,
        color: 'var(--text-primary)',
        generateLabels: (chart) => {
          const data = chart.data
          if (data.labels?.length && data.datasets.length) {
            return data.labels.map((label, index) => {
              const dataset = data.datasets[0]
              const value = dataset.data[index] as number
              const total = (dataset.data as number[]).reduce((sum, val) => sum + val, 0)
              const percentage = total > 0 ? ((value / total) * 100).toFixed(1) : '0'
              
              return {
                text: `${label} (${percentage}%)`,
                fillStyle: dataset.backgroundColor?.[index] as string,
                strokeStyle: dataset.borderColor as string,
                lineWidth: dataset.borderWidth as number,
                hidden: false,
                index
              }
            })
          }
          return []
        }
      }
    },
    tooltip: {
      backgroundColor: 'var(--bg-primary)',
      titleColor: 'var(--text-primary)',
      bodyColor: 'var(--text-secondary)',
      borderColor: 'var(--border-color)',
      borderWidth: 1,
      cornerRadius: 8,
      callbacks: {
        label: (context) => {
          const label = context.label || ''
          const value = context.parsed
          const total = (context.dataset.data as number[]).reduce((sum, val) => sum + val, 0)
          const percentage = total > 0 ? ((value / total) * 100).toFixed(1) : '0'
          return `${label}: ${value} (${percentage}%)`
        }
      }
    }
  }
}))
</script>