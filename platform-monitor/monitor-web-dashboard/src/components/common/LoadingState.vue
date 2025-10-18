<template>
  <div class="loading-state" :class="{ 'loading-overlay': overlay, 'loading-inline': !overlay }">
    <div class="loading-content">
      <div class="loading-spinner" :class="spinnerSize">
        <div class="spinner-ring"></div>
        <div class="spinner-ring"></div>
        <div class="spinner-ring"></div>
        <div class="spinner-ring"></div>
      </div>
      
      <div v-if="message" class="loading-message">
        {{ message }}
      </div>
      
      <div v-if="showProgress && progress !== null" class="loading-progress">
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: `${progress}%` }"></div>
        </div>
        <div class="progress-text">{{ Math.round(progress) }}%</div>
      </div>
      
      <div v-if="showCancel" class="loading-actions">
        <button class="btn btn-secondary btn-sm" @click="$emit('cancel')">
          Cancel
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
interface Props {
  message?: string
  overlay?: boolean
  size?: 'sm' | 'md' | 'lg'
  showProgress?: boolean
  progress?: number | null
  showCancel?: boolean
}

withDefaults(defineProps<Props>(), {
  message: '',
  overlay: false,
  size: 'md',
  showProgress: false,
  progress: null,
  showCancel: false
})

defineEmits<{
  cancel: []
}>()

const spinnerSize = computed(() => {
  const sizeMap = {
    sm: 'spinner-sm',
    md: 'spinner-md',
    lg: 'spinner-lg'
  }
  return sizeMap[props.size]
})
</script>

<script lang="ts">
import { computed } from 'vue'

export default {
  name: 'LoadingState'
}
</script>

<style scoped>
.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
}

.loading-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  z-index: 9999;
  backdrop-filter: blur(2px);
}

.loading-inline {
  padding: 2rem;
  min-height: 200px;
}

.loading-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  background-color: var(--bg-primary);
  padding: 2rem;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  max-width: 300px;
  text-align: center;
}

.loading-inline .loading-content {
  background: none;
  box-shadow: none;
  padding: 1rem;
}

.loading-spinner {
  position: relative;
  display: inline-block;
}

.spinner-sm {
  width: 24px;
  height: 24px;
}

.spinner-md {
  width: 40px;
  height: 40px;
}

.spinner-lg {
  width: 60px;
  height: 60px;
}

.spinner-ring {
  position: absolute;
  border: 2px solid transparent;
  border-top: 2px solid var(--color-primary);
  border-radius: 50%;
  animation: spin 1.2s cubic-bezier(0.5, 0, 0.5, 1) infinite;
}

.spinner-sm .spinner-ring {
  width: 24px;
  height: 24px;
  border-width: 2px;
}

.spinner-md .spinner-ring {
  width: 40px;
  height: 40px;
  border-width: 3px;
}

.spinner-lg .spinner-ring {
  width: 60px;
  height: 60px;
  border-width: 4px;
}

.spinner-ring:nth-child(1) {
  animation-delay: -0.45s;
}

.spinner-ring:nth-child(2) {
  animation-delay: -0.3s;
}

.spinner-ring:nth-child(3) {
  animation-delay: -0.15s;
}

@keyframes spin {
  0% {
    transform: rotate(0deg);
  }
  100% {
    transform: rotate(360deg);
  }
}

.loading-message {
  font-size: 0.875rem;
  color: var(--text-secondary);
  font-weight: 500;
}

.loading-progress {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.progress-bar {
  width: 100%;
  height: 6px;
  background-color: var(--bg-tertiary);
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background-color: var(--color-primary);
  border-radius: 3px;
  transition: width 0.3s ease;
}

.progress-text {
  font-size: 0.75rem;
  color: var(--text-tertiary);
  text-align: center;
}

.loading-actions {
  margin-top: 0.5rem;
}

/* Skeleton loading animation */
.skeleton {
  background: linear-gradient(90deg, var(--bg-secondary) 25%, var(--bg-tertiary) 50%, var(--bg-secondary) 75%);
  background-size: 200% 100%;
  animation: skeleton-loading 1.5s infinite;
}

@keyframes skeleton-loading {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

/* Pulse animation for simple loading states */
.pulse {
  animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
}

@keyframes pulse {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0.5;
  }
}
</style>