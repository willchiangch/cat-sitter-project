import { test, expect } from '@playwright/test'
import { AuthPage } from '../../pages/AuthPage'
import { ClientSittersPage } from '../../pages/ClientSittersPage'

test.describe('Client Sitters Page E2E', () => {
  let authPage, sittersPage

  test.beforeEach(async ({ page }) => {
    authPage = new AuthPage(page)
    sittersPage = new ClientSittersPage(page)

    await page.goto('/')
    await page.evaluate(async () => {
      const registrations = await navigator.serviceWorker.getRegistrations()
      for (const registration of registrations) {
        await registration.unregister()
      }
    })
  })

  test('Client clicks 保母 tab → /client/sitters renders without 404', async ({ page }) => {
    await authPage.injectClientSmokeAuth('/client')
    await page.waitForLoadState('networkidle')

    // Click the 保母 nav tab in the bottom navigation
    const sittersNavLink = page.locator('nav').getByRole('link', { name: '保母' })
    await sittersNavLink.waitFor({ state: 'visible', timeout: 10000 })
    await sittersNavLink.click()

    await expect(page).toHaveURL(/\/client\/sitters/)
    await expect(sittersPage.header).toBeVisible({ timeout: 10000 })
    await expect(page).not.toHaveURL(/404|not-found/)
  })

  test('Search sitter code → UI responds with search state', async ({ page }) => {
    await authPage.injectClientSmokeAuth('/client/sitters')
    await page.waitForLoadState('networkidle')

    await sittersPage.searchInput.waitFor({ state: 'visible', timeout: 10000 })
    await sittersPage.searchBySitterCode('SMOKE001')

    // After submit, either spinner appears briefly or empty state is shown
    const spinnerOrEmpty = page.locator('[class*="animate-spin"]').or(sittersPage.emptyState)
    await expect(spinnerOrEmpty.first()).toBeVisible({ timeout: 5000 })
  })
})
