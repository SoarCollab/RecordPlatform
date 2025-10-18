<template>
  <div class="data-export">
    <button
      class="export-trigger"
      :class="triggerClass"
      @click="showExportModal = true"
      :disabled="isExporting"
    >
      <svg v-if="!isExporting" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
        <polyline points="7,10 12,15 17,10"></polyline>
        <line x1="12" y1="15" x2="12" y2="3"></line>
      </svg>
      <svg v-else class="loading-spinner" width="16" height="16" viewBox="0 0 24 24">
        <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2" fill="none" stroke-dasharray="31.416" stroke-dashoffset="31.416">
          <animate attributeName="stroke-dasharray" dur="2s" values="0 31.416;15.708 15.708;0 31.416" repeatCount="indefinite"/>
          <animate attributeName="stroke-dashoffset" dur="2s" values="0;-15.708;-31.416" repeatCount="indefinite"/>
        </circle>
      </svg>
      {{ isExporting ? 'Exporting...' : (buttonText || 'Export') }}
    </button>

    <!-- Export Modal -->
    <teleport to="body">
      <div v-if="showExportModal" class="modal-overlay" @click="showExportModal = false">
        <div class="modal-content" @click.stop>
          <div class="modal-header">
            <h3>Export Data</h3>
            <button class="modal-close" @click="showExportModal = false">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
              </svg>
            </button>
          </div>

          <div class="modal-body">
            <form @submit.prevent="handleExport">
              <!-- Export Format -->
              <div class="form-group">
                <label class="form-label">Export Format</label>
                <div class="format-options">
                  <label
                    v-for="format in availableFormats"
                    :key="format.value"
                    class="format-option"
                    :class="{ 'selected': exportConfig.format === format.value }"
                  >
                    <input
                      v-model="exportConfig.format"
                      type="radio"
                      :value="format.value"
                      class="format-radio"
                    />
                    <div class="format-info">
                      <div class="format-icon">
                        <component :is="format.icon" />
                      </div>
                      <div class="format-details">
                        <span class="format-name">{{ format.name }}</span>
                        <span class="format-description">{{ format.description }}</span>
                      </div>
                    </div>
                  </label>
                </div>
              </div>

              <!-- Date Range -->
              <div class="form-group">
                <label class="form-label">Date Range</label>
                <div class="date-range-options">
                  <div class="preset-ranges">
                    <button
                      v-for="preset in datePresets"
                      :key="preset.value"
                      type="button"
                      class="preset-btn"
                      :class="{ 'active': selectedDatePreset === preset.value }"
                      @click="selectDatePreset(preset)"
                    >
                      {{ preset.label }}
                    </button>
                  </div>
                  
                  <div class="custom-range">
                    <div class="range-inputs">
                      <div class="input-group">
                        <label class="input-label">From</label>
                        <input
                          v-model="exportConfig.dateRange.start"
                          type="datetime-local"
                          class="date-input"
                        />
                      </div>
                      <div class="input-group">
                        <label class="input-label">To</label>
                        <input
                          v-model="exportConfig.dateRange.end"
                          type="datetime-local"
                          class="date-input"
                        />
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Data Selection -->
              <div class="form-group" v-if="dataFields.length > 0">
                <label class="form-label">Data Fields</label>
                <div class="field-selection">
                  <label class="field-option">
                    <input
                      type="checkbox"
                      :checked="allFieldsSelected"
                      @change="toggleAllFields"
                      class="field-checkbox"
                    />
                    <span class="field-name">Select All</span>
                  </label>
                  
                  <label
                    v-for="field in dataFields"
                    :key="field.key"
                    class="field-option"
                  >
                    <input
                      v-model="exportConfig.fields"
                      type="checkbox"
                      :value="field.key"
                      class="field-checkbox"
                    />
                    <span class="field-name">{{ field.label }}</span>
                    <span v-if="field.description" class="field-description">{{ field.description }}</span>
                  </label>
                </div>
              </div>

              <!-- Filters -->
              <div class="form-group" v-if="showFilters">
                <label class="form-label">Apply Current Filters</label>
                <label class="checkbox-label">
                  <input
                    v-model="exportConfig.includeFilters"
                    type="checkbox"
                    class="checkbox"
                  />
                  <span class="checkbox-text">Include current search and filter settings</span>
                </label>
              </div>

              <!-- Advanced Options -->
              <div class="form-group">
                <button
                  type="button"
                  class="advanced-toggle"
                  @click="showAdvanced = !showAdvanced"
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline :points="showAdvanced ? '6,9 12,15 18,9' : '9,18 15,12 9,6'"></polyline>
                  </svg>
                  Advanced Options
                </button>

                <div v-if="showAdvanced" class="advanced-options">
                  <div class="option-group">
                    <label class="form-label">File Name</label>
                    <input
                      v-model="exportConfig.filename"
                      type="text"
                      class="input"
                      placeholder="export-data"
                    />
                  </div>

                  <div class="option-group" v-if="exportConfig.format === 'csv'">
                    <label class="form-label">CSV Options</label>
                    <div class="csv-options">
                      <label class="checkbox-label">
                        <input
                          v-model="exportConfig.csvOptions.includeHeaders"
                          type="checkbox"
                          class="checkbox"
                        />
                        <span class="checkbox-text">Include column headers</span>
                      </label>
                      
                      <div class="input-group">
                        <label class="input-label">Delimiter</label>
                        <select v-model="exportConfig.csvOptions.delimiter" class="select">
                          <option value=",">Comma (,)</option>
                          <option value=";">Semicolon (;)</option>
                          <option value="\t">Tab</option>
                          <option value="|">Pipe (|)</option>
                        </select>
                      </div>
                    </div>
                  </div>

                  <div class="option-group">
                    <label class="checkbox-label">
                      <input
                        v-model="exportConfig.compress"
                        type="checkbox"
                        class="checkbox"
                      />
                      <span class="checkbox-text">Compress file (ZIP)</span>
                    </label>
                  </div>
                </div>
              </div>

              <!-- Export Summary -->
              <div class="export-summary">
                <div class="summary-item">
                  <span class="summary-label">Format:</span>
                  <span class="summary-value">{{ getFormatName(exportConfig.format) }}</span>
                </div>
                <div class="summary-item">
                  <span class="summary-label">Date Range:</span>
                  <span class="summary-value">{{ formatDateRange() }}</span>
                </div>
                <div class="summary-item" v-if="dataFields.length > 0">
                  <span class="summary-label">Fields:</span>
                  <span class="summary-value">{{ exportConfig.fields.length }} selected</span>
                </div>
                <div class="summary-item" v-if="estimatedSize">
                  <span class="summary-label">Estimated Size:</span>
                  <span class="summary-value">{{ estimatedSize }}</span>
                </div>
              </div>

              <div class="modal-actions">
                <button type="button" class="btn btn-secondary" @click="showExportModal = false">
                  Cancel
                </button>
                <button
                  type="submit"
                  class="btn btn-primary"
                  :disabled="!canExport || isExporting"
                >
                  {{ isExporting ? 'Exporting...' : 'Export Data' }}
                </button>
              </div>
            </form>
          </div>
        </div>
      </div>
    </teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { format, subDays, subHours, startOfDay, endOfDay } from 'date-fns'

