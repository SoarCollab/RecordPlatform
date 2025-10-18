import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export interface UserPreferences {
  // Appearance
  theme: 'light' | 'dark' | 'auto'
  language: string
  fontSize: 'small' | 'medium' | 'large'
  
  // Dashboard
  defaultDashboard: string
  autoRefresh: boolean
  refreshInterval: number // seconds
  showWelcomeMessage: boolean
  
  // Notifications
  enableNotifications: boolean
  notificationSound: boolean
  emailNotifications: boolean
  pushNotifications: boolean
  
  // Data & Performance
  dataRetention: number // days
  maxDataPoints: number
  enableCompression: boolean
  
  // Accessibility
  highContrast: boolean
  reducedMotion: boolean
  screenReader: boolean
  
  // Advanced
  developerMode: boolean
  debugMode: boolean
  experimentalFeatures: boolean
}

export interface DashboardLayout {
  id: string
  name: string
  isDefault: boolean
  widgets: Array<{
    id: string
    type: string
    position: { x: number; y: number; width: number; height: number }
    config: Record<string, any>
  }>
}

export const useSettingsStore = defineStore('settings', () => {
  const preferences = ref<UserPreferences>({
    // Appearance
    theme: 'auto',
    language: 'en',
    fontSize: 'medium',
    
    // Dashboard
    defaultDashboard: 'main',
    autoRefresh: true,
    refreshInterval: 30,
    showWelcomeMessage: true,
    
    // Notifications
    enableNotifications: true,
    notificationSound: true,
    emailNotifications: true,
    pushNotifications: false,
    
    // Data & Performance
    dataRetention: 30,
    maxDataPoints: 100,
    enableCompression: true,
    
    // Accessibility
    highContrast: false,
    reducedMotion: false,
    screenReader: false,
    
    // Advanced
    developerMode: false,
    debugMode: false,
    experimentalFeatures: false
  })

  const dashboardLayouts = ref<DashboardLayout[]>([
    {
      id: 'main',
      name: 'Main Dashboard',
      isDefault: true,
      widgets: []
    }
  ])

  const isLoading = ref(false)
  const error = ref<string | null>(null)

  // Load preferences from localStorage
  const loadPreferences = (): void => {
    try {
      const saved = localStorage.getItem('user_preferences')
      if (saved) {
        const parsed = JSON.parse(saved)
        preferences.value = { ...preferences.value, ...parsed }
      }
    } catch (err) {
      console.error('Failed to load preferences:', err)
    }
  }

  // Save preferences to localStorage
  const savePreferences = (): void => {
    try {
      localStorage.setItem('user_preferences', JSON.stringify(preferences.value))
    } catch (err) {
      console.error('Failed to save preferences:', err)
    }
  }

  // Load dashboard layouts
  const loadDashboardLayouts = (): void => {
    try {
      const saved = localStorage.getItem('dashboard_layouts')
      if (saved) {
        dashboardLayouts.value = JSON.parse(saved)
      }
    } catch (err) {
      console.error('Failed to load dashboard layouts:', err)
    }
  }

  // Save dashboard layouts
  const saveDashboardLayouts = (): void => {
    try {
      localStorage.setItem('dashboard_layouts', JSON.stringify(dashboardLayouts.value))
    } catch (err) {
      console.error('Failed to save dashboard layouts:', err)
    }
  }

  // Update a specific preference
  const updatePreference = <K extends keyof UserPreferences>(
    key: K, 
    value: UserPreferences[K]
  ): void => {
    preferences.value[key] = value
    savePreferences()
  }

  // Update multiple preferences
  const updatePreferences = (updates: Partial<UserPreferences>): void => {
    Object.assign(preferences.value, updates)
    savePreferences()
  }

  // Reset preferences to defaults
  const resetPreferences = (): void => {
    const defaults: UserPreferences = {
      theme: 'auto',
      language: 'en',
      fontSize: 'medium',
      defaultDashboard: 'main',
      autoRefresh: true,
      refreshInterval: 30,
      showWelcomeMessage: true,
      enableNotifications: true,
      notificationSound: true,
      emailNotifications: true,
      pushNotifications: false,
      dataRetention: 30,
      maxDataPoints: 100,
      enableCompression: true,
      highContrast: false,
      reducedMotion: false,
      screenReader: false,
      developerMode: false,
      debugMode: false,
      experimentalFeatures: false
    }
    
    preferences.value = defaults
    savePreferences()
  }

  // Dashboard layout management
  const createDashboardLayout = (name: string): DashboardLayout => {
    const layout: DashboardLayout = {
      id: `dashboard_${Date.now()}`,
      name,
      isDefault: false,
      widgets: []
    }
    
    dashboardLayouts.value.push(layout)
    saveDashboardLayouts()
    return layout
  }

  const updateDashboardLayout = (id: string, updates: Partial<DashboardLayout>): void => {
    const index = dashboardLayouts.value.findIndex(layout => layout.id === id)
    if (index !== -1) {
      dashboardLayouts.value[index] = { ...dashboardLayouts.value[index], ...updates }
      saveDashboardLayouts()
    }
  }

  const deleteDashboardLayout = (id: string): void => {
    const index = dashboardLayouts.value.findIndex(layout => layout.id === id)
    if (index !== -1 && !dashboardLayouts.value[index].isDefault) {
      dashboardLayouts.value.splice(index, 1)
      saveDashboardLayouts()
    }
  }

  const setDefaultDashboard = (id: string): void => {
    dashboardLayouts.value.forEach(layout => {
      layout.isDefault = layout.id === id
    })
    preferences.value.defaultDashboard = id
    saveDashboardLayouts()
    savePreferences()
  }

  // Export/Import settings
  const exportSettings = (): string => {
    return JSON.stringify({
      preferences: preferences.value,
      dashboardLayouts: dashboardLayouts.value,
      exportedAt: new Date().toISOString(),
      version: '1.0'
    }, null, 2)
  }

  const importSettings = (settingsJson: string): void => {
    try {
      const imported = JSON.parse(settingsJson)
      
      if (imported.preferences) {
        preferences.value = { ...preferences.value, ...imported.preferences }
        savePreferences()
      }
      
      if (imported.dashboardLayouts) {
        dashboardLayouts.value = imported.dashboardLayouts
        saveDashboardLayouts()
      }
    } catch (err) {
      throw new Error('Invalid settings file format')
    }
  }

  // Apply theme changes to document
  const applyTheme = (): void => {
    const theme = preferences.value.theme
    let isDark = false
    
    if (theme === 'dark') {
      isDark = true
    } else if (theme === 'auto') {
      isDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    }
    
    document.documentElement.classList.toggle('dark', isDark)
  }

  // Apply accessibility settings
  const applyAccessibilitySettings = (): void => {
    document.documentElement.classList.toggle('high-contrast', preferences.value.highContrast)
    document.documentElement.classList.toggle('reduced-motion', preferences.value.reducedMotion)
    
    // Update font size
    const fontSizeMap = {
      small: '14px',
      medium: '16px',
      large: '18px'
    }
    document.documentElement.style.fontSize = fontSizeMap[preferences.value.fontSize]
  }

  // Watch for preference changes and apply them
  watch(() => preferences.value.theme, applyTheme, { immediate: true })
  watch(() => preferences.value.highContrast, applyAccessibilitySettings)
  watch(() => preferences.value.reducedMotion, applyAccessibilitySettings)
  watch(() => preferences.value.fontSize, applyAccessibilitySettings)

  // Initialize
  loadPreferences()
  loadDashboardLayouts()
  applyTheme()
  applyAccessibilitySettings()

  return {
    preferences,
    dashboardLayouts,
    isLoading,
    error,
    updatePreference,
    updatePreferences,
    resetPreferences,
    createDashboardLayout,
    updateDashboardLayout,
    deleteDashboardLayout,
    setDefaultDashboard,
    exportSettings,
    importSettings,
    applyTheme,
    applyAccessibilitySettings
  }
})