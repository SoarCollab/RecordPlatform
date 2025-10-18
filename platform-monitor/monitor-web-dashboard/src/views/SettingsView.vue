<template>
  <div class="settings-view">
    <div class="settings-header">
      <h1 class="settings-title">Settings</h1>
      <div class="settings-actions">
        <button class="btn btn-secondary" @click="exportSettings">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
            <polyline points="7,10 12,15 17,10"></polyline>
            <line x1="12" y1="15" x2="12" y2="3"></line>
          </svg>
          Export Settings
        </button>
        <button class="btn btn-secondary" @click="showImportModal = true">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
            <polyline points="17,10 12,5 7,10"></polyline>
            <line x1="12" y1="5" x2="12" y2="15"></line>
          </svg>
          Import Settings
        </button>
      </div>
    </div>

    <div class="settings-content">
      <div class="settings-sidebar">
        <nav class="settings-nav">
          <button
            v-for="section in settingSections"
            :key="section.id"
            class="nav-item"
            :class="{ 'active': activeSection === section.id }"
            @click="activeSection = section.id"
          >
            <component :is="section.icon" class="nav-icon" />
            <span class="nav-text">{{ section.name }}</span>
          </button>
        </nav>
      </div>

      <div class="settings-main">
        <!-- Appearance Settings -->
        <div v-if="activeSection === 'appearance'" class="settings-section">
          <h2 class="section-title">Appearance</h2>
          
          <div class="setting-group">
            <label class="setting-label">Theme</label>
            <div class="theme-options">
              <label
                v-for="theme in themeOptions"
                :key="theme.value"
                class="theme-option"
                :class="{ 'selected': preferences.theme === theme.value }"
              >
                <input
                  v-model="preferences.theme"
                  type="radio"
                  :value="theme.value"
                  class="theme-radio"
                  @change="updatePreference('theme', theme.value)"
                />
                <div class="theme-preview" :class="theme.value">
                  <div class="theme-header"></div>
                  <div class="theme-content"></div>
                </div>
                <span class="theme-name">{{ theme.name }}</span>
              </label>
            </div>
          </div>

          <div class="setting-group">
            <label class="setting-label">Font Size</label>
            <select
              v-model="preferences.fontSize"
              class="setting-select"
              @change="updatePreference('fontSize', preferences.fontSize)"
            >
              <option value="small">Small</option>
              <option value="medium">Medium</option>
              <option value="large">Large</option>
            </select>
          </div>

          <div class="setting-group">
            <label class="setting-label">Language</label>
            <select
              v-model="preferences.language"
              class="setting-select"
              @change="updatePreference('language', preferences.language)"
            >
              <option value="en">English</option>
              <option value="es">Español</option>
              <option value="fr">Français</option>
              <option value="de">Deutsch</option>
              <option value="zh">中文</option>
            </select>
          </div>
        </div>

        <!-- Dashboard Settings -->
        <div v-if="activeSection === 'dashboard'" class="settings-section">
          <h2 class="section-title">Dashboard</h2>
          
          <div class="setting-group">
            <label class="checkbox-label">
              <input
                v-model="preferences.autoRefresh"
                type="checkbox"
                class="checkbox"
                @change="updatePreference('autoRefresh', preferences.autoRefresh)"
              />
              <span class="checkbox-text">Auto-refresh dashboard</span>
            </label>
          </div>

          <div class="setting-group" v-if="preferences.autoRefresh">
            <label class="setting-label">Refresh Interval</label>
            <div class="input-with-unit">
              <input
                v-model.number="preferences.refreshInterval"
                type="number"
                min="5"
                max="300"
                class="setting-input"
                @change="updatePreference('refreshInterval', preferences.refreshInterval)"
              />
              <span class="input-unit">seconds</span>
            </div>
          </div>

          <div class="setting-group">
            <label class="checkbox-label">
              <input
                v-model="preferences.showWelcomeMessage"
                type="checkbox"
                class="checkbox"
                @change="updatePreference('showWelcomeMessage', preferences.showWelcomeMessage)"
              />
              <span class="checkbox-text">Show welcome message</span>
            </label>
          </div>

          <div class="setting-group">
            <label class="setting-label">Default Dashboard</label>
            <select
              v-model="preferences.defaultDashboard"
              class="setting-select"
              @change="updatePreference('defaultDashboard', preferences.defaultDashboard)"
            >
              <option
                v-for="layout in dashboardLayouts"
                :key="layout.id"
                :value="layout.id"
              >
                {{ layout.name }}
              </option>
            </select>
          </div>
        </div>

        <!-- Notifications Settings -->
        <div v-if="activeSection === 'notifications'" class="settings-section">
          <h2 class="section-title">Notifications</h2>
          
          <div class="setting-group">
            <label class="checkbox-label">
              <input
                v-model="preferences.enableNotifications"
                type="checkbox"
                class="checkbox"
                @change="updatePreference('enableNotifications', preferences.enableNotifications)"
              />
              <span class="checkbox-text">Enable notifications</span>
            </label>
          </div>

          <div v-if="preferences.enableNotifications" class="notification-settings">
            <div class="setting-group">
              <label class="checkbox-label">
                <input
                  v-model="preferences.notificationSound"
                  type="checkbox"
                  class="checkbox"
                  @change="updatePreference('notificationSound', preferences.notificationSound)"
                />
                <span class="checkbox-text">Play notification sounds</span>
              </label>
            </div>

            <div class="setting-group">
              <label class="checkbox-label">
                <input
                  v-model="preferences.emailNotifications"
                  type="checkbox"
                  class="checkbox"
                  @change="updatePreference('emailNotifications', preferences.emailNotifications)"
                />
                <span class="checkbox-text">Email notifications</span>
              </label>
            </div>

            <div class="setting-group">
              <label class="checkbox-label">
                <input
                  v-model="preferences.pushNotifications"
                  type="checkbox"
                  class="checkbox"
                  @change="updatePreference('pushNotifications', preferences.pushNotifications)"
                />
                <span class="checkbox-text">Browser push notifications</span>
              </label>
            </div>
          </div>
        </div>

        <!-- Data & Performance Settings -->
        <div v-if="activeSection === 'data'" class="settings-section">
          <h2 class="section-title">Data & Performance</h2>
          
          <div class="setting-group">
            <label class="setting-label">Data Retention</label>
            <div class="input-with-unit">
              <input
                v-model.number="preferences.dataRetention"
                type="number"
                min="1"
                max="365"
                class="setting-input"
                @change="updatePreference('dataRetention', preferences.dataRetention)"
              />
              <span class="input-unit">days</span>
            </div>
          </div>

          <div class="setting-group">
            <label class="setting-label">Max Data Points per Chart</label>
            <input
              v-model.number="preferences.maxDataPoints"
              type="number"
              min="10"
              max="1000"
              class="setting-input"
              @change="updatePreference('maxDataPoints', preferences.maxDataPoints)"
            />
          </div>

          <div class="setting-group">
            <label class="checkbox-label">
              <input
                v-model="preferences.enableCompression"
                type="checkbox"
                class="checkbox"
                @change="updatePreference('enableCompression', preferences.enableCompression)"
              />
              <span class="checkbox-text">Enable data compression</span>
            </label>
          </div>
        </div>

        <!-- Accessibility Settings -->
        <div v-if="activeSection === 'accessibility'" class="settings-section">
          <h2 class="section-title">Accessibility</h2>
          
          <div class="setting-group">
            <label class="checkbox-label">
              <input
                v-model="preferences.highContrast"
                type="checkbox"
                class="checkbox"
                @change="updatePreference('highContrast', preferences.highContrast)"
              />
              <span class="checkbox-text">High contrast mode</span>
            </label>
          </div>

          <div class="setting-group">
            <label class="checkbox-label">
              <input
                v-model="preferences.reducedMotion"
                type="checkbox"
                class="checkbox"
                @change="updatePreference('reducedMotion', preferences.reducedMotion)"
              />
              <span class="checkbox-text">Reduce motion and animations</span>
            </label>
          </div>

          <div class="setting-group">
            <label class="checkbox-label">
              <input
                v-model="preferences.screenReader"
                type="checkbox"
                class="checkbox"
                @change="updatePreference('screenReader', preferences.screenReader)"
              />
              <span class="checkbox-text">Screen reader optimizations</span>
            </label>
          </div>
        </div>

        <!-- Account & Security Settings -->
        <div v-if="activeSection === 'account'" class="settings-section">
          <h2 class="section-title">Account & Security</h2>
          
          <div class="setting-group">
            <h3 class="subsection-title">Profile Information</h3>
            <div class="profile-info">
              <div class="profile-avatar">
                <div class="avatar-circle">
                  {{ userInitials }}
                </div>
                <button class="btn btn-secondary btn-sm">Change Avatar</button>
              </div>
              <div class="profile-details">
                <div class="detail-item">
                  <label class="detail-label">Username</label>
                  <span class="detail-value">{{ user?.username }}</span>
                </div>
                <div class="detail-item">
                  <label class="detail-label">Email</label>
                  <span class="detail-value">{{ user?.email || 'Not set' }}</span>
                </div>
                <div class="detail-item">
                  <label class="detail-label">Role</label>
                  <span class="detail-value">{{ user?.role || 'User' }}</span>
                </div>
                <div class="detail-item">
                  <label class="detail-label">Last Login</label>
                  <span class="detail-value">{{ formatLastLogin(user?.lastLogin) }}</span>
                </div>
              </div>
            </div>
          </div>

          <div class="setting-group">
            <h3 class="subsection-title">Active Sessions</h3>
            <div class="sessions-list">
              <div
                v-for="session in activeSessions"
                :key="session.id"
                class="session-item"
                :class="{ 'current-session': session.isCurrent }"
              >
                <div class="session-info">
                  <div class="session-device">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <rect v-if="session.deviceType === 'desktop'" x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
                      <rect v-else-if="session.deviceType === 'tablet'" x="4" y="2" width="16" height="20" rx="2" ry="2"></rect>
                      <rect v-else x="5" y="2" width="14" height="20" rx="2" ry="2"></rect>
                    </svg>
                    <span class="device-name">{{ session.deviceName }}</span>
                    <span v-if="session.isCurrent" class="current-badge">Current</span>
                  </div>
                  <div class="session-details">
                    <span class="session-location">{{ session.location }}</span>
                    <span class="session-time">{{ formatSessionTime(session.lastActive) }}</span>
                  </div>
                </div>
                <button
                  v-if="!session.isCurrent"
                  class="btn btn-secondary btn-sm"
                  @click="terminateSession(session.id)"
                >
                  Terminate
                </button>
              </div>
            </div>
            <button class="btn btn-secondary" @click="terminateAllOtherSessions">
              Terminate All Other Sessions
            </button>
          </div>

          <div class="setting-group">
            <h3 class="subsection-title">Security</h3>
            <div class="security-actions">
              <button class="btn btn-secondary" @click="showChangePasswordModal = true">
                Change Password
              </button>
              <button class="btn btn-secondary" @click="showMfaSetupModal = true">
                {{ user?.mfaEnabled ? 'Manage' : 'Enable' }} Two-Factor Authentication
              </button>
              <button class="btn btn-secondary" @click="downloadSecurityLog">
                Download Security Log
              </button>
            </div>
          </div>
        </div>

        <!-- Advanced Settings -->
        <div v-if="activeSection === 'advanced'" class="settings-section">
          <h2 class="section-title">Advanced</h2>
          
          <div class="setting-group">
            <label class="checkbox-label">
              <input
                v-model="preferences.developerMode"
                type="checkbox"
                class="checkbox"
                @change="updatePreference('developerMode', preferences.developerMode)"
              />
              <span class="checkbox-text">Developer mode</span>
            </label>
          </div>

          <div class="setting-group">
            <label class="checkbox-label">
              <input
                v-model="preferences.debugMode"
                type="checkbox"
                class="checkbox"
                @change="updatePreference('debugMode', preferences.debugMode)"
              />
              <span class="checkbox-text">Debug mode</span>
            </label>
          </div>

          <div class="setting-group">
            <label class="checkbox-label">
              <input
                v-model="preferences.experimentalFeatures"
                type="checkbox"
                class="checkbox"
                @change="updatePreference('experimentalFeatures', preferences.experimentalFeatures)"
              />
              <span class="checkbox-text">Enable experimental features</span>
            </label>
          </div>

          <div class="setting-group">
            <button class="btn btn-secondary" @click="resetAllSettings">
              Reset All Settings
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Import Settings Modal -->
    <teleport to="body">
      <div v-if="showImportModal" class="modal-overlay" @click="showImportModal = false">
        <div class="modal-content" @click.stop>
          <div class="modal-header">
            <h3>Import Settings</h3>
            <button class="modal-close" @click="showImportModal = false">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
              </svg>
            </button>
          </div>
          <div class="modal-body">
            <div class="import-area">
              <textarea
                v-model="importData"
                placeholder="Paste your exported settings JSON here..."
                class="import-textarea"
                rows="10"
              ></textarea>
            </div>
          </div>
          <div class="modal-actions">
            <button class="btn btn-secondary" @click="showImportModal = false">
              Cancel
            </button>
            <button
              class="btn btn-primary"
              @click="importSettings"
              :disabled="!importData.trim()"
            >
              Import Settings
            </button>
          </div>
        </div>
      </div>
    </teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { useAuthStore } from '@/stores/auth'
