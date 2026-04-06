import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

test.describe('Sitter Subscription Management', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await authPage.injectSmokeAuth('SITTER')
  })

  test('should navigate to subscription management page from profile', async ({ page }) => {
    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    // Click 管理訂閱 button
    const subscriptionBtn = page.getByRole('button', { name: /管理訂閱/i })
    await subscriptionBtn.waitFor({ state: 'visible', timeout: 10000 })
    await subscriptionBtn.click()

    await page.waitForURL(/\/sitter\/subscription/, { timeout: 10000 })
    await expect(page.getByText(/訂閱方案管理/i)).toBeVisible()
  })

  test('should display plan cards on subscription page', async ({ page }) => {
    await page.goto('/sitter/subscription')
    await page.waitForLoadState('networkidle')

    // Four plan cards should be visible
    await expect(page.getByText('免費版').first()).toBeVisible()
    await expect(page.getByText('基礎版').first()).toBeVisible()
    await expect(page.getByText('專業版').first()).toBeVisible()
    await expect(page.getByText('頂級版').first()).toBeVisible()
  })

  test('should show current plan status card', async ({ page }) => {
    await page.goto('/sitter/subscription')
    await page.waitForLoadState('networkidle')

    // Current plan status hero card
    await expect(page.getByText(/目前方案/i)).toBeVisible()
  })

  test('should toggle between monthly and annual billing', async ({ page }) => {
    await page.goto('/sitter/subscription')
    await page.waitForLoadState('networkidle')

    // Click annual toggle
    const annualBtn = page.getByRole('button', { name: /年繳/i })
    await annualBtn.click()

    // Prices should change (verify -15% badge visible)
    await expect(page.locator('span').filter({ hasText: '-15%' })).toBeVisible()
  })
})
