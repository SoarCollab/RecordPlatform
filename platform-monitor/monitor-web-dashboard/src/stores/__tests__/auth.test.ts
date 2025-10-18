import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '../auth'
import type { AuthResponse, User } from '@/types/auth'

// Mock the API
vi.mock('@/services/api', () => ({
  authApi: {
    login: vi.fn(),
    logout: vi.fn(),
    refreshToken: vi.fn(),
    getProfile: vi.fn(),
  }
}))

describe('Auth Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('should initialize with default state', () => {
    const authStore = useAuthStore()
    
    expect(authStore.user).toBeNull()
    expect(authStore.token).toBeNull()
    expect(authStore.isLoading).toBe(false)
    expect(authStore.error).toBeNull()
    expect(authStore.isAuthenticated).toBe(false)
  })

  it('should load token from localStorage on initialization', () => {
    const mockToken = 'mock-token'
    localStorage.setItem('auth_token', mockToken)
    
    const authStore = useAuthStore()
    
    expect(authStore.token).toBe(mockToken)
  })

  it('should handle successful login', async () => {
    const { authApi } = await import('@/services/api')
    const mockUser: User = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      roles: ['user'],
      permissions: ['read'],
      mfaEnabled: false,
      createdAt: '2024-01-01T00:00:00Z'
    }
    
    const mockResponse: AuthResponse = {
      token: 'mock-token',
      user: mockUser,
      expiresIn: 3600
    }

    vi.mocked(authApi.login).mockResolvedValue(mockResponse)

    const authStore = useAuthStore()
    await authStore.login({ username: 'testuser', password: 'password' })

    expect(authStore.user).toEqual(mockUser)
    expect(authStore.token).toBe('mock-token')
    expect(authStore.isAuthenticated).toBe(true)
    expect(localStorage.getItem('auth_token')).toBe('mock-token')
  })

  it('should handle login failure', async () => {
    const { authApi } = await import('@/services/api')
    const mockError = new Error('Invalid credentials')
    
    vi.mocked(authApi.login).mockRejectedValue(mockError)

    const authStore = useAuthStore()
    
    await expect(authStore.login({ username: 'testuser', password: 'wrong' }))
      .rejects.toThrow('Invalid credentials')
    
    expect(authStore.user).toBeNull()
    expect(authStore.token).toBeNull()
    expect(authStore.error).toBe('Invalid credentials')
  })

  it('should handle logout', async () => {
    const { authApi } = await import('@/services/api')
    vi.mocked(authApi.logout).mockResolvedValue()

    const authStore = useAuthStore()
    
    // Set initial state
    authStore.user = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      roles: ['user'],
      permissions: ['read'],
      mfaEnabled: false,
      createdAt: '2024-01-01T00:00:00Z'
    }
    authStore.token = 'mock-token'
    localStorage.setItem('auth_token', 'mock-token')

    await authStore.logout()

    expect(authStore.user).toBeNull()
    expect(authStore.token).toBeNull()
    expect(authStore.isAuthenticated).toBe(false)
    expect(localStorage.getItem('auth_token')).toBeNull()
  })

  it('should handle token refresh', async () => {
    const { authApi } = await import('@/services/api')
    const mockResponse = { token: 'new-token', expiresIn: 3600 }
    
    vi.mocked(authApi.refreshToken).mockResolvedValue(mockResponse)
    localStorage.setItem('refresh_token', 'refresh-token')

    const authStore = useAuthStore()
    await authStore.refreshToken()

    expect(authStore.token).toBe('new-token')
    expect(localStorage.getItem('auth_token')).toBe('new-token')
  })

  it('should logout on refresh token failure', async () => {
    const { authApi } = await import('@/services/api')
    vi.mocked(authApi.refreshToken).mockRejectedValue(new Error('Token expired'))

    const authStore = useAuthStore()
    authStore.token = 'old-token'
    
    await expect(authStore.refreshToken()).rejects.toThrow('Token expired')
    
    expect(authStore.token).toBeNull()
    expect(authStore.user).toBeNull()
  })
})