import { formatDistanceToNow } from 'date-fns'

const settingsStore = useSettingsStore()
const authStore = useAuthStore()

const activeSection = ref('appearance')
const showImportModal = ref(false)
const showChangePasswordModal = ref(false)
const showMfaSetupModal = ref(false)
const importData = ref('')
const activeSessions = ref([
  {
    id: '1',
    deviceName: 'Chrome on Windows',
    deviceType: 'desktop',
    location: 'New York, US',
    lastActive: new Date(),
    isCurrent: true
  },
  {
    id: '2',
    deviceName: 'Safari on iPhone',
    deviceType: 'mobile',
    location: 'New York, US',
    lastActive: new Date(Date.now() - 2 * 60 * 60 * 1000),
    isCurrent: false
  }
])

const preferences = computed(() => settingsStore.preferences)
const dashboardLayouts = computed(() => settingsStore.dashboardLayouts)
const user = computed(() => authStore.user)

const userInitials = computed(() => {
  if (!user.value) return 'U'
  return user.value.username.substring(0, 2).toUpperCase()
})

const settingSections = [
  {
    id: 'appearance',
    name: 'Appearance',
    icon: 'PaletteIcon'
  },
  {
    id: 'dashboard',
    name: 'Dashboard',
    icon: 'DashboardIcon'
  },
  {
    id: 'notifications',
    name: 'Notifications',
    icon: 'BellIcon'
  },
  {
    id: 'data',
    name: 'Data & Performance',
    icon: 'DatabaseIcon'
  },
  {
    id: 'accessibility',
    name: 'Accessibility',
    icon: 'AccessibilityIcon'
  },
  {
    id: 'account',
    name: 'Account & Security',
    icon: 'UserIcon'
  },
  {
    id: 'advanced',
    name: 'Advanced',
    icon: 'SettingsIcon'
  }
]

