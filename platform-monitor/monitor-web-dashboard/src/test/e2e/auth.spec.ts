import { test, expect } from '@playwright/test'

test.describe('Authentication Flow', () => {
  test.beforeEach(async ({ page }) => {
    // Mock API responses
    await page.route('**/api/v2/auth/login', async route => {
      const request = route.request()
      const body = request.postDataJSON()
      
      if (body.username === 'admin' && body.password === 'password') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            token: 'mock-jwt-token',
            user: {
              id: '1',
              username: 'admin',
              email: 'admin@example.com',
              roles: ['admin'],
              permissions: ['read', 'write', 'admin'],
              mfaEnabled: false,
              createdAt: '2024-01-01T00:00:00Z'
            },
            expiresIn: 3600
          })
        })
      } else {
        await route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: 'AUTHENTICATION_FAILED',
              message: 'Invalid credentials'
            }
          })
        })
      }
    })
  })

  test('should display login form when not authenticated', async ({ page }) => {
    await page.goto('/')
    
    // Should redirect to login
    await expect(page).toHaveURL('/login')
    await expect(page.locator('h1')).toContainText('Monitor Dashboard')
    await expect(page.locator('input[type="text"]')).toBeVisible()
    await expect(page.locator('input[type="password"]')).toBeVisible()
    await expect(page.locator('button[type="submit"]')).toContainText('Sign in')
  })

  test('should login successfully with valid credentials', async ({ page }) => {
    await page.goto('/login')
    
    await page.fill('input[type="text"]', 'admin')
    await page.fill('input[type="password"]', 'password')
    await page.click('button[type="submit"]')
    
    // Should redirect to dashboard
    await expect(page).toHaveURL('/')
    await expect(page.locator('.app-title')).toContainText('Monitor Dashboard')
    await expect(page.locator('.user-name')).toContainText('admin')
  })

  test('should show error with invalid credentials', async ({ page }) => {
    await page.goto('/login')
    
    await page.fill('input[type="text"]', 'admin')
    await page.fill('input[type="password"]', 'wrongpassword')
    await page.click('button[type="submit"]')
    
    await expect(page.locator('.error-message')).toContainText('Invalid credentials')
    await expect(page).toHaveURL('/login')
  })

  test('should handle MFA requirement', async ({ page }) => {
    // Mock MFA required response
    await page.route('**/api/v2/auth/login', async route => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          error: {
            code: 'MFA_REQUIRED',
            message: 'MFA required'
          }
        })
      })
    })

    await page.goto('/login')
    
    await page.fill('input[type="text"]', 'admin')
    await page.fill('input[type="password"]', 'password')
    await page.click('button[type="submit"]')
    
    // Should show MFA input
    await expect(page.locator('input[maxlength="6"]')).toBeVisible()
    await expect(page.locator('.error-message')).toContainText('Please enter your MFA code')
  })

  test('should logout successfully', async ({ page }) => {
    // Mock logout endpoint
    await page.route('**/api/v2/auth/logout', async route => {
      await route.fulfill({ status: 200 })
    })

    await page.goto('/login')
    
    // Login first
    await page.fill('input[type="text"]', 'admin')
    await page.fill('input[type="password"]', 'password')
    await page.click('button[type="submit"]')
    
    await expect(page).toHaveURL('/')
    
    // Open user menu and logout
    await page.click('.user-button')
    await page.click('.dropdown-item:has-text("Logout")')
    
    // Should redirect to login
    await expect(page).toHaveURL('/login')
  })

  test('should remember login state on page refresh', async ({ page }) => {
    await page.goto('/login')
    
    // Login
    await page.fill('input[type="text"]', 'admin')
    await page.fill('input[type="password"]', 'password')
    await page.click('button[type="submit"]')
    
    await expect(page).toHaveURL('/')
    
    // Refresh page
    await page.reload()
    
    // Should still be logged in
    await expect(page).toHaveURL('/')
    await expect(page.locator('.user-name')).toContainText('admin')
  })

  test('should validate form inputs', async ({ page }) => {
    await page.goto('/login')
    
    // Try to submit empty form
    await page.click('button[type="submit"]')
    
    // Form should not submit (HTML5 validation)
    await expect(page).toHaveURL('/login')
    
    // Fill only username
    await page.fill('input[type="text"]', 'admin')
    await page.click('button[type="submit"]')
    
    // Should still be on login page
    await expect(page).toHaveURL('/login')
  })
})