<template>
  <div v-if="hasError" class="error-boundary">
    <div class="error-container">
      <div class="error-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10"></circle>
          <line x1="15" y1="9" x2="9" y2="15"></line>
          <line x1="9" y1="9" x2="15" y2="15"></line>
        </svg>
      </div>
      
      <div class="error-content">
        <h2 class="error-title">{{ errorTitle }}</h2>
        <p class="error-message">{{ errorMessage }}</p>
        
        <div v-if="showDetails && errorDetails" class="error-details">
          <button 
            class="details-toggle" 
            @click="showErrorDetails = !showErrorDetails"
          >
            {{ showErrorDetails ? 'Hide' : 'Show' }} Details
            <svg 
              width="16" 
              height="16" 
              viewBox="0 0 24 24" 
              fill="none" 
              stroke="currentColor" 
              stroke-width="2"
              :class="{ 'rotate-180': showErrorDetails }"
            >
              <polyline points="6,9 12,15 18,9"></polyline>
            </svg>
          </button>
          
          <div v-if="showErrorDetails" class="error-stack">
            <pre>{{ errorDetails }}</pre>
          </div>
        </div>
        
        <div class="error-actions">
          <button class="btn btn-primary" @click="retry">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="23,4 23,10 17,10"></polyline>
              <polyline points="1,20 1,14 7,14"></polyline>
              <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15"></path>
            </svg>
            Try Again
          </button>
          
          <button class="btn btn-secondary" @click="goHome">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path>
              <polyline points="9,22 9,12 15,12 15,22"></polyline>
            </svg>
            Go to Dashboard
          </button>
          
          <button v-if="canReport" class="btn btn-secondary" @click="reportError">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
              <polyline points="14,2 14,8 20,8"></polyline>
              <line x1="16" y1="13" x2="8" y2="13"></line>
              <line x1="16" y1="17" x2="8" y2="17"></line>
              <polyline points="10,9 9,9 8,9"></polyline>
            </svg>
            Report Issue
          </button>
        </div>
      </div>
    </div>
  </div>
  
  <slot v-else></slot>
</template>

<script setup lang="ts">
import { ref, onErrorCaptured } from 'vue'
import { useRouter } from 'vue-router'

interface Props {
  showDetails?: boolean
  canReport?: boolean
  fallbackTitle?: string
  fallbackMessage?: string
}

const props = withDefaults(defineProps<Props>(), {
  showDetails: true,
  canReport: true,
  fallbackTitle: 'Something went wrong',
  fallbackMessage: 'An unexpected error occurred. Please try again or contact support if the problem persists.'
})

const emit = defineEmits<{
  error: [error: Error]
  retry: []
  report: [error: Error, errorInfo: any]
}>()

const router = useRouter()

const hasError = ref(false)
const errorTitle = ref('')
const errorMessage = ref('')
const errorDetails = ref('')
const showErrorDetails = ref(false)
const currentError = ref<Error | null>(null)

const getErrorTitle = (error: Error): string => {
  if (error.name === 'ChunkLoadError') {
    return 'Loading Error'
  }
  if (error.name === 'NetworkError') {
    return 'Network Error'
  }
  if (error.message?.includes('fetch')) {
    return 'Connection Error'
  }
  return props.fallbackTitle
}

const getErrorMessage = (error: Error): string => {
  if (error.name === 'ChunkLoadError') {
    return 'Failed to load application resources. This might be due to a network issue or an application update.'
  }
  if (error.name === 'NetworkError') {
    return 'Unable to connect to the server. Please check your internet connection and try again.'
  }
  if (error.message?.includes('fetch')) {
    return 'Failed to communicate with the server. Please check your connection and try again.'
  }
  if (error.message) {
    return error.message
  }
  return props.fallbackMessage
}

const handleError = (error: Error, errorInfo?: any) => {
  console.error('Error caught by boundary:', error, errorInfo)
  
  hasError.value = true
  currentError.value = error
  errorTitle.value = getErrorTitle(error)
  errorMessage.value = getErrorMessage(error)
  errorDetails.value = error.stack || error.toString()
  
  emit('error', error)
}

const retry = () => {
  hasError.value = false
  currentError.value = null
  errorTitle.value = ''
  errorMessage.value = ''
  errorDetails.value = ''
  showErrorDetails.value = false
  
  emit('retry')
  
  // Force a re-render by reloading the page for chunk load errors
  if (currentError.value?.name === 'ChunkLoadError') {
    window.location.reload()
  }
}

const goHome = () => {
  hasError.value = false
  router.push('/')
}

const reportError = () => {
  if (currentError.value) {
    emit('report', currentError.value, {
      url: window.location.href,
      userAgent: navigator.userAgent,
      timestamp: new Date().toISOString()
    })
    
    // Could also send to an error reporting service here
    console.log('Error reported:', currentError.value)
  }
}

// Catch Vue errors
onErrorCaptured((error: Error, instance, info) => {
  handleError(error, { instance, info })
  return false // Prevent the error from propagating further
})

// Catch global JavaScript errors
if (typeof window !== 'undefined') {
  window.addEventListener('error', (event) => {
    handleError(event.error || new Error(event.message))
  })
  
  window.addEventListener('unhandledrejection', (event) => {
    handleError(new Error(event.reason?.message || 'Unhandled promise rejection'))
  })
}

// Expose method to manually trigger error handling
defineExpose({
  handleError
})
</script>

<style scoped>
.error-boundary {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2rem;
  background-color: var(--bg-secondary);
}

.error-container {
  max-width: 600px;
  width: 100%;
  background-color: var(--bg-primary);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
  padding: 3rem;
  text-align: center;
}

.error-icon {
  color: var(--color-error);
  margin-bottom: 1.5rem;
  display: flex;
  justify-content: center;
}

.error-content {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.error-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.error-message {
  font-size: 1rem;
  color: var(--text-secondary);
  line-height: 1.6;
  margin: 0;
}

.error-details {
  text-align: left;
}

.details-toggle {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  background: none;
  border: none;
  color: var(--color-primary);
  cursor: pointer;
  font-size: 0.875rem;
  font-weight: 500;
  padding: 0.5rem 0;
  transition: color 0.2s ease;
}

.details-toggle:hover {
  color: var(--color-primary-dark);
}

.details-toggle svg {
  transition: transform 0.2s ease;
}

.rotate-180 {
  transform: rotate(180deg);
}

.error-stack {
  margin-top: 1rem;
  padding: 1rem;
  background-color: var(--bg-secondary);
  border-radius: var(--radius-md);
  border: 1px solid var(--border-color);
  max-height: 200px;
  overflow-y: auto;
}

.error-stack pre {
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 0.75rem;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}

.error-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  justify-content: center;
  margin-top: 1rem;
}

.error-actions .btn {
  min-width: 120px;
}

@media (max-width: 640px) {
  .error-container {
    padding: 2rem;
  }
  
  .error-actions {
    flex-direction: column;
  }
  
  .error-actions .btn {
    width: 100%;
  }
}
</style>