const themeOptions = [
  { value: 'light', name: 'Light' },
  { value: 'dark', name: 'Dark' },
  { value: 'auto', name: 'Auto' }
]

const updatePreference = (key: string, value: any) => {
  settingsStore.updatePreference(key as any, value)
}

const exportSettings = () => {
  const settings = settingsStore.exportSettings()
  const blob = new Blob([settings], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `monitor-settings-${new Date().toISOString().split('T')[0]}.json`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

const importSettings = () => {
  try {
    settingsStore.importSettings(importData.value)
    showImportModal.value = false
    importData.value = ''
    
    // Show success notification
    window.dispatchEvent(new CustomEvent('app:notification', {
      detail: {
        type: 'success',
        title: 'Settings Imported',
        message: 'Your settings have been imported successfully.'
      }
    }))
  } catch (error) {
    // Show error notification
    window.dispatchEvent(new CustomEvent('app:notification', {
      detail: {
        type: 'error',
        title: 'Import Failed',
        message: 'Invalid settings file format.'
      }
    }))
  }
}

const resetAllSettings = () => {
  if (confirm('Are you sure you want to reset all settings to their defaults? This action cannot be undone.')) {
    settingsStore.resetPreferences()
    
    // Show success notification
    window.dispatchEvent(new CustomEvent('app:notification', {
      detail: {
        type: 'success',
        title: 'Settings Reset',
        message: 'All settings have been reset to their defaults.'
      }
    }))
  }
}

const formatLastLogin = (lastLogin?: string): string => {
  if (!lastLogin) return 'Never'
  return formatDistanceToNow(new Date(lastLogin), { addSuffix: true })
}

const formatSessionTime = (time: Date): string => {
  return formatDistanceToNow(time, { addSuffix: true })
}

const terminateSession = async (sessionId: string) => {
  try {
    // API call to terminate session
    activeSessions.value = activeSessions.value.filter(s => s.id !== sessionId)
    
    window.dispatchEvent(new CustomEvent('app:notification', {
      detail: {
        type: 'success',
        title: 'Session Terminated',
        message: 'The session has been terminated successfully.'
      }
    }))
  } catch (error) {
    window.dispatchEvent(new CustomEvent('app:notification', {
      detail: {
        type: 'error',
        title: 'Failed to Terminate Session',
        message: 'Could not terminate the session. Please try again.'
      }
    }))
  }
}

const terminateAllOtherSessions = async () => {
  if (confirm('Are you sure you want to terminate all other sessions? This will log out all other devices.')) {
    try {
      // API call to terminate all other sessions
      activeSessions.value = activeSessions.value.filter(s => s.isCurrent)
      
      window.dispatchEvent(new CustomEvent('app:notification', {
        detail: {
          type: 'success',
          title: 'Sessions Terminated',
          message: 'All other sessions have been terminated.'
        }
      }))
    } catch (error) {
      window.dispatchEvent(new CustomEvent('app:notification', {
        detail: {
          type: 'error',
          title: 'Failed to Terminate Sessions',
          message: 'Could not terminate sessions. Please try again.'
        }
      }))
    }
  }
}

const downloadSecurityLog = () => {
  // Generate mock security log
  const securityLog = {
    user: user.value?.username,
    generatedAt: new Date().toISOString(),
    events: [
      {
        timestamp: new Date().toISOString(),
        event: 'login',
        ip: '192.168.1.100',
        userAgent: navigator.userAgent,
        success: true
      }
    ]
  }
  
  const blob = new Blob([JSON.stringify(securityLog, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `security-log-${new Date().toISOString().split('T')[0]}.json`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.settings-view {
  padding: 0;
}

.settings-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--border-color-light);
}

.settings-title {
  font-size: 2rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.settings-actions {
  display: flex;
  gap: 0.75rem;
}

.settings-content {
  display: grid;
  grid-template-columns: 250px 1fr;
  gap: 2rem;
}

.settings-sidebar {
  background-color: var(--bg-secondary);
  border-radius: var(--radius-lg);
  padding: 1rem;
  height: fit-content;
}

.settings-nav {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  background: none;
  border: none;
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  cursor: pointer;
  text-align: left;
  transition: all 0.2s ease;
  width: 100%;
}

.nav-item:hover {
  background-color: var(--bg-tertiary);
  color: var(--text-primary);
}

.nav-item.active {
  background-color: var(--color-primary);
  color: white;
}

.nav-icon {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.nav-text {
  font-size: 0.875rem;
  font-weight: 500;
}

.settings-main {
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 2rem;
}

.settings-section {
  max-width: 600px;
}

.section-title {
  font-size: 1.5rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 2rem 0;
}

.setting-group {
  margin-bottom: 2rem;
}

.setting-label {
  display: block;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.setting-select,
.setting-input {
  width: 100%;
  max-width: 300px;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background-color: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.875rem;
}

.input-with-unit {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.input-unit {
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  cursor: pointer;
  font-size: 0.875rem;
}

.checkbox {
  width: 1rem;
  height: 1rem;
  accent-color: var(--color-primary);
}

.checkbox-text {
  color: var(--text-primary);
}

.theme-options {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
}

.theme-option {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
}

.theme-radio {
  display: none;
}

.theme-preview {
  width: 80px;
  height: 60px;
  border: 2px solid var(--border-color);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: border-color 0.2s ease;
}

.theme-option.selected .theme-preview {
  border-color: var(--color-primary);
}

.theme-preview.light {
  background-color: #ffffff;
}

.theme-preview.dark {
  background-color: #111827;
}

.theme-preview.auto {
  background: linear-gradient(45deg, #ffffff 50%, #111827 50%);
}

.theme-header {
  height: 20px;
  background-color: var(--bg-secondary);
}

.theme-content {
  height: 40px;
  background-color: var(--bg-primary);
}

.theme-name {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--text-primary);
}

.notification-settings {
  margin-left: 1.5rem;
  padding-left: 1rem;
  border-left: 2px solid var(--border-color-light);
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background-color: var(--bg-primary);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
  max-width: 500px;
  width: 90%;
  max-height: 80vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.5rem;
  border-bottom: 1px solid var(--border-color);
}

.modal-header h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text-primary);
}

.modal-close {
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0.25rem;
  border-radius: var(--radius-sm);
  transition: all 0.2s ease;
}

.modal-close:hover {
  color: var(--text-primary);
  background-color: var(--bg-tertiary);
}

.modal-body {
  padding: 1.5rem;
  flex: 1;
  overflow-y: auto;
}

.import-textarea {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background-color: var(--bg-secondary);
  color: var(--text-primary);
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 0.875rem;
  resize: vertical;
}

.modal-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
  padding: 1.5rem;
  border-top: 1px solid var(--border-color);
}

/* Account & Security Styles */
.subsection-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 1rem 0;
}

.profile-info {
  display: flex;
  gap: 2rem;
  align-items: flex-start;
}

.profile-avatar {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
}

.avatar-circle {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background-color: var(--color-primary);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.5rem;
  font-weight: 600;
}

.profile-details {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.detail-label {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.detail-value {
  font-size: 0.875rem;
  color: var(--text-primary);
}

.sessions-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  margin-bottom: 1rem;
}

.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem;
  background-color: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  transition: border-color 0.2s ease;
}

.session-item.current-session {
  border-color: var(--color-primary);
  background-color: rgba(59, 130, 246, 0.05);
}

.session-info {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.session-device {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-primary);
}

.current-badge {
  background-color: var(--color-primary);
  color: white;
  font-size: 0.75rem;
  font-weight: 600;
  padding: 0.125rem 0.5rem;
  border-radius: var(--radius-sm);
}

.session-details {
  display: flex;
  gap: 1rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.security-actions {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  align-items: flex-start;
}

@media (max-width: 768px) {
  .settings-content {
    grid-template-columns: 1fr;
  }
  
  .settings-sidebar {
    order: 2;
  }
  
  .settings-main {
    order: 1;
  }
  
  .settings-nav {
    flex-direction: row;
    overflow-x: auto;
    gap: 0.5rem;
  }
  
  .nav-item {
    white-space: nowrap;
    flex-shrink: 0;
  }
  
  .theme-options {
    grid-template-columns: 1fr;
  }
}
</style>