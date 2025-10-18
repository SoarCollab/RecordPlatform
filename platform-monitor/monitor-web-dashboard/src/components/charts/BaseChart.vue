<template>
  <div class="chart-wrapper" :style="{ height: height }">
    <canvas ref="canvasRef"></canvas>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import {
  Chart,
  ChartConfiguration,
  ChartType,
  registerables,
  TimeScale,
  LinearScale,
  CategoryScale
} from 'chart.js'
import 'chartjs-adapter-date-fns'

Chart.register(...registerables)

interface Props {
  type: ChartType
  data: any
  options?: any
  height?: string
  updateMode?: 'resize' | 'reset' | 'none'
}

const props = withDefaults(defineProps<Props>(), {
  height: '300px',
  updateMode: 'resize'
})

const canvasRef = ref<HTMLCanvasElement>()
let chartInstance: Chart | null = null

const createChart = () => {
  if (!canvasRef.value) return

  const config: ChartConfiguration = {
    type: props.type,
    data: props.data,
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          labels: {
            usePointStyle: true,
            color: 'var(--text-primary)'
          }
        }
      },
      scales: {
        x: {
          grid: {
            color: 'var(--border-color-light)'
          },
          ticks: {
            color: 'var(--text-secondary)'
          }
        },
        y: {
          grid: {
            color: 'var(--border-color-light)'
          },
          ticks: {
            color: 'var(--text-secondary)'
          }
        }
      },
      ...props.options
    }
  }

  chartInstance = new Chart(canvasRef.value, config)
}

const updateChart = () => {
  if (!chartInstance) return

  chartInstance.data = props.data
  
  if (props.updateMode === 'resize') {
    chartInstance.resize()
  } else if (props.updateMode === 'reset') {
    chartInstance.reset()
  }
  
  chartInstance.update('none')
}

const destroyChart = () => {
  if (chartInstance) {
    chartInstance.destroy()
    chartInstance = null
  }
}

watch(() => props.data, updateChart, { deep: true })
watch(() => props.options, () => {
  destroyChart()
  nextTick(createChart)
}, { deep: true })

onMounted(() => {
  nextTick(createChart)
})

onUnmounted(() => {
  destroyChart()
})

defineExpose({
  chart: () => chartInstance,
  update: updateChart,
  destroy: destroyChart
})
</script>

<style scoped>
.chart-wrapper {
  position: relative;
  width: 100%;
}

canvas {
  display: block;
  width: 100% !important;
  height: 100% !important;
}
</style>