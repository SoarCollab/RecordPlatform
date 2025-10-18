<template>
  <div class="advanced-search" :class="{ 'search-expanded': isExpanded }">
    <div class="search-input-container">
      <div class="search-input-wrapper">
        <svg class="search-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8"></circle>
          <path d="m21 21-4.35-4.35"></path>
        </svg>
        <input
          ref="searchInputRef"
          v-model="searchQuery"
          type="text"
          :placeholder="placeholder"
          class="search-input"
          @focus="handleFocus"
          @blur="handleBlur"
          @keydown="handleKeydown"
        />
        <button
          v-if="searchQuery"
          class="clear-button"
          @click="clearSearch"
          title="Clear search"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"></line>
            <line x1="6" y1="6" x2="18" y2="18"></line>
          </svg>
        </button>
      </div>
      
      <button
        class="filters-toggle"
        :class="{ 'active': showFilters || hasActiveFilters }"
        @click="toggleFilters"
        title="Advanced filters"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polygon points="22,3 2,3 10,12.46 10,19 14,21 14,12.46"></polygon>
        </svg>
        <span v-if="activeFiltersCount > 0" class="filter-count">{{ activeFiltersCount }}</span>
      </button>
    </div>

    <!-- Advanced Filters Panel -->
    <div v-if="showFilters" class="filters-panel">
      <div class="filters-header">
        <h4 class="filters-title">Advanced Filters</h4>
        <button class="clear-filters-btn" @click="clearAllFilters" :disabled="!hasActiveFilters">
          Clear All
        </button>
      </div>

      <div class="filters-content">
        <div class="filter-group" v-for="filter in filterConfig" :key="filter.key">
          <label class="filter-label">{{ filter.label }}</label>
          
          <!-- Select Filter -->
          <select
            v-if="filter.type === 'select'"
            v-model="filters[filter.key]"
            class="filter-select"
          >
            <option value="">{{ filter.placeholder || 'All' }}</option>
            <option v-for="option in filter.options" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>

          <!-- Multi-select Filter -->
          <div v-else-if="filter.type === 'multiselect'" class="multiselect-container">
            <div class="multiselect-tags">
              <span
                v-for="value in filters[filter.key]"
                :key="value"
                class="multiselect-tag"
              >
                {{ getOptionLabel(filter, value) }}
                <button @click="removeMultiselectValue(filter.key, value)">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="18" y1="6" x2="6" y2="18"></line>
                    <line x1="6" y1="6" x2="18" y2="18"></line>
                  </svg>
                </button>
              </span>
            </div>
            <select
              :value="''"
              @change="addMultiselectValue(filter.key, $event.target.value)"
              class="filter-select"
            >
              <option value="">{{ filter.placeholder || 'Select...' }}</option>
              <option
                v-for="option in getAvailableOptions(filter)"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </option>
            </select>
          </div>

          <!-- Range Filter -->
          <div v-else-if="filter.type === 'range'" class="range-container">
            <input
              v-model="filters[filter.key].min"
              type="number"
              :placeholder="filter.minPlaceholder || 'Min'"
              class="range-input"
            />
            <span class="range-separator">to</span>
            <input
              v-model="filters[filter.key].max"
              type="number"
              :placeholder="filter.maxPlaceholder || 'Max'"
              class="range-input"
            />
          </div>

          <!-- Date Range Filter -->
          <div v-else-if="filter.type === 'daterange'" class="date-range-container">
            <input
              v-model="filters[filter.key].start"
              type="datetime-local"
              class="date-input"
            />
            <span class="range-separator">to</span>
            <input
              v-model="filters[filter.key].end"
              type="datetime-local"
              class="date-input"
            />
          </div>

          <!-- Boolean Filter -->
          <label v-else-if="filter.type === 'boolean'" class="checkbox-label">
            <input
              v-model="filters[filter.key]"
              type="checkbox"
              class="checkbox"
            />
            <span class="checkbox-text">{{ filter.checkboxLabel || 'Enabled' }}</span>
          </label>
        </div>
      </div>

      <div class="filters-actions">
        <button class="btn btn-primary btn-sm" @click="applyFilters">
          Apply Filters
        </button>
        <button class="btn btn-secondary btn-sm" @click="resetFilters">
          Reset
        </button>
      </div>
    </div>

    <!-- Search Suggestions -->
    <div v-if="showSuggestions && suggestions.length > 0" class="suggestions-panel">
      <div class="suggestions-header">
        <span class="suggestions-title">Suggestions</span>
      </div>
      <div class="suggestions-list">
        <button
          v-for="(suggestion, index) in suggestions"
          :key="index"
          class="suggestion-item"
          :class="{ 'suggestion-active': selectedSuggestionIndex === index }"
          @click="selectSuggestion(suggestion)"
        >
          <svg class="suggestion-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"></circle>
            <path d="m21 21-4.35-4.35"></path>
          </svg>
          <span class="suggestion-text">{{ suggestion.text }}</span>
          <span v-if="suggestion.category" class="suggestion-category">{{ suggestion.category }}</span>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'

