import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import AppHeader from '@/components/layout/AppHeader.vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useWebSocketStore } from '@/stores/websocket'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: { template: '<div>Home</div>' } },
    { path: '/login', component: { template: '<div>Login</div>' } },
    { path: '/clients', component: { template: '<div>Clients</div>' } },
  ]
})

describe('AppHeader', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('should render correctly when authenticated', () => {
    const authStore = useAuthStore()
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

    const wrapper = mount(AppHeader, {
      global: {
        plugins: [router]
      }
    })

    expect(wrapper.find('.app-title').text()).toBe('Monitor Dashboard')
    expect(wrapper.find('.user-name').text()).toBe('testuser')
    expect(wrapper.find('.user-avatar').text()).toBe('TE')
  })

  it('should show connection status', () => {
    const authStore = useAuthStore()
    authStore.user = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      roles: ['user'],
      permissions: ['read'],
      mfaEnabled: false,
      createdAt: '2024-01-01T00:00:00Z'
    }

    const wsStore = useWebSocketStore()
    wsStore.isConnected = true

    const wrapper = mount(AppHeader, {
      global: {
        plugins: [router]
      }
    })

    expect(wrapper.find('.connection-status').classes()).toContain('connected')
    expect(wrapper.find('.status-text').text()).toBe('Connected')
  })

  it('should toggle theme', async () => {
    const authStore = useAuthStore()
    authStore.user = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      roles: ['user'],
      permissions: ['read'],
      mfaEnabled: false,
      createdAt: '2024-01-01T00:00:00Z'
    }

    const themeStore = useThemeStore()
    const toggleThemeSpy = vi.spyOn(themeStore, 'toggleTheme')

    const wrapper = mount(AppHeader, {
      global: {
        plugins: [router]
      }
    })

    await wrapper.find('.theme-toggle').trigger('click')
    expect(toggleThemeSpy).toHaveBeenCalled()
  })

  it('should handle search', async () => {
    const authStore = useAuthStore()
    authStore.user = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      roles: ['user'],
      permissions: ['read'],
      mfaEnabled: false,
      createdAt: '2024-01-01T00:00:00Z'
    }

    const wrapper = mount(AppHeader, {
      global: {
        plugins: [router]
      }
    })

    const searchInput = wrapper.find('.search-input')
    await searchInput.setValue('test query')
    await searchInput.trigger('keyup.enter')

    expect(router.currentRoute.value.name).toBe('clients')
    expect(router.currentRoute.value.query.search).toBe('test query')
  })

  it('should show and hide user menu', async () => {
    const authStore = useAuthStore()
    authStore.user = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      roles: ['user'],
      permissions: ['read'],
      mfaEnabled: false,
      createdAt: '2024-01-01T00:00:00Z'
    }

    const wrapper = mount(AppHeader, {
      global: {
        plugins: [router]
      }
    })

    expect(wrapper.find('.user-dropdown').exists()).toBe(false)

    await wrapper.find('.user-button').trigger('click')
    expect(wrapper.find('.user-dropdown').exists()).toBe(true)

    // Click outside to close (simulate)
    await wrapper.find('.user-button').trigger('click')
    expect(wrapper.find('.user-dropdown').exists()).toBe(false)
  })

  it('should handle logout', async () => {
    const authStore = useAuthStore()
    authStore.user = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      roles: ['user'],
      permissions: ['read'],
      mfaEnabled: false,
      createdAt: '2024-01-01T00:00:00Z'
    }

    const logoutSpy = vi.spyOn(authStore, 'logout').mockResolvedValue()

    const wrapper = mount(AppHeader, {
      global: {
        plugins: [router]
      }
    })

    await wrapper.find('.user-button').trigger('click')
    await wrapper.find('.dropdown-item:last-child').trigger('click')

    expect(logoutSpy).toHaveBeenCalled()
  })

  it('should toggle sidebar on mobile', async () => {
    const authStore = useAuthStore()
    authStore.user = {
      id: '1',
      username: 'testuser',
      email: 'test@example.com',
      roles: ['user'],
      permissions: ['read'],
      mfaEnabled: false,
      createdAt: '2024-01-01T00:00:00Z'
    }

    const wrapper = mount(AppHeader, {
      global: {
        plugins: [router]
      }
    })

    const toggleSpy = vi.spyOn(document.body.classList, 'toggle')
    await wrapper.find('.menu-toggle').trigger('click')

    expect(toggleSpy).toHaveBeenCalledWith('sidebar-collapsed')
  })
})