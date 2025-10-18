<template>
  <div class="metric-widget" :class="{ 'widget-loading': isLoading }">
    <div class="widget-header">
      <div class="widget-title-section">
        <h3 class="widget-title">{{ title }}</h3>
        <span v-if="subtitle" class="widget-subtitle">{{ subtitle }}</span>
      </div>
      <div class="widget-actions">
        <button 
          v-if="showRefresh" 
          class="widget-action-btn" 
          @click="$emit('refresh')"
          :disabled="isLoading"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23,4 23,10 17,10"></polyline>
            <polyline points="1,20 1,14 7,14"></polyline>
            <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15"></path>
          </svg>
        </button>
        <button 
          v-if="showSettings" 
          class="widget-action-btn" 
          @click="$emit('settings')"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="3"></circle>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1 1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
          </svg>
        </button>
      </div>
    </div>

    <div class="widget-content">
      <div v-if="isLoading" class="widget-loading-state">
        <div class="loading-spinner"></div>
        <span>Loading...</span>
      </div>
      
      <div v-else-if="error" class="widget-error-state">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10"></circle>
          <line x1="15" y1="9" x2="9" y2="15"></line>
          <line x1="9" y1="9" x2="15" y2="15"></line>
        </svg>
        <span>{{ error }}</span>
      </div>
      
      <slot v-else></slot>
    </div>

    <div v-if="showFooter" class="widget-footer">
      <slot name="footer">
        <span v-if="lastUpdated" class="last-updated">
          Last updated: {{ formatLastUpdated(lastUpdated) }}
        </span>
      </slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { formatDistanceToNow } from 'date-fns'

interface Props {
  title: string
  subtitle?: string
  isLoading?: boolean
  error?: string | null
  showRefresh?: boolean
  showSettings?: boolean
  showFooter?: boolean
  lastUpdated?: Date | string | null
}

const props = withDefaults(defineProps<Props>(), {
  isLoading: false,
  error: null,
  showRefresh: true,
  showSettings: false,
  showFooter: true,
  lastUpdated: null
})

defineEmits<{
  refresh: []
  settings: []
}>()

const formatLastUpdated = (date: Date | string): string => {
  const dateObj = typeof date === 'string' ? new Date(date) : date
  return formatDistanceToNow(dateObj, { addSuffix: true })
}
</script>

<style scoped>
.metric-widget {
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  transition: all 0.2s ease;
  overflow: hidden;
}

.metric-widget:hover {
  box-shadow: var(--shadow-md);
}

.widget-loading {
  opacity: 0.7;
}

.widget-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 1rem 1rem 0 1rem;
  border-bottom: 1px solid var(--border-color-light);
  margin-bottom: 1rem;
}

.widget-title-section {
  flex: 1;
  min-width: 0;
}

.widget-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 0.25rem 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.widget-subtitle {
  font-size: 0.75rem;
  color: var(--text-secondary);
  display: block;
}

.widget-actions {
  display: flex;
  gap: 0.5rem;
  flex-shrink: 0;
}

.widget-action-btn {
  background: none;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: 0.25rem;
  border-radius: var(--radius-sm);
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.widget-action-btn:hover:not(:disabled) {
  color: var(--text-primary);
  background-color: var(--bg-tertiary);
}

.widget-action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.widget-content {
  padding: 0 1rem 1rem 1rem;
  min-height: 100px;
}

.widget-loading-state,
.widget-error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  min-height: 100px;
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.widget-error-state {
  color: var(--color-error);
}

.loading-spinner {
  width: 24px;
  height: 24px;
  border: 2px solid var(--border-color-light);
  border-top: 2px solid var(--color-primary);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.widget-footer {
  padding: 0.75rem 1rem;
  border-top: 1px solid var(--border-color-light);
  background-color: var(--bg-secondary);
}

.last-updated {
  font-size: 0.75rem;
  color: var(--text-tertiary);
}
</style>