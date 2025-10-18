<template>
  <div class="dashboard-grid" :class="{ 'grid-editing': isEditing }">
    <div 
      v-for="widget in widgets" 
      :key="widget.id"
      class="grid-item"
      :style="getWidgetStyle(widget)"
      @mousedown="startDrag(widget, $event)"
    >
      <div class="widget-container">
        <div v-if="isEditing" class="widget-controls">
          <button class="control-btn" @click="editWidget(widget)" title="Edit Widget">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
            </svg>
          </button>
          <button class="control-btn" @click="removeWidget(widget)" title="Remove Widget">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
        
        <component 
          :is="getWidgetComponent(widget.type)" 
          v-bind="widget.config"
          @refresh="refreshWidget(widget)"
        />
        
        <div v-if="isEditing" class="resize-handle" @mousedown="startResize(widget, $event)">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="15,3 21,3 21,9"></polyline>
            <polyline points="9,21 3,21 3,15"></polyline>
            <line x1="21" y1="3" x2="14" y2="10"></line>
            <line x1="3" y1="21" x2="10" y2="14"></line>
          </svg>
        </div>
      </div>
    </div>
    
    <!-- Add Widget Button -->
    <div v-if="isEditing" class="add-widget-area">
      <button class="add-widget-btn" @click="showAddWidget = true">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19"></line>
          <line x1="5" y1="12" x2="19" y2="12"></line>
        </svg>
        Add Widget
      </button>
    </div>
  </div>

  <!-- Add Widget Modal -->
  <div v-if="showAddWidget" class="modal-overlay" @click="showAddWidget = false">
    <div class="modal-content" @click.stop>
      <div class="modal-header">
        <h3>Add Widget</h3>
        <button class="close-btn" @click="showAddWidget = false">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"></line>
            <line x1="6" y1="6" x2="18" y2="18"></line>
          </svg>
        </button>
      </div>
      
      <div class="widget-types">
        <div 
          v-for="type in availableWidgetTypes" 
          :key="type.id"
          class="widget-type-card"
          @click="addWidget(type)"
        >
          <div class="widget-type-icon">
            <component :is="type.icon" />
          </div>
          <div class="widget-type-info">
            <h4>{{ type.name }}</h4>
            <p>{{ type.description }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import RealtimeMetricsWidget from './RealtimeMetricsWidget.vue'
import ClientStatusWidget from './ClientStatusWidget.vue'
import MetricWidget from './MetricWidget.vue'

interface Widget {
  id: string
  type: string
  position: {
    x: number
    y: number
    width: number
    height: number
  }
  config: Record<string, any>
}

interface WidgetType {
  id: string
  name: string
  description: string
  icon: string
  defaultConfig: Record<string, any>
  defaultSize: { width: number; height: number }
}

interface Props {
  widgets: Widget[]
  isEditing?: boolean
  gridSize?: number
  columns?: number
}

const props = withDefaults(defineProps<Props>(), {
  isEditing: false,
  gridSize: 20,
  columns: 12
})

const emit = defineEmits<{
  'update:widgets': [widgets: Widget[]]
  'widget-added': [widget: Widget]
  'widget-removed': [widgetId: string]
  'widget-updated': [widget: Widget]
}>()

const showAddWidget = ref(false)
const dragState = ref<{
  widget: Widget | null
  isDragging: boolean
  isResizing: boolean
  startX: number
  startY: number
  startPosition: { x: number; y: number; width: number; height: number }
} | null>(null)

const availableWidgetTypes: WidgetType[] = [
  {
    id: 'realtime-metrics',
    name: 'Realtime Metrics',
    description: 'Display real-time system metrics with charts',
    icon: 'chart-line',
    defaultConfig: {
      title: 'CPU Usage',
      metricKey: 'cpuUsage',
      unit: '%',
      warningThreshold: 70,
      criticalThreshold: 85
    },
    defaultSize: { width: 4, height: 3 }
  },
  {
    id: 'client-status',
    name: 'Client Status',
    description: 'Show client connection status distribution',
    icon: 'monitor',
    defaultConfig: {},
    defaultSize: { width: 3, height: 4 }
  },
  {
    id: 'metric-summary',
    name: 'Metric Summary',
    description: 'Display summary statistics for metrics',
    icon: 'bar-chart',
    defaultConfig: {
      title: 'System Overview',
      metrics: ['cpu', 'memory', 'disk']
    },
    defaultSize: { width: 3, height: 2 }
  }
]

const getWidgetComponent = (type: string) => {
  switch (type) {
    case 'realtime-metrics':
      return RealtimeMetricsWidget
    case 'client-status':
      return ClientStatusWidget
    case 'metric-summary':
      return MetricWidget
    default:
      return MetricWidget
  }
}

const getWidgetStyle = (widget: Widget) => {
  const { x, y, width, height } = widget.position
  return {
    gridColumn: `${x + 1} / span ${width}`,
    gridRow: `${y + 1} / span ${height}`,
    minHeight: `${height * 100}px`
  }
}

const addWidget = (type: WidgetType) => {
  const newWidget: Widget = {
    id: `widget_${Date.now()}`,
    type: type.id,
    position: {
      x: 0,
      y: findNextAvailablePosition().y,
      width: type.defaultSize.width,
      height: type.defaultSize.height
    },
    config: { ...type.defaultConfig }
  }
  
  const updatedWidgets = [...props.widgets, newWidget]
  emit('update:widgets', updatedWidgets)
  emit('widget-added', newWidget)
  showAddWidget.value = false
}

const removeWidget = (widget: Widget) => {
  const updatedWidgets = props.widgets.filter(w => w.id !== widget.id)
  emit('update:widgets', updatedWidgets)
  emit('widget-removed', widget.id)
}

const editWidget = (widget: Widget) => {
  // Emit event for parent to handle widget editing
  emit('widget-updated', widget)
}

const refreshWidget = (widget: Widget) => {
  // Handle widget refresh
  console.log('Refreshing widget:', widget.id)
}

const findNextAvailablePosition = () => {
  const occupiedPositions = new Set()
  
  props.widgets.forEach(widget => {
    for (let x = widget.position.x; x < widget.position.x + widget.position.width; x++) {
      for (let y = widget.position.y; y < widget.position.y + widget.position.height; y++) {
        occupiedPositions.add(`${x},${y}`)
      }
    }
  })
  
  // Find first available position
  for (let y = 0; y < 100; y++) {
    for (let x = 0; x <= props.columns - 4; x++) {
      let canPlace = true
      for (let checkX = x; checkX < x + 4; checkX++) {
        for (let checkY = y; checkY < y + 3; checkY++) {
          if (occupiedPositions.has(`${checkX},${checkY}`)) {
            canPlace = false
            break
          }
        }
        if (!canPlace) break
      }
      if (canPlace) {
        return { x, y }
      }
    }
  }
  
  return { x: 0, y: 0 }
}

const startDrag = (widget: Widget, event: MouseEvent) => {
  if (!props.isEditing) return
  
  event.preventDefault()
  dragState.value = {
    widget,
    isDragging: true,
    isResizing: false,
    startX: event.clientX,
    startY: event.clientY,
    startPosition: { ...widget.position }
  }
  
  document.addEventListener('mousemove', handleMouseMove)
  document.addEventListener('mouseup', handleMouseUp)
}

const startResize = (widget: Widget, event: MouseEvent) => {
  if (!props.isEditing) return
  
  event.preventDefault()
  event.stopPropagation()
  
  dragState.value = {
    widget,
    isDragging: false,
    isResizing: true,
    startX: event.clientX,
    startY: event.clientY,
    startPosition: { ...widget.position }
  }
  
  document.addEventListener('mousemove', handleMouseMove)
  document.addEventListener('mouseup', handleMouseUp)
}

const handleMouseMove = (event: MouseEvent) => {
  if (!dragState.value) return
  
  const deltaX = event.clientX - dragState.value.startX
  const deltaY = event.clientY - dragState.value.startY
  
  const gridDeltaX = Math.round(deltaX / (props.gridSize * 4))
  const gridDeltaY = Math.round(deltaY / (props.gridSize * 4))
  
  if (dragState.value.isDragging) {
    // Handle dragging
    const newX = Math.max(0, Math.min(props.columns - dragState.value.widget!.position.width, 
      dragState.value.startPosition.x + gridDeltaX))
    const newY = Math.max(0, dragState.value.startPosition.y + gridDeltaY)
    
    dragState.value.widget!.position.x = newX
    dragState.value.widget!.position.y = newY
  } else if (dragState.value.isResizing) {
    // Handle resizing
    const newWidth = Math.max(2, Math.min(props.columns - dragState.value.widget!.position.x,
      dragState.value.startPosition.width + gridDeltaX))
    const newHeight = Math.max(2, dragState.value.startPosition.height + gridDeltaY)
    
    dragState.value.widget!.position.width = newWidth
    dragState.value.widget!.position.height = newHeight
  }
}

const handleMouseUp = () => {
  if (dragState.value?.widget) {
    emit('widget-updated', dragState.value.widget)
  }
  
  dragState.value = null
  document.removeEventListener('mousemove', handleMouseMove)
  document.removeEventListener('mouseup', handleMouseUp)
}

onUnmounted(() => {
  document.removeEventListener('mousemove', handleMouseMove)
  document.removeEventListener('mouseup', handleMouseUp)
})
</script>

<style scoped>
.dashboard-grid {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 1rem;
  padding: 1rem;
  min-height: 400px;
}

.grid-editing {
  background-image: 
    linear-gradient(to right, var(--border-color-light) 1px, transparent 1px),
    linear-gradient(to bottom, var(--border-color-light) 1px, transparent 1px);
  background-size: 20px 20px;
}

.grid-item {
  position: relative;
  min-height: 200px;
}

.widget-container {
  position: relative;
  height: 100%;
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease;
}

.grid-editing .widget-container {
  border: 2px dashed var(--color-primary);
  cursor: move;
}

.widget-container:hover {
  box-shadow: var(--shadow-md);
}

.widget-controls {
  position: absolute;
  top: 0.5rem;
  right: 0.5rem;
  display: flex;
  gap: 0.25rem;
  z-index: 10;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.grid-editing .widget-controls {
  opacity: 1;
}

.control-btn {
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  padding: 0.25rem;
  cursor: pointer;
  color: var(--text-secondary);
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.control-btn:hover {
  background-color: var(--bg-secondary);
  color: var(--text-primary);
}

.resize-handle {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 20px;
  height: 20px;
  cursor: nw-resize;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
  opacity: 0;
  transition: opacity 0.2s ease;
}

.grid-editing .resize-handle {
  opacity: 1;
}

.resize-handle:hover {
  color: var(--text-primary);
}

.add-widget-area {
  grid-column: 1 / -1;
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100px;
  border: 2px dashed var(--border-color);
  border-radius: var(--radius-lg);
  background-color: var(--bg-secondary);
}

.add-widget-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 1rem 2rem;
  background-color: var(--color-primary);
  color: white;
  border: none;
  border-radius: var(--radius-md);
  font-size: 1rem;
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.add-widget-btn:hover {
  background-color: var(--color-primary-dark);
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background-color: var(--bg-primary);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  max-width: 600px;
  width: 90%;
  max-height: 80vh;
  overflow-y: auto;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.5rem;
  border-bottom: 1px solid var(--border-color);
}

.modal-header h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text-primary);
}

.close-btn {
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0.25rem;
  border-radius: var(--radius-sm);
  transition: all 0.2s ease;
}

.close-btn:hover {
  color: var(--text-primary);
  background-color: var(--bg-secondary);
}

.widget-types {
  padding: 1.5rem;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 1rem;
}

.widget-type-card {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all 0.2s ease;
}

.widget-type-card:hover {
  border-color: var(--color-primary);
  background-color: var(--bg-secondary);
}

.widget-type-icon {
  width: 3rem;
  height: 3rem;
  background-color: var(--color-primary);
  color: white;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.widget-type-info h4 {
  margin: 0 0 0.25rem 0;
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
}

.widget-type-info p {
  margin: 0;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

@media (max-width: 768px) {
  .dashboard-grid {
    grid-template-columns: repeat(6, 1fr);
    gap: 0.5rem;
    padding: 0.5rem;
  }
  
  .widget-types {
    grid-template-columns: 1fr;
  }
}
</style>