interface DataField {
  key: string
  label: string
  description?: string
}

interface ExportFormat {
  value: string
  name: string
  description: string
  icon: string
}

interface DatePreset {
  label: string
  value: string
  start: Date
  end: Date
}

interface Props {
  data?: any[]
  dataFields?: DataField[]
  buttonText?: string
  triggerClass?: string
  showFilters?: boolean
  estimatedSize?: string
}

const props = withDefaults(defineProps<Props>(), {
  data: () => [],
  dataFields: () => [],
  showFilters: true
})

const emit = defineEmits<{
  export: [config: any]
}>()

const showExportModal = ref(false)
const showAdvanced = ref(false)
const isExporting = ref(false)
const selectedDatePreset = ref('24h')

const exportConfig = ref({
  format: 'csv',
  dateRange: {
    start: format(subHours(new Date(), 24), "yyyy-MM-dd'T'HH:mm"),
    end: format(new Date(), "yyyy-MM-dd'T'HH:mm")
  },
  fields: [] as string[],
  includeFilters: true,
  filename: 'export-data',
  csvOptions: {
    includeHeaders: true,
    delimiter: ','
  },
  compress: false
})

const availableFormats: ExportFormat[] = [
  {
    value: 'csv',
    name: 'CSV',
    description: 'Comma-separated values, compatible with Excel',
    icon: 'CsvIcon'
  },
  {
    value: 'json',
    name: 'JSON',
    description: 'JavaScript Object Notation, for developers',
    icon: 'JsonIcon'
  },
  {
    value: 'xlsx',
    name: 'Excel',
    description: 'Microsoft Excel spreadsheet format',
    icon: 'ExcelIcon'
  },
  {
    value: 'pdf',
    name: 'PDF',
    description: 'Portable Document Format, for reports',
    icon: 'PdfIcon'
  }
]

