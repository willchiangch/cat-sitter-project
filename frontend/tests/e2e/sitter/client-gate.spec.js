import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'

test.describe('Sitter Client Gate (Whitelist & Blacklist)', () => {
  let authPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    await authPage.injectSmokeAuth('SITTER')
  })

  test('should navigate to client gate from profile', async ({ page }) => {
    await page.goto('/profile')
    await page.waitForLoadState('networkidle')

    const gateBtn = page.getByRole('button', { name: /客群門禁管理/i })
    await gateBtn.waitFor({ state: 'visible', timeout: 10000 })
    await gateBtn.click()

    await page.waitForURL(/\/sitter\/client-gate/, { timeout: 10000 })
  })

  test('should display whitelist tab by default', async ({ page }) => {
    await page.goto('/sitter/client-gate')
    await page.waitForLoadState('networkidle')

    await expect(page.getByRole('button', { name: /白名單/i })).toBeVisible()
    await expect(page.getByRole('button', { name: /黑名單/i })).toBeVisible()
  })

  test('should switch between whitelist and blacklist tabs', async ({ page }) => {
    await page.goto('/sitter/client-gate')
    await page.waitForLoadState('networkidle')

    // Switch to blacklist
    const blacklistTab = page.getByRole('button', { name: /黑名單/i })
    await blacklistTab.click()

    // Search input should still be visible
    await expect(page.locator('input[type="text"], input[placeholder]').first()).toBeVisible()
  })

  test('should show search input for adding clients', async ({ page }) => {
    await page.goto('/sitter/client-gate')
    await page.waitForLoadState('networkidle')

    const searchInput = page.locator('input').first()
    await expect(searchInput).toBeVisible()
  })
})
