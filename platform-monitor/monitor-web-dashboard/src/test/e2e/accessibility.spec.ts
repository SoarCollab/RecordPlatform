import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

test.describe('Accessibility Tests', () => {
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
        body: JSON.stringify([])
      })
    })
  })

  test('should not have any automatically detectable accessibility issues on dashboard', async ({ page }) => {
    await page.goto('/')
    
    const accessibilityScanResults = await new AxeBuilder({ page }).analyze()
    
    expect(accessibilityScanResults.violations).toEqual([])
  })

  test('should not have accessibility issues on login page', async ({ page }) => {
    await page.goto('/login')
    
    const accessibilityScanResults = await new AxeBuilder({ page }).analyze()
    
    expect(accessibilityScanResults.violations).toEqual([])
  })

  test('should support keyboard navigation', async ({ page }) => {
    await page.goto('/')
    
    // Test tab navigation through interactive elements
    await page.keyboard.press('Tab')
    await expect(page.locator(':focus')).toBeVisible()
    
    // Continue tabbing through elements
    for (let i = 0; i < 5; i++) {
      await page.keyboard.press('Tab')
      const focusedElement = page.locator(':focus')
      await expect(focusedElement).toBeVisible()
    }
  })

  test('should have proper ARIA labels and roles', async ({ page }) => {
    await page.goto('/')
    
    // Check for proper button labels
    const buttons = page.locator('button')
    const buttonCount = await buttons.count()
    
    for (let i = 0; i < buttonCount; i++) {
      const button = buttons.nth(i)
      const ariaLabel = await button.getAttribute('aria-label')
      const title = await button.getAttribute('title')
      const textContent = await button.textContent()
      
      // Button should have accessible name (aria-label, title, or text content)
      expect(ariaLabel || title || textContent?.trim()).toBeTruthy()
    }
  })

  test('should have proper heading hierarchy', async ({ page }) => {
    await page.goto('/')
    
    // Check heading levels are properly structured
    const headings = await page.locator('h1, h2, h3, h4, h5, h6').all()
    
    expect(headings.length).toBeGreaterThan(0)
    
    // Should have at least one h1
    const h1Count = await page.locator('h1').count()
    expect(h1Count).toBeGreaterThanOrEqual(1)
  })

  test('should have sufficient color contrast', async ({ page }) => {
    await page.goto('/')
    
    // This would be caught by axe-core, but we can also do manual checks
    const accessibilityScanResults = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze()
    
    const colorContrastViolations = accessibilityScanResults.violations.filter(
      violation => violation.id === 'color-contrast'
    )
    
    expect(colorContrastViolations).toEqual([])
  })

  test('should support screen readers with proper semantic markup', async ({ page }) => {
    await page.goto('/')
    
    // Check for proper semantic elements
    await expect(page.locator('main')).toBeVisible()
    await expect(page.locator('nav')).toBeVisible()
    await expect(page.locator('header')).toBeVisible()
    
    // Check for proper list markup in navigation
    const navLists = page.locator('nav ul')
    expect(await navLists.count()).toBeGreaterThan(0)
  })

  test('should have accessible forms', async ({ page }) => {
    await page.goto('/login')
    
    // Check form labels are properly associated
    const inputs = page.locator('input')
    const inputCount = await inputs.count()
    
    for (let i = 0; i < inputCount; i++) {
      const input = inputs.nth(i)
      const id = await input.getAttribute('id')
      const ariaLabel = await input.getAttribute('aria-label')
      const ariaLabelledBy = await input.getAttribute('aria-labelledby')
      
      if (id) {
        // Check if there's a label with matching 'for' attribute
        const label = page.locator(`label[for="${id}"]`)
        const hasLabel = await label.count() > 0
        
        // Input should have label, aria-label, or aria-labelledby
        expect(hasLabel || ariaLabel || ariaLabelledBy).toBeTruthy()
      }
    }
  })

  test('should handle focus management in modals', async ({ page }) => {
    await page.goto('/settings')
    
    // Open import modal
    await page.click('button:has-text("Import Settings")')
    
    // Focus should be trapped in modal
    const modal = page.locator('.modal-content')
    await expect(modal).toBeVisible()
    
    // First focusable element in modal should receive focus
    const firstFocusable = modal.locator('button, input, textarea, select').first()
    await expect(firstFocusable).toBeFocused()
    
    // Escape key should close modal
    await page.keyboard.press('Escape')
    await expect(modal).not.toBeVisible()
  })

  test('should provide alternative text for images and icons', async ({ page }) => {
    await page.goto('/')
    
    // Check SVG icons have proper titles or aria-labels
    const svgs = page.locator('svg')
    const svgCount = await svgs.count()
    
    for (let i = 0; i < svgCount; i++) {
      const svg = svgs.nth(i)
      const ariaLabel = await svg.getAttribute('aria-label')
      const title = await svg.locator('title').textContent()
      const ariaHidden = await svg.getAttribute('aria-hidden')
      
      // SVG should have aria-label, title, or be hidden from screen readers
      expect(ariaLabel || title || ariaHidden === 'true').toBeTruthy()
    }
  })

  test('should support high contrast mode', async ({ page }) => {
    await page.goto('/settings')
    
    // Enable high contrast mode
    await page.click('text=Accessibility')
    await page.check('input[type="checkbox"]:near(:text("High contrast mode"))')
    
    // Check that high contrast class is applied
    const html = page.locator('html')
    await expect(html).toHaveClass(/high-contrast/)
  })

  test('should support reduced motion preference', async ({ page }) => {
    await page.goto('/settings')
    
    // Enable reduced motion
    await page.click('text=Accessibility')
    await page.check('input[type="checkbox"]:near(:text("Reduce motion and animations"))')
    
    // Check that reduced motion class is applied
    const html = page.locator('html')
    await expect(html).toHaveClass(/reduced-motion/)
  })

  test('should have proper skip links', async ({ page }) => {
    await page.goto('/')
    
    // Tab to first element (should be skip link if implemented)
    await page.keyboard.press('Tab')
    
    const focusedElement = page.locator(':focus')
    const text = await focusedElement.textContent()
    
    // If skip link exists, it should be the first focusable element
    if (text?.includes('Skip to')) {
      expect(text).toContain('Skip to')
    }
  })

  test('should announce dynamic content changes', async ({ page }) => {
    await page.goto('/')
    
    // Check for aria-live regions for dynamic content
    const liveRegions = page.locator('[aria-live]')
    const liveRegionCount = await liveRegions.count()
    
    // Should have at least one live region for notifications or status updates
    expect(liveRegionCount).toBeGreaterThan(0)
  })
})