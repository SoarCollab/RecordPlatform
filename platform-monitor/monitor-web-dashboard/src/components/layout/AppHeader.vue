<template>
  <header class="app-header">
    <div class="header-left">
      <button class="menu-toggle" @click="toggleSidebar">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="3" y1="6" x2="21" y2="6"></line>
          <line x1="3" y1="12" x2="21" y2="12"></line>
          <line x1="3" y1="18" x2="21" y2="18"></line>
        </svg>
      </button>
      <h1 class="app-title">Monitor Dashboard</h1>
    </div>

    <div class="header-center">
      <div class="search-container">
        <input
          type="text"
          placeholder="Search clients, metrics..."
          class="search-input"
          v-model="searchQuery"
          @keyup.enter="performSearch"
        />
        <button class="search-button" @click="performSearch">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"></circle>
            <path d="m21 21-4.35-4.35"></path>
          </svg>
        </button>
      </div>
    </div>

    <div class="header-right">
      <div class="connection-status" :class="{ 'connected': isWebSocketConnected }">
        <div class="status-indicator"></div>
        <span class="status-text">{{ connectionStatusText }}</span>
      </div>

      <button class="theme-toggle" @click="toggleTheme" :title="themeToggleTitle">
        <svg v-if="isDarkMode" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="5"></circle>
          <line x1="12" y1="1" x2="12" y2="3"></line>
          <line x1="12" y1="21" x2="12" y2="23"></line>
          <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line>
          <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line>
          <line x1="1" y1="12" x2="3" y2="12"></line>
          <line x1="21" y1="12" x2="23" y2="12"></line>
          <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line>
          <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>
        </svg>
        <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>
        </svg>
      </button>

      <div class="user-menu" ref="userMenuRef">
        <button class="user-button" @click="toggleUserMenu">
          <div class="user-avatar">
            {{ userInitials }}
          </div>
          <span class="user-name">{{ user?.username }}</span>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="6,9 12,15 18,9"></polyline>
          </svg>
        </button>

        <div v-if="showUserMenu" class="user-dropdown">
          <router-link to="/settings" class="dropdown-item" @click="closeUserMenu">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="3"></circle>
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1 1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
            </svg>
            Settings
          </router-link>
          <button class="dropdown-item" @click="handleLogout">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path>
              <polyline points="16,17 21,12 16,7"></polyline>
              <line x1="21" y1="12" x2="9" y2="12"></line>
            </svg>
            Logout
          </button>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useWebSocketStore } from '@/stores/websocket'
import { onClickOutside } from '@vueuse/core'

const router = useRouter()
const authStore = useAuthStore()
const themeStore = useThemeStore()
const webSocketStore = useWebSocketStore()

const searchQuery = ref('')
const showUserMenu = ref(false)
const userMenuRef = ref<HTMLElement>()

const user = computed(() => authStore.user)
const isDarkMode = computed(() => themeStore.isDarkMode)
const isWebSocketConnected = computed(() => webSocketStore.isConnected)

const userInitials = computed(() => {
  if (!user.value) return 'U'
  return user.value.username.substring(0, 2).toUpperCase()
})

const themeToggleTitle = computed(() => 
  isDarkMode.value ? 'Switch to light mode' : 'Switch to dark mode'
)

const connectionStatusText = computed(() => 
  isWebSocketConnected.value ? 'Connected' : 'Disconnected'
)

const toggleSidebar = () => {
  // Emit event to parent or use a global state
  document.body.classList.toggle('sidebar-collapsed')
}

const toggleTheme = () => {
  themeStore.toggleTheme()
}

const toggleUserMenu = () => {
  showUserMenu.value = !showUserMenu.value
}

const closeUserMenu = () => {
  showUserMenu.value = false
}

const performSearch = () => {
  if (searchQuery.value.trim()) {
    router.push({ 
      name: 'clients', 
      query: { search: searchQuery.value.trim() } 
    })
  }
}

const handleLogout = async () => {
  try {
    await authStore.logout()
    router.push('/login')
  } catch (error) {
    console.error('Logout failed:', error)
  }
}

// Close user menu when clicking outside
onClickOutside(userMenuRef, closeUserMenu)

onMounted(() => {
  // Connect to WebSocket when header mounts
  if (authStore.isAuthenticated) {
    webSocketStore.connect()
  }
})

onUnmounted(() => {
  webSocketStore.disconnect()
})
</script>

<style scoped>
.app-header {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 60px;
  background-color: var(--bg-primary);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 1rem;
  z-index: 1000;
  box-shadow: var(--shadow-sm);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.menu-toggle {
  display: none;
  background: none;
  border: none;
  color: var(--text-primary);
  cursor: pointer;
  padding: 0.5rem;
  border-radius: var(--radius-md);
  transition: background-color 0.2s ease;
}

.menu-toggle:hover {
  background-color: var(--bg-tertiary);
}

.app-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.header-center {
  flex: 1;
  max-width: 400px;
  margin: 0 2rem;
}

.search-container {
  position: relative;
  display: flex;
  align-items: center;
}

.search-input {
  width: 100%;
  padding: 0.5rem 2.5rem 0.5rem 1rem;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  background-color: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.875rem;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.search-input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgb(59 130 246 / 0.1);
}

.search-button {
  position: absolute;
  right: 0.5rem;
  background: none;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0.25rem;
  border-radius: var(--radius-sm);
  transition: color 0.2s ease;
}

.search-button:hover {
  color: var(--text-primary);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.connection-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0.75rem;
  border-radius: var(--radius-lg);
  background-color: var(--bg-secondary);
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.status-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: var(--color-error);
  transition: background-color 0.2s ease;
}

.connection-status.connected .status-indicator {
  background-color: var(--color-success);
}

.theme-toggle {
  background: none;
  border: none;
  color: var(--text-primary);
  cursor: pointer;
  padding: 0.5rem;
  border-radius: var(--radius-md);
  transition: background-color 0.2s ease;
}

.theme-toggle:hover {
  background-color: var(--bg-tertiary);
}

.user-menu {
  position: relative;
}

.user-button {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  background: none;
  border: none;
  color: var(--text-primary);
  cursor: pointer;
  padding: 0.5rem;
  border-radius: var(--radius-md);
  transition: background-color 0.2s ease;
}

.user-button:hover {
  background-color: var(--bg-tertiary);
}

.user-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background-color: var(--color-primary);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.75rem;
  font-weight: 600;
}

.user-name {
  font-size: 0.875rem;
  font-weight: 500;
}

.user-dropdown {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 0.5rem;
  background-color: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  min-width: 180px;
  z-index: 1001;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  padding: 0.75rem 1rem;
  background: none;
  border: none;
  color: var(--text-primary);
  font-size: 0.875rem;
  text-decoration: none;
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.dropdown-item:hover {
  background-color: var(--bg-secondary);
}

.dropdown-item:first-child {
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
}

.dropdown-item:last-child {
  border-radius: 0 0 var(--radius-lg) var(--radius-lg);
}

@media (max-width: 768px) {
  .menu-toggle {
    display: block;
  }

  .header-center {
    display: none;
  }

  .user-name {
    display: none;
  }

  .connection-status .status-text {
    display: none;
  }
}
</style>