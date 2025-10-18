import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/services/api'
import type { User, LoginCredentials, AuthResponse } from '@/types/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const token = ref<string | null>(localStorage.getItem('auth_token'))
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => !!token.value && !!user.value)

  const login = async (credentials: LoginCredentials): Promise<void> => {
    isLoading.value = true
    error.value = null
    
    try {
      const response: AuthResponse = await authApi.login(credentials)
      
      token.value = response.token
      user.value = response.user
      
      localStorage.setItem('auth_token', response.token)
      
      // Set up token refresh if needed
      if (response.refreshToken) {
        localStorage.setItem('refresh_token', response.refreshToken)
      }
    } catch (err: any) {
      error.value = err.message || 'Login failed'
      throw err
    } finally {
      isLoading.value = false
    }
  }

  const logout = async (): Promise<void> => {
    try {
      if (token.value) {
        await authApi.logout()
      }
    } catch (err) {
      console.error('Logout error:', err)
    } finally {
      user.value = null
      token.value = null
      localStorage.removeItem('auth_token')
      localStorage.removeItem('refresh_token')
    }
  }

  const refreshToken = async (): Promise<void> => {
    const refreshTokenValue = localStorage.getItem('refresh_token')
    if (!refreshTokenValue) {
      throw new Error('No refresh token available')
    }

    try {
      const response = await authApi.refreshToken(refreshTokenValue)
      token.value = response.token
      localStorage.setItem('auth_token', response.token)
    } catch (err) {
      await logout()
      throw err
    }
  }

  const fetchUserProfile = async (): Promise<void> => {
    if (!token.value) return
    
    try {
      user.value = await authApi.getProfile()
    } catch (err) {
      console.error('Failed to fetch user profile:', err)
      await logout()
    }
  }

  // Initialize user profile if token exists
  if (token.value && !user.value) {
    fetchUserProfile()
  }

  return {
    user,
    token,
    isLoading,
    error,
    isAuthenticated,
    login,
    logout,
    refreshToken,
    fetchUserProfile
  }
})