interface FilterOption {
  label: string
  value: string | number
}

interface FilterConfig {
  key: string
  label: string
  type: 'select' | 'multiselect' | 'range' | 'daterange' | 'boolean'
  options?: FilterOption[]
  placeholder?: string
  minPlaceholder?: string
  maxPlaceholder?: string
  checkboxLabel?: string
}

interface SearchSuggestion {
  text: string
  category?: string
  value?: any
}

interface Props {
  placeholder?: string
  filterConfig?: FilterConfig[]
  suggestions?: SearchSuggestion[]
  debounceMs?: number
}

const props = withDefaults(defineProps<Props>(), {
  placeholder: 'Search...',
  filterConfig: () => [],
  suggestions: () => [],
  debounceMs: 300
})

const emit = defineEmits<{
  search: [query: string, filters: Record<string, any>]
  'filter-change': [filters: Record<string, any>]
}>()

const searchInputRef = ref<HTMLInputElement>()
const searchQuery = ref('')
const showFilters = ref(false)
const showSuggestions = ref(false)
const selectedSuggestionIndex = ref(-1)
const isExpanded = ref(false)

// Initialize filters based on config
const filters = ref<Record<string, any>>({})

const initializeFilters = () => {
  props.filterConfig.forEach(config => {
    switch (config.type) {
      case 'multiselect':
        filters.value[config.key] = []
        break
      case 'range':
      case 'daterange':
        filters.value[config.key] = { min: '', max: '', start: '', end: '' }
        break
      case 'boolean':
        filters.value[config.key] = false
        break
      default:
        filters.value[config.key] = ''
    }
  })
}

initializeFilters()

const hasActiveFilters = computed(() => {
  return Object.entries(filters.value).some(([key, value]) => {
    if (Array.isArray(value)) return value.length > 0
    if (typeof value === 'object' && value !== null) {
      return Object.values(value).some(v => v !== '' && v !== null)
    }
    return value !== '' && value !== false && value !== null
  })
})

const activeFiltersCount = computed(() => {
  let count = 0
  Object.entries(filters.value).forEach(([key, value]) => {
    if (Array.isArray(value) && value.length > 0) count++
    else if (typeof value === 'object' && value !== null) {
      if (Object.values(value).some(v => v !== '' && v !== null)) count++
    } else if (value !== '' && value !== false && value !== null) count++
  })
  return count
})

const handleFocus = () => {
  isExpanded.value = true
  showSuggestions.value = props.suggestions.length > 0
}

const handleBlur = () => {
  // Delay to allow suggestion clicks
  setTimeout(() => {
    showSuggestions.value = false
    if (!showFilters.value) {
      isExpanded.value = false
    }
  }, 200)
}

const handleKeydown = (event: KeyboardEvent) => {
  if (!showSuggestions.value || props.suggestions.length === 0) return

  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      selectedSuggestionIndex.value = Math.min(
        selectedSuggestionIndex.value + 1,
        props.suggestions.length - 1
      )
      break
    case 'ArrowUp':
      event.preventDefault()
      selectedSuggestionIndex.value = Math.max(selectedSuggestionIndex.value - 1, -1)
      break
    case 'Enter':
      event.preventDefault()
      if (selectedSuggestionIndex.value >= 0) {
        selectSuggestion(props.suggestions[selectedSuggestionIndex.value])
      } else {
        performSearch()
      }
      break
    case 'Escape':
      showSuggestions.value = false
      searchInputRef.value?.blur()
      break
  }
}

const clearSearch = () => {
  searchQuery.value = ''
  performSearch()
}

const toggleFilters = () => {
  showFilters.value = !showFilters.value
  isExpanded.value = showFilters.value || showSuggestions.value
}

const selectSuggestion = (suggestion: SearchSuggestion) => {
  searchQuery.value = suggestion.text
  showSuggestions.value = false
  selectedSuggestionIndex.value = -1
  performSearch()
}

const getOptionLabel = (filter: FilterConfig, value: string | number): string => {
  const option = filter.options?.find(opt => opt.value === value)
  return option?.label || String(value)
}

const getAvailableOptions = (filter: FilterConfig): FilterOption[] => {
  const selectedValues = filters.value[filter.key] || []
  return filter.options?.filter(opt => !selectedValues.includes(opt.value)) || []
}

const addMultiselectValue = (key: string, value: string) => {
  if (value && !filters.value[key].includes(value)) {
    filters.value[key].push(value)
  }
}

const removeMultiselectValue = (key: string, value: string) => {
  const index = filters.value[key].indexOf(value)
  if (index > -1) {
    filters.value[key].splice(index, 1)
  }
}

