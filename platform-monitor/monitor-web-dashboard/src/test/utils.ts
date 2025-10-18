import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import type { ComponentMountingOptions } from '@vue/test-utils'

/**
 * Creates a test router with basic routes
 */
export function createTestRouter() {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div>Home</div>' } },
      { path: '/login', component: { template: '<div>Login</div>' } },
      { path: '/clients', component: { template: '<div>Clients</div>' } },
      { path: '/clients/:id', component: { template: '<div>Client Detail</div>' } },
      { path: '/alerts', component: { template: '<div>Alerts</div>' } },
      { path: '/settings', component: { template: '<div>Settings</div>' } },
    ]
  })
}

/**
 * Sets up Pinia for testing
 */
export function setupPinia() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return pinia
}

/**
 * Creates default mounting options for Vue Test Utils
 */
export function createMountingOptions(overrides: ComponentMountingOptions<any> = {}): ComponentMountingOptions<any> {
  const router = createTestRouter()
  const pinia = setupPinia()

  return {
    global: {
      plugins: [router, pinia],
      stubs: {
        // Stub Chart.js components
        'LineChart': { template: '<div class="mock-line-chart"></div>' },
        'DoughnutChart': { template: '<div class="mock-doughnut-chart"></div>' },
        'BaseChart': { template: '<div class="mock-base-chart"></div>' },
        // Stub teleport
        'Teleport': { template: '<div><slot /></div>' },
      },
      mocks: {
        $t: (key: string) => key, // Mock i18n
      },
    },
    ...overrides
  }
}

/**
 * Mock user data for testing
 */
export const mockUser = {
  id: '1',
  username: 'testuser',
  email: 'test@example.com',
  roles: ['user'],
  permissions: ['read'],
  mfaEnabled: false,
  createdAt: '2024-01-01T00:00:00Z'
}

/**
 * Mock client data for testing
 */
export const mockClients = [
  {
    id: '1',
    clientId: 'client-1',
    name: 'Test Server 1',
    hostname: 'server1.test.com',
    ipAddress: '192.168.1.10',
    region: 'us-east-1',
    environment: 'prod' as const,
    status: 'active' as const,
    lastHeartbeat: new Date().toISOString(),
    connectionFailures: 0,
    dataCompressionEnabled: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: new Date().toISOString()
  },
  {
    id: '2',
    clientId: 'client-2',
    name: 'Test Server 2',
    hostname: 'server2.test.com',
    ipAddress: '192.168.1.11',
    region: 'us-west-1',
    environment: 'staging' as const,
    status: 'inactive' as const,
    lastHeartbeat: new Date(Date.now() - 300000).toISOString(),
    connectionFailures: 2,
    dataCompressionEnabled: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: new Date().toISOString()
  }
]

/**
 * Mock metrics data for testing
 */
export const mockMetricsData = Array.from({ length: 10 }, (_, i) => ({
  timestamp: new Date(Date.now() - (10 - i) * 60000),
  value: Math.random() * 100
}))

/**
 * Waits for the next tick and any pending promises
 */
export async function flushPromises() {
  return new Promise(resolve => setTimeout(resolve, 0))
}

/**
 * Creates a mock WebSocket for testing
 */
export function createMockWebSocket() {
  const mockSocket = {
    readyState: 1,
    send: vi.fn(),
    close: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
    onopen: null as ((event: Event) => void) | null,
    onmessage: null as ((event: MessageEvent) => void) | null,
    onclose: null as ((event: CloseEvent) => void) | null,
    onerror: null as ((event: Event) => void) | null,
  }

  return mockSocket
}

/**
 * Simulates a WebSocket message
 */
export function simulateWebSocketMessage(socket: any, data: any) {
  if (socket.onmessage) {
    socket.onmessage({
      data: JSON.stringify(data),
      type: 'message',
      target: socket,
      currentTarget: socket,
      bubbles: false,
      cancelable: false,
      defaultPrevented: false,
      eventPhase: 0,
      isTrusted: true,
      timeStamp: Date.now(),
      preventDefault: () => {},
      stopImmediatePropagation: () => {},
      stopPropagation: () => {},
    } as MessageEvent)
  }
}

/**
 * Mock localStorage for testing
 */
export function createMockLocalStorage() {
  const store: Record<string, string> = {}
  
  return {
    getItem: vi.fn((key: string) => store[key] || null),
    setItem: vi.fn((key: string, value: string) => {
      store[key] = value
    }),
    removeItem: vi.fn((key: string) => {
      delete store[key]
    }),
    clear: vi.fn(() => {
      Object.keys(store).forEach(key => delete store[key])
    }),
    length: 0,
    key: vi.fn()
  }
}

/**
 * Asserts that an element has accessible name
 */
export function expectAccessibleName(element: any, expectedName?: string) {
  const ariaLabel = element.getAttribute('aria-label')
  const ariaLabelledBy = element.getAttribute('aria-labelledby')
  const title = element.getAttribute('title')
  const textContent = element.textContent?.trim()
  
  const accessibleName = ariaLabel || title || textContent
  
  if (expectedName) {
    expect(accessibleName).toBe(expectedName)
  } else {
    expect(accessibleName).toBeTruthy()
  }
}

/**
 * Simulates keyboard navigation
 */
export async function simulateKeyboardNavigation(wrapper: any, keys: string[]) {
  for (const key of keys) {
    await wrapper.trigger('keydown', { key })
  }
}

/**
 * Waits for an element to be visible
 */
export async function waitForElement(wrapper: any, selector: string, timeout = 1000) {
  const start = Date.now()
  
  while (Date.now() - start < timeout) {
    const element = wrapper.find(selector)
    if (element.exists()) {
      return element
    }
    await flushPromises()
  }
  
  throw new Error(`Element ${selector} not found within ${timeout}ms`)
}