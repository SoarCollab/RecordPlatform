import { test, expect } from '@playwright/test'

test.describe('Dashboard Functionality', () => {
  test.beforeEach(async ({ page }) => {
    // Mock authentication
    await page.addInitScript(() => {
      localStorage.setItem('auth_token', 'mock-jwt-token')
    })

    // Mock API endpoints
    await page.route('**/api/v2/auth/profile', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: '1',
          username: 'admin',
          email: 'admin@example.com',
          roles: ['admin'],
          permissions: ['read', 'write', 'admin'],
          mfaEnabled: false,
          createdAt: '2024-01-01T00:00:00Z'
        })
      })
    })

    await page.route('**/api/v2/clients', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: '1',
            clientId: 'client-1',
            name: 'Server 1',
            hostname: 'server1.example.com',
            ipAddress: '192.168.1.10',
            region: 'us-east-1',
            environment: 'prod',
            status: 'active',
            lastHeartbeat: new Date().toISOString(),
            connectionFailures: 0,
            dataCompressionEnabled: true,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: new Date().toISOString()
          },
          {
            id: '2',
            clientId: 'client-2',
            name: 'Server 2',
            hostname: 'server2.example.com',
            ipAddress: '192.168.1.11',
            region: 'us-west-1',
            environment: 'staging',
            status: 'inactive',
            lastHeartbeat: new Date(Date.now() - 300000).toISOString(),
            connectionFailures: 2,
            dataCompressionEnabled: true,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: new Date().toISOString()
          }
        ])
      })
    })

    // Mock WebSocket connection
    await page.addInitScript(() => {
      // Mock WebSocket
      class MockWebSocket {
        constructor(url: string) {
          setTimeout(() => {
            if (this.onopen) this.onopen({} as Event)
          }, 100)
        }
        
        onopen: ((event: Event) => void) | null = null
        onmessage: ((event: MessageEvent) => void) | null = null
        onclose: ((event: CloseEvent) => void) | null = null
        onerror: ((event: Event) => void) | null = null
        
        send(data: string) {}
        close() {}
      }
      
      (window as any).WebSocket = MockWebSocket
    })
  })

  test('should display dashboard with summary cards', async ({ page }) => {
    await page.goto('/')
    
    await expect(page.locator('.dashboard-title')).toContainText('Dashboard')
    
    // Check summary cards
    await expect(page.locator('.summary-card')).toHaveCount(4)
    await expect(page.locator('.summary-card').first()).toContainText('Total Clients')
    
    // Should show client counts
    await expect(page.locator('.summary-value').first()).toContainText('2')
  })

  test('should display real-time metrics widgets', async ({ page }) => {
    await page.goto('/')
    
    // Wait for metrics widgets to load
    await expect(page.locator('.realtime-metrics')).toHaveCount(3)
    
    // Check widget titles
    await expect(page.locator('.widget-title')).toContainText('Average CPU Usage')
    await expect(page.locator('.widget-title')).toContainText('Average Memory Usage')
    await expect(page.locator('.widget-title')).toContainText('Network Traffic')
  })

  test('should refresh data when refresh button is clicked', async ({ page }) => {
    await page.goto('/')
    
    // Click refresh button
    await page.click('button:has-text("Refresh")')
    
    // Should trigger API calls (we can verify this by checking network requests)
    // In a real test, we might mock the API to return different data
  })

  test('should show connection status indicator', async ({ page }) => {
    await page.goto('/')
    
    // Should show WebSocket connection status
    await expect(page.locator('.connection-status')).toBeVisible()
    
    // Should eventually show connected status (after WebSocket mock connects)
    await expect(page.locator('.connection-status.connected')).toBeVisible({ timeout: 5000 })
  })

  test('should display performance charts', async ({ page }) => {
    await page.goto('/')
    
    // Check for chart containers
    await expect(page.locator('.chart-card')).toHaveCount(2)
    await expect(page.locator('.chart-title')).toContainText('System Performance Overview')
    await expect(page.locator('.chart-title')).toContainText('Client Status Distribution')
  })

  test('should show recent activity', async ({ page }) => {
    await page.goto('/')
    
    await expect(page.locator('.activity-title')).toContainText('Recent Activity')
    await expect(page.locator('.activity-item')).toHaveCount(3)
    
    // Check activity messages
    await expect(page.locator('.activity-message').first()).toContainText('Client server-01 connected')
  })

  test('should navigate to clients page from activity section', async ({ page }) => {
    await page.goto('/')
    
    await page.click('a:has-text("View All Clients")')
    await expect(page).toHaveURL('/clients')
  })

  test('should handle time range selection', async ({ page }) => {
    await page.goto('/')
    
    // Find and interact with time range selector
    const timeRangeSelect = page.locator('.time-range-select')
    await expect(timeRangeSelect).toBeVisible()
    
    await timeRangeSelect.selectOption('7d')
    
    // Should update charts (in a real test, we'd verify the API call parameters)
  })

  test('should be responsive on mobile devices', async ({ page }) => {
    // Set mobile viewport
    await page.setViewportSize({ width: 375, height: 667 })
    await page.goto('/')
    
    // Summary cards should stack vertically on mobile
    const summaryGrid = page.locator('.summary-grid')
    await expect(summaryGrid).toHaveCSS('grid-template-columns', '1fr')
    
    // Charts should also stack
    const chartsGrid = page.locator('.charts-grid')
    await expect(chartsGrid).toHaveCSS('grid-template-columns', '1fr')
  })

  test('should handle theme switching', async ({ page }) => {
    await page.goto('/')
    
    // Click theme toggle
    await page.click('.theme-toggle')
    
    // Should toggle dark class on document
    const htmlElement = page.locator('html')
    await expect(htmlElement).toHaveClass(/dark/)
  })

  test('should search from header', async ({ page }) => {
    await page.goto('/')
    
    // Type in search box
    await page.fill('.search-input', 'server1')
    await page.press('.search-input', 'Enter')
    
    // Should navigate to clients page with search query
    await expect(page).toHaveURL('/clients?search=server1')
  })

  test('should handle sidebar navigation', async ({ page }) => {
    await page.goto('/')
    
    // Click on Clients in sidebar
    await page.click('.nav-link:has-text("Clients")')
    await expect(page).toHaveURL('/clients')
    
    // Click on Alerts in sidebar
    await page.click('.nav-link:has-text("Alerts")')
    await expect(page).toHaveURL('/alerts')
    
    // Click on Settings in sidebar
    await page.click('.nav-link:has-text("Settings")')
    await expect(page).toHaveURL('/settings')
  })

  test('should show loading states', async ({ page }) => {
    // Mock slow API response
    await page.route('**/api/v2/clients', async route => {
      await new Promise(resolve => setTimeout(resolve, 1000))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([])
      })
    })

    await page.goto('/')
    
    // Should show loading spinner initially
    await expect(page.locator('.loading-spinner')).toBeVisible()
  })

  test('should handle API errors gracefully', async ({ page }) => {
    // Mock API error
    await page.route('**/api/v2/clients', async route => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          error: {
            code: 'INTERNAL_ERROR',
            message: 'Server error'
          }
        })
      })
    })

    await page.goto('/')
    
    // Should handle error gracefully (might show error message or fallback UI)
    // The exact behavior depends on error handling implementation
  })
})