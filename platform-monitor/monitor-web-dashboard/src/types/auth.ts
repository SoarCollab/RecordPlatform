export interface User {
  id: string
  username: string
  email: string
  roles: string[]
  permissions: string[]
  mfaEnabled: boolean
  lastLogin?: string
  createdAt: string
}

export interface LoginCredentials {
  username: string
  password: string
  mfaCode?: string
  rememberMe?: boolean
}

export interface AuthResponse {
  token: string
  refreshToken?: string
  user: User
  expiresIn: number
}

export interface RefreshTokenResponse {
  token: string
  expiresIn: number
}

export interface MfaSetupResponse {
  secret: string
  qrCode: string
  backupCodes: string[]
}