const datePresets: DatePreset[] = [
  {
    label: 'Last Hour',
    value: '1h',
    start: subHours(new Date(), 1),
    end: new Date()
  },
  {
    label: 'Last 24 Hours',
    value: '24h',
    start: subHours(new Date(), 24),
    end: new Date()
  },
  {
    label: 'Last 7 Days',
    value: '7d',
    start: startOfDay(subDays(new Date(), 7)),
    end: endOfDay(new Date())
  },
  {
    label: 'Last 30 Days',
    value: '30d',
    start: startOfDay(subDays(new Date(), 30)),
    end: endOfDay(new Date())
  }
]

const allFieldsSelected = computed(() => {
  return props.dataFields.length > 0 && exportConfig.value.fields.length === props.dataFields.length
})

const canExport = computed(() => {
  return exportConfig.value.format && 
         exportConfig.value.dateRange.start && 
         exportConfig.value.dateRange.end &&
         (props.dataFields.length === 0 || exportConfig.value.fields.length > 0)
})

const getFormatName = (format: string): string => {
  return availableFormats.find(f => f.value === format)?.name || format.toUpperCase()
}

const formatDateRange = (): string => {
  const start = new Date(exportConfig.value.dateRange.start)
  const end = new Date(exportConfig.value.dateRange.end)
  return `${format(start, 'MMM dd, HH:mm')} - ${format(end, 'MMM dd, HH:mm')}`
}

const selectDatePreset = (preset: DatePreset) => {
  selectedDatePreset.value = preset.value
  exportConfig.value.dateRange = {
    start: format(preset.start, "yyyy-MM-dd'T'HH:mm"),
    end: format(preset.end, "yyyy-MM-dd'T'HH:mm")
  }
}

const toggleAllFields = (event: Event) => {
  const target = event.target as HTMLInputElement
  if (target.checked) {
    exportConfig.value.fields = props.dataFields.map(field => field.key)
  } else {
    exportConfig.value.fields = []
  }
}

const handleExport = async () => {
  if (!canExport.value) return

  isExporting.value = true
  
  try {
    await emit('export', { ...exportConfig.value })
    showExportModal.value = false
    
    // Show success notification
    window.dispatchEvent(new CustomEvent('app:notification', {
      detail: {
        type: 'success',
        title: 'Export Complete',
        message: 'Your data has been exported successfully.'
      }
    }))
  } catch (error) {
    console.error('Export failed:', error)
    
    // Show error notification
    window.dispatchEvent(new CustomEvent('app:notification', {
      detail: {
        type: 'error',
        title: 'Export Failed',
        message: 'There was an error exporting your data. Please try again.'
      }
    }))
  } finally {
    isExporting.value = false
  }
}

// Initialize fields selection
watch(() => props.dataFields, (newFields) => {
  if (newFields.length > 0 && exportConfig.value.fields.length === 0) {
    exportConfig.value.fields = newFields.map(field => field.key)
  }
}, { immediate: true })

// Reset date preset when custom dates are changed
watch(() => exportConfig.value.dateRange, () => {
  const currentStart = new Date(exportConfig.value.dateRange.start)
  const currentEnd = new Date(exportConfig.value.dateRange.end)
  
  const matchingPreset = datePresets.find(preset => {
    const presetStart = new Date(format(preset.start, "yyyy-MM-dd'T'HH:mm"))
    const presetEnd = new Date(format(preset.end, "yyyy-MM-dd'T'HH:mm"))
    return Math.abs(currentStart.getTime() - presetStart.getTime()) < 60000 &&
           Math.abs(currentEnd.getTime() - presetEnd.getTime()) < 60000
  })
  
  selectedDatePreset.value = matchingPreset?.value || 'custom'
}, { deep: true })
</script>

