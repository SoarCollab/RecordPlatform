import axios, { AxiosInstance, AxiosResponse } from 'axios'
import type { 
  User, 
  LoginCredentials, 
  AuthResponse, 
  RefreshTokenResponse 
} from '@/types/auth'
import type { 
  Client, 
  ClientMetrics, 
  ClientHistoryQuery, 
  ClientHistoryData,
  ClientSummary 
} from '@/types/client'

class ApiClient {
  private client: AxiosInstance

  constructor() {
    this.client = axios.create({
      baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v2',
      timeout: 10000,
      headers: {
        'Content-Type': 'application/json',
      },
    })

    this.setupInterceptors()
  }

  private setupInterceptors(): void {
    // Request interceptor to add auth token
    this.client.interceptors.request.use(
      (config) => {
        const token = localStorage.getItem('auth_token')
        if (token) {
          config.headers.Authorization = `Bearer ${token}`
        }
        return config
      },
      (error) => Promise.reject(error)
    )

    // Response interceptor to handle token refresh
    this.client.interceptors.response.use(
      (response) => response,
      async (error) => {
        const originalRequest = error.config

        if (error.response?.status === 401 && !originalRequest._retry) {
          originalRequest._retry = true

          try {
            const refreshToken = localStorage.getItem('refresh_token')
            if (refreshToken) {
              const response = await this.refreshToken(refreshToken)
              localStorage.setItem('auth_token', response.token)
              originalRequest.headers.Authorization = `Bearer ${response.token}`
              return this.client(originalRequest)
            }
          } catch (refreshError) {
            // Refresh failed, redirect to login
            localStorage.removeItem('auth_token')
            localStorage.removeItem('refresh_token')
            window.location.href = '/login'
          }
        }

        return Promise.reject(error)
      }
    )
  }

  // Auth API methods
  async login(credentials: LoginCredentials): Promise<AuthResponse> {
    const response: AxiosResponse<AuthResponse> = await this.client.post('/auth/login', credentials)
    return response.data
  }

  async logout(): Promise<void> {
    await this.client.post('/auth/logout')
  }

  async refreshToken(refreshToken: string): Promise<RefreshTokenResponse> {
    const response: AxiosResponse<RefreshTokenResponse> = await this.client.post('/auth/refresh', {
      refreshToken
    })
    return response.data
  }

  async getProfile(): Promise<User> {
    const response: AxiosResponse<User> = await this.client.get('/auth/profile')
    return response.data
  }

  // Client API methods
  async getClients(): Promise<Client[]> {
    const response: AxiosResponse<Client[]> = await this.client.get('/clients')
    return response.data
  }

  async getClient(clientId: string): Promise<Client> {
    const response: AxiosResponse<Client> = await this.client.get(`/clients/${clientId}`)
    return response.data
  }

  async updateClient(clientId: string, updates: Partial<Client>): Promise<Client> {
    const response: AxiosResponse<Client> = await this.client.put(`/clients/${clientId}`, updates)
    return response.data
  }

  async deleteClient(clientId: string): Promise<void> {
    await this.client.delete(`/clients/${clientId}`)
  }

  async getClientMetrics(clientId: string, timeRange?: string): Promise<ClientMetrics> {
    const params = timeRange ? { timeRange } : {}
    const response: AxiosResponse<ClientMetrics> = await this.client.get(
      `/clients/${clientId}/metrics`,
      { params }
    )
    return response.data
  }

  async getClientHistory(query: ClientHistoryQuery): Promise<ClientHistoryData[]> {
    const response: AxiosResponse<ClientHistoryData[]> = await this.client.post(
      '/clients/history',
      query
    )
    return response.data
  }

  async getClientsSummary(): Promise<ClientSummary> {
    const response: AxiosResponse<ClientSummary> = await this.client.get('/clients/summary')
    return response.data
  }
}

// Create singleton instance
const apiClient = new ApiClient()

// Export individual API modules
export const authApi = {
  login: apiClient.login.bind(apiClient),
  logout: apiClient.logout.bind(apiClient),
  refreshToken: apiClient.refreshToken.bind(apiClient),
  getProfile: apiClient.getProfile.bind(apiClient),
}

export const clientApi = {
  getClients: apiClient.getClients.bind(apiClient),
  getClient: apiClient.getClient.bind(apiClient),
  updateClient: apiClient.updateClient.bind(apiClient),
  deleteClient: apiClient.deleteClient.bind(apiClient),
  getClientMetrics: apiClient.getClientMetrics.bind(apiClient),
  getClientHistory: apiClient.getClientHistory.bind(apiClient),
  getClientsSummary: apiClient.getClientsSummary.bind(apiClient),
}

export default apiClient