const clearAllFilters = () => {
  initializeFilters()
  performSearch()
}

const resetFilters = () => {
  initializeFilters()
}

const applyFilters = () => {
  performSearch()
  showFilters.value = false
  isExpanded.value = false
}

// Debounced search
let searchTimeout: number
const performSearch = () => {
  clearTimeout(searchTimeout)
  searchTimeout = window.setTimeout(() => {
    emit('search', searchQuery.value, { ...filters.value })
  }, props.debounceMs)
}

// Watch for search query changes
watch(searchQuery, performSearch)

// Watch for filter changes
watch(filters, () => {
  emit('filter-change', { ...filters.value })
}, { deep: true })
</script>

<style scoped>
.advanced-search {
  position: relative;
  width: 100%;
  max-width: 600px;
}

.search-input-container {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}

.search-input-wrapper {
  position: relative;
  flex: 1;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 0.75rem;
  color: var(--text-tertiary);
  pointer-events: none;
}

.search-input {
  width: 100%;
  padding: 0.75rem 2.5rem 0.75rem 2.5rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  background-color: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.875rem;
  transition: all 0.2s ease;
}

.search-input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgb(59 130 246 / 0.1);
  background-color: var(--bg-primary);
}

.clear-button {
  position: absolute;
  right: 0.75rem;
  background: none;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: 0.25rem;
  border-radius: var(--radius-sm);
  transition: color 0.2s ease;
}

.clear-button:hover {
  color: var(--text-primary);
}

.filters-toggle {
  position: relative;
  background: none;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0.75rem;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.filters-toggle:hover,
.filters-toggle.active {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background-color: var(--bg-primary);
}

.filter-count {
  position: absolute;
  top: -6px;
  right: -6px;
  background-color: var(--color-primary);
  color: white;
  font-size: 0.75rem;
  font-weight: 600;
  padding: 0.125rem 0.375rem;
  border-radius: 1rem;
  min-width: 1.25rem;
  text-align: center;
}

.filters-panel {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 0.5rem;
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  z-index: 100;
  padding: 1rem;
}

.filters-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid var(--border-color-light);
}

.filters-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.clear-filters-btn {
  background: none;
  border: none;
  color: var(--color-primary);
  cursor: pointer;
  font-size: 0.875rem;
  padding: 0.25rem 0.5rem;
  border-radius: var(--radius-sm);
  transition: all 0.2s ease;
}

.clear-filters-btn:hover:not(:disabled) {
  background-color: var(--bg-secondary);
}

.clear-filters-btn:disabled {
  color: var(--text-tertiary);
  cursor: not-allowed;
}

.filters-content {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
  margin-bottom: 1rem;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.filter-label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-primary);
}

.filter-select,
.range-input,
.date-input {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background-color: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.875rem;
}

.multiselect-container {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.multiselect-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
}

.multiselect-tag {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.5rem;
  background-color: var(--color-primary);
  color: white;
  font-size: 0.75rem;
  border-radius: var(--radius-sm);
}

.multiselect-tag button {
  background: none;
  border: none;
  color: white;
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
}

.range-container,
.date-range-container {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.range-separator {
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
  font-size: 0.875rem;
}

.checkbox {
  width: 1rem;
  height: 1rem;
  accent-color: var(--color-primary);
}

.filters-actions {
  display: flex;
  gap: 0.5rem;
  justify-content: flex-end;
  padding-top: 0.5rem;
  border-top: 1px solid var(--border-color-light);
}

.suggestions-panel {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 0.5rem;
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  z-index: 100;
  max-height: 300px;
  overflow-y: auto;
}

.suggestions-header {
  padding: 0.75rem 1rem 0.5rem;
  border-bottom: 1px solid var(--border-color-light);
}

.suggestions-title {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.suggestions-list {
  padding: 0.5rem 0;
}

.suggestion-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  padding: 0.75rem 1rem;
  background: none;
  border: none;
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
  transition: background-color 0.2s ease;
}

.suggestion-item:hover,
.suggestion-item.suggestion-active {
  background-color: var(--bg-secondary);
}

.suggestion-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.suggestion-text {
  flex: 1;
  font-size: 0.875rem;
}

.suggestion-category {
  font-size: 0.75rem;
  color: var(--text-tertiary);
  background-color: var(--bg-tertiary);
  padding: 0.125rem 0.5rem;
  border-radius: var(--radius-sm);
}

@media (max-width: 768px) {
  .filters-content {
    grid-template-columns: 1fr;
  }
  
  .search-input-container {
    flex-direction: column;
    align-items: stretch;
  }
  
  .range-container,
  .date-range-container {
    flex-direction: column;
    align-items: stretch;
  }
  
  .range-separator {
    text-align: center;
  }
}
</style>