<style scoped>
.export-trigger {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  background-color: var(--color-primary);
  color: white;
  border: none;
  border-radius: var(--radius-md);
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.export-trigger:hover:not(:disabled) {
  background-color: var(--color-primary-dark);
}

.export-trigger:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.loading-spinner {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
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
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
  max-width: 600px;
  width: 90%;
  max-height: 90vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
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

.modal-close {
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0.25rem;
  border-radius: var(--radius-sm);
  transition: all 0.2s ease;
}

.modal-close:hover {
  color: var(--text-primary);
  background-color: var(--bg-tertiary);
}

.modal-body {
  padding: 1.5rem;
  overflow-y: auto;
  flex: 1;
}

.form-group {
  margin-bottom: 1.5rem;
}

.form-label {
  display: block;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.format-options {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 0.75rem;
}

.format-option {
  display: block;
  cursor: pointer;
}

.format-radio {
  display: none;
}

.format-info {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 1rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  transition: all 0.2s ease;
}

.format-option.selected .format-info {
  border-color: var(--color-primary);
  background-color: rgba(59, 130, 246, 0.05);
}

.format-icon {
  width: 2rem;
  height: 2rem;
  background-color: var(--color-primary);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  flex-shrink: 0;
}

.format-details {
  flex: 1;
  min-width: 0;
}

.format-name {
  display: block;
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
}

.format-description {
  display: block;
  font-size: 0.75rem;
  color: var(--text-secondary);
  line-height: 1.3;
}

.date-range-options {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.preset-ranges {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.preset-btn {
  padding: 0.5rem 0.75rem;
  background-color: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  cursor: pointer;
  font-size: 0.875rem;
  transition: all 0.2s ease;
}

.preset-btn:hover,
.preset-btn.active {
  border-color: var(--color-primary);
  background-color: var(--color-primary);
  color: white;
}

.range-inputs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}

.input-group {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.input-label {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--text-secondary);
}

.date-input,
.input,
.select {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background-color: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.875rem;
}

.field-selection {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-height: 200px;
  overflow-y: auto;
  padding: 0.5rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background-color: var(--bg-secondary);
}

.field-option {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  cursor: pointer;
  padding: 0.5rem;
  border-radius: var(--radius-sm);
  transition: background-color 0.2s ease;
}

.field-option:hover {
  background-color: var(--bg-tertiary);
}

.field-checkbox {
  margin-top: 0.125rem;
  accent-color: var(--color-primary);
}

.field-name {
  font-weight: 500;
  color: var(--text-primary);
}

.field-description {
  font-size: 0.75rem;
  color: var(--text-secondary);
  margin-left: 0.5rem;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
  font-size: 0.875rem;
}

.checkbox {
  accent-color: var(--color-primary);
}

.advanced-toggle {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  background: none;
  border: none;
  color: var(--color-primary);
  cursor: pointer;
  font-size: 0.875rem;
  padding: 0.5rem 0;
  transition: color 0.2s ease;
}

.advanced-toggle:hover {
  color: var(--color-primary-dark);
}

.advanced-options {
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 1px solid var(--border-color-light);
}

.option-group {
  margin-bottom: 1rem;
}

.csv-options {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.export-summary {
  background-color: var(--bg-secondary);
  border-radius: var(--radius-lg);
  padding: 1rem;
  margin-bottom: 1.5rem;
}

.summary-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.25rem 0;
}

.summary-label {
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.summary-value {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-primary);
}

.modal-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
  padding-top: 1rem;
  border-top: 1px solid var(--border-color);
}

@media (max-width: 768px) {
  .format-options {
    grid-template-columns: 1fr;
  }
  
  .range-inputs {
    grid-template-columns: 1fr;
  }
  
  .preset-ranges {
    flex-direction: column;
  }
  
  .modal-actions {
    flex-direction: column;
  }
}
</style>