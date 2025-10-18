<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <h1 class="login-title">Monitor Dashboard</h1>
        <p class="login-subtitle">Sign in to your account</p>
      </div>

      <form @submit.prevent="handleLogin" class="login-form">
        <div class="form-group">
          <label for="username" class="form-label">Username</label>
          <input
            id="username"
            v-model="credentials.username"
            type="text"
            class="input"
            placeholder="Enter your username"
            required
            :disabled="isLoading"
          />
        </div>

        <div class="form-group">
          <label for="password" class="form-label">Password</label>
          <input
            id="password"
            v-model="credentials.password"
            type="password"
            class="input"
            placeholder="Enter your password"
            required
            :disabled="isLoading"
          />
        </div>

        <div v-if="showMfaInput" class="form-group">
          <label for="mfaCode" class="form-label">MFA Code</label>
          <input
            id="mfaCode"
            v-model="credentials.mfaCode"
            type="text"
            class="input"
            placeholder="Enter 6-digit code"
            maxlength="6"
            required
            :disabled="isLoading"
          />
        </div>

        <div class="form-group">
          <label class="checkbox-label">
            <input
              v-model="credentials.rememberMe"
              type="checkbox"
              class="checkbox"
              :disabled="isLoading"
            />
            <span class="checkbox-text">Remember me</span>
          </label>
        </div>

        <div v-if="error" class="error-message">
          {{ error }}
        </div>

        <button
          type="submit"
          class="btn btn-primary btn-lg login-button"
          :disabled="isLoading || !isFormValid"
        >
          <svg v-if="isLoading" class="loading-spinner" width="20" height="20" viewBox="0 0 24 24">
            <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2" fill="none" stroke-dasharray="31.416" stroke-dashoffset="31.416">
              <animate attributeName="stroke-dasharray" dur="2s" values="0 31.416;15.708 15.708;0 31.416" repeatCount="indefinite"/>
              <animate attributeName="stroke-dashoffset" dur="2s" values="0;-15.708;-31.416" repeatCount="indefinite"/>
            </circle>
          </svg>
          {{ isLoading ? 'Signing in...' : 'Sign in' }}
        </button>
      </form>

      <div class="login-footer">
        <p class="footer-text">
          Need help? Contact your system administrator
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { LoginCredentials } from '@/types/auth'

const router = useRouter()
const authStore = useAuthStore()

const credentials = ref<LoginCredentials>({
  username: '',
  password: '',
  mfaCode: '',
  rememberMe: false
})

const showMfaInput = ref(false)
const error = ref<string | null>(null)

const isLoading = computed(() => authStore.isLoading)

const isFormValid = computed(() => {
  const hasBasicCredentials = credentials.value.username.trim() && credentials.value.password.trim()
  const hasMfaIfRequired = !showMfaInput.value || (credentials.value.mfaCode?.length === 6)
  return hasBasicCredentials && hasMfaIfRequired
})

const handleLogin = async () => {
  error.value = null
  
  try {
    await authStore.login(credentials.value)
    router.push('/')
  } catch (err: any) {
    if (err.message?.includes('MFA required')) {
      showMfaInput.value = true
      error.value = 'Please enter your MFA code'
    } else {
      error.value = err.message || 'Login failed. Please check your credentials.'
    }
  }
}

onMounted(() => {
  // Clear any existing auth state
  if (authStore.isAuthenticated) {
    router.push('/')
  }
})
</script>

<style scoped>
.login-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--color-primary) 0%, var(--color-primary-dark) 100%);
  padding: 1rem;
}

.login-card {
  width: 100%;
  max-width: 400px;
  background-color: var(--bg-primary);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
  padding: 2rem;
}

.login-header {
  text-align: center;
  margin-bottom: 2rem;
}

.login-title {
  font-size: 1.875rem;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.login-subtitle {
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.form-label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-primary);
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

.checkbox-text {
  color: var(--text-secondary);
}

.error-message {
  padding: 0.75rem;
  background-color: rgba(239, 68, 68, 0.1);
  border: 1px solid var(--color-error);
  border-radius: var(--radius-md);
  color: var(--color-error);
  font-size: 0.875rem;
  text-align: center;
}

.login-button {
  width: 100%;
  margin-top: 0.5rem;
}

.loading-spinner {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.login-footer {
  margin-top: 2rem;
  text-align: center;
}

.footer-text {
  font-size: 0.75rem;
  color: var(--text-tertiary);
}

@media (max-width: 480px) {
  .login-card {
    padding: 1.5rem;
  }
  
  .login-title {
    font-size: 1.5rem;
  }
}
</style>